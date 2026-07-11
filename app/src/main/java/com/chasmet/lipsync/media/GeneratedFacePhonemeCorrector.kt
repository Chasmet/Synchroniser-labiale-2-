package com.chasmet.lipsync.media

import kotlin.math.floor
import kotlin.math.max

internal object GeneratedFacePhonemeCorrector {

    fun apply(prediction: FloatArray, viseme: VisemeFrame) {
        val size = Wav2LipEngine.IMAGE_SIZE
        val plane = size * size
        if (prediction.size < plane * CHANNELS) return

        val horizontalScale = (
            1f + viseme.width * 0.055f - viseme.roundness * 0.045f
            ).coerceIn(0.93f, 1.07f)
        val verticalScale = (
            1f + viseme.openness * 0.085f - viseme.closure * 0.125f
            ).coerceIn(0.86f, 1.09f)
        if (kotlin.math.abs(horizontalScale - 1f) < 0.002f &&
            kotlin.math.abs(verticalScale - 1f) < 0.002f
        ) return

        val source = prediction.copyOf()
        val centerX = size * 0.50f
        val centerY = size * 0.68f
        val radiusX = size * 0.29f
        val radiusY = size * 0.17f
        val minX = (centerX - radiusX).toInt().coerceAtLeast(1)
        val maxX = (centerX + radiusX).toInt().coerceAtMost(size - 2)
        val minY = (centerY - radiusY).toInt().coerceAtLeast(1)
        val maxY = (centerY + radiusY).toInt().coerceAtMost(size - 2)

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val distance = dx * dx + dy * dy
                if (distance >= 1f) continue
                val mask = 1f - smoothStep(0.38f, 1f, distance)
                val sourceX = centerX + (x - centerX) / horizontalScale
                val sourceY = centerY + (y - centerY) / verticalScale
                val pixel = y * size + x
                val blend = (mask * MAX_GUIDANCE_BLEND).coerceIn(0f, MAX_GUIDANCE_BLEND)
                for (channel in 0 until CHANNELS) {
                    val warped = bilinear(source, channel * plane, size, sourceX, sourceY)
                    val original = source[channel * plane + pixel]
                    prediction[channel * plane + pixel] = (
                        original + (warped - original) * blend
                        ).coerceIn(0f, 1f)
                }
            }
        }
    }

    private fun bilinear(
        data: FloatArray,
        offset: Int,
        size: Int,
        x: Float,
        y: Float
    ): Float {
        val safeX = x.coerceIn(0f, (size - 1).toFloat())
        val safeY = y.coerceIn(0f, (size - 1).toFloat())
        val x0 = floor(safeX).toInt()
        val y0 = floor(safeY).toInt()
        val x1 = (x0 + 1).coerceAtMost(size - 1)
        val y1 = (y0 + 1).coerceAtMost(size - 1)
        val fx = safeX - x0
        val fy = safeY - y0
        val top = data[offset + y0 * size + x0] * (1f - fx) +
            data[offset + y0 * size + x1] * fx
        val bottom = data[offset + y1 * size + x0] * (1f - fx) +
            data[offset + y1 * size + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val x = ((value - edge0) / max(edge1 - edge0, 0.0001f)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private const val CHANNELS = 3
    private const val MAX_GUIDANCE_BLEND = 0.72f
}
