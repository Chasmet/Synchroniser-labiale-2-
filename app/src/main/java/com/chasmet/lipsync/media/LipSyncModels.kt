package com.chasmet.lipsync.media

import android.net.Uri

data class SelectedMedia(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long
)

data class MouthRegion(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
) {
    companion object {
        val DEFAULT = MouthRegion(
            centerX = 0.5f,
            centerY = 0.36f,
            width = 0.24f,
            height = 0.10f
        )
    }
}

data class VisemeFrame(
    val timeUs: Long,
    val openness: Float,
    val width: Float,
    val roundness: Float
)

data class VisemeTimeline(
    val frames: List<VisemeFrame>,
    val durationUs: Long
) {
    fun frameAt(timeUs: Long): VisemeFrame {
        if (frames.isEmpty()) return VisemeFrame(timeUs, 0f, 0f, 0f)
        var low = 0
        var high = frames.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = frames[mid].timeUs
            when {
                value < timeUs -> low = mid + 1
                value > timeUs -> high = mid - 1
                else -> return frames[mid]
            }
        }
        return frames[high.coerceIn(0, frames.lastIndex)]
    }
}

enum class ProcessingStage(val label: String) {
    IDLE("Prêt"),
    PREPARING("Préparation des fichiers"),
    FACE_ANALYSIS("Détection du visage"),
    AUDIO_ANALYSIS("Analyse audio"),
    VIDEO_RENDER("Synchronisation des lèvres"),
    AUDIO_TRANSCODE("Préparation du MP3"),
    ASSEMBLY("Assemblage final"),
    EXPORT("Enregistrement"),
    DONE("Terminé"),
    ERROR("Erreur")
}

data class ProcessingStatus(
    val stage: ProcessingStage = ProcessingStage.IDLE,
    val progress: Float = 0f,
    val message: String = "",
    val currentBlock: Int = 0,
    val totalBlocks: Int = 0
)
