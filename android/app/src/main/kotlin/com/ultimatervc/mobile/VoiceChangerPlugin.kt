package com.ultimatervc.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class VoiceChangerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private var methodChannel: MethodChannel? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        private fun invokeOnMainThread(method: String, arguments: Any?) {
            mainHandler.post {
                methodChannel?.invokeMethod(method, arguments)
            }
        }

        fun notifyOverlayStopped() {
            invokeOnMainThread("voiceChangerOverlayStopped", null)
        }

        fun notifyOverlayProcessingChanged(processing: Boolean) {
            invokeOnMainThread("voiceChangerProcessingChanged", processing)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "ultimate_rvc_voice_changer")
        methodChannel = channel
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel = null
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startVoiceChangerOverlay" -> startVoiceChangerOverlay(call, result)
            "stopVoiceChangerOverlay" -> stopVoiceChangerOverlay(result)
            "getResumableVoiceChangerJobMetadata" -> getResumableVoiceChangerJobMetadata(call, result)
            else -> result.notImplemented()
        }
    }

    private fun startVoiceChangerOverlay(call: MethodCall, result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(appContext)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${appContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            result.error("OVERLAY_PERMISSION_REQUIRED", "需要开启悬浮窗权限", null)
            return
        }
        val modelPath = call.argument<String>("modelPath")
        if (modelPath.isNullOrBlank()) {
            result.error("INVALID_ARGUMENTS", "请选择 .onnx 音色模型", null)
            return
        }
        val intent = Intent(appContext, VoiceChangerOverlayService::class.java).apply {
            putExtra("modelPath", modelPath)
            putExtra("indexPath", call.argument<String>("indexPath"))
            putExtra("pitchChange", call.argument<Double>("pitchChange") ?: 0.0)
            putExtra("indexRate", call.argument<Double>("indexRate") ?: 0.75)
            putExtra("formant", call.argument<Double>("formant") ?: 0.0)
            putExtra("filterRadius", call.argument<Int>("filterRadius") ?: 3)
            putExtra("rmsMixRate", call.argument<Double>("rmsMixRate") ?: 0.25)
            putExtra("protectRate", call.argument<Double>("protectRate") ?: 0.33)
            putExtra("sampleRate", call.argument<Int>("sampleRate") ?: 48_000)
            putExtra("noiseGateDb", call.argument<Double>("noiseGateDb") ?: 35.0)
            putExtra("outputDenoiseEnabled", call.argument<Boolean>("outputDenoiseEnabled") ?: true)
            putExtra("vocalRangeFilterEnabled", call.argument<Boolean>("vocalRangeFilterEnabled") ?: true)
            putExtra("parallelChunkCount", call.argument<Int>("parallelChunkCount") ?: 4)
            putExtra("overlayDiameter", call.argument<Double>("overlayDiameter") ?: 100.0)
            putExtra("overlayOpacity", call.argument<Double>("overlayOpacity") ?: 0.7)
            putExtra("playbackDelaySeconds", call.argument<Double>("playbackDelaySeconds") ?: 3.0)
            putExtra("enableRootPerformanceMode", call.argument<Boolean>("enableRootPerformanceMode") ?: false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        result.success(true)
    }

    private fun stopVoiceChangerOverlay(result: MethodChannel.Result) {
        scope.launch {
            appContext.stopService(Intent(appContext, VoiceChangerOverlayService::class.java))
            RemoteInferenceClient.stopInferenceProcessAndWait(appContext)
            Handler(Looper.getMainLooper()).post {
                result.success(true)
            }
        }
    }

    private fun getResumableVoiceChangerJobMetadata(call: MethodCall, result: MethodChannel.Result) {
        val inputPath = call.argument<String>("inputPath")
        val modelPath = call.argument<String>("modelPath")
        if (modelPath.isNullOrBlank()) {
            result.success(null)
            return
        }
        val modelFile = File(modelPath)
        if (!modelFile.isFile) {
            result.success(null)
            return
        }
        val directMetadata = VoiceChangerRecorder(
            context = appContext,
            modelsDir = File(appContext.filesDir, "models"),
            config = VoiceChangerRecordingConfig(
                modelPath = modelPath,
                indexPath = call.argument<String>("indexPath"),
                pitchChange = call.argument<Double>("pitchChange") ?: 0.0,
                formant = call.argument<Double>("formant") ?: 0.0,
                indexRate = call.argument<Double>("indexRate") ?: 0.75,
                rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25,
                protectRate = call.argument<Double>("protectRate") ?: 0.33,
                filterRadius = call.argument<Int>("filterRadius") ?: 3,
                sampleRate = call.argument<Int>("sampleRate") ?: 48_000,
                noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0,
                outputDenoiseEnabled = call.argument<Boolean>("outputDenoiseEnabled") ?: true,
                vocalRangeFilterEnabled = call.argument<Boolean>("vocalRangeFilterEnabled") ?: true,
                parallelChunkCount = 1,
                playbackDelaySeconds = 3.0,
                enableRootPerformanceMode = false,
            ),
            onProcessingProgress = {},
            onProcessingComplete = {},
            onProcessingFailed = {},
            onNormalPlaybackComplete = {},
            onTrialPlaybackComplete = {},
        ).resumableMetadata()
        val directMetadataMap = if (directMetadata != null) {
            mapOf(
                "jobId" to directMetadata.jobId,
                "overallProgress" to directMetadata.overallProgress,
                "state" to directMetadata.state,
                "accumulatedElapsedMs" to 0,
            )
        } else {
            null
        }
        val resumableInputPath = inputPath ?: run {
            VoiceChangerRecorder(
                context = appContext,
                modelsDir = File(appContext.filesDir, "models"),
                config = VoiceChangerRecordingConfig(
                    modelPath = modelPath,
                    indexPath = call.argument<String>("indexPath"),
                    pitchChange = call.argument<Double>("pitchChange") ?: 0.0,
                    formant = call.argument<Double>("formant") ?: 0.0,
                    indexRate = call.argument<Double>("indexRate") ?: 0.75,
                    rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25,
                    protectRate = call.argument<Double>("protectRate") ?: 0.33,
                    filterRadius = call.argument<Int>("filterRadius") ?: 3,
                    sampleRate = call.argument<Int>("sampleRate") ?: 48_000,
                    noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0,
                    outputDenoiseEnabled = call.argument<Boolean>("outputDenoiseEnabled") ?: true,
                    vocalRangeFilterEnabled = call.argument<Boolean>("vocalRangeFilterEnabled") ?: true,
                    parallelChunkCount = 1,
                    playbackDelaySeconds = 3.0,
                    enableRootPerformanceMode = false,
                ),
                onProcessingProgress = {},
                onProcessingComplete = {},
                onProcessingFailed = {},
                onNormalPlaybackComplete = {},
                onTrialPlaybackComplete = {},
            ).currentInputPath()
        }
        if (resumableInputPath.isNullOrBlank() || !File(resumableInputPath).isFile) {
            result.success(directMetadataMap)
            return
        }
        val request = RvcInferenceRequest(
            songPath = resumableInputPath,
            modelPath = modelPath,
            indexPath = call.argument<String>("indexPath"),
            pitchChange = call.argument<Double>("pitchChange") ?: 0.0,
            indexRate = call.argument<Double>("indexRate") ?: 0.75,
            formant = call.argument<Double>("formant") ?: 0.0,
            filterRadius = call.argument<Int>("filterRadius") ?: 3,
            rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25,
            protectRate = call.argument<Double>("protectRate") ?: 0.33,
            sampleRate = call.argument<Int>("sampleRate") ?: 48_000,
            noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0,
            outputDenoiseEnabled = call.argument<Boolean>("outputDenoiseEnabled") ?: true,
            vocalRangeFilterEnabled = call.argument<Boolean>("vocalRangeFilterEnabled") ?: true,
            parallelChunkCount = 1,
            allowResume = true,
            workspaceRelativePath = ResumableInferenceJobStore.VOICE_CHANGER_INFERENCE_TEMP_DIRECTORY,
            cancellationToken = CancellationToken(),
            onProgress = { _, _ -> },
        )
        val metadata = RvcInferenceEngine(
            modelsDir = File(appContext.filesDir, "models"),
            resumableJobStore = ResumableInferenceJobStore(
                appContext.filesDir,
                ResumableInferenceJobStore.VOICE_CHANGER_INFERENCE_TEMP_DIRECTORY,
            ),
        ).getResumableMetadata(request)
        val mergedMetadata = when {
            metadata != null && directMetadata != null -> {
                if (metadata.overallProgress >= directMetadata.overallProgress) {
                    mapOf(
                        "jobId" to metadata.jobId,
                        "overallProgress" to metadata.overallProgress,
                        "state" to metadata.state,
                        "accumulatedElapsedMs" to metadata.accumulatedElapsedMs,
                    )
                } else {
                    mapOf(
                        "jobId" to directMetadata.jobId,
                        "overallProgress" to directMetadata.overallProgress,
                        "state" to directMetadata.state,
                        "accumulatedElapsedMs" to 0,
                    )
                }
            }
            metadata != null -> mapOf(
                "jobId" to metadata.jobId,
                "overallProgress" to metadata.overallProgress,
                "state" to metadata.state,
                "accumulatedElapsedMs" to metadata.accumulatedElapsedMs,
            )
            directMetadata != null -> mapOf(
                "jobId" to directMetadata.jobId,
                "overallProgress" to directMetadata.overallProgress,
                "state" to directMetadata.state,
                "accumulatedElapsedMs" to 0,
            )
            else -> null
        }
        val metadataMap = if (metadata != null) {
            mapOf(
                "jobId" to metadata.jobId,
                "overallProgress" to metadata.overallProgress,
                "state" to metadata.state,
                "accumulatedElapsedMs" to metadata.accumulatedElapsedMs,
            )
        } else {
            null
        }
        result.success(mergedMetadata ?: metadataMap)
    }
}
