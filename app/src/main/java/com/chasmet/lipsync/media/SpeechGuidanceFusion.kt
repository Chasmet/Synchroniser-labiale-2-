package com.chasmet.lipsync.media

import kotlin.math.min

internal object SpeechGuidanceFusion {

    fun fuse(
        base: VisemeTimeline,
        guidance: SpeechGuidance,
        activity: VoiceActivityTimeline
    ): VisemeTimeline {
        if (base.frames.isEmpty()) return base
        val guidedFrames = base.frames.map { frame ->
            val speechActive = activity.durationUs <= 0L || activity.isSpeechAt(frame.timeUs)
            val cue = guidance.cueAt(frame.timeUs)
            var result = if (!speechActive) {
                frame.copy(
                    openness = frame.openness * 0.16f,
                    width = frame.width * 0.28f,
                    roundness = frame.roundness * 0.24f,
                    closure = maxOf(frame.closure, 0.90f)
                )
            } else {
                frame
            }
            if (cue != null) result = applyCue(result, cue, frame.timeUs)
            result
        }
        return VisemeTimeline(guidedFrames, base.durationUs)
    }

    private fun applyCue(frame: VisemeFrame, cue: PhonemeCue, timeUs: Long): VisemeFrame {
        val confidence = ((cue.confidence - 0.38f) / 0.50f).coerceIn(0f, 1f)
        val strength = (
            confidence * edgeStrength(cue, timeUs) * MAX_TEXT_GUIDANCE
            ).coerceIn(0f, MAX_TEXT_GUIDANCE)
        if (strength <= 0f) return frame

        val target = when (cue.shape) {
            FrenchLipShape.REST -> floatArrayOf(0.02f, 0.20f, 0.08f, 0.96f)
            FrenchLipShape.BILABIAL -> floatArrayOf(0.025f, 0.30f, 0.10f, 0.97f)
            FrenchLipShape.LABIODENTAL -> floatArrayOf(0.18f, 0.68f, 0.06f, 0.42f)
            FrenchLipShape.OPEN -> floatArrayOf(0.90f, 0.54f, 0.08f, 0.02f)
            FrenchLipShape.MID -> floatArrayOf(0.56f, 0.66f, 0.08f, 0.06f)
            FrenchLipShape.WIDE -> floatArrayOf(0.38f, 0.90f, 0.02f, 0.05f)
            FrenchLipShape.ROUND -> floatArrayOf(0.52f, 0.22f, 0.94f, 0.04f)
            FrenchLipShape.ROUND_TIGHT -> floatArrayOf(0.28f, 0.14f, 1.00f, 0.08f)
            FrenchLipShape.POSTALVEOLAR -> floatArrayOf(0.30f, 0.52f, 0.36f, 0.18f)
            FrenchLipShape.CONSONANT -> floatArrayOf(0.24f, 0.58f, 0.08f, 0.26f)
        }

        fun mix(source: Float, destination: Float): Float =
            (source + (destination - source) * strength).coerceIn(0f, 1f)

        return frame.copy(
            openness = mix(frame.openness, target[0]),
            width = mix(frame.width, target[1]),
            roundness = mix(frame.roundness, target[2]),
            closure = mix(frame.closure, target[3])
        )
    }

    private fun edgeStrength(cue: PhonemeCue, timeUs: Long): Float {
        val duration = (cue.endUs - cue.startUs).coerceAtLeast(1L)
        val fadeUs = min(35_000L, duration / 3L).coerceAtLeast(1L)
        val fadeIn = ((timeUs - cue.startUs).toFloat() / fadeUs).coerceIn(0f, 1f)
        val fadeOut = ((cue.endUs - timeUs).toFloat() / fadeUs).coerceIn(0f, 1f)
        return min(fadeIn, fadeOut)
    }

    private const val MAX_TEXT_GUIDANCE = 0.58f
}
