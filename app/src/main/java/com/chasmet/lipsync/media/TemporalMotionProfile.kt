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
        private const val ASSET_NAME = "chk_temporal_motion_profile_v4.json"

        /**
         * Le résultat de diagnostic fourni présente un retard visuel moyen d'environ
         * 567 ms. Le moteur de secours analyse des fenêtres de 40 ms : 14 fenêtres
         * correspondent à 560 ms et compensent ce retard sans toucher au moteur
         * génératif Wav2Lip, qui utilise son propre alignement Mel.
         */
        val DEFAULT = TemporalMotionProfile(
            name = "Profil Pro v4 calibré audio",
            lookAheadFrames = 14,
            attackFactor = 0.88f,
            releaseFactor = 0.72f,
            closureStrength = 0.90f,
            opennessGain = 1.08f,
            widthGain = 1.02f,
            roundnessGain = 0.72f,
            minimumSpeechGate = 0.035f
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
                ).coerceIn(0, 20),
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
                ).toFloat().coerceIn(0.5f, 1.6f),
                minimumSpeechGate = inference.optDouble(
                    "minimum_speech_gate",
                    DEFAULT.minimumSpeechGate.toDouble()
                ).toFloat().coerceIn(0f, 0.20f)
            )
        }
    }
}
