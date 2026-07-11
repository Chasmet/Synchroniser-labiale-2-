package com.chasmet.lipsync.media

import kotlin.math.min
import kotlin.math.roundToInt

internal data class RenderGeometry(
    val encodedWidth: Int,
    val encodedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotationDegrees: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val viewportX: Int,
    val viewportY: Int,
    val viewportWidth: Int,
    val viewportHeight: Int
)

internal fun renderGeometry(
    encodedWidth: Int,
    encodedHeight: Int,
    rotationDegrees: Int,
    aspectRatio: OutputAspectRatio
): RenderGeometry {
    require(encodedWidth > 0 && encodedHeight > 0) { "Dimensions vidéo invalides" }

    val rotation = ((rotationDegrees % 360) + 360) % 360
    val normalizedRotation = if (rotation in setOf(0, 90, 180, 270)) rotation else 0
    val swapDimensions = normalizedRotation == 90 || normalizedRotation == 270
    val displayWidth = if (swapDimensions) encodedHeight else encodedWidth
    val displayHeight = if (swapDimensions) encodedWidth else encodedHeight
    val outputWidth = aspectRatio.outputWidth
    val outputHeight = aspectRatio.outputHeight

    val scale = min(
        outputWidth.toFloat() / displayWidth.toFloat(),
        outputHeight.toFloat() / displayHeight.toFloat()
    )

    fun even(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return if (safe % 2 == 0) safe else safe - 1
    }

    val viewportWidth = even((displayWidth * scale).roundToInt()).coerceAtMost(outputWidth)
    val viewportHeight = even((displayHeight * scale).roundToInt()).coerceAtMost(outputHeight)
    val viewportX = (outputWidth - viewportWidth) / 2
    val viewportY = (outputHeight - viewportHeight) / 2

    return RenderGeometry(
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        rotationDegrees = normalizedRotation,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        viewportX = viewportX,
        viewportY = viewportY,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    )
}
