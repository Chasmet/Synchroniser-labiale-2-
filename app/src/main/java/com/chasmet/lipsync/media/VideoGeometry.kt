package com.chasmet.lipsync.media

internal data class RenderGeometry(
    val encodedWidth: Int,
    val encodedHeight: Int,
    val rotationDegrees: Int,
    val outputWidth: Int,
    val outputHeight: Int
)

internal fun renderGeometry(
    encodedWidth: Int,
    encodedHeight: Int,
    rotationDegrees: Int
): RenderGeometry {
    require(encodedWidth > 0 && encodedHeight > 0) { "Dimensions vidéo invalides" }
    val rotation = ((rotationDegrees % 360) + 360) % 360
    val normalizedRotation = if (rotation in setOf(0, 90, 180, 270)) rotation else 0
    val swapDimensions = normalizedRotation == 90 || normalizedRotation == 270

    return RenderGeometry(
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        rotationDegrees = normalizedRotation,
        outputWidth = if (swapDimensions) encodedHeight else encodedWidth,
        outputHeight = if (swapDimensions) encodedWidth else encodedHeight
    )
}
