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

internal data class DisplayedDimensions(
    val width: Int,
    val height: Int
)

internal fun renderGeometry(
    encodedWidth: Int,
    encodedHeight: Int,
    rotationDegrees: Int,
    aspectRatio: OutputAspectRatio
): RenderGeometry {
    require(encodedWidth > 0 && encodedHeight > 0) { "Dimensions vidéo invalides" }

    val rotation = normalizeRotation(rotationDegrees)
    val displayed = displayedDimensions(encodedWidth, encodedHeight, rotation)
    val outputWidth = aspectRatio.outputWidth
    val outputHeight = aspectRatio.outputHeight

    val scale = min(
        outputWidth.toFloat() / displayed.width.toFloat(),
        outputHeight.toFloat() / displayed.height.toFloat()
    )

    fun even(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return if (safe % 2 == 0) safe else safe - 1
    }

    val viewportWidth = even((displayed.width * scale).roundToInt()).coerceAtMost(outputWidth)
    val viewportHeight = even((displayed.height * scale).roundToInt()).coerceAtMost(outputHeight)
    val viewportX = (outputWidth - viewportWidth) / 2
    val viewportY = (outputHeight - viewportHeight) / 2

    return RenderGeometry(
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        displayWidth = displayed.width,
        displayHeight = displayed.height,
        rotationDegrees = rotation,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        viewportX = viewportX,
        viewportY = viewportY,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    )
}

/**
 * Le rendu encode déjà des pixels dans les dimensions exactes demandées.
 * Une orientation MP4 supplémentaire ferait pivoter ces pixels une seconde fois
 * sur certains lecteurs Android. Le fichier final doit donc toujours être neutre.
 */
internal fun finalOrientationHint(
    encodedWidth: Int,
    encodedHeight: Int,
    aspectRatio: OutputAspectRatio
): Int {
    require(encodedWidth > 0 && encodedHeight > 0) { "Dimensions vidéo finales invalides" }
    require(
        encodedWidth == aspectRatio.outputWidth && encodedHeight == aspectRatio.outputHeight
    ) {
        "Les pixels encodés ne correspondent pas au format ${aspectRatio.label}"
    }
    return 0
}

internal fun displayedDimensions(
    encodedWidth: Int,
    encodedHeight: Int,
    rotationDegrees: Int
): DisplayedDimensions {
    val rotation = normalizeRotation(rotationDegrees)
    val swapsDimensions = rotation == 90 || rotation == 270
    return if (swapsDimensions) {
        DisplayedDimensions(encodedHeight, encodedWidth)
    } else {
        DisplayedDimensions(encodedWidth, encodedHeight)
    }
}

private fun normalizeRotation(rotationDegrees: Int): Int {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    return if (rotation in setOf(0, 90, 180, 270)) rotation else 0
}
