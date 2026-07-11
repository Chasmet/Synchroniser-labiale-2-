package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {
    @Test
    fun portraitChoiceAlwaysCreatesNineBySixteen() {
        val geometry = renderGeometry(
            encodedWidth = 1080,
            encodedHeight = 1920,
            rotationDegrees = 0,
            aspectRatio = OutputAspectRatio.PORTRAIT_9_16
        )

        assertEquals(720, geometry.outputWidth)
        assertEquals(1280, geometry.outputHeight)
        assertEquals(720, geometry.viewportWidth)
        assertEquals(1280, geometry.viewportHeight)
    }

    @Test
    fun landscapeChoiceAlwaysCreatesSixteenByNine() {
        val geometry = renderGeometry(
            encodedWidth = 1920,
            encodedHeight = 1080,
            rotationDegrees = 0,
            aspectRatio = OutputAspectRatio.LANDSCAPE_16_9
        )

        assertEquals(1280, geometry.outputWidth)
        assertEquals(720, geometry.outputHeight)
        assertEquals(1280, geometry.viewportWidth)
        assertEquals(720, geometry.viewportHeight)
    }

    @Test
    fun portraitSourceInLandscapeIsCenteredWithoutStretching() {
        val geometry = renderGeometry(
            encodedWidth = 1080,
            encodedHeight = 1920,
            rotationDegrees = 0,
            aspectRatio = OutputAspectRatio.LANDSCAPE_16_9
        )

        assertEquals(1280, geometry.outputWidth)
        assertEquals(720, geometry.outputHeight)
        assertEquals(404, geometry.viewportWidth)
        assertEquals(720, geometry.viewportHeight)
        assertEquals(438, geometry.viewportX)
        assertEquals(0, geometry.viewportY)
    }

    @Test
    fun landscapeSourceInPortraitIsCenteredWithoutStretching() {
        val geometry = renderGeometry(
            encodedWidth = 1920,
            encodedHeight = 1080,
            rotationDegrees = 0,
            aspectRatio = OutputAspectRatio.PORTRAIT_9_16
        )

        assertEquals(720, geometry.outputWidth)
        assertEquals(1280, geometry.outputHeight)
        assertEquals(720, geometry.viewportWidth)
        assertEquals(404, geometry.viewportHeight)
        assertEquals(0, geometry.viewportX)
        assertEquals(438, geometry.viewportY)
    }

    @Test
    fun metadataRotationNinetyIsAppliedBeforeRatioCalculation() {
        val geometry = renderGeometry(
            encodedWidth = 1920,
            encodedHeight = 1080,
            rotationDegrees = 90,
            aspectRatio = OutputAspectRatio.PORTRAIT_9_16
        )

        assertEquals(1080, geometry.displayWidth)
        assertEquals(1920, geometry.displayHeight)
        assertEquals(90, geometry.rotationDegrees)
        assertEquals(720, geometry.viewportWidth)
        assertEquals(1280, geometry.viewportHeight)
    }

    @Test
    fun portraitEncodedFramesNeedNoFinalRotationForPortraitChoice() {
        val hint = finalOrientationHint(
            encodedWidth = 720,
            encodedHeight = 1280,
            aspectRatio = OutputAspectRatio.PORTRAIT_9_16
        )

        assertEquals(0, hint)
        assertEquals(DisplayedDimensions(720, 1280), displayedDimensions(720, 1280, hint))
    }

    @Test
    fun landscapeEncodedFramesAreRotatedForPortraitChoice() {
        val hint = finalOrientationHint(
            encodedWidth = 1280,
            encodedHeight = 720,
            aspectRatio = OutputAspectRatio.PORTRAIT_9_16
        )

        assertEquals(90, hint)
        assertEquals(DisplayedDimensions(720, 1280), displayedDimensions(1280, 720, hint))
    }

    @Test
    fun landscapeEncodedFramesNeedNoFinalRotationForLandscapeChoice() {
        val hint = finalOrientationHint(
            encodedWidth = 1280,
            encodedHeight = 720,
            aspectRatio = OutputAspectRatio.LANDSCAPE_16_9
        )

        assertEquals(0, hint)
        assertEquals(DisplayedDimensions(1280, 720), displayedDimensions(1280, 720, hint))
    }

    @Test
    fun portraitEncodedFramesAreRotatedForLandscapeChoice() {
        val hint = finalOrientationHint(
            encodedWidth = 720,
            encodedHeight = 1280,
            aspectRatio = OutputAspectRatio.LANDSCAPE_16_9
        )

        assertEquals(90, hint)
        assertEquals(DisplayedDimensions(1280, 720), displayedDimensions(720, 1280, hint))
    }
}
