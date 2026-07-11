package com.chasmet.lipsync.media

import android.content.Context
import org.json.JSONObject

/** Réglages mesurés sur les vidéos personnelles sans intégrer les vidéos sources. */
internal data class TemporalMotionProfile(
    val name: String,
    val lookAheadFrames: Int,
    val attackFactor: Float,
    val releaseFactor: Float,
    val closureStrength: Float,
    val opennessGain: Float,
    val widthGain: Float,
    val roundnessGain: Float,
    val minimumSpeechGate: Float
) {
    companion object {
        private const val ASSET_NAME = "chk_temporal_motion_profile_v3.json"

        val DEFAULT = TemporalMotionProfile(
            name = "Profil temporel standard",
            lookAheadFrames = 2,
            attackFactor = 0.62f,
            releaseFactor = 0.34f,
            closureStrength = 0.72f,
            opennessGain = 1.16f,
            widthGain = 1.06f,
            roundnessGain = 1.04f,
            minimumSpeechGate = 0.03f
        )

        fun load(context: Context): TemporalMotionProfile {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val inference = root.getJSONObject("inference")
            return TemporalMotionProfile(
                name = root.optString("name", DEFAULT.name),
                lookAheadFrames = inference.optInt(
                    "look_ahead_frames",
                    DEFAULT.lookAheadFrames
                ).coerceIn(0, 5),
                attackFactor = inference.optDouble(
                    "attack_factor",
                    DEFAULT.attackFactor.toDouble()
                ).toFloat().coerceIn(0.05f, 0.95f),
                releaseFactor = inference.optDouble(
                    "release_factor",
                    DEFAULT.releaseFactor.toDouble()
                ).toFloat().coerceIn(0.05f, 0.95f),
                closureStrength = inference.optDouble(
                    "closure_strength",
                    DEFAULT.closureStrength.toDouble()
                ).toFloat().coerceIn(0f, 1f),
                opennessGain = inference.optDouble(
                    "openness_gain",
                    DEFAULT.opennessGain.toDouble()
                ).toFloat().coerceIn(0.7f, 1.6f),
                widthGain = inference.optDouble(
                    "width_gain",
                    DEFAULT.widthGain.toDouble()
                ).toFloat().coerceIn(0.7f, 1.6f),
                roundnessGain = inference.optDouble(
                    "roundness_gain",
                    DEFAULT.roundnessGain.toDouble()
                ).toFloat().coerceIn(0.7f, 1.6f),
                minimumSpeechGate = inference.optDouble(
                    "minimum_speech_gate",
                    DEFAULT.minimumSpeechGate.toDouble()
                ).toFloat().coerceIn(0f, 0.20f)
            )
        }
    }
}
