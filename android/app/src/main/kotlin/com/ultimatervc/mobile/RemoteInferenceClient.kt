package com.ultimatervc.mobile

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RemoteInferenceClient(private val context: Context) {
    private val replyThread = sharedReplyThread

    fun infer(request: RvcInferenceRequest): String {
        val appContext = context.applicationContext
        val resultLatch = CountDownLatch(1)
        val outputPath = AtomicReference<String?>()
        val error = AtomicReference<Throwable?>()
        val lastProgressAtMs = AtomicReference(SystemClock.elapsedRealtime())
        val cancelRequested = AtomicBoolean(false)
        val serviceBound = AtomicBoolean(false)
        val replyMessenger = Messenger(Handler(replyThread.looper) { message ->
            when (message.what) {
                InferenceIpcProtocol.MSG_PROGRESS -> {
                    lastProgressAtMs.set(SystemClock.elapsedRealtime())
                    request.onProgress(
                        message.data.getDouble(InferenceIpcProtocol.KEY_PERCENT),
                        message.data.getString(InferenceIpcProtocol.KEY_STEP).orEmpty(),
                    )
                    true
                }
                InferenceIpcProtocol.MSG_SUCCESS -> {
                    if (!cancelRequested.get()) {
                        outputPath.set(message.data.getString(InferenceIpcProtocol.KEY_OUTPUT_PATH))
                        resultLatch.countDown()
                    }
                    true
                }
                InferenceIpcProtocol.MSG_ERROR -> {
                    val code = message.data.getString(InferenceIpcProtocol.KEY_ERROR_CODE).orEmpty()
                    val messageText = message.data.getString(InferenceIpcProtocol.KEY_ERROR_MESSAGE).orEmpty()
                    if (!(cancelRequested.get() && code == "INFERENCE_CANCELLED")) {
                        error.set(RemoteInferenceException(code, messageText))
                        resultLatch.countDown()
                    }
                    true
                }
                else -> false
            }
        })
        val serviceMessenger = AtomicReference<Messenger?>()
        val serviceLatch = CountDownLatch(1)
        lateinit var connection: ServiceConnection
        val intent = Intent(context, InferenceProcessService::class.java)
        fun setRemoteError(code: String, message: String) {
            if (error.compareAndSet(null, RemoteInferenceException(code, message))) {
                resultLatch.countDown()
            }
        }
        fun safeUnbindService() {
            if (serviceBound.compareAndSet(true, false)) {
                runCatching { appContext.unbindService(connection) }
            }
        }
        fun requestRemoteStop(remote: Messenger?) {
            if (!cancelRequested.compareAndSet(false, true)) return
            runCatching { remote?.send(Message.obtain(null, InferenceIpcProtocol.MSG_CANCEL)) }
            runCatching { appContext.stopService(intent) }
            safeUnbindService()
        }
        fun waitForInferenceProcessExit(): Throwable {
            val deadline = SystemClock.elapsedRealtime() + REMOTE_PROCESS_EXIT_TIMEOUT_MS
            while (true) {
                if (!isInferenceProcessAlive(appContext)) {
                    return java.util.concurrent.CancellationException("生成已中止")
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    return RemoteInferenceException("INFERENCE_STOP_TIMEOUT", "终止生成后推理进程仍未退出")
                }
                SystemClock.sleep(minOf(PROCESS_LIVENESS_POLL_INTERVAL_MS, remaining))
            }
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceMessenger.set(Messenger(service))
                serviceLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceMessenger.set(null)
                setRemoteError("INFERENCE_DISCONNECTED", "推理进程已断开")
            }

            override fun onBindingDied(name: ComponentName?) {
                serviceMessenger.set(null)
                setRemoteError("INFERENCE_DISCONNECTED", "推理进程已断开")
            }

            override fun onNullBinding(name: ComponentName?) {
                serviceMessenger.set(null)
                setRemoteError("INFERENCE_DISCONNECTED", "推理进程已断开")
            }
        }
        runCatching { appContext.startService(intent) }
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            error("无法连接推理进程")
        }
        serviceBound.set(true)
        try {
            if (!serviceLatch.await(SERVICE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                error("推理进程连接超时")
            }
            val remote = serviceMessenger.get() ?: error("推理进程不可用")
            remote.send(Message.obtain(null, InferenceIpcProtocol.MSG_INFER_AUDIO).apply {
                replyTo = replyMessenger
                data = request.toBundle(FilePathResolver.modelsDir(appContext))
            })
            var lastProcessLivenessCheckAtMs = SystemClock.elapsedRealtime()
            while (!resultLatch.await(REMOTE_POLL_MS, TimeUnit.MILLISECONDS)) {
                val now = SystemClock.elapsedRealtime()
                if (!cancelRequested.get() && now - lastProcessLivenessCheckAtMs >= PROCESS_LIVENESS_POLL_INTERVAL_MS) {
                    lastProcessLivenessCheckAtMs = now
                    if (!isInferenceProcessAlive(appContext)) {
                        setRemoteError("INFERENCE_PROCESS_EXITED", "推理进程已中断")
                        break
                    }
                }
                if (!cancelRequested.get() && (!remote.binder.isBinderAlive || !remote.binder.pingBinder())) {
                    setRemoteError("INFERENCE_DISCONNECTED", "推理进程已断开")
                    break
                }
                try {
                    request.cancellationToken.throwIfCancelled()
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    requestRemoteStop(remote)
                    throw waitForInferenceProcessExit()
                }
                if (Thread.currentThread().isInterrupted) {
                    requestRemoteStop(remote)
                    throw waitForInferenceProcessExit()
                }
                if (now - lastProgressAtMs.get() >= REMOTE_INFERENCE_IDLE_TIMEOUT_MS) {
                    requestRemoteStop(remote)
                    setRemoteError("INFERENCE_STALLED", "推理进程长时间无进度")
                }
            }
            error.get()?.let { throw it }
            return outputPath.get() ?: error("推理进程未返回输出路径")
        } finally {
            safeUnbindService()
        }
    }

    private fun RvcInferenceRequest.toBundle(modelsDir: String): Bundle {
        return Bundle().apply {
            putString(InferenceIpcProtocol.KEY_MODELS_DIR, modelsDir)
            putString(InferenceIpcProtocol.KEY_SONG_PATH, songPath)
            putString(InferenceIpcProtocol.KEY_MODEL_PATH, modelPath)
            putString(InferenceIpcProtocol.KEY_INDEX_PATH, indexPath)
            putDouble(InferenceIpcProtocol.KEY_PITCH_CHANGE, pitchChange)
            putDouble(InferenceIpcProtocol.KEY_INDEX_RATE, indexRate)
            putDouble(InferenceIpcProtocol.KEY_FORMANT, formant)
            putInt(InferenceIpcProtocol.KEY_FILTER_RADIUS, filterRadius)
            putDouble(InferenceIpcProtocol.KEY_RMS_MIX_RATE, rmsMixRate)
            putDouble(InferenceIpcProtocol.KEY_PROTECT_RATE, protectRate)
            putInt(InferenceIpcProtocol.KEY_SAMPLE_RATE, sampleRate)
            putDouble(InferenceIpcProtocol.KEY_NOISE_GATE_DB, noiseGateDb)
            putBoolean(InferenceIpcProtocol.KEY_OUTPUT_DENOISE_ENABLED, outputDenoiseEnabled)
            putBoolean(InferenceIpcProtocol.KEY_VOCAL_RANGE_FILTER_ENABLED, vocalRangeFilterEnabled)
            putInt(InferenceIpcProtocol.KEY_PARALLEL_CHUNK_COUNT, parallelChunkCount)
            putBoolean(InferenceIpcProtocol.KEY_ALLOW_RESUME, allowResume)
            putString(InferenceIpcProtocol.KEY_WORKSPACE_RELATIVE_PATH, workspaceRelativePath)
        }
    }

    fun openRealtimeSession(
        modelPath: String,
        indexPath: String?,
        pitchChange: Double,
        indexRate: Double,
        formant: Double,
        sampleLength: Double,
        rmsMixRate: Double,
        protectRate: Double,
        sampleRate: Int,
        noiseGateDb: Double,
        outputDenoiseEnabled: Boolean,
        vocalRangeFilterEnabled: Boolean,
        parallelChunkCount: Int,
        extraInferenceLength: Double,
        crossfadeLength: Double,
        delayBufferSeconds: Double,
        onStatus: (Map<String, Any>) -> Unit,
    ): RemoteRealtimeInferenceSession {
        return RemoteRealtimeInferenceSession(
            context = context.applicationContext,
            config = Bundle().apply {
                putString(InferenceIpcProtocol.KEY_MODELS_DIR, FilePathResolver.modelsDir(context.applicationContext))
                putString(InferenceIpcProtocol.KEY_MODEL_PATH, modelPath)
                putString(InferenceIpcProtocol.KEY_INDEX_PATH, indexPath)
                putDouble(InferenceIpcProtocol.KEY_PITCH_CHANGE, pitchChange)
                putDouble(InferenceIpcProtocol.KEY_INDEX_RATE, indexRate)
                putDouble(InferenceIpcProtocol.KEY_FORMANT, formant)
                putDouble(InferenceIpcProtocol.KEY_SAMPLE_LENGTH, sampleLength)
                putInt(InferenceIpcProtocol.KEY_FILTER_RADIUS, 3)
                putDouble(InferenceIpcProtocol.KEY_RMS_MIX_RATE, rmsMixRate)
                putDouble(InferenceIpcProtocol.KEY_PROTECT_RATE, protectRate)
                putInt(InferenceIpcProtocol.KEY_SAMPLE_RATE, sampleRate)
                putDouble(InferenceIpcProtocol.KEY_NOISE_GATE_DB, noiseGateDb)
                putBoolean(InferenceIpcProtocol.KEY_OUTPUT_DENOISE_ENABLED, outputDenoiseEnabled)
                putBoolean(InferenceIpcProtocol.KEY_VOCAL_RANGE_FILTER_ENABLED, vocalRangeFilterEnabled)
                putInt(InferenceIpcProtocol.KEY_PARALLEL_CHUNK_COUNT, parallelChunkCount)
                putDouble(InferenceIpcProtocol.KEY_EXTRA_INFERENCE_LENGTH, extraInferenceLength)
                putDouble(InferenceIpcProtocol.KEY_CROSSFADE_LENGTH, crossfadeLength)
                putDouble(InferenceIpcProtocol.KEY_DELAY_BUFFER_SECONDS, delayBufferSeconds)
            },
            onStatus = onStatus,
        )
    }

    class RemoteRealtimeInferenceSession(
        private val context: Context,
        private val config: Bundle,
        private val onStatus: (Map<String, Any>) -> Unit,
    ) : AutoCloseable {
        private val replyThread = HandlerThread("RemoteRealtimeInferenceReply").apply { start() }
        private val serviceMessenger = AtomicReference<Messenger?>()
        private val serviceLatch = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceMessenger.set(Messenger(service))
                serviceLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceMessenger.set(null)
            }
        }

        init {
            val intent = Intent(context, InferenceProcessService::class.java)
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                replyThread.quitSafely()
                error("无法连接实时推理进程")
            }
            if (!serviceLatch.await(SERVICE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                close()
                error("实时推理进程连接超时")
            }
            sendRequest(InferenceIpcProtocol.MSG_START_REALTIME, config)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { serviceMessenger.get()?.send(Message.obtain(null, InferenceIpcProtocol.MSG_STOP_REALTIME)) }
            runCatching { context.unbindService(connection) }
            stopInferenceProcessAndWait(context)
            replyThread.quitSafely()
        }

        private fun sendRequest(what: Int, data: Bundle): Bundle {
            check(!closed.get()) { "实时推理会话已关闭" }
            val resultLatch = CountDownLatch(1)
            val response = AtomicReference<Bundle?>()
            val error = AtomicReference<Throwable?>()
            val replyMessenger = Messenger(Handler(replyThread.looper) { message ->
                when (message.what) {
                    InferenceIpcProtocol.MSG_SUCCESS -> {
                        response.set(message.data)
                        resultLatch.countDown()
                        true
                    }
                    InferenceIpcProtocol.MSG_PROGRESS -> {
                        onStatus(bundleToMap(message.data))
                        true
                    }
                    InferenceIpcProtocol.MSG_ERROR -> {
                        error.set(RemoteInferenceException(
                            message.data.getString(InferenceIpcProtocol.KEY_ERROR_CODE).orEmpty(),
                            message.data.getString(InferenceIpcProtocol.KEY_ERROR_MESSAGE).orEmpty(),
                        ))
                        resultLatch.countDown()
                        true
                    }
                    else -> false
                }
            })
            val remote = serviceMessenger.get() ?: error("实时推理进程不可用")
            remote.send(Message.obtain(null, what).apply {
                replyTo = replyMessenger
                this.data = data
            })
            if (!resultLatch.await(REALTIME_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw InterruptedException("实时推理进程响应超时")
            }
            error.get()?.let { throw it }
            return response.get() ?: Bundle.EMPTY
        }

        private fun bundleToMap(bundle: Bundle): Map<String, Any> {
            return bundle.keySet().mapNotNull { key ->
                val value = bundle.get(key) ?: return@mapNotNull null
                key to value
            }.toMap()
        }
    }

    private object FilePathResolver {
        fun modelsDir(context: Context): String {
            return File(context.filesDir, "models").absolutePath
        }
    }

    class RemoteInferenceException(val code: String, message: String) : Exception(message)

    companion object {
        val sharedReplyThread = HandlerThread("RemoteInferenceReply").apply { start() }
        const val SERVICE_CONNECT_TIMEOUT_MS = 10_000L
        const val REMOTE_POLL_MS = 100L
        const val REMOTE_INFERENCE_IDLE_TIMEOUT_MS = 90_000L
        const val PROCESS_LIVENESS_POLL_INTERVAL_MS = 3_000L
        const val REMOTE_PROCESS_EXIT_TIMEOUT_MS = 30_000L
        const val REALTIME_REQUEST_TIMEOUT_MS = 30_000L

        fun isInferenceProcessAlive(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val targetProcessName = context.packageName + ":inference"
            return activityManager.runningAppProcesses?.any { it.processName == targetProcessName } == true
        }

        fun stopInferenceProcessAndWait(context: Context, timeoutMs: Long = REMOTE_PROCESS_EXIT_TIMEOUT_MS): Boolean {
            val appContext = context.applicationContext
            runCatching { appContext.stopService(Intent(appContext, InferenceProcessService::class.java)) }
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isInferenceProcessAlive(appContext)) {
                    return true
                }
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                SystemClock.sleep(minOf(PROCESS_LIVENESS_POLL_INTERVAL_MS, remaining))
            }
            return !isInferenceProcessAlive(appContext)
        }
    }
}
