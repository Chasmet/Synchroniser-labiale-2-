package com.chasmet.lipsync.media

import kotlin.math.abs
import kotlin.math.max

/**
 * Barrière de sécurité avant la fusion générative.
 *
 * Le centre de la bouche doit rester dans le cadre du visage et sa taille doit
 * être cohérente avec celle du visage. Si le suivi fournit un repère aberrant,
 * Wav2Lip est ignoré pour cette image au lieu de modifier une autre zone.
 */
internal object MouthPlacementGuard {

    fun confidence(mouth: MouthRegion, face: FaceRegion): Float {
        val halfFaceWidth = (face.width * 0.5f).coerceAtLeast(0.001f)
        val halfFaceHeight = (face.height * 0.5f).coerceAtLeast(0.001f)
        val dx = abs(mouth.centerX - face.centerX) / halfFaceWidth
        val dy = abs(mouth.centerY - face.centerY) / halfFaceHeight
        val mouthToFaceWidth = mouth.width / face.width.coerceAtLeast(0.001f)
        val mouthToFaceHeight = mouth.height / face.height.coerceAtLeast(0.001f)

        if (dx > 0.94f || dy > 0.94f) return 0f
        if (mouthToFaceWidth !in 0.055f..0.72f) return 0f
        if (mouthToFaceHeight !in 0.025f..0.52f) return 0f

        val centerScore = (1f - max(dx, dy)).coerceIn(0f, 1f)
        val widthScore = triangularScore(mouthToFaceWidth, 0.11f, 0.30f, 0.62f)
        val heightScore = triangularScore(mouthToFaceHeight, 0.045f, 0.16f, 0.44f)
        return (centerScore * 0.46f + widthScore * 0.32f + heightScore * 0.22f)
            .coerceIn(0f, 1f)
    }

    fun isSafe(mouth: MouthRegion, face: FaceRegion): Boolean {
        return confidence(mouth, face) >= MIN_SAFE_CONFIDENCE
    }

    private fun triangularScore(value: Float, minimum: Float, ideal: Float, maximum: Float): Float {
        if (value <= minimum || value >= maximum) return 0f
        return if (value <= ideal) {
            ((value - minimum) / (ideal - minimum)).coerceIn(0f, 1f)
        } else {
            ((maximum - value) / (maximum - ideal)).coerceIn(0f, 1f)
        }
    }

    private const val MIN_SAFE_CONFIDENCE = 0.28f
}
