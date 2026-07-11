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
                    openness = frame.openness * 0.12f,
                    width = frame.width * 0.24f,
                    roundness = frame.roundness * 0.18f,
                    closure = maxOf(frame.closure, 0.94f)
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
        val recognition = ((cue.confidence - 0.34f) / 0.56f).coerceIn(0f, 1f)
        val alignment = ((cue.alignmentConfidence - 0.22f) / 0.68f).coerceIn(0f, 1f)
        val aligned = cue.phoneme.isNotBlank()
        val maximum = if (aligned) MAX_ALIGNED_GUIDANCE else MAX_FALLBACK_GUIDANCE
        val shapePriority = when (cue.shape) {
            FrenchLipShape.BILABIAL -> 1.00f
            FrenchLipShape.LABIODENTAL -> 0.94f
            FrenchLipShape.OPEN,
            FrenchLipShape.ROUND,
            FrenchLipShape.ROUND_TIGHT -> 0.96f
            FrenchLipShape.WIDE,
            FrenchLipShape.MID -> 0.90f
            FrenchLipShape.POSTALVEOLAR -> 0.86f
            FrenchLipShape.CONSONANT -> 0.72f
            FrenchLipShape.REST -> 1.00f
        }
        val evidence = if (aligned) {
            recognition * 0.46f + alignment * 0.54f
        } else recognition * 0.72f
        val strength = (evidence * edgeStrength(cue, timeUs) * maximum * shapePriority)
            .coerceIn(0f, maximum)
        if (strength <= 0f) return frame

        val target = when (cue.shape) {
            FrenchLipShape.REST -> floatArrayOf(0.01f, 0.18f, 0.04f, 0.99f)
            FrenchLipShape.BILABIAL -> floatArrayOf(0.01f, 0.26f, 0.04f, 1.00f)
            FrenchLipShape.LABIODENTAL -> floatArrayOf(0.14f, 0.76f, 0.03f, 0.52f)
            FrenchLipShape.OPEN -> floatArrayOf(0.98f, 0.52f, 0.05f, 0.00f)
            FrenchLipShape.MID -> floatArrayOf(0.60f, 0.70f, 0.05f, 0.04f)
            FrenchLipShape.WIDE -> floatArrayOf(0.34f, 0.98f, 0.01f, 0.03f)
            FrenchLipShape.ROUND -> floatArrayOf(0.56f, 0.18f, 1.00f, 0.02f)
            FrenchLipShape.ROUND_TIGHT -> floatArrayOf(0.24f, 0.10f, 1.00f, 0.06f)
            FrenchLipShape.POSTALVEOLAR -> floatArrayOf(0.34f, 0.48f, 0.44f, 0.14f)
            FrenchLipShape.CONSONANT -> floatArrayOf(0.22f, 0.60f, 0.05f, 0.30f)
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
        val fadeUs = min(20_000L, duration / 4L).coerceAtLeast(1L)
        val fadeIn = ((timeUs - cue.startUs).toFloat() / fadeUs).coerceIn(0f, 1f)
        val fadeOut = ((cue.endUs - timeUs).toFloat() / fadeUs).coerceIn(0f, 1f)
        return min(fadeIn, fadeOut)
    }

    private const val MAX_ALIGNED_GUIDANCE = 0.88f
    private const val MAX_FALLBACK_GUIDANCE = 0.64f
}
