package com.chasmet.lipsync.media

internal object GeneratedBlendPolicy {
    fun strength(
        faceConfidence: Float,
        audioActivity: Float,
        qualityConfidence: Float,
        protectedFrame: Boolean
    ): Float {
        val tracking = ((faceConfidence - 0.20f) / 0.60f).coerceIn(0.68f, 1f)
        val activity = 0.88f + audioActivity.coerceIn(0f, 1f) * 0.06f
        val quality = 0.48f + qualityConfidence.coerceIn(0f, 1f) * 0.52f
        val raw = tracking * activity * quality
        return if (protectedFrame) raw.coerceIn(0f, 0.62f) else raw.coerceIn(0f, 0.94f)
    }
}
