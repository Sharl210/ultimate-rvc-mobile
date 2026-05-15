package com.ultimatervc.mobile

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class RvcInferenceRequest(
    val songPath: String,
    val modelPath: String,
    val indexPath: String?,
    val pitchChange: Double,
    val indexRate: Double,
    val formant: Double,
    val filterRadius: Int,
    val rmsMixRate: Double,
    val protectRate: Double,
    val sampleRate: Int,
    val noiseGateDb: Double,
    val outputDenoiseEnabled: Boolean,
    val vocalRangeFilterEnabled: Boolean,
    val parallelChunkCount: Int,
    val allowResume: Boolean = false,
    val workspaceRelativePath: String = ResumableInferenceJobStore.AUDIO_INFERENCE_TEMP_DIRECTORY,
    val cancellationToken: CancellationToken,
    val onProgress: (Double, String) -> Unit,
)

private data class VoiceChunkInput(
    val chunkFrameCount: Int,
    val centerOffsetFrames: Int,
    val centerFrameCount: Int,
    val phone: FloatArray,
    val pitch: LongArray,
    val pitchf: FloatArray,
    val noise: FloatArray,
)

class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    private val activeRunOptions = Collections.synchronizedSet(mutableSetOf<OrtSession.RunOptions>())

    fun cancel() {
        cancelled.set(true)
        activeRunOptions.toList().forEach { it.setTerminate(true) }
    }

    fun bindRunOptions(runOptions: OrtSession.RunOptions) {
        activeRunOptions.add(runOptions)
        if (cancelled.get()) {
            runOptions.setTerminate(true)
        }
    }

    fun clearRunOptions(runOptions: OrtSession.RunOptions) {
        activeRunOptions.remove(runOptions)
    }

    fun throwIfCancelled() {
        if (cancelled.get()) {
            throw java.util.concurrent.CancellationException("生成已中止")
        }
    }

    fun rethrowIfTerminateException(error: OrtException): Nothing {
        if (cancelled.get()) {
            throw java.util.concurrent.CancellationException("生成已中止").also { it.initCause(error) }
        }
        throw error
    }
}

data class ResumableInferenceMetadata(
    val jobId: String,
    val overallProgress: Double,
    val state: String,
    val accumulatedElapsedMs: Long,
)

