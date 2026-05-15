package com.ultimatervc.mobile

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class VoiceChangerResumableMetadata(
    val jobId: String,
    val overallProgress: Double,
    val state: String,
)

data class VoiceChangerRecordingConfig(
    val modelPath: String,
    val indexPath: String?,
    val pitchChange: Double,
    val formant: Double,
    val indexRate: Double,
    val rmsMixRate: Double,
    val protectRate: Double,
    val filterRadius: Int,
    val sampleRate: Int,
    val noiseGateDb: Double,
    val outputDenoiseEnabled: Boolean,
    val vocalRangeFilterEnabled: Boolean,
    val parallelChunkCount: Int,
    val playbackDelaySeconds: Double,
    val enableRootPerformanceMode: Boolean,
)

class VoiceChangerRecorder(
    private val context: Context,
    private val modelsDir: File,
    private val config: VoiceChangerRecordingConfig,
    private val onProcessingProgress: (Double) -> Unit,
    private val onProcessingComplete: () -> Unit,
    private val onProcessingFailed: (String) -> Unit,
    private val onNormalPlaybackComplete: () -> Unit,
    private val onTrialPlaybackComplete: () -> Unit,
) {
    private val workspaceRoot = TempWorkspaceManager(context.filesDir, context.cacheDir).resolveModeRoot(TempWorkspaceMode.VOICE_CHANGER)
    private val manifestFile = File(workspaceRoot, "manifest.json")
    private var latestRecordingDisplayName = "recording_${readableTimestamp()}.wav"
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var inputFile: File? = null
    private var outputFile: File? = null
    private var processingToken: CancellationToken? = null
    private var processingThread: Thread? = null
    private var deleteAfterCurrentPlayback = false
    private var discardCurrentRecording = false
    private var processedSampleCount = 0
    private var processedSampleRate = 0
    private var currentProcessingProgress = 0.0
    private val pauseRequested = AtomicBoolean(false)
    private var suppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    fun canStartProcessing(): Boolean {
        return NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)?.let { token ->
            token.release()
            true
        } ?: false
    }

    fun startRecording() {
        if (!recording.compareAndSet(false, true)) return
        discardCurrentRecording = false
        deleteWorkingFiles()
        workspaceRoot.mkdirs()
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            MIN_BUFFER_FRAMES * BYTES_PER_PCM_16,
        )
        latestRecordingDisplayName = "recording_${readableTimestamp()}.wav"
        val file = File(workspaceRoot, latestRecordingDisplayName)
        inputFile = file
        file.parentFile?.mkdirs()
        writeManifest(state = "RECORDING", overallProgress = 0.0, inputPath = file.absolutePath, outputPath = null)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        ).also { record ->
            val buffer = ShortArray(bufferSize / BYTES_PER_PCM_16)
            enableCaptureEnhancements(record.audioSessionId)
            record.startRecording()
            thread(name = "VoiceChangerRecorder") {
                var sampleCount = 0
                writeWavStream(file, config.sampleRate) { output ->
                    while (recording.get()) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val cleaned = preprocessRecordedAudio(buffer, read)
                            writePcm16(output, cleaned, read)
                            sampleCount += read
                        }
                    }
                }
                runCatching { record.stop() }
                releaseCaptureEnhancements()
                record.release()
                audioRecord = null
                if (discardCurrentRecording) {
                    file.delete()
                    if (inputFile == file) {
                        inputFile = null
                    }
                    clearManifest()
                } else {
                    processRecording(file)
                }
            }
        }
    }

    fun stopRecordingAndProcess() {
        recording.set(false)
    }

    fun stopRecordingWithoutProcessing() {
        discardCurrentRecording = true
        recording.set(false)
        runCatching { audioRecord?.stop() }
        releaseCaptureEnhancements()
        audioRecord?.release()
        audioRecord = null
        inputFile?.delete()
        inputFile = null
    }

    fun cancelProcessing() {
        pauseRequested.set(false)
        processingToken?.cancel()
        processingThread?.interrupt()
        stopPlayback()
        deleteWorkingFiles()
        processingToken = null
        processingThread = null
        currentProcessingProgress = 0.0
        processedSampleCount = 0
        processedSampleRate = 0
        clearManifest()
    }

    fun pauseProcessing() {
        pauseRequested.set(true)
        processingToken?.cancel()
        processingThread?.interrupt()
        stopPlayback()
        processingToken = null
        processingThread = null
        outputFile?.delete()
        outputFile = null
        processedSampleCount = 0
        processedSampleRate = 0
        val inputPath = currentInputPath() ?: return
        writeManifest(state = "PAUSED", overallProgress = currentProcessingProgress, inputPath = inputPath, outputPath = null)
    }

    fun canRetryProcessing(): Boolean = currentInputPath() != null

    fun currentOverallProgress(): Double {
        val manifest = loadManifest() ?: return currentProcessingProgress
        return manifest.optDouble("overallProgress", currentProcessingProgress).coerceIn(0.0, 100.0)
    }

    fun retryProcessing(): Boolean {
        val file = inputFile ?: currentInputPath()?.let { File(it) } ?: return false
        pauseRequested.set(false)
        inputFile = file
        stopPlayback()
        outputFile?.delete()
        outputFile = null
        currentProcessingProgress = currentOverallProgress().coerceAtLeast(1.0)
        processedSampleCount = 0
        processedSampleRate = 0
        processRecording(file)
        return true
    }

    fun currentInputPath(): String? {
        inputFile?.absolutePath?.let { return it }
        val manifest = loadManifest() ?: return null
        val inputPath = manifest.optString("inputPath")
        return inputPath.takeIf { it.isNotBlank() && File(it).isFile }
    }

    fun togglePlayback() {
        val activeTrack = audioTrack
        if (activeTrack != null && activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
            activeTrack.pause()
            return
        }
        if (activeTrack != null && activeTrack.playState == AudioTrack.PLAYSTATE_PAUSED) {
            activeTrack.play()
            return
        }
        playOutput(deleteAfterPlayback = deleteAfterCurrentPlayback)
    }

    fun playNormal() {
        playOutput(deleteAfterPlayback = true)
    }

    fun playTrial() {
        playOutput(deleteAfterPlayback = false)
    }

    fun stopPlayback() {
        audioTrack?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        audioTrack = null
    }

    fun discardWorkingFiles() {
        stopPlayback()
        deleteWorkingFiles()
        clearManifest()
    }

    fun resumableMetadata(): VoiceChangerResumableMetadata? {
        val manifest = loadManifest() ?: return null
        val inputPath = manifest.optString("inputPath")
        val outputPath = manifest.optString("outputPath").takeUnless { manifest.isNull("outputPath") || it.isBlank() }
        if (inputPath.isBlank() || !File(inputPath).isFile) return null
        if (outputPath != null && !File(outputPath).isFile) return null
        return VoiceChangerResumableMetadata(
            jobId = manifest.optString("jobId").ifBlank { "voice_changer" },
            overallProgress = manifest.optDouble("overallProgress", 0.0),
            state = manifest.optString("state", "PENDING"),
        )
    }

    private fun processRecording(file: File) {
        processingThread = thread(name = "VoiceChangerProcessor") {
            pauseRequested.set(false)
            val cancellationToken = CancellationToken()
            processingToken = cancellationToken
            var rootPerformanceSession: RVCPlugin.RootPerformanceSession? = null
            val progressCallback = { percent: Double, _: String -> onProcessingProgress(percent) }
            try {
                currentProcessingProgress = currentProcessingProgress.coerceAtLeast(1.0)
                writeManifest(state = "RUNNING", overallProgress = currentProcessingProgress, inputPath = file.absolutePath, outputPath = null)
                rootPerformanceSession = RVCPlugin.RootPerformanceSession.startIfEnabled(
                    config.enableRootPerformanceMode,
                    File(context.filesDir, "root_performance/node_cache.txt"),
                ) { _ -> }
                val outputPath = RemoteInferenceClient(context).infer(
                    RvcInferenceRequest(
                        songPath = file.absolutePath,
                        modelPath = config.modelPath,
                        indexPath = config.indexPath,
                        pitchChange = config.pitchChange,
                        indexRate = config.indexRate,
                        formant = config.formant,
                        filterRadius = config.filterRadius,
                        rmsMixRate = config.rmsMixRate,
                        protectRate = config.protectRate,
                        sampleRate = config.sampleRate,
                        noiseGateDb = config.noiseGateDb,
                        outputDenoiseEnabled = config.outputDenoiseEnabled,
                        vocalRangeFilterEnabled = config.vocalRangeFilterEnabled,
                        parallelChunkCount = config.parallelChunkCount,
                        allowResume = true,
                        workspaceRelativePath = ResumableInferenceJobStore.VOICE_CHANGER_INFERENCE_TEMP_DIRECTORY,
                        cancellationToken = cancellationToken,
                        onProgress = { percent, _ ->
                            currentProcessingProgress = percent
                            writeManifest(state = "RUNNING", overallProgress = percent, inputPath = file.absolutePath, outputPath = null)
                            progressCallback(percent, "")
                        },
                    ),
                )
                outputFile = processingOutputFileFor(file)
                File(outputPath).copyTo(outputFile!!, overwrite = true)
                val wavInfo = readWavInfo(outputFile!!)
                processedSampleCount = wavInfo.sampleCount
                processedSampleRate = wavInfo.sampleRate
                currentProcessingProgress = 100.0
                writeManifest(state = "COMPLETED", overallProgress = 100.0, inputPath = file.absolutePath, outputPath = outputFile!!.absolutePath)
                RemoteInferenceClient.stopInferenceProcessAndWait(context)
                rootPerformanceSession?.restore()
                rootPerformanceSession = null
                requestNativeMemoryCleanup()
                onProcessingComplete()
            } catch (error: java.util.concurrent.CancellationException) {
                RemoteInferenceClient.stopInferenceProcessAndWait(context)
                if (pauseRequested.get()) {
                    writeManifest(state = "PAUSED", overallProgress = currentProcessingProgress, inputPath = file.absolutePath, outputPath = null)
                    return@thread
                }
                throw error
            } catch (error: Throwable) {
                RemoteInferenceClient.stopInferenceProcessAndWait(context)
                if (pauseRequested.get()) {
                    writeManifest(state = "PAUSED", overallProgress = currentProcessingProgress, inputPath = file.absolutePath, outputPath = null)
                    return@thread
                }
                writeManifest(state = "FAILED", overallProgress = 0.0, inputPath = file.absolutePath, outputPath = null)
                onProcessingFailed(error.message ?: "处理出错")
            } finally {
                RemoteInferenceClient.stopInferenceProcessAndWait(context)
                rootPerformanceSession?.restore()
                processingToken = null
                processingThread = null
            }
        }
    }

    fun targetSaveDisplayPath(): String {
        val source = outputFile
        val displayName = source?.name ?: "processed.rvc.wav"
        return "Download/RVC_Convert/变声器模式/$displayName"
    }

    fun targetSaveRawRecordingDisplayPath(): String {
        val source = inputFile ?: currentInputPath()?.let { File(it) }
        val displayName = source?.name ?: latestRecordingDisplayName
        return "Download/RVC_Convert/变声器模式/$displayName"
    }

    fun saveRawRecording(): String {
        val source = inputFile ?: currentInputPath()?.let { File(it) } ?: error("暂无原始录音")
        require(source.exists()) { "原始录音不存在" }
        val displayPath = targetSaveRawRecordingDisplayPath()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RVC_Convert/变声器模式")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建媒体文件")
        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } ?: error("无法写入媒体文件")
        return displayPath
    }

    fun saveProcessedOutput(): String {
        val source = outputFile ?: error("暂无处理后的音频")
        require(source.exists()) { "处理后的音频不存在" }
        val displayPath = targetSaveDisplayPath()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RVC_Convert/变声器模式")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建媒体文件")
        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } ?: error("无法写入媒体文件")
        return displayPath
    }

    fun processedDurationSeconds(): Int {
        if (processedSampleRate <= 0) return 0
        return (processedSampleCount.toDouble() / processedSampleRate).toInt().coerceAtLeast(0)
    }

    fun playbackRemainingSeconds(): Int {
        val track = audioTrack ?: return 0
        if (processedSampleRate <= 0) return 0
        val remaining = processedSampleCount - track.playbackHeadPosition
        return kotlin.math.ceil(remaining.coerceAtLeast(0).toDouble() / processedSampleRate).toInt()
    }

    private fun playOutput(deleteAfterPlayback: Boolean) {
        val file = outputFile ?: return
        val wavPcm = readWavPcm(file)
        val pcm = wavPcm.samples
        if (pcm.isEmpty()) return
        deleteAfterCurrentPlayback = deleteAfterPlayback
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            wavPcm.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(pcm.size * BYTES_PER_PCM_16, MIN_BUFFER_FRAMES * BYTES_PER_PCM_16),
            AudioTrack.MODE_STATIC,
        )
        audioTrack = track
        processedSampleCount = pcm.size
        processedSampleRate = wavPcm.sampleRate
        val written = track.write(pcm, 0, pcm.size)
        check(written >= 0) { "音频写入失败：$written" }
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                stopPlayback()
                if (deleteAfterPlayback) {
                    deleteWorkingFiles()
                    onNormalPlaybackComplete()
                } else {
                    onTrialPlaybackComplete()
                }
            }

            override fun onPeriodicNotification(track: AudioTrack) = Unit
        })
        track.play()
    }

    private fun deleteWorkingFiles() {
        inputFile?.delete()
        outputFile?.delete()
        inputFile = null
        outputFile = null
    }

    private fun processingOutputFileFor(input: File): File {
        return File(workspaceRoot, processedOutputFileName(input))
    }

    private fun processedOutputFileName(input: File): String {
        val inputName = input.nameWithoutExtension.ifBlank { "recording_${readableTimestamp()}" }
        val modelName = File(config.modelPath).nameWithoutExtension.ifBlank { "model" }
        return "${inputName}_[${modelName}].rvc.wav"
    }

    private fun loadManifest(): org.json.JSONObject? {
        if (!manifestFile.isFile) return null
        return org.json.JSONObject(manifestFile.readText(Charsets.UTF_8))
    }

    private fun writeManifest(
        state: String,
        overallProgress: Double,
        inputPath: String,
        outputPath: String?,
    ) {
        workspaceRoot.mkdirs()
        manifestFile.writeText(
            org.json.JSONObject()
                .put("jobId", "voice_changer")
                .put("state", state)
                .put("overallProgress", overallProgress)
                .put("inputPath", inputPath)
                .put("modelPath", config.modelPath)
                .put("indexPath", config.indexPath ?: org.json.JSONObject.NULL)
                .put("pitchChange", config.pitchChange)
                .put("indexRate", config.indexRate)
                .put("formant", config.formant)
                .put("filterRadius", config.filterRadius)
                .put("rmsMixRate", config.rmsMixRate)
                .put("protectRate", config.protectRate)
                .put("sampleRate", config.sampleRate)
                .put("noiseGateDb", config.noiseGateDb)
                .put("outputDenoiseEnabled", config.outputDenoiseEnabled)
                .put("vocalRangeFilterEnabled", config.vocalRangeFilterEnabled)
                .put("outputPath", outputPath ?: org.json.JSONObject.NULL)
                .toString(2),
            Charsets.UTF_8,
        )
    }

    private fun clearManifest() {
        manifestFile.delete()
    }

    private fun readableTimestamp(): String {
        return SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.US).format(Date())
    }

    private fun enableCaptureEnhancements(audioSessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            suppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        }
    }

    private fun releaseCaptureEnhancements() {
        suppressor?.release()
        suppressor = null
        gainControl?.release()
        gainControl = null
    }

    private fun preprocessRecordedAudio(source: ShortArray, sampleCount: Int): ShortArray {
        var peak = 0
        for (index in 0 until sampleCount) {
            peak = maxOf(peak, kotlin.math.abs(source[index].toInt()))
        }
        if (peak == 0) return source.copyOf(sampleCount)
        val targetPeak = (Short.MAX_VALUE * RECORDING_TARGET_PEAK_RATIO).toInt().coerceAtLeast(1)
        val gain = (targetPeak.toDouble() / peak.toDouble()).coerceAtMost(RECORDING_MAX_GAIN)
        val processed = ShortArray(sampleCount)
        for (index in 0 until sampleCount) {
            val boosted = (source[index] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            processed[index] = boosted.toShort()
        }
        return processed
    }

    private fun writeWavStream(file: File, sampleRate: Int, block: (FileOutputStream) -> Unit) {
        FileOutputStream(file).use { output ->
            output.write(ByteArray(WAV_HEADER_BYTES))
            block(output)
        }
        updateWavHeader(file, sampleRate)
    }

    private fun writePcm16(output: FileOutputStream, samples: ShortArray, sampleCount: Int) {
        val pcm = ByteBuffer.allocate(sampleCount * BYTES_PER_PCM_16).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            pcm.putShort(samples[index])
        }
        output.write(pcm.array())
    }

    private fun updateWavHeader(file: File, sampleRate: Int) {
        val dataSize = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0).toInt()
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(WAV_HEADER_BYTES - 8 + dataSize)
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
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * BYTES_PER_PCM_16
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(WAV_HEADER_BYTES - 8 + dataSize)
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
        FileOutputStream(file).use { output ->
            output.write(header.array())
            val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { pcm.putShort(it) }
            output.write(pcm.array())
        }
    }

    private data class WavPcm(val samples: ShortArray, val sampleRate: Int)

    private data class WavInfo(val sampleCount: Int, val sampleRate: Int)

    private fun readWavInfo(file: File): WavInfo {
        val header = ByteArray(WAV_HEADER_BYTES)
        FileInputStream(file).use { input ->
            val read = input.read(header)
            if (read < WAV_HEADER_BYTES) return WavInfo(0, config.sampleRate)
        }
        val sampleRate = ByteBuffer.wrap(header, 24, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
        val dataSize = ByteBuffer.wrap(header, 40, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.coerceAtLeast(0)
        return WavInfo(dataSize / BYTES_PER_PCM_16, sampleRate)
    }

    private fun readWavPcm(file: File): WavPcm {
        val bytes = file.readBytes()
        if (bytes.size <= WAV_HEADER_BYTES) return WavPcm(ShortArray(0), config.sampleRate)
        val sampleRate = ByteBuffer.wrap(bytes, 24, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
        val sampleCount = (bytes.size - WAV_HEADER_BYTES) / BYTES_PER_PCM_16
        val buffer = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, sampleCount * BYTES_PER_PCM_16).order(ByteOrder.LITTLE_ENDIAN)
        return WavPcm(ShortArray(sampleCount) { buffer.short }, sampleRate)
    }

    private fun requestNativeMemoryCleanup() {
        System.runFinalization()
        System.gc()
    }

    private companion object {
        const val MIN_BUFFER_FRAMES = 4_096
        const val BYTES_PER_PCM_16 = 2
        const val WAV_HEADER_BYTES = 44
        const val RECORDING_TARGET_PEAK_RATIO = 0.72
        const val RECORDING_MAX_GAIN = 3.0
    }
}
