package com.ultimatervc.mobile

import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.security.MessageDigest

enum class ImportedFileKind(val directoryName: String) {
    AUDIO("audio"),
    MODEL("models"),
    INDEX("indexes"),
}

enum class TempWorkspaceMode(val directoryName: String) {
    AUDIO_INFERENCE("audio_inference"),
    VOICE_CHANGER("voice_changer"),
}

data class ManagedImportedFile(
    val path: String,
    val referenceCount: Int,
    val lastUpdatedAtMs: Long,
)

class TempWorkspaceManager(
    private val appFilesDir: File,
    private val appCacheDir: File,
) {
    companion object {
        const val IMPORTS_DIRECTORY_NAME = "file_picker"
        const val TEMP_DIRECTORY_NAME = "TEMP"
        const val REGISTRY_FILE_NAME = "reference_registry.json"
        const val TEMP_FILE_SUFFIX = ".tmp"
    }

    fun resolveImportRoot(kind: ImportedFileKind): File {
        return File(File(appCacheDir, IMPORTS_DIRECTORY_NAME), kind.directoryName).also { it.mkdirs() }
    }

    fun resolveModeRoot(mode: TempWorkspaceMode): File {
        return File(File(appFilesDir, TEMP_DIRECTORY_NAME), mode.directoryName).also { it.mkdirs() }
    }

    fun importPickedFile(kind: ImportedFileKind, sourcePath: String): ManagedImportedFile {
        val source = File(sourcePath)
        require(source.isFile) { "导入文件不存在：$sourcePath" }
        val target = resolveImportedTarget(kind, source)
        if (!target.exists()) {
            moveOrCopyIntoImportRoot(source, target)
        } else {
            cleanupPickerTempSource(source)
        }
        return acquireReference(target.absolutePath)
    }

    fun acquireReference(path: String): ManagedImportedFile {
        val normalizedPath = File(path).absolutePath
        val registry = loadRegistry()
        val fileState = registry.optJSONObject(normalizedPath) ?: JSONObject()
        val nextCount = fileState.optInt("referenceCount", 0) + 1
        val updatedAtMs = System.currentTimeMillis()
        registry.put(normalizedPath, JSONObject().apply {
            put("referenceCount", nextCount)
            put("lastUpdatedAtMs", updatedAtMs)
        })
        saveRegistry(registry)
        return ManagedImportedFile(normalizedPath, nextCount, updatedAtMs)
    }

    fun releaseReference(path: String): ManagedImportedFile {
        val normalizedPath = File(path).absolutePath
        val registry = loadRegistry()
        val fileState = registry.optJSONObject(normalizedPath) ?: JSONObject()
        val nextCount = (fileState.optInt("referenceCount", 0) - 1).coerceAtLeast(0)
        val updatedAtMs = System.currentTimeMillis()
        if (nextCount == 0) {
            registry.remove(normalizedPath)
        } else {
            registry.put(normalizedPath, JSONObject().apply {
                put("referenceCount", nextCount)
                put("lastUpdatedAtMs", updatedAtMs)
            })
        }
        saveRegistry(registry)
        return ManagedImportedFile(normalizedPath, nextCount, updatedAtMs)
    }

    fun deleteIfUnused(path: String): Boolean {
        val normalizedPath = File(path).absolutePath
        val registry = loadRegistry()
        val referenceCount = registry.optJSONObject(normalizedPath)?.optInt("referenceCount", 0) ?: 0
        if (referenceCount > 0) return false
        return File(normalizedPath).delete()
    }

    fun clearModeTempWorkspace(mode: TempWorkspaceMode) {
        val root = File(File(appFilesDir, TEMP_DIRECTORY_NAME), mode.directoryName)
        root.deleteRecursively()
        root.mkdirs()
        when (mode) {
            TempWorkspaceMode.AUDIO_INFERENCE -> {
                File(appFilesDir, "outputs").deleteRecursively()
                File(appFilesDir, "audio_inference_output").deleteRecursively()
            }
            TempWorkspaceMode.VOICE_CHANGER -> File(appFilesDir, "voice_changer").deleteRecursively()
        }
    }

    private fun loadRegistry(): JSONObject {
        val file = registryFile()
        if (!file.isFile) return JSONObject()
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun saveRegistry(registry: JSONObject) {
        val file = registryFile()
        file.parentFile?.mkdirs()
        val tempFile = File(file.absolutePath + TEMP_FILE_SUFFIX)
        FileOutputStream(tempFile).use { output ->
            output.write(registry.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Os.rename(tempFile.absolutePath, file.absolutePath)
    }

    private fun registryFile(): File {
        return File(File(appCacheDir, IMPORTS_DIRECTORY_NAME), REGISTRY_FILE_NAME)
    }

    private fun resolveImportedTarget(kind: ImportedFileKind, source: File): File {
        val importRoot = resolveImportRoot(kind)
        val sourceFingerprint = fileIdentityFingerprint(source)
        val originalName = source.name.ifBlank { sourceFingerprint + source.extension.takeIf { it.isNotBlank() }?.let { ".${it.lowercase()}" }.orEmpty() }
        val extension = source.extension.takeIf { it.isNotBlank() }?.let { ".${it.lowercase()}" }.orEmpty()
        val baseName = source.nameWithoutExtension.ifBlank { sourceFingerprint }

        var candidate = File(importRoot, originalName)
        var duplicateIndex = 2
        while (candidate.exists() && fileIdentityFingerprint(candidate) != sourceFingerprint) {
            candidate = File(importRoot, "${baseName}_$duplicateIndex$extension")
            duplicateIndex += 1
        }
        return candidate
    }

    private fun moveOrCopyIntoImportRoot(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        cleanupPickerTempSource(source)
    }

    private fun cleanupPickerTempSource(source: File) {
        val parent = source.parentFile ?: return
        val pickerRoot = File(appCacheDir, IMPORTS_DIRECTORY_NAME)
        if (parent.parentFile?.absolutePath != pickerRoot.absolutePath) return
        if (!parent.name.all { it.isDigit() }) return
        parent.deleteRecursively()
    }

    private fun fileIdentityFingerprint(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
