package com.chasmet.lipsync.media

import android.content.Context
import org.json.JSONObject

data class DentalAppearanceProfile(
    val enamelLumaP10: Float,
    val enamelLumaP50: Float,
    val enamelLumaP90: Float,
    val enamelSaturation: Float,
    val enamelRgbToSkin: FloatArray,
    val visibleToothAreaP50: Float,
    val visibleToothAreaP90: Float,
    val cavityAreaP50: Float,
    val cavityLumaToSkin: Float,
    val detailSigma: Float,
    val upperTeethShare: Float
) {
    companion object {
        const val ASSET_NAME = "chk_personal_dental_profile_v1.json"

        val CONSERVATIVE_DEFAULT = DentalAppearanceProfile(
            enamelLumaP10 = 1.05f,
            enamelLumaP50 = 1.25f,
            enamelLumaP90 = 1.80f,
            enamelSaturation = 0.34f,
            enamelRgbToSkin = floatArrayOf(1.12f, 1.18f, 1.16f),
            visibleToothAreaP50 = 0.17f,
            visibleToothAreaP90 = 0.42f,
            cavityAreaP50 = 0.03f,
            cavityLumaToSkin = 0.30f,
            detailSigma = 0.12f,
            upperTeethShare = 0.90f
        )

        fun load(context: Context): DentalAppearanceProfile = runCatching {
            val root = JSONObject(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
            val rgb = root.getJSONArray("enamel_rgb_to_skin_p50")
            DentalAppearanceProfile(
                enamelLumaP10 = root.getDouble("enamel_luma_to_skin_p10").toFloat(),
                enamelLumaP50 = root.getDouble("enamel_luma_to_skin_p50").toFloat(),
                enamelLumaP90 = root.getDouble("enamel_luma_to_skin_p90").toFloat(),
                enamelSaturation = root.getDouble("enamel_saturation_p50").toFloat(),
                enamelRgbToSkin = FloatArray(3) { rgb.getDouble(it).toFloat() },
                visibleToothAreaP50 = root.getDouble("visible_tooth_area_p50").toFloat(),
                visibleToothAreaP90 = root.getDouble("visible_tooth_area_p90").toFloat(),
                cavityAreaP50 = root.getDouble("cavity_area_p50").toFloat(),
                cavityLumaToSkin = root.getDouble("cavity_luma_to_skin_p50").toFloat(),
                detailSigma = root.getDouble("detail_sigma_p50").toFloat(),
                upperTeethShare = root.getDouble("upper_teeth_share_p50").toFloat()
            )
        }.getOrDefault(CONSERVATIVE_DEFAULT)
    }
}

internal object DentalVisibilityPolicy {
    /** Faible correction de base, renforcée seulement si des dents basses sont inventées. */
    fun lowerTeethSuppression(expectedUpperShare: Float, observedUpperShare: Float): Float {
        val excess = (expectedUpperShare - observedUpperShare).coerceAtLeast(0f)
        return (0.06f + excess * 0.72f).coerceIn(0.06f, 0.32f)
    }
}
