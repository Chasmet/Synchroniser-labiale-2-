package com.chasmet.lipsync.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max

/** Renforce uniquement la zone labiale du visage généré selon le phonème actif. */
internal class GeneratedFaceRgbaCorrector {
    private val size = Wav2LipEngine.IMAGE_SIZE
    private val output = ByteBuffer
        .allocateDirect(size * size * CHANNELS)
        .order(ByteOrder.nativeOrder())

    fun apply(generated: GeneratedFace, viseme: VisemeFrame): GeneratedFace {
        val horizontalScale = (
            1f + viseme.width * 0.046f - viseme.roundness * 0.041f
            ).coerceIn(0.95f, 1.055f)
        val verticalScale = (
            1f + viseme.openness * 0.078f - viseme.closure * 0.105f
            ).coerceIn(0.88f, 1.09f)
        if (kotlin.math.abs(horizontalScale - 1f) < 0.002f &&
            kotlin.math.abs(verticalScale - 1f) < 0.002f
        ) return generated

        val source = generated.rgba.duplicate().apply { position(0) }
        output.clear()
        output.put(source.duplicate().apply { position(0) })

        val canonical = generated.canonicalMouth
        val centerX = size * canonical.centerX
        val centerY = size * canonical.centerY
        val radiusX = (size * canonical.radiusX * 1.32f).coerceIn(size * 0.10f, size * 0.34f)
        val radiusY = (size * canonical.radiusY * 1.42f).coerceIn(size * 0.06f, size * 0.23f)
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
                val mask = 1f - smoothStep(0.30f, 1f, distance)
                val sourceX = centerX + (x - centerX) / horizontalScale
                val sourceY = centerY + (y - centerY) / verticalScale
                val blend = (mask * MAX_BLEND).coerceIn(0f, MAX_BLEND)
                val destinationIndex = (y * size + x) * CHANNELS
                for (channel in 0 until RGB_CHANNELS) {
                    val original = source.get(destinationIndex + channel).toInt() and 0xff
                    val warped = bilinear(source, sourceX, sourceY, channel)
                    val value = (original + (warped - original) * blend)
                        .toInt()
                        .coerceIn(0, 255)
                    output.put(destinationIndex + channel, value.toByte())
                }
                output.put(destinationIndex + 3, 0xff.toByte())
            }
        }
        output.position(0)
        return GeneratedFace(
            rgba = output.duplicate().apply { position(0) },
            audioActivity = generated.audioActivity,
            canonicalMouth = generated.canonicalMouth,
            quality = generated.quality
        )
    }

    private fun bilinear(buffer: ByteBuffer, x: Float, y: Float, channel: Int): Float {
        val safeX = x.coerceIn(0f, (size - 1).toFloat())
        val safeY = y.coerceIn(0f, (size - 1).toFloat())
        val x0 = floor(safeX).toInt()
        val y0 = floor(safeY).toInt()
        val x1 = (x0 + 1).coerceAtMost(size - 1)
        val y1 = (y0 + 1).coerceAtMost(size - 1)
        val fx = safeX - x0
        val fy = safeY - y0
        fun value(px: Int, py: Int): Float =
            (buffer.get((py * size + px) * CHANNELS + channel).toInt() and 0xff).toFloat()
        val top = value(x0, y0) * (1f - fx) + value(x1, y0) * fx
        val bottom = value(x0, y1) * (1f - fx) + value(x1, y1) * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val x = ((value - edge0) / max(edge1 - edge0, 0.0001f)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private companion object {
        const val CHANNELS = 4
        const val RGB_CHANNELS = 3
        const val MAX_BLEND = 0.58f
    }
}
