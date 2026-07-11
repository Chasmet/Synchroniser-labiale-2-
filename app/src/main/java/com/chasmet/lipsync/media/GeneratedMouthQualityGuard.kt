package com.chasmet.lipsync.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class GeneratedQualityMetrics(
    val confidence: Float = 1f,
    val toothAreaRatio: Float = 0f,
    val cavityAreaRatio: Float = 0f,
    val boundaryError: Float = 0f,
    val detailScore: Float = 1f,
    val temporalJump: Float = 0f,
    val protectedFrame: Boolean = false,
    val corrected: Boolean = false
)

/**
 * Contrôle chaque prédiction Wav2Lip avant son compositing. Il ne dessine pas
 * de dents : il empêche surtout les aplats blancs, les cavités noires, le bruit
 * temporel et les dents inférieures étrangères au sourire de référence.
 */
internal class GeneratedMouthQualityGuard(
    private val profile: DentalAppearanceProfile
) {
    private val size = Wav2LipEngine.IMAGE_SIZE
    private val output = ByteBuffer
        .allocateDirect(size * size * CHANNELS)
        .order(ByteOrder.nativeOrder())
    private var previous: ByteArray? = null

    fun apply(sourceRgba: ByteBuffer, generated: GeneratedFace): GeneratedFace {
        val source = sourceRgba.duplicate().apply { position(0) }
        val raw = generated.rgba.duplicate().apply { position(0) }
        output.clear()
        output.put(raw.duplicate().apply { position(0) })
        output.position(0)

        val mouth = generated.canonicalMouth
        val radiusX = (mouth.radiusX * size).coerceIn(10f, size * 0.36f)
        val radiusY = (mouth.radiusY * size).coerceIn(6f, size * 0.24f)
        val centerX = mouth.centerX * size
        val centerY = mouth.centerY * size
        val minX = (centerX - radiusX * 2.0f).toInt().coerceAtLeast(1)
        val maxX = (centerX + radiusX * 2.0f).toInt().coerceAtMost(size - 2)
        val minY = (centerY - radiusY * 2.2f).toInt().coerceAtLeast(1)
        val maxY = (centerY + radiusY * 2.2f).toInt().coerceAtMost(size - 2)

        val skinSamples = ArrayList<Float>()
        val skinRed = ArrayList<Float>()
        val skinGreen = ArrayList<Float>()
        val skinBlue = ArrayList<Float>()
        var innerPixels = 0
        var toothPixels = 0
        var upperToothPixels = 0
        var cavityPixels = 0
        var boundaryPixels = 0
        var boundaryDifference = 0f
        var detailEnergy = 0f
        var detailPixels = 0
        var temporalDifference = 0f
        var temporalPixels = 0
        val old = previous

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val distance = dx * dx + dy * dy
                val index = (y * size + x) * CHANNELS
                if (distance in 1.25f..3.20f) {
                    skinSamples += luma(source, index)
                    skinRed += channel(source, index)
                    skinGreen += channel(source, index + 1)
                    skinBlue += channel(source, index + 2)
                }
                if (distance <= 1f) {
                    innerPixels++
                }
            }
        }

        val skinLuma = median(skinSamples).coerceIn(0.06f, 0.92f)
        val sourceSkin = floatArrayOf(
            median(skinRed), median(skinGreen), median(skinBlue)
        )
        val toothThreshold = max(skinLuma * profile.enamelLumaP10 * 0.88f, skinLuma * 1.04f)
        val cavityThreshold = skinLuma * profile.cavityLumaToSkin * 0.78f

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val distance = dx * dx + dy * dy
                val index = (y * size + x) * CHANNELS
                if (distance <= 1f) {
                    val pixelLuma = luma(raw, index)
                    val saturation = saturation(raw, index)
                    val isTooth = pixelLuma >= toothThreshold &&
                        saturation <= (profile.enamelSaturation + 0.16f).coerceAtMost(0.66f)
                    if (isTooth) {
                        toothPixels++
                        if (y >= centerY) upperToothPixels++
                    }
                    if (pixelLuma <= cavityThreshold) cavityPixels++

                    val left = luma(raw, index - CHANNELS)
                    val right = luma(raw, index + CHANNELS)
                    val down = luma(raw, index - size * CHANNELS)
                    val up = luma(raw, index + size * CHANNELS)
                    detailEnergy += abs(left - right) + abs(down - up)
                    detailPixels++
                    if (old != null) {
                        temporalDifference += abs(pixelLuma - luma(old, index))
                        temporalPixels++
                    }
                } else if (distance in 1.0f..1.62f) {
                    boundaryDifference += abs(luma(raw, index) - luma(source, index))
                    boundaryPixels++
                }
            }
        }

        val toothArea = toothPixels.toFloat() / innerPixels.coerceAtLeast(1)
        val cavityArea = cavityPixels.toFloat() / innerPixels.coerceAtLeast(1)
        val observedUpper = upperToothPixels.toFloat() / toothPixels.coerceAtLeast(1)
        val boundaryError = boundaryDifference / boundaryPixels.coerceAtLeast(1)
        val detail = detailEnergy / (detailPixels.coerceAtLeast(1) * 2f)
        val temporalJump = temporalDifference / temporalPixels.coerceAtLeast(1)

        val toothScore = when {
            toothArea <= profile.visibleToothAreaP90 * 1.16f -> 1f
            toothArea >= profile.visibleToothAreaP90 * 1.75f -> 0f
            else -> 1f - (toothArea / profile.visibleToothAreaP90 - 1.16f) / 0.59f
        }.coerceIn(0f, 1f)
        val cavityLimit = max(profile.cavityAreaP50 * 3.5f, 0.12f)
        val cavityScore = (1f - cavityArea / cavityLimit).coerceIn(0f, 1f)
        val boundaryScore = (1f - boundaryError / 0.24f).coerceIn(0f, 1f)
        val detailScore = (detail / max(profile.detailSigma * 0.45f, 0.025f)).coerceIn(0f, 1f)
        val temporalScore = (1f - temporalJump / 0.28f).coerceIn(0f, 1f)
        val confidence = (
            toothScore * 0.24f + cavityScore * 0.18f + boundaryScore * 0.25f +
                detailScore * 0.15f + temporalScore * 0.18f
            ).coerceIn(0f, 1f)
        val protected = confidence < 0.62f || boundaryError > 0.22f || temporalJump > 0.26f
        val lowerSuppression = DentalVisibilityPolicy.lowerTeethSuppression(
            profile.upperTeethShare,
            observedUpper
        )
        val corrected = correctPixels(
            raw = raw,
            centerX = centerX,
            centerY = centerY,
            radiusX = radiusX,
            radiusY = radiusY,
            bounds = intArrayOf(minX, maxX, minY, maxY),
            skinLuma = skinLuma,
            sourceSkin = sourceSkin,
            toothThreshold = toothThreshold,
            cavityThreshold = cavityThreshold,
            lowerSuppression = lowerSuppression
        )

        val snapshot = ByteArray(output.capacity())
        output.position(0)
        output.get(snapshot)
        output.position(0)
        previous = snapshot
        return generated.copy(
            rgba = output.duplicate().apply { position(0) },
            quality = GeneratedQualityMetrics(
                confidence = confidence,
                toothAreaRatio = toothArea,
                cavityAreaRatio = cavityArea,
                boundaryError = boundaryError,
                detailScore = detailScore,
                temporalJump = temporalJump,
                protectedFrame = protected,
                corrected = corrected
            )
        )
    }

    fun reset() {
        previous = null
    }

    private fun correctPixels(
        raw: ByteBuffer,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        bounds: IntArray,
        skinLuma: Float,
        sourceSkin: FloatArray,
        toothThreshold: Float,
        cavityThreshold: Float,
        lowerSuppression: Float
    ): Boolean {
        var changed = false
        val enamelCap = (skinLuma * profile.enamelLumaP90 * 1.08f).coerceIn(0.34f, 0.96f)
        val cavityFloor = (skinLuma * profile.cavityLumaToSkin * 0.64f).coerceAtLeast(0.015f)
        for (y in bounds[2]..bounds[3]) {
            for (x in bounds[0]..bounds[1]) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val distance = dx * dx + dy * dy
                if (distance > 1f) continue
                val index = (y * size + x) * CHANNELS
                var red = channel(raw, index)
                var green = channel(raw, index + 1)
                var blue = channel(raw, index + 2)
                var pixelLuma = red * 0.2126f + green * 0.7152f + blue * 0.0722f
                val toothLike = pixelLuma >= toothThreshold &&
                    saturation(red, green, blue) <= (profile.enamelSaturation + 0.16f)
                if (toothLike) {
                    val target = floatArrayOf(
                        sourceSkin[0] * profile.enamelRgbToSkin[0],
                        sourceSkin[1] * profile.enamelRgbToSkin[1],
                        sourceSkin[2] * profile.enamelRgbToSkin[2]
                    )
                    red = red * 0.90f + target[0].coerceIn(0f, 1f) * 0.10f
                    green = green * 0.90f + target[1].coerceIn(0f, 1f) * 0.10f
                    blue = blue * 0.90f + target[2].coerceIn(0f, 1f) * 0.10f
                    pixelLuma = red * 0.2126f + green * 0.7152f + blue * 0.0722f
                    if (pixelLuma > enamelCap) {
                        val scale = enamelCap / pixelLuma.coerceAtLeast(0.001f)
                        red *= scale
                        green *= scale
                        blue *= scale
                    }
                    if (y < centerY) {
                        val scale = 1f - lowerSuppression
                        red *= scale
                        green *= scale
                        blue *= scale
                    }
                    changed = true
                } else if (pixelLuma < cavityThreshold && pixelLuma < cavityFloor) {
                    val lift = (cavityFloor - pixelLuma) * 0.55f
                    red += lift
                    green += lift * 0.78f
                    blue += lift * 0.74f
                    changed = true
                }

                if (distance < 0.82f) {
                    val left = (index - CHANNELS).coerceAtLeast(0)
                    val right = (index + CHANNELS).coerceAtMost(raw.capacity() - CHANNELS)
                    val localMean = (luma(raw, left) + luma(raw, right)) * 0.5f
                    val sharpen = (pixelLuma - localMean) * 0.055f
                    red += sharpen
                    green += sharpen
                    blue += sharpen
                }
                output.put(index, toByte(red))
                output.put(index + 1, toByte(green))
                output.put(index + 2, toByte(blue))
                output.put(index + 3, 0xff.toByte())
            }
        }
        output.position(0)
        return changed
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0.35f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5f
        } else sorted[middle]
    }

    private fun luma(buffer: ByteBuffer, index: Int): Float =
        channel(buffer, index) * 0.2126f + channel(buffer, index + 1) * 0.7152f +
            channel(buffer, index + 2) * 0.0722f

    private fun luma(buffer: ByteArray, index: Int): Float =
        (buffer[index].toInt() and 0xff) / 255f * 0.2126f +
            (buffer[index + 1].toInt() and 0xff) / 255f * 0.7152f +
            (buffer[index + 2].toInt() and 0xff) / 255f * 0.0722f

    private fun channel(buffer: ByteBuffer, index: Int): Float =
        (buffer.get(index).toInt() and 0xff) / 255f

    private fun saturation(buffer: ByteBuffer, index: Int): Float = saturation(
        channel(buffer, index),
        channel(buffer, index + 1),
        channel(buffer, index + 2)
    )

    private fun saturation(red: Float, green: Float, blue: Float): Float {
        val maximum = max(red, max(green, blue))
        val minimum = minOf(red, green, blue)
        return if (maximum <= 0.001f) 0f else (maximum - minimum) / maximum
    }

    private fun toByte(value: Float): Byte =
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    private companion object {
        const val CHANNELS = 4
    }
}