class RvcInferenceEngine(
    private val modelsDir: File,
    private val resumableJobStore: ResumableInferenceJobStore? = null,
) {
    fun infer(request: RvcInferenceRequest): String {
        request.cancellationToken.throwIfCancelled()
        val songFile = File(request.songPath)
        val voiceModelFile = File(request.modelPath)

        require(songFile.exists()) { "音频文件不存在" }
        require(voiceModelFile.exists()) { "模型文件不存在" }
        request.indexPath?.let { require(File(it).exists()) { "索引文件不存在" } }
        require(request.modelPath.lowercase().endsWith(".onnx")) { "请选择 .onnx 音色模型" }
        request.indexPath?.let { require(it.lowercase().endsWith(".index")) { "请选择 .index 索引文件" } }

        val hubertModel = File(modelsDir, "hubert.onnx")
        val rmvpeModel = File(modelsDir, "rmvpe.onnx")
        val missingModels = listOf(hubertModel, rmvpeModel)
            .filterNot { it.exists() }
            .joinToString { it.name }
        require(missingModels.isEmpty()) { "缺少 ONNX 模型：$missingModels" }

        val audioPlan = inspectAudioPlan(songFile)
        val tempOutputDir = File(modelsDir.parentFile ?: modelsDir, request.workspaceRelativePath)
        tempOutputDir.mkdirs()
        val finalOutputDir = if (request.workspaceRelativePath == ResumableInferenceJobStore.VOICE_CHANGER_INFERENCE_TEMP_DIRECTORY) {
            File(tempOutputDir, "final_output")
        } else {
            File(modelsDir.parentFile ?: modelsDir, "audio_inference_output")
        }
        finalOutputDir.mkdirs()
        if (audioPlan.requiresSegmentation) {
            val outputPath = File(finalOutputDir, rvcOutputFileName(songFile, voiceModelFile))
            return inferSegmented(request, songFile, voiceModelFile, hubertModel, rmvpeModel, audioPlan, outputPath)
        }

        val gatedMono16k = prepareOfflineInput16k(songFile, request)
        val outputPath = File(finalOutputDir, rvcOutputFileName(songFile, voiceModelFile))

        OrtEnvironment.getEnvironment().use { environment ->
            request.onProgress(2.0, "准备 CPU 推理")

            createCpuOptimizedSession(environment, hubertModel).use { hubertSession ->
                createCpuOptimizedSession(environment, rmvpeModel).use { rmvpeSession ->
                        request.cancellationToken.throwIfCancelled()
                        val features = extractHubertFeatures(environment, hubertSession, gatedMono16k)
                        request.onProgress(25.0, "提取音色特征")
                        val phone100Hz = repeatFeaturesForRvc(features)
                        val actualFrameCount = phone100Hz.size / HUBERT_FEATURE_SIZE
                        val frameCount = alignVoiceFrameCount(actualFrameCount)
                        val phone = fitFeatures(phone100Hz, frameCount)
                        val index = loadOptionalFeatureIndex(request.indexPath, request.onProgress)
                        val indexedPhone = fuseIndexFeatures(phone, frameCount, index, request.indexRate)
                        request.cancellationToken.throwIfCancelled()
                        val pitchf = extractRmvpePitch(environment, rmvpeSession, gatedMono16k, frameCount, request.pitchChange)
                        val filteredPitchf = applyMedianPitchFilter(pitchf, request.filterRadius)
                        val pitch = filteredPitchf.map { coarsePitch(it) }.toLongArray()
                        val protectedPhone = applyProtectBlend(indexedPhone, phone, filteredPitchf, request.protectRate)
                        request.onProgress(40.0, "估计音高")

                        request.onProgress(55.0, "准备分块生成")

                        val sessionPoolSize = request.parallelChunkCount.coerceIn(1, MAX_PARALLEL_CHUNK_COUNT)
                        val voiceSessionThreadCount = voiceChunkThreadCount(sessionPoolSize)
                        val voiceSessions = MutableList(sessionPoolSize) {
                            createCpuOptimizedSession(environment, voiceModelFile, voiceSessionThreadCount)
                        }
                        val audio = try {
                            synthesizeVoice(
                                environment = environment,
                                voiceSessions = voiceSessions,
                                phone = protectedPhone,
                                frameCount = frameCount,
                                pitch = pitch,
                                pitchf = filteredPitchf,
                                parallelChunkCount = request.parallelChunkCount,
                                cancellationToken = request.cancellationToken,
                                onProgress = request.onProgress,
                            )
                        } finally {
                            voiceSessions.asReversed().forEach { it.close() }
                        }
                        val formantAudio = applyFormant(audio, request.formant)
                        val matchedAudio = mixRms(formantAudio, gatedMono16k, request.rmsMixRate, request.sampleRate)
                        val filteredAudio = applyOutputAudioFilters(matchedAudio, request.noiseGateDb, request.outputDenoiseEnabled, request.vocalRangeFilterEnabled, request.sampleRate)
                        request.onProgress(98.0, "写入音频")
                        request.cancellationToken.throwIfCancelled()
                        writeWav(outputPath, filteredAudio, request.sampleRate)
                }
            }
        }

        return outputPath.absolutePath
    }

    private fun rvcOutputFileName(input: File, model: File): String {
        val inputBase = input.nameWithoutExtension.ifBlank { "input" }
        val modelBase = model.nameWithoutExtension.ifBlank { "model" }
        return "${inputBase}_[${modelBase}].rvc.wav"
    }

    fun getResumableMetadata(request: RvcInferenceRequest): ResumableInferenceMetadata? {
        val store = resumableJobStore ?: return null
        if (!store.hasResumableArtifacts()) return null
        val manifest = store.loadCurrentManifest() ?: return null
        if (manifest.state == ResumableInferenceJobStore.ResumableInferenceJobState.COMPLETED) {
            return null
        }
        val chunksDirectory = File(manifest.chunksDirectoryPath)
        val outputsDirectory = File(manifest.outputsDirectoryPath)
        val hasAnyArtifacts = chunksDirectory.isDirectory && chunksDirectory.listFiles()?.any { it.isFile } == true ||
            outputsDirectory.isDirectory && outputsDirectory.listFiles()?.any { it.isFile } == true
        if (!hasAnyArtifacts) {
            return null
        }
        return ResumableInferenceMetadata(
            jobId = manifest.jobId,
            overallProgress = manifest.overallProgress,
            state = manifest.state.name,
            accumulatedElapsedMs = manifest.accumulatedElapsedMs,
        )
    }

    fun clearResumableCache(request: RvcInferenceRequest): Boolean {
        val store = resumableJobStore ?: return false
        val manifest = store.findReusableManifest(
            sourceAudioPath = request.songPath,
            modelPath = request.modelPath,
            indexPath = request.indexPath,
            parameterFingerprint = parameterFingerprint(request),
        ) ?: return false
        store.deleteJob(manifest.jobId)
        return true
    }

    private fun inferSegmented(
        request: RvcInferenceRequest,
        songFile: File,
        voiceModelFile: File,
        hubertModel: File,
        rmvpeModel: File,
        audioPlan: AudioPlan,
        outputPath: File,
    ): String {
        val segments = buildAudioSegments(audioPlan.durationUs)
        require(segments.isNotEmpty()) { "音频分段结果为空" }
        val store = resumableJobStore
        val fingerprint = parameterFingerprint(request)
        var manifest = store?.findReusableManifest(
            sourceAudioPath = request.songPath,
            modelPath = request.modelPath,
            indexPath = request.indexPath,
            parameterFingerprint = fingerprint,
        )
        if (!request.allowResume && manifest != null) {
            store?.deleteJob(manifest.jobId)
            manifest = null
        }
        if (manifest?.segmentCount != segments.size) {
            manifest?.let { existing -> store?.deleteJob(existing.jobId) }
            manifest = null
        }
        if (manifest == null && store != null) {
            manifest = store.createManifest(
                sourceAudioPath = request.songPath,
                modelPath = request.modelPath,
                indexPath = request.indexPath,
                parameterFingerprint = fingerprint,
                segmentCount = segments.size,
            )
        }
        val activeManifest = manifest
        val layout = if (store != null && activeManifest != null) store.prepareLayout(activeManifest.jobId) else null
        if (manifest != null && store != null) {
            val diskCompleted = store.existingConvertedChunkIndexes(manifest.jobId, manifest.segmentCount)
            if (diskCompleted.isNotEmpty() && diskCompleted != manifest.completedChunkIndexes) {
                manifest = manifest.copy(
                    completedChunkIndexes = diskCompleted,
                    lastCompletedChunkIndex = diskCompleted.maxOrNull() ?: -1,
                    overallProgress = completedChunkProgressPercent(diskCompleted.size, manifest.segmentCount),
                )
                store.saveManifest(manifest)
            }
        }
        request.onProgress(1.0, "准备分段处理")
        if (activeManifest != null) {
            val isResumingExistingProgress = activeManifest.completedChunkIndexes.isNotEmpty()
            request.onProgress(
                activeManifest.overallProgress.coerceAtLeast(1.0),
                if (isResumingExistingProgress) "恢复分段任务" else "准备分段任务",
            )
            val runningManifest = activeManifest.copy(state = ResumableInferenceJobStore.ResumableInferenceJobState.RUNNING)
            manifest = runningManifest
            store?.saveManifest(manifest)
            store?.saveManifest(runningManifest)
        }
        if (layout != null) {
            prepareInputChunkFiles(songFile, segments, layout, request)
        }

        val allChunksAlreadyReady = manifest != null && manifest.completedChunkIndexes.size == segments.size
        try {
            if (allChunksAlreadyReady) {
                request.onProgress((manifest?.overallProgress ?: 95.0).coerceAtLeast(95.0), "恢复最终合并")
            }
            if (!allChunksAlreadyReady) {
            OrtEnvironment.getEnvironment().use { environment ->
                createCpuOptimizedSession(environment, hubertModel).use { hubertSession ->
                    createCpuOptimizedSession(environment, rmvpeModel).use { rmvpeSession ->
                        val sessionPoolSize = request.parallelChunkCount.coerceIn(1, MAX_PARALLEL_CHUNK_COUNT)
                        val voiceSessionThreadCount = voiceChunkThreadCount(sessionPoolSize)
                        val voiceSessions = MutableList(sessionPoolSize) {
                            createCpuOptimizedSession(environment, voiceModelFile, voiceSessionThreadCount)
                        }
                        try {
                            WavSegmentWriter(outputPath, request.sampleRate).use { writer ->
                                segments.forEachIndexed { segmentIndex, segment ->
                                    request.cancellationToken.throwIfCancelled()
                                    val segmentStartedAtMs = SystemClock.elapsedRealtime()
                                    val currentManifest = manifest
                                    if (currentManifest != null && layout != null) {
                                    val convertedChunkFile = layout.convertedChunkFile(segmentIndex)
                                    if (currentManifest.completedChunkIndexes.contains(segmentIndex) && convertedChunkFile.isFile) {
                                        appendSegmentAudio(writer, readCachedSegmentAudio(convertedChunkFile), segment)
                                        return@forEachIndexed
                                    }
                                }
                                    val decoded = if (layout != null) {
                                        readDecodedAudioWav(layout.chunkFile(segmentIndex))
                                    } else {
                                        decodeAudioSegment(songFile, segment.decodeStartUs, segment.decodeEndUs)
                                    }
                                    val segmentAudio = convertDecodedAudio(
                                        request = request,
                                        environment = environment,
                                        hubertSession = hubertSession,
                                        rmvpeSession = rmvpeSession,
                                        voiceSessions = voiceSessions,
                                        decoded = decoded,
                                        applyOutputPostProcessing = false,
                                        onProgress = { percent, step ->
                                            val overallPercent = mapSegmentProgress(segmentIndex, segments.size, percent)
                                            request.onProgress(overallPercent, "第${segmentIndex + 1}/${segments.size}段：$step")
                                        },
                                    )
                                    if (layout != null) {
                                        writeWav(layout.convertedChunkFile(segmentIndex), segmentAudio, request.sampleRate)
                                    }
                                    appendSegmentAudio(writer, segmentAudio, segment)
                                    val manifestBeforeUpdate = manifest
                                    if (manifestBeforeUpdate != null) {
                                        val completedChunks = (manifestBeforeUpdate.completedChunkIndexes + segmentIndex).distinct().sorted()
                                        val overallPercent = completedChunkProgressPercent(completedChunks.size, segments.size)
                                        val chunkElapsedMs = (SystemClock.elapsedRealtime() - segmentStartedAtMs).coerceAtLeast(0L)
                                        val resumedElapsedMs = manifestBeforeUpdate.accumulatedElapsedMs + chunkElapsedMs
                                        val updatedManifest = manifestBeforeUpdate.copy(
                                            completedChunkIndexes = completedChunks,
                                            lastCompletedChunkIndex = segmentIndex,
                                            overallProgress = overallPercent,
                                            accumulatedElapsedMs = resumedElapsedMs,
                                            state = ResumableInferenceJobStore.ResumableInferenceJobState.PAUSED,
                                        )
                                        manifest = updatedManifest
                                        store?.saveManifest(updatedManifest)
                                        request.onProgress(
                                            overallPercent,
                                            "保存进度点 ${segmentIndex + 1}",
                                        )
                                    }
                                }
                            }
                        } finally {
                            voiceSessions.asReversed().forEach { it.close() }
                        }
                    }
                }
            }
            }
        } catch (error: java.util.concurrent.CancellationException) {
            manifest?.let { pausedManifest ->
                store?.saveManifest(pausedManifest.copy(
                    state = ResumableInferenceJobStore.ResumableInferenceJobState.PAUSED,
                ))
            }
            throw error
        } catch (error: Throwable) {
            manifest?.let { failedManifest ->
                store?.saveManifest(failedManifest.copy(
                    state = ResumableInferenceJobStore.ResumableInferenceJobState.FAILED,
                ))
            }
            throw error
        }

        manifest?.let {
            val finalizePending = it.copy(state = ResumableInferenceJobStore.ResumableInferenceJobState.FINALIZE_PENDING)
            manifest = finalizePending
            store?.saveManifest(finalizePending)
        }
        requestNativeMemoryCleanup()
        finalizeSegmentedOutput(request, songFile, outputPath, layout, segments)

        manifest?.let {
            store?.saveManifest(it.copy(
                overallProgress = 100.0,
                state = ResumableInferenceJobStore.ResumableInferenceJobState.COMPLETED,
            ))
            store?.deleteJob(it.jobId)
        }
        request.onProgress(100.0, "生成完成")
        return outputPath.absolutePath
    }

    private fun parameterFingerprint(request: RvcInferenceRequest): String = JSONObject().apply {
        put("pitchChange", request.pitchChange)
        put("indexRate", request.indexRate)
        put("formant", request.formant)
        put("filterRadius", request.filterRadius)
        put("rmsMixRate", request.rmsMixRate)
        put("protectRate", request.protectRate)
        put("sampleRate", request.sampleRate)
        put("noiseGateDb", request.noiseGateDb)
        put("outputDenoiseEnabled", request.outputDenoiseEnabled)
        put("vocalRangeFilterEnabled", request.vocalRangeFilterEnabled)
        put("parallelChunkCount", request.parallelChunkCount)
    }.toString()

    private fun convertDecodedAudio(
        request: RvcInferenceRequest,
        environment: OrtEnvironment,
        hubertSession: OrtSession,
        rmvpeSession: OrtSession,
        voiceSessions: List<OrtSession>,
        decoded: DecodedAudio,
        applyOutputPostProcessing: Boolean = true,
        onProgress: (Double, String) -> Unit,
    ): FloatArray {
        request.cancellationToken.throwIfCancelled()
        onProgress(8.0, "解码音频")
        val mono16k = resampleToMono(decoded, HUBERT_SAMPLE_RATE)
        val gatedMono16k = applyInputAudioFilters(mono16k, request.noiseGateDb, request.outputDenoiseEnabled, request.vocalRangeFilterEnabled, HUBERT_SAMPLE_RATE)
        request.cancellationToken.throwIfCancelled()
        val features = extractHubertFeatures(environment, hubertSession, gatedMono16k)
        onProgress(25.0, "提取音色特征")
        val phone100Hz = repeatFeaturesForRvc(features)
        val actualFrameCount = phone100Hz.size / HUBERT_FEATURE_SIZE
        val frameCount = alignVoiceFrameCount(actualFrameCount)
        val phone = fitFeatures(phone100Hz, frameCount)
        val index = loadOptionalFeatureIndex(request.indexPath, onProgress)
        val indexedPhone = fuseIndexFeatures(phone, frameCount, index, request.indexRate)
        request.cancellationToken.throwIfCancelled()
        val pitchf = extractRmvpePitch(environment, rmvpeSession, gatedMono16k, frameCount, request.pitchChange)
        val filteredPitchf = applyMedianPitchFilter(pitchf, request.filterRadius)
        val pitch = filteredPitchf.map { coarsePitch(it) }.toLongArray()
        val protectedPhone = applyProtectBlend(indexedPhone, phone, filteredPitchf, request.protectRate)
        onProgress(40.0, "估计音高")
        onProgress(55.0, "准备分块生成")

        val audio = synthesizeVoice(
            environment = environment,
            voiceSessions = voiceSessions,
            phone = protectedPhone,
            frameCount = frameCount,
            pitch = pitch,
            pitchf = filteredPitchf,
            parallelChunkCount = request.parallelChunkCount,
            cancellationToken = request.cancellationToken,
            onProgress = onProgress,
        )
        val formantAudio = applyFormant(audio, request.formant)
        if (!applyOutputPostProcessing) {
            return formantAudio
        }
        val matchedAudio = mixRms(formantAudio, gatedMono16k, request.rmsMixRate, request.sampleRate)
        val filteredAudio = applyOutputAudioFilters(matchedAudio, request.noiseGateDb, request.outputDenoiseEnabled, request.vocalRangeFilterEnabled, request.sampleRate)
        onProgress(98.0, "写入音频")
        request.cancellationToken.throwIfCancelled()
        return filteredAudio
    }

    private fun finalizeSegmentedOutput(
        request: RvcInferenceRequest,
        songFile: File,
        outputPath: File,
        layout: ResumableInferenceJobStore.ResumableInferenceJobLayout?,
        segments: List<AudioSegment>,
    ) {
        request.cancellationToken.throwIfCancelled()
        request.onProgress(98.0, "整段后处理")
        val source16k = prepareOfflineInput16k(songFile, request)
        val stitchedAudio = if (layout != null && layout.outputsDirectory.isDirectory) {
            rebuildSegmentedOutputFile(layout, request, segments, outputPath)
            readCachedSegmentAudio(outputPath)
        } else {
            readCachedSegmentAudio(outputPath)
        }
        val matchedAudio = mixRms(stitchedAudio, source16k, request.rmsMixRate, request.sampleRate)
        val filteredAudio = applyOutputAudioFilters(
            matchedAudio,
            request.noiseGateDb,
            request.outputDenoiseEnabled,
            request.vocalRangeFilterEnabled,
            request.sampleRate,
        )
        request.cancellationToken.throwIfCancelled()
        writeWav(outputPath, filteredAudio, request.sampleRate)
    }

    fun openStreamingSession(
        modelPath: String,
        indexPath: String?,
        pitchChange: Double,
        indexRate: Double,
        formant: Double,
        filterRadius: Int,
        rmsMixRate: Double,
        protectRate: Double,
        sampleRate: Int,
        noiseGateDb: Double,
        outputDenoiseEnabled: Boolean,
        vocalRangeFilterEnabled: Boolean,
        parallelChunkCount: Int,
        extraInferenceLength: Double,
    ): RvcStreamingSession {
        val voiceModelFile = File(modelPath)
        require(voiceModelFile.exists()) { "模型文件不存在" }
        indexPath?.let { require(File(it).exists()) { "索引文件不存在" } }
        require(modelPath.lowercase().endsWith(".onnx")) { "请选择 .onnx 音色模型" }
        indexPath?.let { require(it.lowercase().endsWith(".index")) { "请选择 .index 索引文件" } }

        val hubertModel = File(modelsDir, "hubert.onnx")
        val rmvpeModel = File(modelsDir, "rmvpe.onnx")
        val missingModels = listOf(hubertModel, rmvpeModel)
            .filterNot { it.exists() }
            .joinToString { it.name }
        require(missingModels.isEmpty()) { "缺少 ONNX 模型：$missingModels" }

        val environment = OrtEnvironment.getEnvironment()
        return try {
            val hubertSession = createCpuOptimizedSession(environment, hubertModel, REALTIME_INFERENCE_THREAD_COUNT)
            val rmvpeSession = createCpuOptimizedSession(environment, rmvpeModel, REALTIME_INFERENCE_THREAD_COUNT)
            val sessionPoolSize = parallelChunkCount.coerceIn(1, MAX_PARALLEL_CHUNK_COUNT)
            val voiceSessionThreadCount = voiceChunkThreadCount(sessionPoolSize)
            val voiceSessions = MutableList(sessionPoolSize) {
                createCpuOptimizedSession(environment, voiceModelFile, voiceSessionThreadCount)
            }
            RvcStreamingSession(
                environment = environment,
                hubertSession = hubertSession,
                rmvpeSession = rmvpeSession,
                voiceSessions = voiceSessions,
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
                contextSampleCount = (sampleRate * extraInferenceLength.coerceIn(0.0, 5.0)).toInt(),
            )
        } catch (error: Throwable) {
            environment.close()
            throw error
        }
    }

    inner class RvcStreamingSession(
        private val environment: OrtEnvironment,
        private val hubertSession: OrtSession,
        private val rmvpeSession: OrtSession,
        private val voiceSessions: List<OrtSession>,
        private val indexPath: String?,
        private val pitchChange: Double,
        private val indexRate: Double,
        private val formant: Double,
        private val filterRadius: Int,
        private val rmsMixRate: Double,
        private val protectRate: Double,
        private val sampleRate: Int,
        private val noiseGateDb: Double,
        private val outputDenoiseEnabled: Boolean,
        private val vocalRangeFilterEnabled: Boolean,
        private val parallelChunkCount: Int,
        private val contextSampleCount: Int,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val index = loadOptionalFeatureIndex(indexPath) { _, _ -> }
        private var previousInputContext = ShortArray(0)

        fun inferPcm16(inferenceWindow: ShortArray, outputFrameCount: Int): ShortArray {
            check(!closed.get()) { "实时推理会话已关闭" }
            val outputFrames = outputFrameCount.coerceIn(1, inferenceWindow.size)
            val combinedPcm = combineContext(inferenceWindow)
            val mono = FloatArray(combinedPcm.size) { index -> combinedPcm[index] / 32768f }
            val mono16k = applyRealtimeInputAudioFilters(resampleMono(mono, sampleRate, HUBERT_SAMPLE_RATE), noiseGateDb, vocalRangeFilterEnabled, HUBERT_SAMPLE_RATE)
            val features = extractHubertFeatures(environment, hubertSession, mono16k)
            val phone100Hz = repeatFeaturesForRvc(features)
            val actualFrameCount = phone100Hz.size / HUBERT_FEATURE_SIZE
            val frameCount = alignVoiceFrameCount(actualFrameCount)
            val phone = fitFeatures(phone100Hz, frameCount)
            val indexedPhone = fuseIndexFeatures(phone, frameCount, index, indexRate)
            val pitchf = extractRmvpePitch(environment, rmvpeSession, mono16k, frameCount, pitchChange)
            val filteredPitchf = applyMedianPitchFilter(pitchf, filterRadius)
            val pitch = filteredPitchf.map { coarsePitch(it) }.toLongArray()
            val protectedPhone = applyProtectBlend(indexedPhone, phone, filteredPitchf, protectRate)
            val cancellationToken = CancellationToken()
            val audio = synthesizeVoice(
                environment = environment,
                voiceSessions = voiceSessions,
                phone = protectedPhone,
                frameCount = frameCount,
                pitch = pitch,
                pitchf = filteredPitchf,
                parallelChunkCount = parallelChunkCount,
                cancellationToken = cancellationToken,
                onProgress = { _, _ -> },
            )
            val formantAudio = applyFormant(audio, formant)
            val matchedAudio = mixRms(formantAudio, mono16k, rmsMixRate, HUBERT_SAMPLE_RATE)
            val resizedAudio = resizeAudio(matchedAudio, combinedPcm.size)
            updateContext(combinedPcm)
            val currentAudio = resizedAudio.copyOfRange(
                (resizedAudio.size - outputFrames).coerceAtLeast(0),
                resizedAudio.size,
            )
            val filteredAudio = applyOutputAudioFilters(currentAudio, noiseGateDb, outputDenoiseEnabled, vocalRangeFilterEnabled, sampleRate)
            return floatToPcm16(resizeAudio(filteredAudio, outputFrames))
        }

        private fun combineContext(inferenceWindow: ShortArray): ShortArray {
            if (previousInputContext.isEmpty()) {
                return inferenceWindow.copyOf()
            }
            return ShortArray(previousInputContext.size + inferenceWindow.size).also { combined ->
                previousInputContext.copyInto(combined)
                inferenceWindow.copyInto(combined, previousInputContext.size)
            }
        }

        private fun updateContext(combinedPcm: ShortArray) {
            val nextContextSize = min(contextSampleCount, combinedPcm.size)
            previousInputContext = if (nextContextSize <= 0) {
                ShortArray(0)
            } else {
                combinedPcm.copyOfRange(combinedPcm.size - nextContextSize, combinedPcm.size)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            voiceSessions.asReversed().forEach { it.close() }
            previousInputContext = ShortArray(0)
            rmvpeSession.close()
            hubertSession.close()
            environment.close()
        }
    }

    private fun requestNativeMemoryCleanup() {
        System.runFinalization()
        System.gc()
    }

    private fun resizeAudio(audio: FloatArray, targetSize: Int): FloatArray {
        if (targetSize <= 0) return FloatArray(0)
        if (audio.size == targetSize) return audio
        if (audio.isEmpty()) return FloatArray(targetSize)
        return resampleMono(audio, audio.size, targetSize)
    }

    private fun floatToPcm16(audio: FloatArray): ShortArray {
        return ShortArray(audio.size) { index ->
            (audio[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    private fun applyRealtimeInputAudioFilters(audio: FloatArray, noiseGateDb: Double, vocalRangeFilterEnabled: Boolean, sampleRate: Int): FloatArray {
        val rangedAudio = if (vocalRangeFilterEnabled) applyVocalRangeFilter(audio, sampleRate) else audio
        return applyNoiseGate(rangedAudio, noiseGateDb)
    }

    private fun applyInputAudioFilters(audio: FloatArray, noiseGateDb: Double, outputDenoiseEnabled: Boolean, vocalRangeFilterEnabled: Boolean, sampleRate: Int): FloatArray {
        val rangedAudio = if (vocalRangeFilterEnabled) applyVocalRangeFilter(audio, sampleRate) else audio
        val artifactCleaned = if (outputDenoiseEnabled) applyInputArtifactCleanup(rangedAudio, sampleRate) else rangedAudio
        val noiseGated = applyNoiseGate(artifactCleaned, noiseGateDb)
        val denoised = if (outputDenoiseEnabled) applyAdaptiveDenoiseGate(noiseGated, noiseGateDb, sampleRate) else noiseGated
        return applyClarityRecovery(denoised, sampleRate)
    }

    private fun applyOutputAudioFilters(audio: FloatArray, noiseGateDb: Double, outputDenoiseEnabled: Boolean, vocalRangeFilterEnabled: Boolean, sampleRate: Int): FloatArray {
        val rangedAudio = if (vocalRangeFilterEnabled) applyVocalRangeFilter(audio, sampleRate) else audio
        val denoisedAudio = if (outputDenoiseEnabled) applyOutputDenoiseGate(rangedAudio, noiseGateDb, sampleRate) else rangedAudio
        return applySoftLimiter(denoisedAudio)
    }

    private fun applyInputArtifactCleanup(audio: FloatArray, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        val highPassed = BiquadFilter.highPass(sampleRate, INPUT_CLEANUP_HIGH_PASS_HZ, BIQUAD_Q).process(audio)
        val humFiltered = applyHumNotchScaffold(highPassed, sampleRate)
        val declicked = repairClickPopSpikes(humFiltered)
        return smoothElectricalBursts(declicked)
    }

    private fun applyHumNotchScaffold(audio: FloatArray, sampleRate: Int): FloatArray {
        if (!INPUT_HUM_NOTCH_ENABLED || audio.isEmpty() || sampleRate <= 0) return audio
        var filtered = audio
        var frequency = INPUT_HUM_NOTCH_BASE_HZ
        while (frequency < sampleRate * 0.45) {
            filtered = BiquadFilter.notch(sampleRate, frequency, INPUT_HUM_NOTCH_Q).process(filtered)
            frequency += INPUT_HUM_NOTCH_BASE_HZ
        }
        return filtered
    }

    private fun repairClickPopSpikes(audio: FloatArray): FloatArray {
        if (audio.size < CLICK_REPAIR_WINDOW_SAMPLES) return audio
        val output = audio.copyOf()
        for (index in 2 until audio.size - 2) {
            val magnitude = abs(audio[index])
            if (magnitude < CLICK_REPAIR_ABSOLUTE_THRESHOLD) continue
            val neighborLevel = (
                abs(audio[index - 2]) +
                    abs(audio[index - 1]) +
                    abs(audio[index + 1]) +
                    abs(audio[index + 2])
                ) / 4f
            if (magnitude > max(CLICK_REPAIR_ABSOLUTE_THRESHOLD, neighborLevel * CLICK_REPAIR_SPIKE_RATIO)) {
                output[index] = (audio[index - 1] + audio[index + 1]) * 0.5f
            }
        }
        return output
    }

    private fun smoothElectricalBursts(audio: FloatArray): FloatArray {
        if (audio.size < ELECTRICAL_BURST_WINDOW_SAMPLES) return audio
        val output = audio.copyOf()
        for (index in 3 until audio.size - 3) {
            val magnitude = abs(audio[index])
            if (magnitude < ELECTRICAL_BURST_ABSOLUTE_THRESHOLD) continue
            val neighborLevel = (
                abs(audio[index - 3]) +
                    abs(audio[index - 2]) +
                    abs(audio[index - 1]) +
                    abs(audio[index + 1]) +
                    abs(audio[index + 2]) +
                    abs(audio[index + 3])
                ) / 6f
            val edgeEnergy = abs(audio[index] - audio[index - 1]) + abs(audio[index] - audio[index + 1])
            if (magnitude > max(ELECTRICAL_BURST_ABSOLUTE_THRESHOLD, neighborLevel * ELECTRICAL_BURST_RATIO) && edgeEnergy > ELECTRICAL_BURST_EDGE_THRESHOLD) {
                output[index] = (
                    audio[index - 2] +
                        audio[index - 1] +
                        audio[index + 1] +
                        audio[index + 2]
                    ) * 0.25f
            }
        }
        return output
    }

    private fun applyNoiseGate(audio: FloatArray, noiseGateDb: Double): FloatArray {
        val db = noiseGateDb.coerceIn(0.0, 100.0)
        if (db == 0.0 || audio.isEmpty()) return audio
        return FloatArray(audio.size) { index ->
            val sampleLevelDb = 20.0 * log10(abs(audio[index]).coerceAtLeast(NOISE_GATE_EPSILON).toDouble()) + 100.0
            if (sampleLevelDb <= db) 0f else audio[index]
        }
    }

    private fun applyOutputDenoiseGate(audio: FloatArray, noiseGateDb: Double, sampleRate: Int): FloatArray {
        val adaptive = applyAdaptiveDenoiseGate(audio, noiseGateDb, sampleRate)
        val residual = applyResidualNoiseGate(adaptive, noiseGateDb, sampleRate)
        val envelopeSmoothed = suppressEnvelopeArtifacts(residual, sampleRate)
        val deEssed = applyOutputDeEsser(envelopeSmoothed, sampleRate)
        return applyClarityRecovery(deEssed, sampleRate)
    }

    private fun applyClarityRecovery(audio: FloatArray, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        return BiquadFilter.peaking(sampleRate, CLARITY_RECOVERY_HZ, CLARITY_RECOVERY_GAIN_DB, CLARITY_RECOVERY_Q).process(audio)
    }

    private fun applyResidualNoiseGate(audio: FloatArray, noiseGateDb: Double, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        val thresholdDb = ((noiseGateDb.takeIf { it > 0.0 } ?: DEFAULT_RESIDUAL_GATE_DB) - RESIDUAL_GATE_RELAX_DB).coerceIn(0.0, 100.0)
        val frameSize = (sampleRate / RESIDUAL_GATE_FRAMES_PER_SECOND).coerceAtLeast(1)
        val output = FloatArray(audio.size)
        var gain = 1.0f
        var offset = 0
        while (offset < audio.size) {
            val end = min(offset + frameSize, audio.size)
            var sum = 0.0
            for (index in offset until end) {
                val sample = audio[index].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / (end - offset).coerceAtLeast(1))
            val frameDb = 20.0 * log10(rms.coerceAtLeast(NOISE_GATE_EPSILON.toDouble())) + 100.0
            val targetGain = if (frameDb >= thresholdDb) 1.0f else RESIDUAL_GATE_MIN_GAIN
            val rate = if (targetGain > gain) RESIDUAL_GATE_ATTACK_RATE else RESIDUAL_GATE_RELEASE_RATE
            gain += (targetGain - gain) * rate
            for (index in offset until end) {
                output[index] = audio[index] * gain
            }
            offset = end
        }
        return output
    }

    private fun suppressEnvelopeArtifacts(audio: FloatArray, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        val frameSize = (sampleRate / ENVELOPE_ARTIFACT_FRAMES_PER_SECOND).coerceAtLeast(1)
        val output = FloatArray(audio.size)
        var gain = 1.0f
        var offset = 0
        while (offset < audio.size) {
            val end = min(offset + frameSize, audio.size)
            var sum = 0.0
            for (index in offset until end) {
                val sample = audio[index].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / (end - offset).coerceAtLeast(1))
            val frameDb = 20.0 * log10(rms.coerceAtLeast(NOISE_GATE_EPSILON.toDouble())) + 100.0
            val targetGain = if (frameDb >= ENVELOPE_ARTIFACT_OPEN_DB) {
                1.0f
            } else if (frameDb >= ENVELOPE_ARTIFACT_OPEN_DB - ENVELOPE_ARTIFACT_KNEE_DB) {
                val ratio = ((frameDb - (ENVELOPE_ARTIFACT_OPEN_DB - ENVELOPE_ARTIFACT_KNEE_DB)) / ENVELOPE_ARTIFACT_KNEE_DB).toFloat().coerceIn(0f, 1f)
                ENVELOPE_ARTIFACT_MIN_GAIN + (1.0f - ENVELOPE_ARTIFACT_MIN_GAIN) * ratio * ratio
            } else {
                ENVELOPE_ARTIFACT_MIN_GAIN
            }
            val rate = if (targetGain > gain) ENVELOPE_ARTIFACT_ATTACK_RATE else ENVELOPE_ARTIFACT_RELEASE_RATE
            gain += (targetGain - gain) * rate
            for (index in offset until end) {
                output[index] = audio[index] * gain
            }
            offset = end
        }
        return output
    }

    private fun applyOutputDeEsser(audio: FloatArray, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        val notched = BiquadFilter.peaking(sampleRate, DE_ESSER_CENTER_HZ, DE_ESSER_GAIN_DB, DE_ESSER_Q).process(audio)
        return BiquadFilter.highShelf(sampleRate, DE_ESSER_SHELF_HZ, DE_ESSER_SHELF_GAIN_DB, BIQUAD_Q).process(notched)
    }

    private fun applySoftLimiter(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio
        val output = FloatArray(audio.size)
        for (index in audio.indices) {
            val sample = audio[index]
            val magnitude = abs(sample)
            if (magnitude <= SOFT_LIMITER_KNEE) {
                output[index] = sample
                continue
            }
            val over = magnitude - SOFT_LIMITER_KNEE
            val limited = SOFT_LIMITER_KNEE + over / (1f + over / SOFT_LIMITER_CURVE)
            output[index] = if (sample < 0f) {
                -limited.coerceAtMost(SOFT_LIMITER_CEILING)
            } else {
                limited.coerceAtMost(SOFT_LIMITER_CEILING)
            }
        }
        return output
    }

    private fun applyAdaptiveDenoiseGate(audio: FloatArray, noiseGateDb: Double, sampleRate: Int): FloatArray {
        if (audio.isEmpty()) return audio
        val thresholdDb = (noiseGateDb.takeIf { it > 0.0 } ?: DEFAULT_OUTPUT_DENOISE_GATE_DB).coerceIn(0.0, 100.0)
        val frameSize = (sampleRate / DENOISE_FRAMES_PER_SECOND).coerceAtLeast(1)
        var noiseFloorDb = 100.0
        var gain = 1.0f
        val output = FloatArray(audio.size)
        var offset = 0
        while (offset < audio.size) {
            val end = min(offset + frameSize, audio.size)
            var sum = 0.0
            for (index in offset until end) {
                val sample = audio[index].toDouble()
                sum += sample * sample
            }
            val rms = kotlin.math.sqrt(sum / (end - offset).coerceAtLeast(1))
            val frameDb = 20.0 * log10(rms.coerceAtLeast(NOISE_GATE_EPSILON.toDouble())) + 100.0
            noiseFloorDb = min(noiseFloorDb + DENOISE_NOISE_FLOOR_RISE_DB, frameDb)
            if (frameDb < noiseFloorDb) {
                noiseFloorDb = frameDb
            }
            val adaptiveThresholdDb = max(thresholdDb, noiseFloorDb + DENOISE_NOISE_FLOOR_MARGIN_DB)
            val targetGain = if (frameDb >= adaptiveThresholdDb) {
                1.0f
            } else if (frameDb >= adaptiveThresholdDb - DENOISE_SOFT_KNEE_DB) {
                val ratio = ((frameDb - (adaptiveThresholdDb - DENOISE_SOFT_KNEE_DB)) / DENOISE_SOFT_KNEE_DB).toFloat().coerceIn(0f, 1f)
                OUTPUT_DENOISE_MIN_GAIN + (1.0f - OUTPUT_DENOISE_MIN_GAIN) * ratio * ratio
            } else {
                OUTPUT_DENOISE_MIN_GAIN
            }
            val rate = if (targetGain > gain) DENOISE_ATTACK_RATE else DENOISE_RELEASE_RATE
            gain += (targetGain - gain) * rate
            for (index in offset until end) {
                output[index] = audio[index] * gain
            }
            offset = end
        }
        return output
    }

    private fun applyVocalRangeFilter(audio: FloatArray, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        var filtered = BiquadFilter.highPass(sampleRate, VOCAL_RANGE_LOW_HZ, BIQUAD_Q).process(audio)
        filtered = BiquadFilter.highShelf(sampleRate, VOCAL_RANGE_HIGH_HZ, VOCAL_RANGE_HIGH_SHELF_GAIN_DB, BIQUAD_Q).process(filtered)
        return BiquadFilter.peaking(sampleRate, VOCAL_RANGE_PRESENCE_HZ, VOCAL_RANGE_PRESENCE_GAIN_DB, BIQUAD_Q).process(filtered)
    }

    private class BiquadFilter(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(audio: FloatArray): FloatArray {
            val output = FloatArray(audio.size)
            for (index in audio.indices) {
                val input = audio[index].toDouble()
                val value = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                output[index] = value.toFloat()
                x2 = x1
                x1 = input
                y2 = y1
                y1 = value
            }
            return output
        }

        companion object {
            fun highPass(sampleRate: Int, frequencyHz: Double, q: Double): BiquadFilter {
                val params = biquadParams(sampleRate, frequencyHz, q)
                val b0 = (1.0 + params.cosW0) / 2.0
                val b1 = -(1.0 + params.cosW0)
                val b2 = (1.0 + params.cosW0) / 2.0
                val a0 = 1.0 + params.alpha
                val a1 = -2.0 * params.cosW0
                val a2 = 1.0 - params.alpha
                return normalized(b0, b1, b2, a0, a1, a2)
            }

            fun highShelf(sampleRate: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadFilter {
                val params = biquadParams(sampleRate, frequencyHz, q)
                val a = Math.pow(10.0, gainDb / 40.0)
                val sqrtA = kotlin.math.sqrt(a)
                val b0 = a * ((a + 1.0) + (a - 1.0) * params.cosW0 + 2.0 * sqrtA * params.alpha)
                val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * params.cosW0)
                val b2 = a * ((a + 1.0) + (a - 1.0) * params.cosW0 - 2.0 * sqrtA * params.alpha)
                val a0 = (a + 1.0) - (a - 1.0) * params.cosW0 + 2.0 * sqrtA * params.alpha
                val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * params.cosW0)
                val a2 = (a + 1.0) - (a - 1.0) * params.cosW0 - 2.0 * sqrtA * params.alpha
                return normalized(b0, b1, b2, a0, a1, a2)
            }

            fun peaking(sampleRate: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadFilter {
                val params = biquadParams(sampleRate, frequencyHz, q)
                val a = Math.pow(10.0, gainDb / 40.0)
                val b0 = 1.0 + params.alpha * a
                val b1 = -2.0 * params.cosW0
                val b2 = 1.0 - params.alpha * a
                val a0 = 1.0 + params.alpha / a
                val a1 = -2.0 * params.cosW0
                val a2 = 1.0 - params.alpha / a
                return normalized(b0, b1, b2, a0, a1, a2)
            }

            fun notch(sampleRate: Int, frequencyHz: Double, q: Double): BiquadFilter {
                val params = biquadParams(sampleRate, frequencyHz, q)
                val b0 = 1.0
                val b1 = -2.0 * params.cosW0
                val b2 = 1.0
                val a0 = 1.0 + params.alpha
                val a1 = -2.0 * params.cosW0
                val a2 = 1.0 - params.alpha
                return normalized(b0, b1, b2, a0, a1, a2)
            }

            private fun biquadParams(sampleRate: Int, frequencyHz: Double, q: Double): BiquadParams {
                val boundedFrequency = frequencyHz.coerceIn(10.0, sampleRate * 0.45)
                val omega = 2.0 * PI * boundedFrequency / sampleRate
                val sinW0 = sin(omega)
                return BiquadParams(cosW0 = cos(omega), alpha = sinW0 / (2.0 * q.coerceAtLeast(0.1)))
            }

            private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): BiquadFilter {
                return BiquadFilter(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    private data class BiquadParams(val cosW0: Double, val alpha: Double)

    private fun createCpuOptimizedSession(environment: OrtEnvironment, modelFile: File, threadCount: Int = inferenceThreadCount()): OrtSession {
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threadCount)
            options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            options.addXnnpack(mapOf("intra_op_num_threads" to threadCount.toString()))
            return environment.createSession(modelFile.absolutePath, options)
        }
    }

    private fun inferenceThreadCount(): Int {
        return Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    private fun voiceChunkThreadCount(workerCount: Int): Int {
        return max(1, inferenceThreadCount() / workerCount.coerceAtLeast(1))
    }

    private fun decodeAudio(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("未找到可解码的音频轨道")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("音频格式缺少 MIME")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        val output = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var decodedSampleRate = sampleRate
        var decodedChannelCount = channelCount
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        codec.configure(format, null, null, 0)
        codec.start()

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("无法获取解码输入缓冲")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        decodedSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        decodedChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: error("无法获取解码输出缓冲")
                        if (bufferInfo.size > 0) {
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(bytes)
                            output.write(bytes)
                        }
                        outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val pcm = bytesToFloatPcm(output.toByteArray(), decodedChannelCount, pcmEncoding)
        require(pcm.isNotEmpty()) { "音频解码结果为空" }
        return DecodedAudio(pcm, decodedSampleRate, decodedChannelCount)
    }

    private fun decodeAudioSegment(file: File, startUs: Long, endUs: Long): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("未找到可解码的音频轨道")

        extractor.selectTrack(trackIndex)
        extractor.seekTo(startUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("音频格式缺少 MIME")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        val output = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var decodedSampleRate = sampleRate
        var decodedChannelCount = channelCount
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var firstOutputPresentationTimeUs: Long? = null

        codec.configure(format, null, null, 0)
        codec.start()

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("无法获取解码输入缓冲")
                        inputBuffer.clear()
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0 || sampleTimeUs > endUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        decodedSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        decodedChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: error("无法获取解码输出缓冲")
                        if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs <= endUs) {
                            if (firstOutputPresentationTimeUs == null) {
                                firstOutputPresentationTimeUs = bufferInfo.presentationTimeUs
                            }
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(bytes)
                            output.write(bytes)
                        }
                        outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val pcm = bytesToFloatPcm(output.toByteArray(), decodedChannelCount, pcmEncoding)
        require(pcm.isNotEmpty()) { "音频解码结果为空" }
        val rawDecoded = DecodedAudio(pcm, decodedSampleRate, decodedChannelCount)
        return trimDecodedAudioToRequestedWindow(
            audio = rawDecoded,
            actualStartUs = firstOutputPresentationTimeUs ?: startUs,
            requestedStartUs = startUs,
            requestedEndUs = endUs,
        )
    }

    private fun inspectAudioPlan(file: File): AudioPlan {
        val durationUs = readAudioDurationUs(file)
        val requiresSegmentation = file.length() > MAX_DIRECT_INFERENCE_FILE_BYTES || durationUs > MAX_DIRECT_INFERENCE_DURATION_US
        return AudioPlan(durationUs = durationUs, requiresSegmentation = requiresSegmentation)
    }

    private fun readAudioDurationUs(file: File): Long {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (durationMs != null && durationMs > 0L) return durationMs * 1_000L
            } finally {
                retriever.release()
            }
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0L
            val format = extractor.getTrackFormat(trackIndex)
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        } finally {
            extractor.release()
        }
    }

    private fun buildAudioSegments(durationUs: Long): List<AudioSegment> {
        val safeDurationUs = durationUs.coerceAtLeast(SEGMENT_CENTER_DURATION_US)
        val segments = ArrayList<AudioSegment>()
        var centerStartUs = 0L
        while (centerStartUs < safeDurationUs) {
            val centerEndUs = min(centerStartUs + SEGMENT_CENTER_DURATION_US, safeDurationUs)
            segments.add(AudioSegment(
                centerStartUs = centerStartUs,
                centerEndUs = centerEndUs,
                decodeStartUs = (centerStartUs - SEGMENT_CONTEXT_DURATION_US).coerceAtLeast(0L),
                decodeEndUs = min(centerEndUs + SEGMENT_CONTEXT_DURATION_US, safeDurationUs),
            ))
            centerStartUs = centerEndUs
        }
        return segments
    }

    private fun mapSegmentProgress(segmentIndex: Int, segmentCount: Int, innerPercent: Double): Double {
        val normalizedInner = (innerPercent.coerceIn(0.0, 100.0) / 100.0)
        if (segmentCount <= 0) return 0.0
        return ((segmentIndex + normalizedInner) / segmentCount) * 100.0
    }

    private fun completedChunkProgressPercent(completedChunkCount: Int, segmentCount: Int): Double {
        if (segmentCount <= 0) return 0.0
        return (completedChunkCount.toDouble() / segmentCount.toDouble()) * 100.0
    }

    private fun prepareOfflineInput16k(songFile: File, request: RvcInferenceRequest): FloatArray {
        val decoded = decodeAudio(songFile)
        request.cancellationToken.throwIfCancelled()
        request.onProgress(8.0, "解码音频")
        val mono16k = resampleToMono(decoded, HUBERT_SAMPLE_RATE)
        request.cancellationToken.throwIfCancelled()
        return applyInputAudioFilters(
            mono16k,
            request.noiseGateDb,
            request.outputDenoiseEnabled,
            request.vocalRangeFilterEnabled,
            HUBERT_SAMPLE_RATE,
        )
    }

    private fun prepareInputChunkFiles(
        songFile: File,
        segments: List<AudioSegment>,
        layout: ResumableInferenceJobStore.ResumableInferenceJobLayout,
        request: RvcInferenceRequest,
    ) {
        segments.forEachIndexed { segmentIndex, segment ->
            request.cancellationToken.throwIfCancelled()
            val chunkFile = layout.chunkFile(segmentIndex)
            if (chunkFile.isFile) {
                request.onProgress(
                    1.0 + ((segmentIndex + 1).toDouble() / segments.size.toDouble()) * 4.0,
                    "复用输入分片 ${segmentIndex + 1}/${segments.size}",
                )
                return@forEachIndexed
            }
            val decoded = decodeAudioSegment(songFile, segment.decodeStartUs, segment.decodeEndUs)
            request.cancellationToken.throwIfCancelled()
            writeDecodedAudioWav(chunkFile, decoded)
            request.onProgress(
                1.0 + ((segmentIndex + 1).toDouble() / segments.size.toDouble()) * 4.0,
                "预切分输入音频 ${segmentIndex + 1}/${segments.size}",
            )
        }
    }

    private fun trimSegmentCenterAudio(audio: FloatArray, segment: AudioSegment, sampleRate: Int): FloatArray {
        return cropAudioPreferringContexts(
            audio = audio,
            targetSampleCount = segment.expectedCenterSampleCount(sampleRate),
            preferredTrimStartSamples = segment.expectedLeadingContextSampleCount(sampleRate),
            preferredTrimEndSamples = segment.expectedTrailingContextSampleCount(sampleRate),
        )
    }

    private fun appendSegmentAudio(writer: WavSegmentWriter, audio: FloatArray, segment: AudioSegment) {
        val centerWindow = selectSegmentCenterWindow(audio, segment)
        if (centerWindow.isEmpty()) return
        appendWavSegment(writer, centerWindow)
    }

    private fun selectSegmentCenterWindow(audio: FloatArray, segment: AudioSegment): FloatArray {
        if (audio.isEmpty()) return FloatArray(0)
        val decodeDurationUs = (segment.decodeEndUs - segment.decodeStartUs).coerceAtLeast(1L)
        val centerOffsetUs = (segment.centerStartUs - segment.decodeStartUs).coerceAtLeast(0L)
        val centerDurationUs = (segment.centerEndUs - segment.centerStartUs).coerceAtLeast(1L)
        val keepStartSamples = (audio.size.toLong() * centerOffsetUs / decodeDurationUs).toInt().coerceIn(0, audio.size)
        val keepEndSamples = (audio.size.toLong() * (centerOffsetUs + centerDurationUs) / decodeDurationUs).toInt().coerceIn(keepStartSamples, audio.size)
        if (keepEndSamples <= keepStartSamples) return FloatArray(0)
        return audio.copyOfRange(keepStartSamples, keepEndSamples)
    }

    private fun cropAudioPreferringContexts(
        audio: FloatArray,
        targetSampleCount: Int,
        preferredTrimStartSamples: Int,
        preferredTrimEndSamples: Int,
    ): FloatArray {
        val safeTargetSampleCount = targetSampleCount.coerceAtLeast(1)
        if (audio.isEmpty()) return FloatArray(safeTargetSampleCount)
        if (audio.size == safeTargetSampleCount) return audio
        if (audio.size < safeTargetSampleCount) {
            val padded = FloatArray(safeTargetSampleCount)
            audio.copyInto(padded, endIndex = audio.size)
            val tailValue = audio.last()
            for (index in audio.size until padded.size) {
                padded[index] = tailValue
            }
            return padded
        }

        val totalTrim = audio.size - safeTargetSampleCount
        var trimStartSamples = preferredTrimStartSamples.coerceIn(0, totalTrim)
        var trimEndSamples = preferredTrimEndSamples.coerceIn(0, totalTrim - trimStartSamples)
        val remainingTrim = totalTrim - trimStartSamples - trimEndSamples
        trimStartSamples += remainingTrim / 2
        trimEndSamples += remainingTrim - (remainingTrim / 2)
        val endIndex = (audio.size - trimEndSamples).coerceAtLeast(trimStartSamples)
        return audio.copyOfRange(trimStartSamples, endIndex)
    }

    private fun trimDecodedAudioToRequestedWindow(
        audio: DecodedAudio,
        actualStartUs: Long,
        requestedStartUs: Long,
        requestedEndUs: Long,
    ): DecodedAudio {
        val requestedDurationUs = (requestedEndUs - requestedStartUs).coerceAtLeast(1L)
        val startOffsetUs = (requestedStartUs - actualStartUs).coerceAtLeast(0L)
        val sourceFrameCount = (audio.samples.size / audio.channelCount).coerceAtLeast(0)
        if (sourceFrameCount == 0) return audio

        val startFrame = ((startOffsetUs * audio.sampleRate) / 1_000_000L).toInt().coerceIn(0, sourceFrameCount)
        val requestedFrameCount = ((requestedDurationUs * audio.sampleRate) / 1_000_000L).toInt().coerceAtLeast(1)
        val endFrame = (startFrame + requestedFrameCount).coerceIn(startFrame, sourceFrameCount)
        val trimmedFrameCount = (endFrame - startFrame).coerceAtLeast(0)
        if (trimmedFrameCount == sourceFrameCount) return audio

        val trimmedSamples = audio.samples.copyOfRange(
            startFrame * audio.channelCount,
            endFrame * audio.channelCount,
        )
        return DecodedAudio(trimmedSamples, audio.sampleRate, audio.channelCount)
    }

    private fun appendWavSegment(writer: WavSegmentWriter, audio: FloatArray) {
        writer.append(audio)
    }

    private fun readCachedSegmentAudio(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size <= WAV_HEADER_SIZE) return FloatArray(0)
        val sampleCount = (bytes.size - WAV_HEADER_SIZE) / BYTES_PER_PCM_16
        val buffer = ByteBuffer.wrap(bytes, WAV_HEADER_SIZE, sampleCount * BYTES_PER_PCM_16).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(sampleCount) { buffer.short / 32768f }
    }

    private fun readDecodedAudioWav(file: File): DecodedAudio {
        val bytes = file.readBytes()
        require(bytes.size > WAV_HEADER_SIZE) { "音频解码结果为空" }
        val header = ByteBuffer.wrap(bytes, 0, WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val channelCount = header.getShort(22).toInt().coerceAtLeast(1)
        val sampleRate = header.getInt(24).coerceAtLeast(1)
        val dataSize = (bytes.size - WAV_HEADER_SIZE).coerceAtLeast(0)
        val sampleCount = dataSize / BYTES_PER_PCM_16
        val buffer = ByteBuffer.wrap(bytes, WAV_HEADER_SIZE, sampleCount * BYTES_PER_PCM_16).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = FloatArray(sampleCount) { buffer.short / 32768f }
        return DecodedAudio(pcm, sampleRate, channelCount)
    }

    private fun rebuildSegmentedOutputFile(
        layout: ResumableInferenceJobStore.ResumableInferenceJobLayout,
        request: RvcInferenceRequest,
        segments: List<AudioSegment>,
        outputPath: File,
    ) {
        if (segments.isEmpty()) {
            writeWav(outputPath, FloatArray(0), request.sampleRate)
            return
        }
        WavSegmentWriter(outputPath, request.sampleRate).use { writer ->
            segments.forEachIndexed { index, segment ->
                request.cancellationToken.throwIfCancelled()
                request.onProgress(
                    95.0 + ((index + 1).toDouble() / segments.size.toDouble()) * 2.0,
                    "重建最终拼接 ${index + 1}/${segments.size}",
                )
                val file = layout.convertedChunkFile(index)
                require(file.isFile) { "缺少分段结果：${file.name}" }
                appendSegmentAudio(writer, readCachedSegmentAudio(file), segment)
            }
        }
    }

    private fun bytesToFloatPcm(bytes: ByteArray, channelCount: Int, pcmEncoding: Int): FloatArray {
        require(channelCount > 0) { "音频通道数无效" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val sampleCount = bytes.size / BYTES_PER_PCM_16
                FloatArray(sampleCount) { buffer.short / 32768f }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val sampleCount = bytes.size / Float.SIZE_BYTES
                FloatArray(sampleCount) { buffer.float.coerceIn(-1f, 1f) }
            }
            else -> error("不支持的 PCM 编码：$pcmEncoding")
        }
    }

    private fun resampleToMono(audio: DecodedAudio, targetSampleRate: Int): FloatArray {
        val sourceFrameCount = audio.samples.size / audio.channelCount
        val mono = FloatArray(sourceFrameCount)
        for (frame in 0 until sourceFrameCount) {
            var sum = 0f
            for (channel in 0 until audio.channelCount) {
                sum += audio.samples[frame * audio.channelCount + channel]
            }
            mono[frame] = sum / audio.channelCount
        }

        return resampleMono(mono, audio.sampleRate, targetSampleRate)
    }

    private fun resampleMono(mono: FloatArray, sourceSampleRate: Int, targetSampleRate: Int): FloatArray {
        if (sourceSampleRate == targetSampleRate) {
            return mono
        }

        val targetFrameCount = max(1, (mono.size.toLong() * targetSampleRate / sourceSampleRate).toInt())
        val resampled = FloatArray(targetFrameCount)
        val ratio = sourceSampleRate.toDouble() / targetSampleRate
        val normalizedCutoff = min(1.0, targetSampleRate.toDouble() / sourceSampleRate)
        for (index in resampled.indices) {
            val sourcePosition = index * ratio
            val center = sourcePosition.toInt()
            var weightedSum = 0.0
            var weightSum = 0.0
            for (tap in center - WINDOWED_SINC_RADIUS..center + WINDOWED_SINC_RADIUS) {
                val sampleIndex = tap.coerceIn(0, mono.lastIndex)
                val distance = sourcePosition - tap
                val weight = normalizedCutoff * sinc(distance * normalizedCutoff) * hannWindow(distance / WINDOWED_SINC_RADIUS)
                weightedSum += mono[sampleIndex] * weight
                weightSum += weight
            }
            resampled[index] = if (weightSum == 0.0) 0f else (weightedSum / weightSum).toFloat()
        }
        return resampled
    }

    private fun sinc(value: Double): Double {
        if (abs(value) < 1e-8) return 1.0
        val angle = Math.PI * value
        return sin(angle) / angle
    }

    private fun hannWindow(position: Double): Double {
        val bounded = abs(position).coerceAtMost(1.0)
        return 0.5 + 0.5 * cos(Math.PI * bounded)
    }

    private fun extractHubertFeatures(
        environment: OrtEnvironment,
        session: OrtSession,
        audio16k: FloatArray,
    ): FloatArray {
        val paddedLength = alignHubertInputLength(audio16k.size)
        val paddedAudio = FloatArray(paddedLength)
        System.arraycopy(audio16k, 0, paddedAudio, 0, audio16k.size)
        val mask = ByteArray(paddedLength)
        for (index in audio16k.size until paddedLength) {
            mask[index] = 1
        }
        val inputShape = longArrayOf(1, paddedLength.toLong())

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(paddedAudio), inputShape).use { sourceTensor ->
            OnnxTensor.createTensor(environment, ByteBuffer.wrap(mask), inputShape, OnnxJavaType.BOOL).use { maskTensor ->
                session.run(mapOf("source" to sourceTensor, "padding_mask" to maskTensor)).use { result ->
                    val tensor = result[0] as OnnxTensor
                    return tensor.floatBuffer.arrayCopy()
                }
            }
        }
    }

    private fun alignHubertInputLength(length: Int): Int {
        var candidate = max(HUBERT_MIN_SAFE_INPUT_LENGTH, length)
        while (true) {
            val frameCount = calculateHubertFrameCount(candidate)
            if (frameCount > 0 && frameCount % 2 == 0 && candidate % frameCount != 0) {
                return candidate
            }
            candidate++
        }
    }

    private fun calculateHubertFrameCount(length: Int): Int {
        var frameCount = length
        for (index in HUBERT_CONV_KERNELS.indices) {
            val kernel = HUBERT_CONV_KERNELS[index]
            val stride = HUBERT_CONV_STRIDES[index]
            frameCount = (frameCount - kernel) / stride + 1
        }
        return frameCount
    }

    private fun repeatFeaturesForRvc(features: FloatArray): FloatArray {
        val sourceFrameCount = features.size / HUBERT_FEATURE_SIZE
        val repeated = FloatArray(sourceFrameCount * 2 * HUBERT_FEATURE_SIZE)
        for (frame in 0 until sourceFrameCount) {
            val sourceOffset = frame * HUBERT_FEATURE_SIZE
            val targetOffset = frame * 2 * HUBERT_FEATURE_SIZE
            System.arraycopy(features, sourceOffset, repeated, targetOffset, HUBERT_FEATURE_SIZE)
            System.arraycopy(features, sourceOffset, repeated, targetOffset + HUBERT_FEATURE_SIZE, HUBERT_FEATURE_SIZE)
        }
        return repeated
    }

    private fun fitFeatures(features: FloatArray, frameCount: Int): FloatArray {
        val sourceFrameCount = features.size / HUBERT_FEATURE_SIZE
        require(sourceFrameCount > 0) { "HuBERT 特征为空" }
        val fitted = FloatArray(frameCount * HUBERT_FEATURE_SIZE)
        for (frame in 0 until frameCount) {
            val sourceFrame = min(frame, sourceFrameCount - 1)
            System.arraycopy(
                features,
                sourceFrame * HUBERT_FEATURE_SIZE,
                fitted,
                frame * HUBERT_FEATURE_SIZE,
                HUBERT_FEATURE_SIZE,
            )
        }
        return fitted
    }

    private fun alignVoiceFrameCount(frameCount: Int): Int {
        return max(2, frameCount + frameCount % 2)
    }

    private fun loadOptionalFeatureIndex(indexPath: String?, onProgress: (Double, String) -> Unit): FeatureIndex? {
        if (indexPath == null) {
            return null
        }
        return runCatching { loadFeatureIndex(File(indexPath)) }
            .onFailure { onProgress(28.0, "索引格式暂不支持，请先导入 mobile.index") }
            .getOrNull()
    }

    private fun loadFeatureIndex(indexFile: File): FeatureIndex {
        val bytes = indexFile.readBytes()
        val magic = FEATURE_INDEX_MAGIC.toByteArray(Charsets.US_ASCII)
        require(bytes.size >= magic.size + Int.SIZE_BYTES) { "索引文件为空或格式不受支持" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "当前仅支持移动端转换后的 .index 特征表" }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(magic.size)
        val frameCount = buffer.int
        require(frameCount > 0) { "索引特征数量无效" }
        val expectedBytes = magic.size + Int.SIZE_BYTES + frameCount * HUBERT_FEATURE_SIZE * Float.SIZE_BYTES
        require(bytes.size >= expectedBytes) { "索引文件数据不完整" }

        val features = FloatArray(frameCount * HUBERT_FEATURE_SIZE)
        for (index in features.indices) {
            features[index] = buffer.float
        }
        return FeatureIndex(features, frameCount)
    }

    private fun fuseIndexFeatures(
        phone: FloatArray,
        frameCount: Int,
        index: FeatureIndex?,
        indexRate: Double,
    ): FloatArray {
        val rate = indexRate.coerceIn(0.0, 1.0).toFloat()
        if (index == null || rate == 0f) {
            return phone
        }

        val fused = FloatArray(phone.size)
        for (frame in 0 until frameCount) {
            val sourceOffset = frame * HUBERT_FEATURE_SIZE
            val nearestFrame = findNearestIndexFrame(phone, sourceOffset, index)
            val nearestOffset = nearestFrame * HUBERT_FEATURE_SIZE
            for (feature in 0 until HUBERT_FEATURE_SIZE) {
                val original = phone[sourceOffset + feature]
                val indexed = index.features[nearestOffset + feature]
                fused[sourceOffset + feature] = original * (1f - rate) + indexed * rate
            }
        }
        return fused
    }

    private fun findNearestIndexFrame(phone: FloatArray, sourceOffset: Int, index: FeatureIndex): Int {
        var nearestFrame = 0
        var nearestDistance = Double.POSITIVE_INFINITY
        for (frame in 0 until index.frameCount) {
            val indexOffset = frame * HUBERT_FEATURE_SIZE
            var distance = 0.0
            for (feature in 0 until HUBERT_FEATURE_SIZE) {
                val delta = phone[sourceOffset + feature] - index.features[indexOffset + feature]
                distance += delta * delta
            }
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestFrame = frame
            }
        }
        return nearestFrame
    }

    private fun applyMedianPitchFilter(pitchf: FloatArray, radius: Int): FloatArray {
        if (radius <= 0) {
            return pitchf
        }
        val filtered = FloatArray(pitchf.size)
        for (index in pitchf.indices) {
            val start = max(0, index - radius)
            val end = min(pitchf.lastIndex, index + radius)
            val voiced = ArrayList<Float>()
            for (frame in start..end) {
                if (pitchf[frame] > 0f) {
                    voiced.add(pitchf[frame])
                }
            }
            filtered[index] = if (voiced.isEmpty()) 0f else voiced.sorted()[voiced.size / 2]
        }
        return filtered
    }

    private fun applyProtectBlend(
        indexedPhone: FloatArray,
        originalPhone: FloatArray,
        pitchf: FloatArray,
        protectRate: Double,
    ): FloatArray {
        val rate = protectRate.coerceIn(0.0, 1.0).toFloat()
        if (rate == 0f) {
            return indexedPhone
        }
        val protected = FloatArray(indexedPhone.size)
        for (frame in pitchf.indices) {
            val unvoicedBlend = if (pitchf[frame] <= 0f) rate else 0f
            val offset = frame * HUBERT_FEATURE_SIZE
            for (feature in 0 until HUBERT_FEATURE_SIZE) {
                protected[offset + feature] = indexedPhone[offset + feature] * (1f - unvoicedBlend) + originalPhone[offset + feature] * unvoicedBlend
            }
        }
        return protected
    }

    private fun applyFormant(audio: FloatArray, formant: Double): FloatArray {
        formant.coerceIn(-2.0, 2.0)
        return audio
    }

    private fun coarsePitch(f0: Float): Long {
        if (f0 <= 0f) {
            return 1L
        }
        val mel = 1127.0 * kotlin.math.ln(1.0 + f0 / 700.0)
        val melMin = 1127.0 * kotlin.math.ln(1.0 + MIN_F0 / 700.0)
        val melMax = 1127.0 * kotlin.math.ln(1.0 + MAX_F0 / 700.0)
        val coarse = ((mel - melMin) * 254.0 / (melMax - melMin) + 1.0).roundToInt()
        return coarse.coerceIn(1, 255).toLong()
    }

    private fun extractRmvpePitch(
        environment: OrtEnvironment,
        session: OrtSession,
        audio16k: FloatArray,
        targetFrameCount: Int,
        pitchChange: Double,
    ): FloatArray {
        val frameCount = alignRmvpeFrameCount(max(MIN_RMVPE_FRAMES, targetFrameCount))
        val mel = buildRmvpeMelSpectrogram(audio16k, frameCount)
        val semitoneFactor = Math.pow(2.0, pitchChange / 12.0).toFloat()

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(mel), longArrayOf(1, RMVPE_MEL_BINS.toLong(), frameCount.toLong())).use { inputTensor ->
            session.run(mapOf("input" to inputTensor)).use { result ->
                val output = (result[0] as OnnxTensor).floatBuffer.arrayCopy()
                val pitchf = FloatArray(targetFrameCount)
                for (frame in 0 until targetFrameCount) {
                    val sourceFrame = min(frame, frameCount - 1)
                    val outputOffset = sourceFrame * RMVPE_OUTPUT_BINS
                    var bestBin = 0
                    var bestConfidence = 0f
                    for (bin in 0 until RMVPE_OUTPUT_BINS) {
                        val confidence = output.getOrElse(outputOffset + bin) { 0f }
                        if (confidence > bestConfidence) {
                            bestConfidence = confidence
                            bestBin = bin
                        }
                    }
                    pitchf[frame] = if (bestConfidence >= RMVPE_VOICED_THRESHOLD) {
                        rmvpeBinToFrequency(bestBin) * semitoneFactor
                    } else {
                        0f
                    }
                }
                return pitchf
            }
        }
    }

    private fun buildRmvpeMelSpectrogram(audio16k: FloatArray, frameCount: Int): FloatArray {
        val window = FloatArray(RMVPE_FFT_SIZE) { index ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * index / RMVPE_FFT_SIZE)).toFloat()
        }
        val filterbank = buildMelFilterbank()
        val mel = FloatArray(RMVPE_MEL_BINS * frameCount)
        val frameBuffer = FloatArray(RMVPE_FFT_SIZE)

        for (frame in 0 until frameCount) {
            val center = frame * RMVPE_HOP_SIZE
            val start = center - RMVPE_FFT_SIZE / 2
            for (index in 0 until RMVPE_FFT_SIZE) {
                val audioIndex = start + index
                frameBuffer[index] = if (audioIndex in audio16k.indices) audio16k[audioIndex] * window[index] else 0f
            }

            val spectrum = powerSpectrum(frameBuffer)
            for (melBin in 0 until RMVPE_MEL_BINS) {
                var energy = 0.0
                val filterOffset = melBin * RMVPE_SPECTRUM_BINS
                for (spectrumBin in 0 until RMVPE_SPECTRUM_BINS) {
                    energy += spectrum[spectrumBin] * filterbank[filterOffset + spectrumBin]
                }
                mel[melBin * frameCount + frame] = log10(max(energy, RMVPE_LOG_EPSILON)).toFloat()
            }
        }

        return mel
    }

    private fun buildMelFilterbank(): FloatArray {
        val weights = FloatArray(RMVPE_MEL_BINS * RMVPE_SPECTRUM_BINS)
        val melMin = hzToMel(RMVPE_MEL_FMIN)
        val melMax = hzToMel(RMVPE_MEL_FMAX)
        val melPoints = DoubleArray(RMVPE_MEL_BINS + 2) { index ->
            melMin + (melMax - melMin) * index / (RMVPE_MEL_BINS + 1)
        }
        val hzPoints = melPoints.map { melToHz(it) }
        val binPoints = hzPoints.map { hz ->
            ((RMVPE_FFT_SIZE + 1) * hz / HUBERT_SAMPLE_RATE).roundToInt().coerceIn(0, RMVPE_SPECTRUM_BINS - 1)
        }

        for (melBin in 0 until RMVPE_MEL_BINS) {
            val left = binPoints[melBin]
            val center = max(left + 1, binPoints[melBin + 1])
            val right = max(center + 1, binPoints[melBin + 2])
            val weightOffset = melBin * RMVPE_SPECTRUM_BINS
            for (bin in left until center) {
                weights[weightOffset + bin] = (bin - left).toFloat() / (center - left)
            }
            for (bin in center until right.coerceAtMost(RMVPE_SPECTRUM_BINS)) {
                weights[weightOffset + bin] = (right - bin).toFloat() / (right - center)
            }
        }

        return weights
    }

    private fun powerSpectrum(samples: FloatArray): DoubleArray {
        val real = DoubleArray(RMVPE_FFT_SIZE) { index -> samples[index].toDouble() }
        val imaginary = DoubleArray(RMVPE_FFT_SIZE)
        fft(real, imaginary)
        return DoubleArray(RMVPE_SPECTRUM_BINS) { index -> real[index] * real[index] + imaginary[index] * imaginary[index] }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until RMVPE_FFT_SIZE) {
            var bit = RMVPE_FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realTemp = real[i]
                real[i] = real[j]
                real[j] = realTemp
                val imaginaryTemp = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryTemp
            }
        }

        var length = 2
        while (length <= RMVPE_FFT_SIZE) {
            val angle = -2.0 * Math.PI / length
            val wLenReal = cos(angle)
            val wLenImaginary = sin(angle)
            var i = 0
            while (i < RMVPE_FFT_SIZE) {
                var wReal = 1.0
                var wImaginary = 0.0
                for (k in 0 until length / 2) {
                    val evenIndex = i + k
                    val oddIndex = evenIndex + length / 2
                    val oddReal = real[oddIndex] * wReal - imaginary[oddIndex] * wImaginary
                    val oddImaginary = real[oddIndex] * wImaginary + imaginary[oddIndex] * wReal
                    real[oddIndex] = real[evenIndex] - oddReal
                    imaginary[oddIndex] = imaginary[evenIndex] - oddImaginary
                    real[evenIndex] += oddReal
                    imaginary[evenIndex] += oddImaginary
                    val nextWReal = wReal * wLenReal - wImaginary * wLenImaginary
                    wImaginary = wReal * wLenImaginary + wImaginary * wLenReal
                    wReal = nextWReal
                }
                i += length
            }
            length = length shl 1
        }
    }

    private fun hzToMel(hz: Double): Double {
        return 2595.0 * log10(1.0 + hz / 700.0)
    }

    private fun melToHz(mel: Double): Double {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)
    }

    private fun rmvpeBinToFrequency(bin: Int): Float {
        val cents = RMVPE_CENTS_BASE + RMVPE_CENTS_PER_BIN * bin
        return (10.0 * Math.pow(2.0, cents / 1200.0)).toFloat()
    }

    private fun alignRmvpeFrameCount(frameCount: Int): Int {
        return ceil(frameCount / RMVPE_FRAME_ALIGNMENT.toDouble()).toInt() * RMVPE_FRAME_ALIGNMENT
    }

    private fun synthesizeVoice(
        environment: OrtEnvironment,
        voiceSessions: List<OrtSession>,
        phone: FloatArray,
        frameCount: Int,
        pitch: LongArray,
        pitchf: FloatArray,
        parallelChunkCount: Int,
        cancellationToken: CancellationToken,
        onProgress: (Double, String) -> Unit,
    ): FloatArray {
        val inputNames = voiceSessions.first().inputNames
        require(inputNames.containsAll(setOf("phone", "phone_lengths", "pitch", "pitchf", "ds", "rnd"))) {
            "当前音色模型 ONNX 输入不符合 RVC v2 导出格式：${inputNames.joinToString()}"
        }

        val outputChunks = ArrayList<FloatArray>()
        var totalOutputSamples = 0
        val continuousNoise = createContinuousNoise(frameCount)
        val totalChunks = ceil(frameCount / VOICE_CENTER_FRAMES.toDouble()).toInt()
        val chunkInputs = ArrayList<VoiceChunkInput>(totalChunks)
        var completedChunks = 0
        for (centerStart in 0 until frameCount step VOICE_CENTER_FRAMES) {
            cancellationToken.throwIfCancelled()
            val chunkStart = (centerStart - VOICE_CONTEXT_FRAMES).coerceAtLeast(0)
            val chunkFrameCount = min(VOICE_CHUNK_FRAMES, frameCount - chunkStart)
            val centerOffsetFrames = centerStart - chunkStart
            val centerFrameCount = min(VOICE_CENTER_FRAMES, frameCount - centerStart)
            chunkInputs.add(VoiceChunkInput(
                chunkFrameCount = chunkFrameCount,
                centerOffsetFrames = centerOffsetFrames,
                centerFrameCount = centerFrameCount,
                phone = copyFrameSlice(phone, chunkStart, chunkFrameCount, VOICE_CHUNK_FRAMES, HUBERT_FEATURE_SIZE),
                pitch = copyLongSlice(pitch, chunkStart, chunkFrameCount, VOICE_CHUNK_FRAMES),
                pitchf = copyFloatSlice(pitchf, chunkStart, chunkFrameCount, VOICE_CHUNK_FRAMES),
                noise = copyNoiseSlice(continuousNoise, chunkStart, chunkFrameCount, frameCount, VOICE_CHUNK_FRAMES),
            ))
        }

        val boundedParallelChunkCount = parallelChunkCount.coerceIn(1, MAX_PARALLEL_CHUNK_COUNT)
        val actualParallelChunkCount = min(
            boundedParallelChunkCount,
            min(chunkInputs.size.coerceAtLeast(1), voiceSessions.size.coerceAtLeast(1)),
        )
        val activeVoiceSessions = voiceSessions.take(actualParallelChunkCount)
        for (chunkBatch in chunkInputs.chunked(actualParallelChunkCount)) {
            val chunkAudios = runBlocking(Dispatchers.Default) {
                chunkBatch.mapIndexed { index, chunk ->
                    async {
                        val voiceSession = activeVoiceSessions[index]
                        cancellationToken.throwIfCancelled()
                        runVoiceChunk(environment, voiceSession, chunk.phone, chunk.pitch, chunk.pitchf, chunk.noise, cancellationToken)
                    }
                }.awaitAll()
            }
            chunkBatch.zip(chunkAudios).forEach { (chunk, chunkAudio) ->
                cancellationToken.throwIfCancelled()
                val appended = appendCrossfadedChunkAudio(chunkAudio, chunk.centerOffsetFrames, chunk.centerFrameCount, VOICE_CHUNK_FRAMES, outputChunks)
                totalOutputSamples += appended
                completedChunks++
                onProgress(55.0 + 40.0 * completedChunks / totalChunks, "分块生成中")
            }
        }

        return flattenAudioChunks(outputChunks, totalOutputSamples).normalizePeak()
    }

    private fun runVoiceChunk(
        environment: OrtEnvironment,
        voiceSession: OrtSession,
        phone: FloatArray,
        pitch: LongArray,
        pitchf: FloatArray,
        rnd: FloatArray,
        cancellationToken: CancellationToken,
    ): FloatArray {
        val tensors = linkedMapOf<String, OnnxTensor>()
        val runOptions = OrtSession.RunOptions()

        try {
            tensors["phone"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(phone), longArrayOf(1, VOICE_CHUNK_FRAMES.toLong(), HUBERT_FEATURE_SIZE.toLong()))
            tensors["phone_lengths"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(VOICE_CHUNK_FRAMES.toLong())), longArrayOf(1))
            tensors["pitch"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(pitch), longArrayOf(1, VOICE_CHUNK_FRAMES.toLong()))
            tensors["pitchf"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pitchf), longArrayOf(1, VOICE_CHUNK_FRAMES.toLong()))
            tensors["ds"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1))
            tensors["rnd"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(rnd), longArrayOf(1, RVC_NOISE_CHANNELS.toLong(), VOICE_CHUNK_FRAMES.toLong()))

            cancellationToken.bindRunOptions(runOptions)
            try {
                voiceSession.run(tensors, runOptions).use { result ->
                    val tensor = result[0] as OnnxTensor
                    return tensor.floatBuffer.arrayCopy()
                }
            } catch (error: OrtException) {
                cancellationToken.rethrowIfTerminateException(error)
            }
        } finally {
            cancellationToken.clearRunOptions(runOptions)
            runOptions.close()
            tensors.values.forEach { it.close() }
        }
    }

    private fun createContinuousNoise(frameCount: Int): FloatArray {
        return FloatArray(RVC_NOISE_CHANNELS * frameCount) { Random.nextFloat() * 0.02f - 0.01f }
    }

    private fun copyNoiseSlice(
        source: FloatArray,
        startFrame: Int,
        sourceFrameCount: Int,
        totalFrameCount: Int,
        targetFrameCount: Int,
    ): FloatArray {
        val target = FloatArray(RVC_NOISE_CHANNELS * targetFrameCount)
        val copyFrameCount = sourceFrameCount.coerceAtMost(targetFrameCount)
        for (channel in 0 until RVC_NOISE_CHANNELS) {
            val sourceOffset = channel * totalFrameCount + startFrame
            val targetOffset = channel * targetFrameCount
            System.arraycopy(source, sourceOffset, target, targetOffset, copyFrameCount)
            val fillValue = if (copyFrameCount > 0) target[targetOffset + copyFrameCount - 1] else 0f
            for (frame in copyFrameCount until targetFrameCount) {
                target[targetOffset + frame] = fillValue
            }
        }
        return target
    }

    private fun copyFrameSlice(
        source: FloatArray,
        startFrame: Int,
        sourceFrameCount: Int,
        targetFrameCount: Int,
        frameSize: Int,
    ): FloatArray {
        val target = FloatArray(targetFrameCount * frameSize)
        val sourceOffset = startFrame * frameSize
        val copyLength = sourceFrameCount * frameSize
        System.arraycopy(source, sourceOffset, target, 0, copyLength)
        if (sourceFrameCount in 1 until targetFrameCount) {
            val lastSourceOffset = (sourceFrameCount - 1) * frameSize
            for (frame in sourceFrameCount until targetFrameCount) {
                System.arraycopy(target, lastSourceOffset, target, frame * frameSize, frameSize)
            }
        }
        return target
    }

    private fun copyLongSlice(source: LongArray, startFrame: Int, sourceFrameCount: Int, targetFrameCount: Int): LongArray {
        val target = LongArray(targetFrameCount)
        System.arraycopy(source, startFrame, target, 0, sourceFrameCount)
        val fillValue = if (sourceFrameCount > 0) target[sourceFrameCount - 1] else 1L
        for (frame in sourceFrameCount until targetFrameCount) {
            target[frame] = fillValue
        }
        return target
    }

    private fun copyFloatSlice(source: FloatArray, startFrame: Int, sourceFrameCount: Int, targetFrameCount: Int): FloatArray {
        val target = FloatArray(targetFrameCount)
        System.arraycopy(source, startFrame, target, 0, sourceFrameCount)
        val fillValue = if (sourceFrameCount > 0) target[sourceFrameCount - 1] else 0f
        for (frame in sourceFrameCount until targetFrameCount) {
            target[frame] = fillValue
        }
        return target
    }

    private fun appendCrossfadedChunkAudio(chunkAudio: FloatArray, centerOffsetFrames: Int, centerFrameCount: Int, voiceChunkFrames: Int, outputChunks: MutableList<FloatArray>): Int {
        val keepStartSamples = (chunkAudio.size.toLong() * centerOffsetFrames / voiceChunkFrames).toInt().coerceIn(0, chunkAudio.size)
        val keepEndSamples = (chunkAudio.size.toLong() * (centerOffsetFrames + centerFrameCount) / voiceChunkFrames).toInt().coerceIn(keepStartSamples, chunkAudio.size)
        val keepSamples = keepEndSamples - keepStartSamples
        if (keepSamples <= 0) return 0
        val chunk = chunkAudio.copyOfRange(keepStartSamples, keepEndSamples)
        val previous = outputChunks.lastOrNull()
        val crossfadeSamples = min(CHUNK_CROSSFADE_SAMPLES, min(previous?.size ?: 0, chunk.size))
        if (previous != null) {
            for (index in 0 until crossfadeSamples) {
                val previousIndex = previous.size - crossfadeSamples + index
                val phase = (index + 1).toDouble() / (crossfadeSamples + 1)
                val fadeIn = sin(0.5 * Math.PI * phase).toFloat()
                val fadeOut = cos(0.5 * Math.PI * phase).toFloat()
                previous[previousIndex] = blendBoundarySample(previous[previousIndex], chunk[index], fadeOut, fadeIn)
            }
        }
        if (crossfadeSamples < chunk.size) {
            outputChunks.add(chunk.copyOfRange(crossfadeSamples, chunk.size))
            return chunk.size - crossfadeSamples
        }
        return 0
    }

    private fun flattenAudioChunks(chunks: List<FloatArray>, totalSamples: Int): FloatArray {
        val output = FloatArray(totalSamples)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, output, offset, chunk.size)
            offset += chunk.size
        }
        return output
    }

    private fun blendBoundarySample(previous: Float, current: Float, fadeOut: Float, fadeIn: Float): Float {
        val mixed = previous * fadeOut + current * fadeIn
        val normalization = sqrt(fadeOut * fadeOut + fadeIn * fadeIn).coerceAtLeast(1e-6f)
        return mixed / normalization
    }

    private fun mixRms(converted: FloatArray, source16k: FloatArray, rmsMixRate: Double, sampleRate: Int): FloatArray {
        val rate = rmsMixRate.coerceIn(0.0, 1.0).toFloat()
        if (rate == 0f || converted.isEmpty() || source16k.isEmpty()) {
            return converted
        }
        val sourceOutputRate = resampleMono(source16k, HUBERT_SAMPLE_RATE, sampleRate)
        val sourceRms = rms(sourceOutputRate)
        val convertedRms = rms(converted)
        if (sourceRms == 0f || convertedRms == 0f) {
            return converted
        }
        val gain = 1f + (sourceRms / convertedRms - 1f) * rate
        return FloatArray(converted.size) { index -> converted[index] * gain }
    }

    private fun rms(audio: FloatArray): Float {
        var sum = 0.0
        for (sample in audio) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / audio.size).toFloat()
    }

    private fun FloatArray.normalizePeak(): FloatArray {
        var peak = 0f
        for (sample in this) {
            peak = max(peak, abs(sample))
        }
        if (peak <= 1f || peak == 0f) {
            return this
        }
        for (index in indices) {
            this[index] /= peak
        }
        return this
    }

    private fun FloatBuffer.arrayCopy(): FloatArray {
        val duplicate = duplicate()
        duplicate.rewind()
        val values = FloatArray(duplicate.remaining())
        duplicate.get(values)
        return values
    }

    private fun writeWav(file: File, audio: FloatArray, sampleRate: Int) {
        FileOutputStream(file).use { output ->
            val dataSize = audio.size * BYTES_PER_PCM_16
            val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(sampleRate * BYTES_PER_PCM_16)
            header.putShort(BYTES_PER_PCM_16.toShort())
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            output.write(header.array())

            val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audio) {
                val clipped = sample.coerceIn(-1f, 1f)
                pcm.putShort((clipped * Short.MAX_VALUE).roundToInt().toShort())
            }
            output.write(pcm.array())
        }
    }

    private fun writeDecodedAudioWav(file: File, audio: DecodedAudio) {
        FileOutputStream(file).use { output ->
            val dataSize = audio.samples.size * BYTES_PER_PCM_16
            val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(audio.channelCount.toShort())
            header.putInt(audio.sampleRate)
            header.putInt(audio.sampleRate * audio.channelCount * BYTES_PER_PCM_16)
            header.putShort((audio.channelCount * BYTES_PER_PCM_16).toShort())
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            output.write(header.array())

            val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audio.samples) {
                val clipped = sample.coerceIn(-1f, 1f)
                pcm.putShort((clipped * Short.MAX_VALUE).roundToInt().toShort())
            }
            output.write(pcm.array())
        }
    }

    private class WavSegmentWriter(file: File, private val sampleRate: Int) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        private var sampleCount = 0
        private var previousTail = FloatArray(0)

        init {
            output.setLength(0)
            output.write(ByteArray(WAV_HEADER_SIZE))
        }

        fun append(audio: FloatArray) {
            if (audio.isEmpty()) return

            val overlapSamples = minOf(SEGMENT_JOIN_CROSSFADE_SAMPLES, previousTail.size, audio.size)
            if (overlapSamples > 0) {
                val mixedOverlap = FloatArray(overlapSamples) { index ->
                    val fadeIn = (index + 1).toFloat() / overlapSamples.toFloat()
                    val fadeOut = 1f - fadeIn
                    previousTail[previousTail.size - overlapSamples + index] * fadeOut + audio[index] * fadeIn
                }
                output.seek(output.filePointer - overlapSamples * BYTES_PER_PCM_16)
                output.write(encodePcm16(mixedOverlap))
                output.write(encodePcm16(audio.copyOfRange(overlapSamples, audio.size)))
                sampleCount += audio.size - overlapSamples
            } else {
                output.write(encodePcm16(audio))
                sampleCount += audio.size
            }

            val tailSize = minOf(SEGMENT_JOIN_CROSSFADE_SAMPLES, audio.size)
            previousTail = audio.copyOfRange(audio.size - tailSize, audio.size)
        }

        override fun close() {
            val dataSize = sampleCount * BYTES_PER_PCM_16
            val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(sampleRate * BYTES_PER_PCM_16)
            header.putShort(BYTES_PER_PCM_16.toShort())
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            output.seek(0)
            output.write(header.array())
            output.close()
        }

        private fun encodePcm16(audio: FloatArray): ByteArray {
            val pcm = ByteBuffer.allocate(audio.size * BYTES_PER_PCM_16).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audio) {
                val clipped = sample.coerceIn(-1f, 1f)
                pcm.putShort((clipped * Short.MAX_VALUE).roundToInt().toShort())
            }
            return pcm.array()
        }
    }

    private data class DecodedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val channelCount: Int,
    )

    private data class AudioPlan(
        val durationUs: Long,
        val requiresSegmentation: Boolean,
    )

    private data class AudioSegment(
        val centerStartUs: Long,
        val centerEndUs: Long,
        val decodeStartUs: Long,
        val decodeEndUs: Long,
    ) {
        fun expectedCenterSampleCount(sampleRate: Int): Int =
            (((centerEndUs - centerStartUs).coerceAtLeast(1L) * sampleRate.toLong()) / 1_000_000L).toInt().coerceAtLeast(1)

        fun expectedLeadingContextSampleCount(sampleRate: Int): Int =
            (((centerStartUs - decodeStartUs).coerceAtLeast(0L) * sampleRate.toLong()) / 1_000_000L).toInt().coerceAtLeast(0)

        fun expectedTrailingContextSampleCount(sampleRate: Int): Int =
            (((decodeEndUs - centerEndUs).coerceAtLeast(0L) * sampleRate.toLong()) / 1_000_000L).toInt().coerceAtLeast(0)
    }

    data class FeatureIndex(
        val features: FloatArray,
        val frameCount: Int,
    )

    private companion object {
        const val HUBERT_SAMPLE_RATE = 16_000
        const val HUBERT_FEATURE_SIZE = 768
        const val HUBERT_MIN_SAFE_INPUT_LENGTH = 801
        const val VOICE_CHUNK_FRAMES = 200
        const val VOICE_CENTER_FRAMES = 80
        const val VOICE_CONTEXT_FRAMES = (VOICE_CHUNK_FRAMES - VOICE_CENTER_FRAMES) / 2
        const val DEFAULT_PARALLEL_CHUNK_COUNT = 4
        const val MAX_PARALLEL_CHUNK_COUNT = 32
        const val CHUNK_CROSSFADE_SAMPLES = 768
        const val WINDOWED_SINC_RADIUS = 16
        const val RVC_NOISE_CHANNELS = 192
        const val NOISE_GATE_EPSILON = 1e-5f
        const val RMVPE_MEL_BINS = 128
        const val RMVPE_OUTPUT_BINS = 360
        const val RMVPE_FFT_SIZE = 1024
        const val RMVPE_SPECTRUM_BINS = RMVPE_FFT_SIZE / 2 + 1
        const val RMVPE_HOP_SIZE = 160
        const val MIN_RMVPE_FRAMES = 32
        const val RMVPE_FRAME_ALIGNMENT = 32
        const val RMVPE_MEL_FMIN = 30.0
        const val RMVPE_MEL_FMAX = 8000.0
        const val RMVPE_CENTS_BASE = 1997.3794084376191
        const val RMVPE_CENTS_PER_BIN = 20.0
        const val RMVPE_VOICED_THRESHOLD = 0.03f
        const val RMVPE_LOG_EPSILON = 1e-6
        const val BYTES_PER_PCM_16 = 2
        const val WAV_HEADER_SIZE = 44
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_DIRECT_INFERENCE_DURATION_US = 15_000_000L
        const val MAX_DIRECT_INFERENCE_FILE_BYTES = 5L * 1024L * 1024L
        const val SEGMENT_CENTER_DURATION_US = 15_000_000L
        const val SEGMENT_CONTEXT_DURATION_US = 1_000_000L
        const val SEGMENT_JOIN_CROSSFADE_SAMPLES = 256
        const val MIN_F0 = 50
        const val MAX_F0 = 1100
        const val FEATURE_INDEX_MAGIC = "URVCIDX1"
        val HUBERT_CONV_KERNELS = intArrayOf(10, 3, 3, 3, 3, 2, 2)
        val HUBERT_CONV_STRIDES = intArrayOf(5, 2, 2, 2, 2, 2, 2)
        val REALTIME_INFERENCE_THREAD_COUNT = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        const val VOCAL_RANGE_LOW_CUTOFF_HZ = 50.0
        const val VOCAL_RANGE_LOW_HZ = 60.0
        const val VOCAL_RANGE_HIGH_HZ = 2500.0
        const val VOCAL_RANGE_HIGH_ROLLOFF_HZ = 4500.0
        const val VOCAL_RANGE_HIGH_SHELF_GAIN_DB = -4.5
        const val VOCAL_RANGE_PRESENCE_HZ = 3400.0
        const val VOCAL_RANGE_PRESENCE_GAIN_DB = 1.5
        const val BIQUAD_Q = 0.707
        const val INPUT_CLEANUP_HIGH_PASS_HZ = 70.0
        const val INPUT_HUM_NOTCH_ENABLED = false
        const val INPUT_HUM_NOTCH_BASE_HZ = 50.0
        const val INPUT_HUM_NOTCH_Q = 18.0
        const val CLICK_REPAIR_WINDOW_SAMPLES = 5
        const val CLICK_REPAIR_ABSOLUTE_THRESHOLD = 0.92f
        const val CLICK_REPAIR_SPIKE_RATIO = 6.0f
        const val ELECTRICAL_BURST_WINDOW_SAMPLES = 7
        const val ELECTRICAL_BURST_ABSOLUTE_THRESHOLD = 0.72f
        const val ELECTRICAL_BURST_RATIO = 3.8f
        const val ELECTRICAL_BURST_EDGE_THRESHOLD = 0.58f
        const val SOFT_LIMITER_KNEE = 0.88f
        const val SOFT_LIMITER_CURVE = 0.18f
        const val SOFT_LIMITER_CEILING = 0.8912509f
        const val DEFAULT_RESIDUAL_GATE_DB = 24.0
        const val RESIDUAL_GATE_RELAX_DB = 10.0
        const val RESIDUAL_GATE_MIN_GAIN = 0.18f
        const val RESIDUAL_GATE_FRAMES_PER_SECOND = 80
        const val RESIDUAL_GATE_ATTACK_RATE = 0.35f
        const val RESIDUAL_GATE_RELEASE_RATE = 0.08f
        const val ENVELOPE_ARTIFACT_FRAMES_PER_SECOND = 140
        const val ENVELOPE_ARTIFACT_OPEN_DB = 48.0
        const val ENVELOPE_ARTIFACT_KNEE_DB = 16.0
        const val ENVELOPE_ARTIFACT_MIN_GAIN = 0.42f
        const val ENVELOPE_ARTIFACT_ATTACK_RATE = 0.42f
        const val ENVELOPE_ARTIFACT_RELEASE_RATE = 0.12f
        const val DE_ESSER_CENTER_HZ = 6900.0
        const val DE_ESSER_GAIN_DB = -3.5
        const val DE_ESSER_Q = 2.2
        const val DE_ESSER_SHELF_HZ = 9400.0
        const val DE_ESSER_SHELF_GAIN_DB = -1.8
        const val DEFAULT_OUTPUT_DENOISE_GATE_DB = 35.0
        const val OUTPUT_DENOISE_MIN_GAIN = 0.035f
        const val CLARITY_RECOVERY_HZ = 3000.0
        const val CLARITY_RECOVERY_GAIN_DB = 1.0
        const val CLARITY_RECOVERY_Q = 0.6
        const val DENOISE_FRAMES_PER_SECOND = 100
        const val DENOISE_NOISE_FLOOR_MARGIN_DB = 8.0
        const val DENOISE_NOISE_FLOOR_RISE_DB = 0.25
        const val DENOISE_SOFT_KNEE_DB = 12.0
        const val DENOISE_ATTACK_RATE = 0.55f
        const val DENOISE_RELEASE_RATE = 0.18f
    }
}
