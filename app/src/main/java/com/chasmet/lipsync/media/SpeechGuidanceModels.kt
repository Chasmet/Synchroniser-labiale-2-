package com.chasmet.lipsync.media

enum class FrenchLipShape {
    REST,
    BILABIAL,
    LABIODENTAL,
    OPEN,
    MID,
    WIDE,
    ROUND,
    ROUND_TIGHT,
    POSTALVEOLAR,
    CONSONANT
}

data class RecognizedWord(
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val confidence: Float
)

data class PhonemeCue(
    val startUs: Long,
    val endUs: Long,
    val shape: FrenchLipShape,
    val confidence: Float
)

data class SpeechGuidance(
    val transcript: String,
    val words: List<RecognizedWord>,
    val cues: List<PhonemeCue>,
    val averageConfidence: Float,
    val accepted: Boolean,
    val engine: String
) {
    fun cueAt(timeUs: Long): PhonemeCue? {
        if (!accepted || cues.isEmpty()) return null
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val cue = cues[middle]
            when {
                timeUs < cue.startUs -> high = middle - 1
                timeUs >= cue.endUs -> low = middle + 1
                else -> return cue
            }
        }
        return null
    }

    companion object {
        val EMPTY = SpeechGuidance(
            transcript = "",
            words = emptyList(),
            cues = emptyList(),
            averageConfidence = 0f,
            accepted = false,
            engine = "Aucune transcription"
        )
    }
}
