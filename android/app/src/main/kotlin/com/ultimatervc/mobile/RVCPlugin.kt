package com.ultimatervc.mobile

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

class RVCPlugin : FlutterPlugin, MethodCallHandler, StreamHandler {
    private lateinit var channel: MethodChannel
    private lateinit var progressChannel: EventChannel
    private lateinit var realtimeStatusChannel: EventChannel
    private lateinit var decibelChannel: EventChannel
    private var progressEventSink: EventSink? = null
    private var realtimeEventSink: EventSink? = null
    private var decibelSession: DecibelMeterSession? = null
    private var recorder: MediaRecorder? = null
    private var realtimeSession: RealtimeRvcSession? = null
    private var activeInferenceJob: Job? = null
    private var activeRootPerformanceSession: RootPerformanceSession? = null
    private var activeCancellationToken: CancellationToken? = null
    private var recordingFile: File? = null
    private var recordingStartedAtMs: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private lateinit var appContext: Context

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        appContext = flutterPluginBinding.applicationContext
        NativeModeGuard.initialize(appContext)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "ultimate_rvc")
        progressChannel = EventChannel(flutterPluginBinding.binaryMessenger, "ultimate_rvc_progress")
        realtimeStatusChannel = EventChannel(flutterPluginBinding.binaryMessenger, "ultimate_rvc_realtime_status")
        decibelChannel = EventChannel(flutterPluginBinding.binaryMessenger, "ultimate_rvc_decibel")
        
        channel.setMethodCallHandler(this)
        progressChannel.setStreamHandler(this)
        realtimeStatusChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                realtimeEventSink = events
            }

            override fun onCancel(arguments: Any?) {
                realtimeEventSink = null
            }
        })
        decibelChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                startDecibelMeter(events)
            }

            override fun onCancel(arguments: Any?) {
                stopDecibelMeter()
            }
        })
        
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        realtimeSession?.stop()
        realtimeSession = null
        activeInferenceJob?.cancel()
        activeInferenceJob = null
        activeCancellationToken?.cancel()
        activeCancellationToken = null
        activeRootPerformanceSession?.restore()
        activeRootPerformanceSession = null
        channel.setMethodCallHandler(null)
        progressChannel.setStreamHandler(null)
        realtimeStatusChannel.setStreamHandler(null)
        decibelChannel.setStreamHandler(null)
        stopDecibelMeter()
        scope.cancel()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> initialize(call, result)
            "checkModels" -> checkModels(call, result)
            "isInferenceProcessRunning" -> isInferenceProcessRunning(result)
            "infer" -> infer(call, result)
            "stopInference" -> stopInference(result)
            "getResumableJobMetadata" -> getResumableJobMetadata(call, result)
            "clearResumableJobCache" -> clearResumableJobCache(call, result)
            "importPickedFile" -> importPickedFile(call, result)
            "releaseImportedFile" -> releaseImportedFile(call, result)
            "clearTempWorkspace" -> clearTempWorkspace(call, result)
            "startRealtimeInference" -> startRealtimeInference(call, result)
            "stopRealtimeInference" -> stopRealtimeInference(result)
            "startRecording" -> startRecording(result)
            "stopRecording" -> stopRecording(result)
            "showToast" -> showToast(call, result)
            "saveGeneratedAudio" -> saveGeneratedAudio(call, result)
            "convertIndex" -> convertIndex(call, result)
            "getVersion" -> getVersion(call, result)
            else -> result.notImplemented()
        }
    }

    private fun initialize(call: MethodCall, result: Result) {
        scope.launch {
            try {
                val modelsReady = ensureBundledModelsReady()
                withContext(Dispatchers.Main) {
                    if (modelsReady) {
                        result.success(true)
                    } else {
                        result.error("INIT_FAILED", "缺少内置 ONNX 模型", null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("INIT_FAILED", e.message, null)
                }
            }
        }
    }

    private fun checkModels(call: MethodCall, result: Result) {
        scope.launch {
            try {
                val allModelsAvailable = ensureBundledModelsReady()

                withContext(Dispatchers.Main) {
                    result.success(allModelsAvailable)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("CHECK_MODELS_FAILED", e.message, null)
                }
            }
        }
    }

    private fun infer(call: MethodCall, result: Result) {
        val songPath = call.argument<String>("songPath")
        val modelPath = call.argument<String>("modelPath")
        val indexPath = call.argument<String>("indexPath")
        val pitchChange = call.argument<Double>("pitchChange") ?: 0.0
        val indexRate = call.argument<Double>("indexRate") ?: 0.75
        val formant = call.argument<Double>("formant") ?: 0.0
        val filterRadius = call.argument<Int>("filterRadius") ?: 3
        val rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25
        val protectRate = call.argument<Double>("protectRate") ?: 0.33
        val sampleRate = call.argument<Int>("sampleRate") ?: DEFAULT_OUTPUT_SAMPLE_RATE
        val noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0
        val outputDenoiseEnabled = call.argument<Boolean>("outputDenoiseEnabled") ?: true
        val vocalRangeFilterEnabled = call.argument<Boolean>("vocalRangeFilterEnabled") ?: true
        val parallelChunkCount = call.argument<Int>("parallelChunkCount") ?: DEFAULT_PARALLEL_CHUNK_COUNT
        val inferenceRunId = call.argument<Long>("inferenceRunId") ?: SystemClock.elapsedRealtimeNanos()
        val enableRootPerformanceMode = call.argument<Boolean>("enableRootPerformanceMode") ?: false
        val allowResume = call.argument<Boolean>("allowResume") ?: false

        if (songPath == null || modelPath == null) {
            result.error("INVALID_ARGUMENTS", "Missing songPath or modelPath", null)
            return
        }
        val modeToken = NativeModeGuard.tryEnter(NativeActiveMode.AUDIO_INFERENCE)
        if (modeToken == null) {
            result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)
            return
        }

        val cancellationToken = CancellationToken()
        val job = scope.launch {
            val wakeLock = acquireInferenceWakeLock()
            var rootPerformanceSession: RootPerformanceSession? = null
            try {
                rootPerformanceSession = RootPerformanceSession.startIfEnabled(enableRootPerformanceMode, getRootPerformanceCacheFile()) { status ->
                    sendProgress(status + ("run_id" to inferenceRunId))
                }
                activeRootPerformanceSession = rootPerformanceSession
                activeCancellationToken = cancellationToken
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                require(ensureBundledModelsReady()) { "缺少内置 ONNX 模型" }

                sendProgress(mapOf(
                    "percent" to 0.0,
                    "current_step" to "准备 ONNX Runtime",
                    "eta" to 0,
                    "run_id" to inferenceRunId,
                ))

                val outputPath = RemoteInferenceClient(appContext).infer(
                    RvcInferenceRequest(
                        songPath = songPath,
                        modelPath = modelPath,
                        indexPath = indexPath,
                        pitchChange = pitchChange,
                        indexRate = indexRate,
                        formant = formant,
                        filterRadius = filterRadius,
                        rmsMixRate = rmsMixRate,
                        protectRate = protectRate,
                        sampleRate = sampleRate,
                        noiseGateDb = noiseGateDb,
                        outputDenoiseEnabled = outputDenoiseEnabled,
                        vocalRangeFilterEnabled = vocalRangeFilterEnabled,
                        parallelChunkCount = parallelChunkCount,
                        allowResume = allowResume,
                        workspaceRelativePath = ResumableInferenceJobStore.AUDIO_INFERENCE_TEMP_DIRECTORY,
                        cancellationToken = cancellationToken,
                        onProgress = { percent, step ->
                            sendProgress(mapOf("percent" to percent, "current_step" to step, "eta" to 0, "run_id" to inferenceRunId))
                        },
                    ),
                )
                
                sendProgress(mapOf(
                    "percent" to 100.0,
                    "current_step" to "完成",
                    "eta" to 0,
                    "run_id" to inferenceRunId,
                ))

                withContext(Dispatchers.Main) {
                    result.success(outputPath.toString())
                }
            } catch (e: RootPerformanceException) {
                withContext(Dispatchers.Main) {
                    result.error(e.code, e.message, null)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    result.error("INFERENCE_CANCELLED", "生成已中止", null)
                }
            } catch (e: RemoteInferenceClient.RemoteInferenceException) {
                withContext(Dispatchers.Main) {
                    result.error(e.code, e.message, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("INFERENCE_FAILED", e.message, null)
                }
            } finally {
                rootPerformanceSession?.restore()
                if (activeRootPerformanceSession == rootPerformanceSession) {
                    activeRootPerformanceSession = null
                }
                if (activeInferenceJob == coroutineContext.job) {
                    activeInferenceJob = null
                }
                if (activeCancellationToken == cancellationToken) {
                    activeCancellationToken = null
                }
                modeToken.release()
                requestNativeMemoryCleanup()
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
        activeInferenceJob = job
    }

    private fun getVersion(call: MethodCall, result: Result) {
        result.success("1.0.0")
    }

    private fun showToast(call: MethodCall, result: Result) {
        val message = call.argument<String>("message") ?: ""
        runOnMain { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
        result.success(true)
    }

    private fun stopInference(result: Result) {
        val job = activeInferenceJob
        activeCancellationToken?.cancel()
        if (job == null) {
            result.success(true)
            return
        }
        scope.launch {
            job?.join()
            withContext(Dispatchers.Main) {
                result.success(true)
            }
        }
    }

    private fun getResumableJobMetadata(call: MethodCall, result: Result) {
        val request = resumableRequestFrom(call) ?: run {
            result.error("INVALID_ARGUMENTS", "Missing songPath or modelPath", null)
            return
        }
        val metadata = RvcInferenceEngine(
            modelsDir = File(getModelsDirectory()),
            resumableJobStore = ResumableInferenceJobStore(appContext.filesDir),
        ).getResumableMetadata(request)
        result.success(metadata?.let {
            mapOf(
                "jobId" to it.jobId,
                "overallProgress" to it.overallProgress,
                "state" to it.state,
                "accumulatedElapsedMs" to it.accumulatedElapsedMs,
            )
        })
    }

    private fun clearResumableJobCache(call: MethodCall, result: Result) {
        val request = resumableRequestFrom(call) ?: run {
            result.error("INVALID_ARGUMENTS", "Missing songPath or modelPath", null)
            return
        }
        val cleared = RvcInferenceEngine(
            modelsDir = File(getModelsDirectory()),
            resumableJobStore = ResumableInferenceJobStore(appContext.filesDir),
        ).clearResumableCache(request)
        result.success(cleared)
    }

    private fun importPickedFile(call: MethodCall, result: Result) {
        val kind = call.argument<String>("kind")
        val sourcePath = call.argument<String>("sourcePath")
        if (kind.isNullOrBlank() || sourcePath.isNullOrBlank()) {
            result.error("INVALID_ARGUMENTS", "Missing kind or sourcePath", null)
            return
        }
        try {
            val imported = TempWorkspaceManager(appContext.filesDir, appContext.cacheDir).importPickedFile(
                kind = when (kind) {
                    "audio" -> ImportedFileKind.AUDIO
                    "model" -> ImportedFileKind.MODEL
                    "index" -> ImportedFileKind.INDEX
                    else -> error("不支持的导入类型：$kind")
                },
                sourcePath = sourcePath,
            )
            result.success(mapOf(
                "path" to imported.path,
                "referenceCount" to imported.referenceCount,
                "lastUpdatedAtMs" to imported.lastUpdatedAtMs,
            ))
        } catch (error: Throwable) {
            result.error("IMPORT_FAILED", error.message, null)
        }
    }

    private fun releaseImportedFile(call: MethodCall, result: Result) {
        val path = call.argument<String>("path")
        if (path.isNullOrBlank()) {
            result.error("INVALID_ARGUMENTS", "Missing path", null)
            return
        }
        try {
            val released = TempWorkspaceManager(appContext.filesDir, appContext.cacheDir).releaseReference(path)
            if (released.referenceCount == 0) {
                TempWorkspaceManager(appContext.filesDir, appContext.cacheDir).deleteIfUnused(path)
            }
            result.success(released.referenceCount)
        } catch (error: Throwable) {
            result.error("RELEASE_FAILED", error.message, null)
        }
    }

    private fun clearTempWorkspace(call: MethodCall, result: Result) {
        val mode = call.argument<String>("mode")
        if (mode.isNullOrBlank()) {
            result.error("INVALID_ARGUMENTS", "Missing mode", null)
            return
        }
        try {
            TempWorkspaceManager(appContext.filesDir, appContext.cacheDir).clearModeTempWorkspace(
                when (mode) {
                    "audio_inference" -> TempWorkspaceMode.AUDIO_INFERENCE
                    "voice_changer" -> TempWorkspaceMode.VOICE_CHANGER
                    else -> error("不支持的 TEMP 模式：$mode")
                },
            )
            result.success(true)
        } catch (error: Throwable) {
            result.error("CLEAR_TEMP_FAILED", error.message, null)
        }
    }

    private fun resumableRequestFrom(call: MethodCall): RvcInferenceRequest? {
        val songPath = call.argument<String>("songPath") ?: return null
        val modelPath = call.argument<String>("modelPath") ?: return null
        return RvcInferenceRequest(
            songPath = songPath,
            modelPath = modelPath,
            indexPath = call.argument<String>("indexPath"),
            pitchChange = call.argument<Double>("pitchChange") ?: 0.0,
            indexRate = call.argument<Double>("indexRate") ?: 0.75,
            formant = call.argument<Double>("formant") ?: 0.0,
            filterRadius = call.argument<Int>("filterRadius") ?: 3,
            rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25,
            protectRate = call.argument<Double>("protectRate") ?: 0.33,
            sampleRate = call.argument<Int>("sampleRate") ?: DEFAULT_OUTPUT_SAMPLE_RATE,
            noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0,
            outputDenoiseEnabled = call.argument<Boolean>("outputDenoiseEnabled") ?: true,
            vocalRangeFilterEnabled = call.argument<Boolean>("vocalRangeFilterEnabled") ?: true,
            parallelChunkCount = call.argument<Int>("parallelChunkCount") ?: DEFAULT_PARALLEL_CHUNK_COUNT,
            allowResume = false,
            workspaceRelativePath = ResumableInferenceJobStore.AUDIO_INFERENCE_TEMP_DIRECTORY,
            cancellationToken = CancellationToken(),
            onProgress = { _, _ -> },
        )
    }

    private fun startRealtimeInference(call: MethodCall, result: Result) {
        val enableRootPerformanceMode = call.argument<Boolean>("enableRootPerformanceMode") ?: false
        val sampleLength = call.argument<Double>("sampleLength") ?: 6.0
        val pitchChange = call.argument<Double>("pitchChange") ?: 0.0
        val indexRate = call.argument<Double>("indexRate") ?: 0.75
        val formant = call.argument<Double>("formant") ?: 0.0
        val rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25
        val protectRate = call.argument<Double>("protectRate") ?: 0.33
        val noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0
        val outputDenoiseEnabled = call.argument<Boolean>("outputDenoiseEnabled") ?: true
        val vocalRangeFilterEnabled = call.argument<Boolean>("vocalRangeFilterEnabled") ?: true
        val crossfadeLength = call.argument<Double>("crossfadeLength") ?: 0.0
        val extraInferenceLength = call.argument<Double>("extraInferenceLength") ?: 0.0
        val delayBufferSeconds = call.argument<Double>("delayBufferSeconds") ?: 0.0
        val sampleRate = call.argument<Int>("sampleRate") ?: DEFAULT_OUTPUT_SAMPLE_RATE
        val parallelChunkCount = call.argument<Int>("parallelChunkCount") ?: DEFAULT_PARALLEL_CHUNK_COUNT
        val modelPath = call.argument<String>("modelPath")
        val indexPath = call.argument<String>("indexPath")
        if (modelPath.isNullOrBlank()) {
            result.error("INVALID_ARGUMENTS", "请选择 .onnx 音色模型", null)
            return
        }
        if (realtimeSession != null) {
            result.error("REALTIME_FAILED", "实时推理已启动", null)
            return
        }
        val modeToken = NativeModeGuard.tryEnter(NativeActiveMode.REALTIME_INFERENCE)
        if (modeToken == null) {
            result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)
            return
        }
        scope.launch {
            try {
                val session = RealtimeRvcSession(
                    remoteRealtimeSession = RemoteInferenceClient(appContext).openRealtimeSession(
                        modelPath = modelPath,
                        indexPath = indexPath,
                        pitchChange = pitchChange,
                        indexRate = indexRate,
                        formant = formant,
                        sampleLength = sampleLength.coerceIn(MIN_REALTIME_SAMPLE_LENGTH_SECONDS, MAX_REALTIME_SAMPLE_LENGTH_SECONDS),
                        rmsMixRate = rmsMixRate,
                        protectRate = protectRate,
                        sampleRate = sampleRate.coerceIn(MIN_REALTIME_SAMPLE_RATE, MAX_REALTIME_SAMPLE_RATE),
                        noiseGateDb = noiseGateDb,
                        outputDenoiseEnabled = outputDenoiseEnabled,
                        vocalRangeFilterEnabled = vocalRangeFilterEnabled,
                        parallelChunkCount = parallelChunkCount,
                        extraInferenceLength = extraInferenceLength,
                        crossfadeLength = crossfadeLength,
                        delayBufferSeconds = delayBufferSeconds,
                        onStatus = { status -> sendRealtimeStatus(status) },
                    ),
                    modeToken = modeToken,
                    onStop = {
                        realtimeSession = null
                    },
                )
                realtimeSession = session
                session.start()
                withContext(Dispatchers.Main) { result.success(true) }
            } catch (e: RootPerformanceException) {
                modeToken.release()
                withContext(Dispatchers.Main) { result.error(e.code, e.message, null) }
            } catch (e: Exception) {
                modeToken.release()
                withContext(Dispatchers.Main) { result.error("REALTIME_FAILED", e.message, null) }
            }
        }
    }

    private fun stopRealtimeInference(result: Result) {
        scope.launch {
            realtimeSession?.stop()
            realtimeSession = null
            withContext(Dispatchers.Main) { result.success(true) }
        }
    }

    private fun isInferenceProcessRunning(result: Result) {
        result.success(RemoteInferenceClient.isInferenceProcessAlive(appContext))
    }

    private fun startRecording(result: Result) {
        try {
            require(recorder == null) { "正在录音" }
            val recordingDir = File(appContext.filesDir, "recordings")
            recordingDir.mkdirs()
            val file = File(recordingDir, "recording_${readableTimestamp()}.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recordingFile = file
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            result.success(true)
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            recordingFile = null
            recordingStartedAtMs = 0L
            result.error("RECORD_FAILED", e.message, null)
        }
    }

    private fun stopRecording(result: Result) {
        try {
            val activeRecorder = recorder ?: error("没有正在进行的录音")
            val file = recordingFile ?: error("录音文件不存在")
            val elapsedMs = SystemClock.elapsedRealtime() - recordingStartedAtMs
            if (elapsedMs < MIN_RECORDING_DURATION_MS) {
                runCatching { activeRecorder.stop() }
                activeRecorder.release()
                recorder = null
                recordingFile = null
                recordingStartedAtMs = 0L
                file.delete()
                result.error("RECORD_FAILED", "录音至少需要 1 秒", null)
                return
            }
            activeRecorder.stop()
            activeRecorder.release()
            recorder = null
            recordingFile = null
            recordingStartedAtMs = 0L
            require(file.length() > 0L) { "录音文件为空" }
            result.success(file.absolutePath)
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            recordingFile = null
            recordingStartedAtMs = 0L
            result.error("RECORD_FAILED", e.message, null)
        }
    }

    private fun saveGeneratedAudio(call: MethodCall, result: Result) {
        val sourcePath = call.argument<String>("sourcePath")
        if (sourcePath == null) {
            result.error("INVALID_ARGUMENTS", "Missing sourcePath", null)
            return
        }
        scope.launch {
            try {
                val savedPath = saveAudioToMediaStore(File(sourcePath))
                withContext(Dispatchers.Main) { result.success(savedPath) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { result.error("SAVE_FAILED", e.message, null) }
            }
        }
    }

    private fun convertIndex(call: MethodCall, result: Result) {
        val sourcePath = call.argument<String>("sourcePath")
        if (sourcePath == null) {
            result.error("INVALID_ARGUMENTS", "Missing sourcePath", null)
            return
        }
        scope.launch {
            try {
                val outputPath = convertMobileIndex(File(sourcePath)).absolutePath
                withContext(Dispatchers.Main) { result.success(outputPath) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { result.error("CONVERT_FAILED", e.message, null) }
            }
        }
    }

    private fun saveAudioToMediaStore(source: File): String {
        require(source.exists()) { "源文件不存在" }
        val displayPath = "Download/RVC_Convert/${source.name}"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RVC_Convert")
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建媒体文件")
        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } ?: error("无法写入媒体文件")
        runOnMain { Toast.makeText(appContext, "已保存在 $displayPath", Toast.LENGTH_LONG).show() }
        return displayPath
    }

    private fun acquireInferenceWakeLock(): PowerManager.WakeLock {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltimateRVC:Inference").apply {
            acquire(INFERENCE_WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun convertMobileIndex(source: File): File {
        require(source.exists()) { "索引文件不存在" }
        val bytes = source.readBytes()
        val magic = FEATURE_INDEX_MAGIC.toByteArray(Charsets.US_ASCII)
        require(bytes.size >= magic.size + Int.SIZE_BYTES) { "请先在 mobile.index 转换页处理标准 RVC index" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "请先在 mobile.index 转换页处理标准 RVC index" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(magic.size)
        val frameCount = buffer.int
        require(frameCount > 0) { "mobile.index 数据无效" }
        val outputDir = File(appContext.filesDir, "indexes")
        outputDir.mkdirs()
        val output = File(outputDir, source.name)
        source.copyTo(output, overwrite = true)
        return output
    }

    private fun getModelsDirectory(): String {
        return File(appContext.filesDir, "models").absolutePath
    }

    private fun getRootPerformanceCacheFile(): File {
        return File(appContext.filesDir, "root_performance/node_cache.txt")
    }

    private fun readableTimestamp(): String {
        return SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.US).format(Date())
    }

    private fun ensureBundledModelsReady(): Boolean {
        val modelsDir = File(getModelsDirectory())
        modelsDir.mkdirs()

        val requiredModels = listOf("hubert.onnx", "rmvpe.onnx")
        for (modelName in requiredModels) {
            val target = File(modelsDir, modelName)
            if (!target.exists()) {
                val assetPaths = listOf(
                    "assets/flutter_assets/assets/models/$modelName",
                    "flutter_assets/assets/models/$modelName",
                )

                val copied = assetPaths.any { assetPath ->
                    try {
                        appContext.assets.open(assetPath).use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        if (target.exists()) {
                            target.delete()
                        }
                        false
                    }
                }

                if (!copied) {
                    return false
                }
            }
        }

        return requiredModels.all { File(modelsDir, it).exists() }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private fun sendResultError(result: Result, code: String, message: String) {
        runOnMain { result.error(code, message, null) }
    }

    private fun sendProgress(progress: Map<String, Any>) {
        runOnMain { progressEventSink?.success(progress) }
    }

    private fun sendProgress(percent: Double, step: String) {
        sendProgress(mapOf(
            "percent" to percent,
            "current_step" to step,
            "eta" to 0,
        ))
    }

    private fun sendError(error: String) {
        runOnMain { progressEventSink?.error("PYTHON_ERROR", error, null) }
    }

    override fun onListen(arguments: Any?, events: EventSink?) {
        progressEventSink = events
    }

    override fun onCancel(arguments: Any?) {
        progressEventSink = null
    }

    private fun sendRealtimeStatus(status: Map<String, Any>) {
        runOnMain { realtimeEventSink?.success(status) }
    }

    private fun startDecibelMeter(events: EventSink?) {
        stopDecibelMeter()
        if (events == null) return
        decibelSession = DecibelMeterSession { value -> runOnMain { events.success(value) } }.also { it.start() }
    }

    private fun stopDecibelMeter() {
        decibelSession?.stop()
        decibelSession = null
    }

    private class RootPerformanceException(val code: String, message: String) : Exception(message)

    private class RealtimeRvcSession(
        private val remoteRealtimeSession: RemoteInferenceClient.RemoteRealtimeInferenceSession,
        private val modeToken: NativeModeGuardToken,
        private val onStop: () -> Unit,
    ) {
        private val stopped = AtomicBoolean(false)

        fun start() {
            check(!stopped.get()) { "实时推理会话已关闭" }
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            remoteRealtimeSession.close()
            requestNativeMemoryCleanup()
            modeToken.release()
            onStop()
        }

        private fun requestNativeMemoryCleanup() {
            System.runFinalization()
            System.gc()
        }
    }

    private fun requestNativeMemoryCleanup() {
        System.runFinalization()
        System.gc()
    }

    private class DecibelMeterSession(private val sendDecibel: (Double) -> Unit) {
        private val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(REALTIME_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            REALTIME_MIN_BUFFER_FRAMES * BYTES_PER_PCM_16,
        )
        private val audioBuffer = ShortArray(bufferSize / BYTES_PER_PCM_16)
        private var running = false
        private var worker: Job? = null
        private val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            REALTIME_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        fun start() {
            require(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "无法初始化分贝仪输入设备" }
            running = true
            audioRecord.startRecording()
            worker = CoroutineScope(Dispatchers.Default).launch {
                try {
                    while (running) {
                        val read = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                        if (read > 0) {
                            sendDecibel(calculateDecibel(read))
                        }
                    }
                } finally {
                    stop()
                }
            }
        }

        fun stop() {
            if (!running) return
            running = false
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }

        private fun calculateDecibel(read: Int): Double {
            var sum = 0.0
            for (index in 0 until read) {
                val sample = audioBuffer[index].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / read)
            val decibel = 20.0 * kotlin.math.log10(rms.coerceAtLeast(1.0))
            return if (decibel.isFinite()) decibel.coerceAtLeast(0.0) else 0.0
        }
    }

    private data class RootCommandResult(
        val exitCode: Int,
        val output: List<String>,
    )

    private data class CpuPolicyState(
        val path: String,
        val minFreq: String?,
        val maxFreq: String?,
        val governor: String?,
        val minFreqMode: String?,
        val maxFreqMode: String?,
        val governorMode: String?,
    )

    private data class AppliedPolicy(
        val original: CpuPolicyState,
        val targetFreq: String?,
        val changed: Boolean,
        val applied: Boolean,
    )

    private data class CpuOnlineState(
        val path: String,
        val online: String?,
        val mode: String?,
    )

    private data class RootWritableState(
        val path: String,
        val value: String?,
        val mode: String?,
    )

    private data class RootNodeCache(
        val policyPaths: List<String>,
        val onlinePaths: List<String>,
        val boostPaths: List<String>,
    )

    class RootPerformanceSession private constructor(
        private val appliedPolicies: List<AppliedPolicy>,
        private val originalOnlineStates: List<CpuOnlineState>,
        private val originalAffinity: String?,
        private val originalNice: String?,
        private val originalCpuset: String?,
        private val originalTunableValues: List<RootWritableState>,
        private val originalBoostStates: List<RootWritableState>,
        private val keepAliveJob: Job?,
    ) {
        private val restored = AtomicBoolean(false)

        fun restore() {
            if (!restored.compareAndSet(false, true)) return
            keepAliveJob?.cancel()
            restoreCpuAffinity(originalAffinity)
            restoreProcessPriority(originalNice)
            restoreCpuset(originalCpuset)
            originalTunableValues.asReversed().forEach { tunable ->
                tunable.value?.let { runCatching { restoreRootFile(tunable.path, it, tunable.mode) } }
            }
            originalBoostStates.asReversed().forEach { boost ->
                boost.value?.let { runCatching { restoreRootFile(boost.path, it, boost.mode) } }
            }
            appliedPolicies.asReversed().forEach { policy ->
                val original = policy.original
                original.governor?.let { runCatching { restoreRootFile("${original.path}/scaling_governor", it, original.governorMode) } }
                original.maxFreq?.let { runCatching { restoreRootFile("${original.path}/scaling_max_freq", it, original.maxFreqMode) } }
                original.minFreq?.let { runCatching { restoreRootFile("${original.path}/scaling_min_freq", it, original.minFreqMode) } }
            }
            originalOnlineStates.asReversed().forEach { cpu ->
                cpu.online?.let { runCatching { restoreRootFile("${cpu.path}/online", it, cpu.mode) } }
            }
        }

        companion object {
            fun startIfEnabled(enabled: Boolean, cacheFile: File? = null, onStatus: (Map<String, Any>) -> Unit): RootPerformanceSession? {
                if (!enabled) return null
                requireRootAccess()
                val originalNice = readRootCommand("ps -o ni= -p ${Process.myPid()}").output.firstOrNull()?.trim()
                val originalAffinity = readRootCommand("taskset -p ${Process.myPid()}").output.firstOrNull()
                val originalCpuset = readRootFile("/proc/${Process.myPid()}/cpuset")
                val cache = loadRootNodeCache(cacheFile)
                val onlinePaths = cache?.onlinePaths?.filter { rootPathExists(it) }?.takeIf { it.isNotEmpty() }
                    ?: onlineCpuDirs().map { "${it.absolutePath}/online" }
                val originalOnlineStates = onlinePaths.mapNotNull { onlinePath ->
                    if (!rootPathExists(onlinePath)) return@mapNotNull null
                    CpuOnlineState(
                        path = onlinePath.removeSuffix("/online"),
                        online = readRootFile(onlinePath),
                        mode = readRootMode(onlinePath),
                    )
                }
                originalOnlineStates.forEach { cpu ->
                    writeRootFileWithLock("${cpu.path}/online", "1")
                }

                val policyPaths = cache?.policyPaths?.filter { rootPathExists(it) }?.takeIf { it.isNotEmpty() }
                    ?: File("/sys/devices/system/cpu/cpufreq")
                        .listFiles { file -> file.isDirectory && file.name.startsWith("policy") }
                        ?.sortedBy { it.name }
                        ?.map { it.absolutePath }
                        .orEmpty()

                if (policyPaths.isEmpty()) {
                    throw RootPerformanceException("ROOT_PERFORMANCE_APPLY_FAILED", "未找到 CPU 频率策略")
                }

                val boostPaths = cache?.boostPaths?.filter { rootPathExists(it) }?.takeIf { it.isNotEmpty() }
                    ?: detectBoostPaths()
                val originalBoostStates = boostPaths.mapNotNull { boostPath ->
                    if (!rootPathExists(boostPath)) return@mapNotNull null
                    RootWritableState(boostPath, readRootFile(boostPath), readRootMode(boostPath))
                }

                val appliedPolicies = policyPaths.map { policyPath -> applyPolicy(policyPath) }
                if (appliedPolicies.any { !it.applied }) {
                    throw RootPerformanceException("ROOT_PERFORMANCE_APPLY_FAILED", "部分 CPU 频率策略应用失败")
                }
                originalBoostStates.forEach { boost ->
                    writeRootFileWithLock(boost.path, "1")
                }
                saveRootNodeCache(cacheFile, RootNodeCache(policyPaths, originalOnlineStates.map { "${it.path}/online" }, originalBoostStates.map { it.path }))
                val keepAliveJob = CoroutineScope(Dispatchers.IO).launch {
                    while (isActive) {
                        ensureAllCpusOnline(originalOnlineStates)
                        appliedPolicies.forEach { policy ->
                            ensurePolicyApplied(policy.original.path)
                        }
                        originalBoostStates.forEach { boost ->
                            ensureBoostEnabled(boost.path)
                        }
                        delay(ROOT_PERFORMANCE_REAPPLY_INTERVAL_MS)
                    }
                }
                boostProcessPriority()
                bindProcessToBigCores(policyPaths.map(::File))
                val originalTunableValues = boostSchedulerHints()
                onStatus(mapOf("percent" to 1.0, "current_step" to "ROOT 性能方案已启用", "eta" to 0))
                return RootPerformanceSession(appliedPolicies, originalOnlineStates, originalAffinity, originalNice, originalCpuset, originalTunableValues, originalBoostStates, keepAliveJob)
            }

            private fun requireRootAccess() {
                val result = try {
                    runSuIdCommand()
                } catch (e: Exception) {
                    throw RootPerformanceException("ROOT_UNAVAILABLE", "未获得 root 授权")
                }
                if (result.exitCode != 0 || result.output.none { it.contains("uid=0") }) {
                    throw RootPerformanceException("ROOT_UNAVAILABLE", "未获得 root 授权")
                }
            }

            private fun applyPolicy(path: String): AppliedPolicy {
                val original = CpuPolicyState(
                    path = path,
                    minFreq = readRootFile("$path/scaling_min_freq"),
                    maxFreq = readRootFile("$path/scaling_max_freq"),
                    governor = readRootFile("$path/scaling_governor"),
                    minFreqMode = readRootMode("$path/scaling_min_freq"),
                    maxFreqMode = readRootMode("$path/scaling_max_freq"),
                    governorMode = readRootMode("$path/scaling_governor"),
                )
                val targetFreq = resolvePolicyTargetFreq(path)
                val governor = preferredGovernor(readRootFile("$path/scaling_available_governors"))

                var changed = false
                if (governor != null) {
                    changed = writeRootFileWithLock("$path/scaling_governor", governor) || changed
                }
                if (targetFreq != null) {
                    changed = writeRootFileWithLock("$path/scaling_max_freq", targetFreq) || changed
                    changed = writeRootFileWithLock("$path/scaling_min_freq", targetFreq) || changed
                }
                val applied = verifyPolicyApplied(path, targetFreq, governor)
                return AppliedPolicy(original = original, targetFreq = targetFreq, changed = changed, applied = applied)
            }

            private fun ensurePolicyApplied(path: String) {
                val targetFreq = resolvePolicyTargetFreq(path) ?: return
                preferredGovernor(readRootFile("$path/scaling_available_governors"))?.let { governor ->
                    writeRootFileWithLock("$path/scaling_governor", governor)
                }
                writeRootFileWithLock("$path/scaling_max_freq", targetFreq)
                writeRootFileWithLock("$path/scaling_min_freq", targetFreq)
            }

            private fun resolvePolicyTargetFreq(path: String): String? {
                val availableFreqs = readRootFile("$path/scaling_available_frequencies")
                    ?.split(Regex("\\s+"))
                    ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
                    .orEmpty()
                if (availableFreqs.isNotEmpty()) {
                    return availableFreqs.maxOrNull()?.toString()
                }
                return readRootFile("$path/cpuinfo_max_freq")?.takeIf { it.all(Char::isDigit) }
            }

            private fun verifyPolicyApplied(path: String, targetFreq: String?, governor: String?): Boolean {
                val freqApplied = targetFreq == null || (
                    readRootFile("$path/scaling_max_freq") == targetFreq &&
                        readRootFile("$path/scaling_min_freq") == targetFreq
                    )
                val governorApplied = governor == null || readRootFile("$path/scaling_governor") == governor
                return freqApplied && governorApplied
            }

            private fun ensureAllCpusOnline(originalOnlineStates: List<CpuOnlineState>) {
                originalOnlineStates.forEach { cpu ->
                    writeRootFileWithLock("${cpu.path}/online", "1")
                }
            }

            private fun ensureBoostEnabled(path: String) {
                writeRootFileWithLock(path, "1")
            }

            private fun preferredGovernor(governorsRaw: String?): String? {
                val governors = governorsRaw
                    ?.split(Regex("\\s+"))
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                return when {
                    governors.contains("performance") -> "performance"
                    governors.contains("schedutil") -> "schedutil"
                    governors.contains("ondemand") -> "ondemand"
                    governors.isNotEmpty() -> governors.first()
                    else -> null
                }
            }

            private fun onlineCpuDirs(): List<File> {
                return File("/sys/devices/system/cpu")
                    .listFiles { file -> file.isDirectory && file.name.matches(Regex("cpu\\d+")) }
                    ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
                    .orEmpty()
            }

            private fun boostProcessPriority() {
                runRootCommand("renice -n -20 -p ${Process.myPid()}")
            }

            private fun restoreProcessPriority(originalNice: String?) {
                val nice = originalNice?.toIntOrNull() ?: 0
                runRootCommand("renice -n $nice -p ${Process.myPid()}")
            }

            private fun bindProcessToBigCores(policyDirs: List<File>) {
                val policyMax = policyDirs.mapNotNull { policy ->
                    val maxFreq = readRootFile("${policy.absolutePath}/cpuinfo_max_freq")?.toLongOrNull()
                    val relatedCpus = readRootFile("${policy.absolutePath}/related_cpus")
                    if (maxFreq == null || relatedCpus == null) null else maxFreq to relatedCpus
                }
                val highest = policyMax.maxByOrNull { it.first }?.second ?: return
                val cpuMask = cpuListToTasksetMask(highest) ?: return
                runRootCommand("taskset -p $cpuMask ${Process.myPid()}")
            }

            private fun restoreCpuAffinity(originalAffinity: String?) {
                val mask = originalAffinity?.substringAfterLast(":")?.trim()?.takeIf { it.isNotBlank() } ?: return
                runRootCommand("taskset -p $mask ${Process.myPid()}")
            }

            private fun restoreCpuset(originalCpuset: String?) {
                val cpuset = originalCpuset?.trim()?.takeIf { it.isNotBlank() } ?: return
                writeFirstExistingRootFile(listOf("/dev/cpuset$cpuset/cgroup.procs"), Process.myPid().toString())
            }

            private fun boostSchedulerHints(): List<RootWritableState> {
                writeFirstExistingRootFile(
                    listOf(
                        "/dev/cpuset/top-app/cgroup.procs",
                        "/dev/cpuset/foreground/cgroup.procs",
                    ),
                    Process.myPid().toString(),
                )
                val tunables = listOf(
                    "/dev/stune/top-app/schedtune.boost",
                    "/dev/stune/foreground/schedtune.boost",
                    "/dev/cpuctl/top-app/cpu.uclamp.min",
                    "/dev/cpuctl/foreground/cpu.uclamp.min",
                )
                    .filter { rootPathExists(it) }
                val originalValues = tunables.map { path -> RootWritableState(path, readRootFile(path), readRootMode(path)) }
                tunables.forEach { path ->
                    val value = if (path.contains("uclamp.min")) "1024" else "100"
                    writeRootFileWithLock(path, value)
                }
                return originalValues
            }

            private fun detectBoostPaths(): List<String> {
                return listOf(
                    "/sys/devices/system/cpu/cpufreq/boost",
                ).filter { rootPathExists(it) }
            }

            private fun loadRootNodeCache(cacheFile: File?): RootNodeCache? {
                val file = cacheFile?.takeIf { it.exists() } ?: return null
                return runCatching {
                    val lines = file.readLines()
                    val entries = lines.associate { line ->
                        val parts = line.split('=', limit = 2)
                        parts.first() to parts.getOrElse(1) { "" }
                    }
                    RootNodeCache(
                        policyPaths = entries["policies"].orEmpty().split('|').filter { it.isNotBlank() },
                        onlinePaths = entries["online"].orEmpty().split('|').filter { it.isNotBlank() },
                        boostPaths = entries["boost"].orEmpty().split('|').filter { it.isNotBlank() },
                    )
                }.getOrNull()
            }

            private fun saveRootNodeCache(cacheFile: File?, cache: RootNodeCache) {
                if (cacheFile == null) return
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeText(
                        buildString {
                            append("policies=")
                            append(cache.policyPaths.joinToString("|"))
                            append('\n')
                            append("online=")
                            append(cache.onlinePaths.joinToString("|"))
                            append('\n')
                            append("boost=")
                            append(cache.boostPaths.joinToString("|"))
                            append('\n')
                        },
                    )
                }
            }

            private fun cpuListToTasksetMask(cpuList: String): String? {
                var mask = 0L
                cpuList.split(",").forEach { part ->
                    val bounds = part.trim().split("-")
                    val start = bounds.getOrNull(0)?.toIntOrNull() ?: return@forEach
                    val end = bounds.getOrNull(1)?.toIntOrNull() ?: start
                    for (cpu in start..end) {
                        if (cpu in 0..62) {
                            mask = mask or (1L shl cpu)
                        }
                    }
                }
                return if (mask == 0L) null else "0x${mask.toString(16)}"
            }

            private fun runSuIdCommand(): RootCommandResult {
                val process = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readLines()
                return RootCommandResult(process.waitFor(), output)
            }

            private fun runRootCommand(command: String): RootCommandResult {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readLines()
                return RootCommandResult(process.waitFor(), output)
            }

            private fun readRootCommand(command: String): RootCommandResult {
                return runRootCommand(command)
            }

            private fun readRootFile(path: String): String? {
                val result = runRootCommand("cat $path")
                return result.output.firstOrNull()?.trim()?.takeIf { result.exitCode == 0 && it.isNotEmpty() }
            }

            private fun readRootMode(path: String): String? {
                val result = runRootCommand("stat -c %a $path")
                return result.output.firstOrNull()?.trim()?.takeIf { result.exitCode == 0 && it.all(Char::isDigit) }
            }

            private fun rootPathExists(path: String): Boolean {
                return runRootCommand("test -e $path").exitCode == 0
            }

            private fun writeFirstExistingRootFile(paths: List<String>, value: String): Boolean {
                return paths.firstOrNull { rootPathExists(it) }?.let { writeRootFile(it, value) } ?: false
            }

            private fun restoreRootFile(path: String, value: String, originalMode: String?): Boolean {
                unlockRootFile(path)
                val wrote = writeRootFile(path, value)
                originalMode?.let { chmodRootFile(path, it) }
                return wrote
            }

            private fun writeRootFileWithLock(path: String, value: String): Boolean {
                unlockRootFile(path)
                val wrote = writeRootFile(path, value)
                if (wrote) {
                    chmodRootFile(path, ROOT_LOCK_MODE)
                }
                return wrote
            }

            private fun unlockRootFile(path: String) {
                chmodRootFile(path, ROOT_UNLOCK_MODE)
            }

            private fun chmodRootFile(path: String, mode: String): Boolean {
                if (mode.any { !it.isDigit() }) return false
                return runRootCommand("chmod $mode $path").exitCode == 0
            }

            private fun writeRootFile(path: String, value: String): Boolean {
                if (!isSafeSysfsValue(value)) return false
                val result = runRootCommand("printf %s $value > $path")
                return result.exitCode == 0
            }

            private fun isSafeSysfsValue(value: String): Boolean {
                return value.isNotBlank() && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            }
        }
    }

    private companion object {
        const val DEFAULT_OUTPUT_SAMPLE_RATE = 48_000
        const val DEFAULT_PARALLEL_CHUNK_COUNT = 4
        const val FEATURE_INDEX_MAGIC = "URVCIDX1"
        const val MIN_RECORDING_DURATION_MS = 1_000L
        const val INFERENCE_WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        const val REALTIME_SAMPLE_RATE = 48_000
        const val MIN_REALTIME_SAMPLE_RATE = 40_000
        const val MAX_REALTIME_SAMPLE_RATE = 48_000
        const val MIN_REALTIME_SAMPLE_LENGTH_SECONDS = 1.0
        const val MAX_REALTIME_SAMPLE_LENGTH_SECONDS = 12.0
        const val REALTIME_MIN_BUFFER_FRAMES = 4_096
        const val REALTIME_INPUT_ALIGNMENT_FRAMES = 960
        const val REALTIME_HOP_MS = 40
        const val REALTIME_OUTPUT_STARTUP_HOPS = 2
        const val REALTIME_PROCESS_POLL_MS = 10L
        const val ROOT_PERFORMANCE_REAPPLY_INTERVAL_MS = 300L
        const val ROOT_LOCK_MODE = "444"
        const val ROOT_UNLOCK_MODE = "644"
        const val BYTES_PER_PCM_16 = 2
        const val WAV_HEADER_BYTES = 44
    }
}
