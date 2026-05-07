package com.ultimatervc.mobile

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class InferenceProcessService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shutdownScheduled = AtomicBoolean(false)
    private val shutdownRunnable = Runnable {
        shutdownScheduled.set(false)
        stopSelf()
    }
    private var activeCancellationToken: CancellationToken? = null
    private var activeRealtimeSession: ServiceRealtimeRvcSession? = null
    private var worker: Thread? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            InferenceIpcProtocol.MSG_INFER_AUDIO -> {
                handleInferAudio(message)
                true
            }
            InferenceIpcProtocol.MSG_CANCEL -> {
                activeCancellationToken?.cancel()
                worker?.interrupt()
                closeRealtimeSession()
                true
            }
            InferenceIpcProtocol.MSG_START_REALTIME -> {
                handleOpenRealtime(message)
                true
            }
            InferenceIpcProtocol.MSG_STOP_REALTIME -> {
                closeRealtimeSession()
                true
            }
            else -> false
        }
    })

    override fun onBind(intent: Intent?): IBinder {
        cancelScheduledShutdown()
        return messenger.binder
    }

    override fun onDestroy() {
        activeCancellationToken?.cancel()
        worker?.interrupt()
        closeRealtimeSession()
        activeCancellationToken = null
        worker = null
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun handleInferAudio(message: Message) {
        val replyTo = message.replyTo ?: return
        val data = message.data
        cancelScheduledShutdown()
        worker = thread(name = "InferenceProcessWorker") {
            val token = CancellationToken()
            activeCancellationToken = token
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val outputPath = RvcInferenceEngine(
                    File(requiredString(data, InferenceIpcProtocol.KEY_MODELS_DIR)),
                    ResumableInferenceJobStore(
                        filesDir,
                        data.getString(InferenceIpcProtocol.KEY_WORKSPACE_RELATIVE_PATH)
                            ?: ResumableInferenceJobStore.AUDIO_INFERENCE_TEMP_DIRECTORY,
                    ),
                ).infer(
                    RvcInferenceRequest(
                        songPath = requiredString(data, InferenceIpcProtocol.KEY_SONG_PATH),
                        modelPath = requiredString(data, InferenceIpcProtocol.KEY_MODEL_PATH),
                        indexPath = data.getString(InferenceIpcProtocol.KEY_INDEX_PATH),
                        pitchChange = data.getDouble(InferenceIpcProtocol.KEY_PITCH_CHANGE),
                        indexRate = data.getDouble(InferenceIpcProtocol.KEY_INDEX_RATE),
                        formant = data.getDouble(InferenceIpcProtocol.KEY_FORMANT),
                        filterRadius = data.getInt(InferenceIpcProtocol.KEY_FILTER_RADIUS),
                        rmsMixRate = data.getDouble(InferenceIpcProtocol.KEY_RMS_MIX_RATE),
                        protectRate = data.getDouble(InferenceIpcProtocol.KEY_PROTECT_RATE),
                        sampleRate = data.getInt(InferenceIpcProtocol.KEY_SAMPLE_RATE),
                        noiseGateDb = data.getDouble(InferenceIpcProtocol.KEY_NOISE_GATE_DB),
                        outputDenoiseEnabled = data.getBoolean(InferenceIpcProtocol.KEY_OUTPUT_DENOISE_ENABLED),
                        vocalRangeFilterEnabled = data.getBoolean(InferenceIpcProtocol.KEY_VOCAL_RANGE_FILTER_ENABLED),
                        parallelChunkCount = data.getInt(InferenceIpcProtocol.KEY_PARALLEL_CHUNK_COUNT),
                        allowResume = data.getBoolean(InferenceIpcProtocol.KEY_ALLOW_RESUME, false),
                        workspaceRelativePath = data.getString(InferenceIpcProtocol.KEY_WORKSPACE_RELATIVE_PATH)
                            ?: ResumableInferenceJobStore.AUDIO_INFERENCE_TEMP_DIRECTORY,
                        cancellationToken = token,
                        onProgress = { percent, step ->
                            sendProgress(replyTo, percent, step)
                        },
                    )
                )
                replyTo.send(Message.obtain(null, InferenceIpcProtocol.MSG_SUCCESS).apply {
                    this.data = Bundle().apply { putString(InferenceIpcProtocol.KEY_OUTPUT_PATH, outputPath) }
                })
            } catch (error: java.util.concurrent.CancellationException) {
                sendError(replyTo, "INFERENCE_CANCELLED", "生成已中止")
            } catch (error: Throwable) {
                sendError(replyTo, "INFERENCE_FAILED", error.message ?: error.javaClass.simpleName)
            } finally {
                activeCancellationToken = null
                worker = null
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                stopSelf()
            }
        }
    }

    private fun handleOpenRealtime(message: Message) {
        val replyTo = message.replyTo ?: return
        val data = message.data
        cancelScheduledShutdown()
        try {
            closeRealtimeSession()
            activeRealtimeSession = ServiceRealtimeRvcSession(
                realtimeEngine = RvcInferenceEngine(File(requiredString(data, InferenceIpcProtocol.KEY_MODELS_DIR))).openStreamingSession(
                    modelPath = requiredString(data, InferenceIpcProtocol.KEY_MODEL_PATH),
                    indexPath = data.getString(InferenceIpcProtocol.KEY_INDEX_PATH),
                    pitchChange = data.getDouble(InferenceIpcProtocol.KEY_PITCH_CHANGE),
                    indexRate = data.getDouble(InferenceIpcProtocol.KEY_INDEX_RATE),
                    formant = data.getDouble(InferenceIpcProtocol.KEY_FORMANT),
                    filterRadius = data.getInt(InferenceIpcProtocol.KEY_FILTER_RADIUS),
                    rmsMixRate = data.getDouble(InferenceIpcProtocol.KEY_RMS_MIX_RATE),
                    protectRate = data.getDouble(InferenceIpcProtocol.KEY_PROTECT_RATE),
                    sampleRate = data.getInt(InferenceIpcProtocol.KEY_SAMPLE_RATE),
                    noiseGateDb = data.getDouble(InferenceIpcProtocol.KEY_NOISE_GATE_DB),
                    outputDenoiseEnabled = data.getBoolean(InferenceIpcProtocol.KEY_OUTPUT_DENOISE_ENABLED),
                    vocalRangeFilterEnabled = data.getBoolean(InferenceIpcProtocol.KEY_VOCAL_RANGE_FILTER_ENABLED),
                    parallelChunkCount = data.getInt(InferenceIpcProtocol.KEY_PARALLEL_CHUNK_COUNT),
                    extraInferenceLength = data.getDouble(InferenceIpcProtocol.KEY_EXTRA_INFERENCE_LENGTH),
                ),
                sampleLength = data.getDouble(InferenceIpcProtocol.KEY_SAMPLE_LENGTH),
                sampleRate = data.getInt(InferenceIpcProtocol.KEY_SAMPLE_RATE),
                crossfadeLength = data.getDouble(InferenceIpcProtocol.KEY_CROSSFADE_LENGTH),
                delayBufferSeconds = data.getDouble(InferenceIpcProtocol.KEY_DELAY_BUFFER_SECONDS),
                replyTo = replyTo,
            ).also { it.start() }
            replyTo.send(Message.obtain(null, InferenceIpcProtocol.MSG_SUCCESS).apply { this.data = Bundle.EMPTY })
        } catch (error: Throwable) {
            closeRealtimeSession()
            sendError(replyTo, "REALTIME_FAILED", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun closeRealtimeSession() {
        activeRealtimeSession?.stop()
        activeRealtimeSession = null
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        requestNativeMemoryCleanup()
    }

    private inner class ServiceRealtimeRvcSession(
        private val realtimeEngine: RvcInferenceEngine.RvcStreamingSession,
        sampleLength: Double,
        sampleRate: Int,
        crossfadeLength: Double,
        delayBufferSeconds: Double,
        private val replyTo: Messenger,
    ) {
        private val boundedSampleRate = sampleRate.coerceIn(MIN_REALTIME_SAMPLE_RATE, MAX_REALTIME_SAMPLE_RATE)
        private val boundedSampleLength = sampleLength.coerceIn(MIN_REALTIME_SAMPLE_LENGTH_SECONDS, MAX_REALTIME_SAMPLE_LENGTH_SECONDS)
        private val outputDelayBufferFrames = (boundedSampleRate * delayBufferSeconds.coerceIn(0.0, 60.0)).toInt()
        private val outputDelayBufferMaxWaitMs = (delayBufferSeconds.coerceIn(0.0, 60.0) * 1_000).roundToLong()
        private val outputDelayStartedAtMs = SystemClock.elapsedRealtime()
        private val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(boundedSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            (boundedSampleRate * boundedSampleLength).toInt().coerceAtLeast(REALTIME_MIN_BUFFER_FRAMES) * BYTES_PER_PCM_16,
        )
        private val audioBuffer = ShortArray(bufferSize / BYTES_PER_PCM_16)
        private val pendingSamples = ShortRingBuffer(alignedRealtimeWindowFrames(boundedSampleLength, boundedSampleRate) * 3)
        private val queuedOutputSamples = ShortRingBuffer(maxOf((boundedSampleRate * delayBufferSeconds.coerceIn(0.0, 60.0)).toInt(), alignedRealtimeWindowFrames(boundedSampleLength, boundedSampleRate)))
        private val outputChannel = Channel<ShortArray>(capacity = 3)
        private val inferenceWindow = ShortArray(alignedRealtimeWindowFrames(boundedSampleLength, boundedSampleRate))
        private val maxPendingInputSamples = inferenceWindow.size * 3
        private val realtimeHopFrames = inferenceWindow.size
        private val outputStartupThresholdFrames = if (outputDelayBufferFrames <= 0) 0 else minOf(outputDelayBufferFrames, realtimeHopFrames * REALTIME_OUTPUT_STARTUP_HOPS)
        private val crossfadeSamples = (boundedSampleRate * crossfadeLength.coerceIn(0.0, 1.0)).toInt()
        private val outputBuffer = ShortArray(audioBuffer.size)
        private val sampleLock = Object()
        private val inferenceLock = Object()
        private val audioTrackLock = Object()
        @Volatile
        private var running = false
        private var audioTrackStarted = false
        private var audioTrackReleased = false
        private var lastInferMs = 0L
        private var lastOutputSamples = 0
        private var lastInputPeak = 0
        private var lastRawOutputPeak = 0
        private var lastOutputPeak = 0
        private var nonEmptyOutputCount = 0L
        private var emptyOutputCount = 0L
        private var outputChannelDropCount = 0L
        private var outputChannelDroppedSamples = 0L
        private var audioTrackWriteCount = 0L
        private var audioTrackWrittenSamples = 0L
        private var worker: Job? = null
        private val stopped = AtomicBoolean(false)
        private var previousOutputTail = ShortArray(0)
        private val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            boundedSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        private val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            boundedSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM,
        )

        fun start() {
            require(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "无法初始化实时输入设备" }
            require(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "无法初始化实时输出设备" }
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            running = true
            audioRecord.startRecording()
            reportRealtimeStatus("实时采集中")
            worker = CoroutineScope(Dispatchers.Default).launch {
                try {
                    val captureJob = launch {
                        while (running) {
                            val read = audioRecord.read(audioBuffer, 0, realtimeHopFrames.coerceAtMost(audioBuffer.size))
                            if (read < 0) {
                                error("实时音频采集失败：$read")
                            }
                            if (read == 0) {
                                delay(REALTIME_PROCESS_POLL_MS)
                            } else {
                                appendRealtimeSamples(read)
                            }
                        }
                    }
                    val inferenceJob = launch {
                        while (running) {
                            if (hasRealtimeChunkReady()) {
                                reportRealtimeStatus("实时推理中")
                                val output = processRealtimeChunk()
                                if (output.isNotEmpty()) {
                                    nonEmptyOutputCount++
                                    val sendResult = outputChannel.trySend(output)
                                    if (sendResult.isFailure) {
                                        outputChannelDropCount++
                                        outputChannelDroppedSamples += output.size
                                        reportRealtimeStatus("实时输出队列繁忙")
                                    }
                                } else {
                                    emptyOutputCount++
                                }
                            } else {
                                delay(REALTIME_PROCESS_POLL_MS)
                            }
                        }
                    }
                    val outputJob = launch {
                        for (output in outputChannel) {
                            reportRealtimeStatus("实时输出中")
                            writeRealtimeOutput(output)
                        }
                    }
                    joinAll(captureJob, inferenceJob, outputJob)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    sendError(replyTo, "REALTIME_FAILED", error.message ?: error.javaClass.simpleName)
                } finally {
                    stop()
                }
            }
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            running = false
            worker?.cancel()
            outputChannel.close()
            runCatching { audioRecord.stop() }
            synchronized(audioTrackLock) {
                runCatching { audioTrack.stop() }
                audioTrackReleased = true
            }
            synchronized(inferenceLock) {
                audioRecord.release()
                synchronized(audioTrackLock) { audioTrack.release() }
                realtimeEngine.close()
            }
            synchronized(sampleLock) {
                pendingSamples.clear()
                queuedOutputSamples.clear()
                audioTrackStarted = false
            }
            previousOutputTail = ShortArray(0)
            worker = null
            requestNativeMemoryCleanup()
        }

        private fun appendRealtimeSamples(read: Int) {
            synchronized(sampleLock) {
                for (index in 0 until read) {
                    pendingSamples.add(audioBuffer[index])
                }
                if (pendingSamples.size > maxPendingInputSamples) {
                    pendingSamples.dropFirst(pendingSamples.size - maxPendingInputSamples)
                }
            }
        }

        private fun hasRealtimeChunkReady(): Boolean {
            return synchronized(sampleLock) { pendingSamples.size >= inferenceWindow.size }
        }

        private fun writeRealtimeOutput(output: ShortArray) {
            if (output.isEmpty()) return
            val outputToWrite = synchronized(sampleLock) {
                queuedOutputSamples.addAll(output)
                if (!audioTrackStarted && shouldStartAudioTrackLocked()) {
                    audioTrackStarted = true
                    drainQueuedOutputLocked()
                } else if (audioTrackStarted) {
                    drainQueuedOutputLocked()
                } else {
                    null
                }
            }
            outputToWrite?.let { output ->
                reportRealtimeStatus("实时输出中")
                startAudioTrackIfNeeded()
                writeAudioTrackFully(output, output.size)
            }
        }

        private fun drainQueuedOutputLocked(): ShortArray? {
            if (queuedOutputSamples.isEmpty()) return null
            return queuedOutputSamples.drainToArray()
        }

        private fun shouldStartAudioTrackLocked(): Boolean {
            if (queuedOutputSamples.isEmpty()) return false
            if (outputStartupThresholdFrames <= 0) return true
            if (queuedOutputSamples.size >= outputStartupThresholdFrames) return true
            val now = SystemClock.elapsedRealtime()
            return outputDelayBufferMaxWaitMs > 0L && now - outputDelayStartedAtMs >= outputDelayBufferMaxWaitMs
        }

        private fun startAudioTrackIfNeeded() {
            synchronized(audioTrackLock) {
                if (stopped.get() || audioTrackReleased) return
                if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.play()
                }
            }
        }

        private fun flushQueuedRealtimeOutput() {
            val queued = synchronized(sampleLock) {
                audioTrackStarted = true
                drainQueuedOutputLocked()
            } ?: return
            startAudioTrackIfNeeded()
            writeAudioTrackFully(queued, queued.size)
        }

        private fun writeAudioTrackFully(samples: ShortArray, size: Int) {
            synchronized(audioTrackLock) {
                if (stopped.get() || audioTrackReleased) return
                var offset = 0
                var zeroWriteRetryCount = 0
                while (offset < size) {
                    if (stopped.get() || audioTrackReleased) return
                    val written = audioTrack.write(samples, offset, size - offset)
                    check(written >= 0) { "实时音频写入失败：$written" }
                    if (written == 0) {
                        delayAudioTrackRetry()
                        zeroWriteRetryCount++
                        if (zeroWriteRetryCount <= AUDIO_TRACK_ZERO_WRITE_MAX_RETRIES) {
                            continue
                        }
                    } else {
                        zeroWriteRetryCount = 0
                    }
                    check(written > 0) { "实时音频写入未接收数据" }
                    offset += written
                    audioTrackWriteCount++
                    audioTrackWrittenSamples += written
                }
            }
        }

        private fun delayAudioTrackRetry() {
            Thread.sleep(REALTIME_PROCESS_POLL_MS)
        }

        private fun processRealtimeChunk(): ShortArray {
            synchronized(sampleLock) {
                for (index in inferenceWindow.indices) {
                    inferenceWindow[index] = pendingSamples[index]
                }
                pendingSamples.dropFirst(realtimeHopFrames)
            }
            val startedAtMs = SystemClock.elapsedRealtime()
            lastInputPeak = peakAbs(inferenceWindow, inferenceWindow.size)
            val output = synchronized(inferenceLock) { realtimeEngine.inferPcm16(inferenceWindow, realtimeHopFrames) }
            lastRawOutputPeak = peakAbs(output, output.size)
            lastInferMs = SystemClock.elapsedRealtime() - startedAtMs
            val outputSize = copyCrossfadedOutput(output)
            lastOutputPeak = peakAbs(outputBuffer, outputSize)
            lastOutputSamples = outputSize
            return outputBuffer.copyOf(outputSize)
        }

        private fun reportRealtimeStatus(status: String) {
            val diagnostics = synchronized(sampleLock) {
                mapOf(
                    "status" to status,
                    "pending_input_samples" to pendingSamples.size,
                    "input_backlog_ms" to pendingSamples.size.toLong() * 1_000 / boundedSampleRate,
                    "queued_output_samples" to queuedOutputSamples.size,
                    "output_delay_target_samples" to outputDelayBufferFrames,
                    "audio_track_started" to audioTrackStarted,
                    "last_infer_ms" to lastInferMs,
                    "last_output_samples" to lastOutputSamples,
                    "non_empty_output_count" to nonEmptyOutputCount,
                    "empty_output_count" to emptyOutputCount,
                    "output_channel_drop_count" to outputChannelDropCount,
                    "output_channel_dropped_samples" to outputChannelDroppedSamples,
                    "audio_track_write_count" to audioTrackWriteCount,
                    "audio_track_written_samples" to audioTrackWrittenSamples,
                    "last_input_peak" to lastInputPeak,
                    "last_raw_output_peak" to lastRawOutputPeak,
                    "last_output_peak" to lastOutputPeak,
                    "realtime_hop_frames" to realtimeHopFrames,
                    "inference_window_frames" to inferenceWindow.size,
                )
            }
            sendRealtimeStatus(replyTo, diagnostics)
        }

        private fun processRemainingRealtimeSamples() {
            val remaining = synchronized(sampleLock) {
                if (pendingSamples.isEmpty()) {
                    null
                } else {
                    val samples = ShortArray(inferenceWindow.size)
                    val copySize = minOf(pendingSamples.size, samples.size)
                    for (index in 0 until copySize) {
                        samples[index] = pendingSamples[index]
                    }
                    pendingSamples.clear()
                    samples
                }
            } ?: return
            val realtimeOutput = currentRealtimeHopOutput(realtimeEngine.inferPcm16(remaining, realtimeHopFrames))
            val outputSize = copyCrossfadedOutput(realtimeOutput)
            writeRealtimeOutput(outputBuffer.copyOf(outputSize))
        }

        private fun currentRealtimeHopOutput(output: ShortArray): ShortArray {
            val startIndex = (output.size - realtimeHopFrames).coerceAtLeast(0)
            return output.copyOfRange(startIndex, output.size)
        }

        private fun copyCrossfadedOutput(output: ShortArray): Int {
            val copySize = minOf(output.size, outputBuffer.size)
            for (index in 0 until copySize) {
                outputBuffer[index] = output[index]
            }
            applyOutputCrossfade(copySize)
            for (index in copySize until outputBuffer.size) {
                outputBuffer[index] = 0
            }
            updatePreviousOutputTail(copySize)
            return copySize
        }

        private fun applyOutputCrossfade(outputSize: Int) {
            val fadeSize = minOf(crossfadeSamples, previousOutputTail.size, outputSize)
            for (index in 0 until fadeSize) {
                val phase = (index + 1).toDouble() / (fadeSize + 1)
                val fadeIn = kotlin.math.sin(0.5 * Math.PI * phase)
                val fadeOut = kotlin.math.cos(0.5 * Math.PI * phase)
                val mixed = previousOutputTail[previousOutputTail.size - fadeSize + index] * fadeOut + outputBuffer[index] * fadeIn
                outputBuffer[index] = mixed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        private fun updatePreviousOutputTail(outputSize: Int) {
            val tailSize = minOf(crossfadeSamples, outputSize)
            previousOutputTail = if (tailSize <= 0) {
                ShortArray(0)
            } else {
                outputBuffer.copyOfRange(outputSize - tailSize, outputSize)
            }
        }

        private fun peakAbs(samples: ShortArray, size: Int): Int {
            var peak = 0
            for (index in 0 until size.coerceAtMost(samples.size)) {
                peak = maxOf(peak, kotlin.math.abs(samples[index].toInt()))
            }
            return peak
        }

    }

    private class ShortRingBuffer(capacity: Int) {
        private val capacityLimit = capacity.coerceAtLeast(1)
        private var buffer = ShortArray(capacityLimit)
        private var start = 0
        var size = 0
            private set

        operator fun get(index: Int): Short {
            require(index in 0 until size)
            return buffer[(start + index) % buffer.size]
        }

        fun isEmpty(): Boolean = size == 0

        fun add(value: Short) {
            if (size == capacityLimit) {
                dropFirst(1)
            }
            buffer[(start + size) % buffer.size] = value
            size++
        }

        fun addAll(values: ShortArray) {
            values.forEach { add(it) }
        }

        fun dropFirst(count: Int) {
            if (count <= 0) return
            if (count >= size) {
                clear()
                return
            }
            start = (start + count) % buffer.size
            size -= count
        }

        fun drainToArray(): ShortArray {
            val out = ShortArray(size)
            for (index in 0 until size) {
                out[index] = get(index)
            }
            clear()
            return out
        }

        fun clear() {
            start = 0
            size = 0
        }
    }

    private fun sendProgress(replyTo: Messenger, percent: Double, step: String) {
        replyTo.send(Message.obtain(null, InferenceIpcProtocol.MSG_PROGRESS).apply {
            data = Bundle().apply {
                putDouble(InferenceIpcProtocol.KEY_PERCENT, percent)
                putString(InferenceIpcProtocol.KEY_STEP, step)
            }
        })
    }

    private fun sendRealtimeStatus(replyTo: Messenger, diagnostics: Map<String, Any>) {
        replyTo.send(Message.obtain(null, InferenceIpcProtocol.MSG_PROGRESS).apply {
            data = Bundle().apply {
                putDouble(InferenceIpcProtocol.KEY_PERCENT, 0.0)
                putString(InferenceIpcProtocol.KEY_STEP, diagnostics["status"] as? String ?: "实时推理中")
                diagnostics.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Double -> putDouble(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
            }
        })
    }

    private fun sendError(replyTo: Messenger, code: String, message: String) {
        replyTo.send(Message.obtain(null, InferenceIpcProtocol.MSG_ERROR).apply {
            data = Bundle().apply {
                putString(InferenceIpcProtocol.KEY_ERROR_CODE, code)
                putString(InferenceIpcProtocol.KEY_ERROR_MESSAGE, message)
            }
        })
    }

    private fun requiredString(data: Bundle, key: String): String {
        return data.getString(key) ?: error("Missing $key")
    }

    private fun scheduleShutdown() {
        if (!shutdownScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed(shutdownRunnable, REMOTE_PROCESS_SHUTDOWN_DELAY_MS)
    }

    private fun cancelScheduledShutdown() {
        mainHandler.removeCallbacks(shutdownRunnable)
        shutdownScheduled.set(false)
    }

    private fun requestNativeMemoryCleanup() {
        System.runFinalization()
        System.gc()
    }

    private companion object {
        const val REMOTE_PROCESS_SHUTDOWN_DELAY_MS = 30_000L
        const val MIN_REALTIME_SAMPLE_RATE = 40_000
        const val MAX_REALTIME_SAMPLE_RATE = 48_000
        const val MIN_REALTIME_SAMPLE_LENGTH_SECONDS = 0.08
        const val MAX_REALTIME_SAMPLE_LENGTH_SECONDS = 6.0
        const val REALTIME_MIN_BUFFER_FRAMES = 4_096
        const val REALTIME_INPUT_ALIGNMENT_FRAMES = 960
        const val REALTIME_HOP_MS = 40
        const val REALTIME_OUTPUT_STARTUP_HOPS = 2
        const val REALTIME_PROCESS_POLL_MS = 10L
        const val AUDIO_TRACK_ZERO_WRITE_MAX_RETRIES = 3
        const val BYTES_PER_PCM_16 = 2

        fun alignedRealtimeWindowFrames(sampleLength: Double, sampleRate: Int): Int {
            val requested = (sampleRate * sampleLength).toInt().coerceAtLeast(REALTIME_MIN_BUFFER_FRAMES)
            val aligned = requested + (REALTIME_INPUT_ALIGNMENT_FRAMES - requested % REALTIME_INPUT_ALIGNMENT_FRAMES) % REALTIME_INPUT_ALIGNMENT_FRAMES
            return aligned.coerceAtLeast(REALTIME_INPUT_ALIGNMENT_FRAMES)
        }

        fun alignedRealtimeHopFrames(sampleRate: Int): Int {
            val requested = sampleRate * REALTIME_HOP_MS / 1000
            val aligned = requested + (REALTIME_INPUT_ALIGNMENT_FRAMES - requested % REALTIME_INPUT_ALIGNMENT_FRAMES) % REALTIME_INPUT_ALIGNMENT_FRAMES
            return aligned.coerceAtLeast(REALTIME_INPUT_ALIGNMENT_FRAMES)
        }
    }
}
