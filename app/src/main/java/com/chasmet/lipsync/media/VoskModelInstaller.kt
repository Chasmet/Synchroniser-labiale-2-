package com.chasmet.lipsync.media

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

internal object VoskModelInstaller {

    fun ensureInstalled(context: Context): File {
        val target = File(context.noBackupFilesDir, MODEL_DIRECTORY)
        val marker = File(target, READY_MARKER)
        if (marker.readTextOrNull() == MODEL_VERSION) return target

        val temporary = File(context.noBackupFilesDir, "$MODEL_DIRECTORY.tmp")
        temporary.deleteRecursively()
        temporary.mkdirs()
        try {
            ZipInputStream(context.assets.open(MODEL_ASSET)).use { zip ->
                var entry = zip.nextEntry
                var extractedFiles = 0
                while (entry != null) {
                    val safePath = sanitizeEntry(entry.name)
                    if (safePath.isNotBlank()) {
                        val output = File(temporary, safePath)
                        check(output.canonicalPath.startsWith(temporary.canonicalPath + File.separator)) {
                            "Archive de reconnaissance vocale invalide"
                        }
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            output.outputStream().buffered().use { destination ->
                                zip.copyTo(destination, DEFAULT_BUFFER_SIZE)
                            }
                            extractedFiles++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                check(extractedFiles >= MINIMUM_MODEL_FILES) {
                    "Modèle vocal français incomplet"
                }
            }

            val children = temporary.listFiles().orEmpty()
            val extractedRoot = if (children.size == 1 && children[0].isDirectory) {
                children[0]
            } else {
                temporary
            }
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!extractedRoot.renameTo(target)) {
                check(extractedRoot.copyRecursively(target, overwrite = true)) {
                    "Impossible d’installer le modèle vocal"
                }
            }
            File(target, READY_MARKER).writeText(MODEL_VERSION)
            check(File(target, "am").exists() || File(target, "conf").exists()) {
                "Structure du modèle vocal non reconnue"
            }
            return target
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun sanitizeEntry(name: String): String {
        val normalized = name.replace('\\', '/').trimStart('/')
        require(!normalized.contains("../")) { "Chemin interdit dans le modèle vocal" }
        return normalized
    }

    private fun File.readTextOrNull(): String? = runCatching {
        if (isFile) readText().trim() else null
    }.getOrNull()

    const val MODEL_ASSET = "vosk-model-small-fr-0.22.zip"
    const val MODEL_VERSION = "vosk-small-fr-0.22-v1"
    private const val MODEL_DIRECTORY = "speech-model-fr-0.22"
    private const val READY_MARKER = ".lipsync-model-ready"
    private const val MINIMUM_MODEL_FILES = 10
}
