package com.ultimatervc.mobile

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

class ResumableInferenceJobStore(
    private val appFilesDir: File,
    private val workspaceRelativePath: String = AUDIO_INFERENCE_TEMP_DIRECTORY,
) {
    companion object {
        const val JOBS_DIRECTORY_NAME = "resumable_inference_jobs"
        const val AUDIO_INFERENCE_TEMP_DIRECTORY = "TEMP/audio_inference"
        const val VOICE_CHANGER_INFERENCE_TEMP_DIRECTORY = "TEMP/voice_changer/inference"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val TEMP_MANIFEST_FILE_SUFFIX = ".tmp"
        const val CHUNKS_DIRECTORY_NAME = "chunks"
        const val OUTPUTS_DIRECTORY_NAME = "outputs"
        const val CHUNK_FILE_NAME_FORMAT = "chunk_%05d.wav"
        const val CONVERTED_CHUNK_FILE_NAME_FORMAT = "converted_chunk_%05d.wav"
    }

    enum class ResumableInferenceJobState {
        PENDING,
        RUNNING,
        PAUSED,
        FINALIZE_PENDING,
        FINALIZING,
        COMPLETED,
        FAILED,
    }

    data class ResumableInferenceJobLayout(
        val jobDirectory: File,
        val chunksDirectory: File,
        val outputsDirectory: File,
        val manifestFile: File,
    ) {
        fun chunkFile(chunkIndex: Int): File =
            File(chunksDirectory, CHUNK_FILE_NAME_FORMAT.format(Locale.US, chunkIndex))

        fun convertedChunkFile(chunkIndex: Int): File =
            File(outputsDirectory, CONVERTED_CHUNK_FILE_NAME_FORMAT.format(Locale.US, chunkIndex))
    }

    data class ResumableInferenceJobManifest(
        val jobId: String,
        val sourceAudioPath: String,
        val sourceAudioFingerprint: String,
        val modelPath: String,
        val modelFingerprint: String,
        val indexPath: String?,
        val parameterFingerprint: String,
        val jobDirectoryPath: String,
        val chunksDirectoryPath: String,
        val outputsDirectoryPath: String,
        val manifestPath: String,
        val segmentCount: Int,
        val completedChunkIndexes: List<Int> = emptyList(),
        val lastCompletedChunkIndex: Int = -1,
        val overallProgress: Double = 0.0,
        val accumulatedElapsedMs: Long = 0L,
        val state: ResumableInferenceJobState = ResumableInferenceJobState.PENDING,
    )

    fun prepareLayout(jobId: String): ResumableInferenceJobLayout {
        val layout = layoutFor(jobId)
        layout.chunksDirectory.mkdirs()
        layout.outputsDirectory.mkdirs()
        return layout
    }

    fun createManifest(
        sourceAudioPath: String,
        modelPath: String,
        indexPath: String?,
        parameterFingerprint: String,
        segmentCount: Int,
    ): ResumableInferenceJobManifest {
        val sourceAudioFingerprint = fileIdentityFingerprint(File(sourceAudioPath))
        val modelFingerprint = fileIdentityFingerprint(File(modelPath))
        val jobId = buildJobId(sourceAudioFingerprint, modelFingerprint, indexPath, parameterFingerprint)
        val layout = prepareLayout(jobId)
        return ResumableInferenceJobManifest(
            jobId = jobId,
            sourceAudioPath = sourceAudioPath,
            sourceAudioFingerprint = sourceAudioFingerprint,
            modelPath = modelPath,
            modelFingerprint = modelFingerprint,
            indexPath = indexPath,
            parameterFingerprint = parameterFingerprint,
            jobDirectoryPath = layout.jobDirectory.absolutePath,
            chunksDirectoryPath = layout.chunksDirectory.absolutePath,
            outputsDirectoryPath = layout.outputsDirectory.absolutePath,
            manifestPath = layout.manifestFile.absolutePath,
            segmentCount = segmentCount,
        )
    }

    fun findReusableManifest(
        sourceAudioPath: String,
        modelPath: String,
        indexPath: String?,
        parameterFingerprint: String,
    ): ResumableInferenceJobManifest? {
        val sourceAudioFingerprint = fileIdentityFingerprint(File(sourceAudioPath))
        val modelFingerprint = fileIdentityFingerprint(File(modelPath))
        val jobId = buildJobId(sourceAudioFingerprint, modelFingerprint, indexPath, parameterFingerprint)
        val manifest = loadCurrentManifest() ?: return null
        if (manifest.jobId != jobId) return null
        if (manifest.sourceAudioPath != sourceAudioPath) return null
        if (manifest.sourceAudioFingerprint != sourceAudioFingerprint) return null
        if (manifest.modelPath != modelPath) return null
        if (manifest.modelFingerprint != modelFingerprint) return null
        if (manifest.indexPath != indexPath) return null
        if (manifest.parameterFingerprint != parameterFingerprint) return null
        return manifest
    }

    fun saveManifest(manifest: ResumableInferenceJobManifest) {
        val manifestFile = File(manifest.manifestPath)
        manifestFile.parentFile?.mkdirs()
        val tempFile = File(manifestFile.absolutePath + TEMP_MANIFEST_FILE_SUFFIX)
        FileOutputStream(tempFile).use { output ->
            output.write(manifest.toJsonObject().toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Os.rename(tempFile.absolutePath, manifestFile.absolutePath)
    }

    fun loadManifest(jobId: String): ResumableInferenceJobManifest? {
        val manifestFile = layoutFor(jobId).manifestFile
        if (!manifestFile.isFile) return null
        return manifestFromJson(JSONObject(manifestFile.readText(Charsets.UTF_8))).takeIf { it.jobId == jobId }
    }

    fun loadCurrentManifest(): ResumableInferenceJobManifest? {
        val manifestFile = File(File(appFilesDir, workspaceRelativePath), MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) return null
        return manifestFromJson(JSONObject(manifestFile.readText(Charsets.UTF_8)))
    }

    fun deleteJob(jobId: String) {
        val layout = layoutFor(jobId)
        layout.jobDirectory.deleteRecursively()
        val currentManifest = loadCurrentManifest()
        if (currentManifest?.jobId == jobId) {
            layout.manifestFile.delete()
        }
    }

    fun hasResumableArtifacts(): Boolean {
        val workspaceRoot = File(appFilesDir, workspaceRelativePath)
        val manifestExists = File(workspaceRoot, MANIFEST_FILE_NAME).isFile
        val hasChunkFiles = workspaceRoot.walkTopDown().any { it.isFile && it.parentFile?.name == CHUNKS_DIRECTORY_NAME }
        val hasOutputChunkFiles = workspaceRoot.walkTopDown().any { it.isFile && it.parentFile?.name == OUTPUTS_DIRECTORY_NAME }
        return manifestExists || hasChunkFiles || hasOutputChunkFiles
    }

    private fun layoutFor(jobId: String): ResumableInferenceJobLayout {
        val workspaceRoot = File(appFilesDir, workspaceRelativePath)
        val jobDirectory = File(workspaceRoot, jobId)
        return ResumableInferenceJobLayout(
            jobDirectory = jobDirectory,
            chunksDirectory = File(jobDirectory, CHUNKS_DIRECTORY_NAME),
            outputsDirectory = File(jobDirectory, OUTPUTS_DIRECTORY_NAME),
            manifestFile = File(workspaceRoot, MANIFEST_FILE_NAME),
        )
    }

    fun existingConvertedChunkIndexes(jobId: String, segmentCount: Int): List<Int> {
        val outputsDirectory = layoutFor(jobId).outputsDirectory
        if (!outputsDirectory.isDirectory || segmentCount <= 0) return emptyList()
        return (0 until segmentCount).filter { index ->
            File(outputsDirectory, CONVERTED_CHUNK_FILE_NAME_FORMAT.format(Locale.US, index)).isFile
        }
    }

    private fun ResumableInferenceJobManifest.toJsonObject(): JSONObject = JSONObject().apply {
        put("jobId", jobId)
        put("sourceAudioPath", sourceAudioPath)
        put("sourceAudioFingerprint", sourceAudioFingerprint)
        put("modelPath", modelPath)
        put("modelFingerprint", modelFingerprint)
        put("indexPath", indexPath ?: JSONObject.NULL)
        put("parameterFingerprint", parameterFingerprint)
        put("jobDirectoryPath", jobDirectoryPath)
        put("chunksDirectoryPath", chunksDirectoryPath)
        put("outputsDirectoryPath", outputsDirectoryPath)
        put("manifestPath", manifestPath)
        put("segmentCount", segmentCount)
        put("completedChunkIndexes", JSONArray(completedChunkIndexes))
        put("lastCompletedChunkIndex", lastCompletedChunkIndex)
        put("overallProgress", overallProgress)
        put("accumulatedElapsedMs", accumulatedElapsedMs)
        put("state", state.name)
    }

    private fun manifestFromJson(json: JSONObject): ResumableInferenceJobManifest {
        val completedChunkIndexes = json.getJSONArray("completedChunkIndexes")
        return ResumableInferenceJobManifest(
            jobId = json.getString("jobId"),
            sourceAudioPath = json.getString("sourceAudioPath"),
            sourceAudioFingerprint = json.getString("sourceAudioFingerprint"),
            modelPath = json.getString("modelPath"),
            modelFingerprint = json.getString("modelFingerprint"),
            indexPath = json.optString("indexPath").takeUnless { json.isNull("indexPath") },
            parameterFingerprint = json.getString("parameterFingerprint"),
            jobDirectoryPath = json.getString("jobDirectoryPath"),
            chunksDirectoryPath = json.getString("chunksDirectoryPath"),
            outputsDirectoryPath = json.getString("outputsDirectoryPath"),
            manifestPath = json.getString("manifestPath"),
            segmentCount = json.getInt("segmentCount"),
            completedChunkIndexes = List(completedChunkIndexes.length()) { index -> completedChunkIndexes.getInt(index) },
            lastCompletedChunkIndex = json.getInt("lastCompletedChunkIndex"),
            overallProgress = json.getDouble("overallProgress"),
            accumulatedElapsedMs = json.optLong("accumulatedElapsedMs", 0L),
            state = ResumableInferenceJobState.valueOf(json.getString("state")),
        )
    }

    private fun fileIdentityFingerprint(file: File): String {
        val identity = listOf(
            file.absolutePath,
            file.length().toString(),
            file.lastModified().toString(),
        ).joinToString("|")
        return sha256Hex(identity)
    }

    private fun buildJobId(
        sourceAudioFingerprint: String,
        modelFingerprint: String,
        indexPath: String?,
        parameterFingerprint: String,
    ): String = sha256Hex(
        listOf(
            sourceAudioFingerprint,
            modelFingerprint,
            indexPath.orEmpty(),
            parameterFingerprint,
        ).joinToString("|")
    )

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
}
