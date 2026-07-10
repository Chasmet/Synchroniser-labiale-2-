package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {
    @Test
    fun verticalWithoutRotationStaysVertical() {
        val geometry = renderGeometry(1080, 1920, 0)
        assertEquals(1080, geometry.outputWidth)
        assertEquals(1920, geometry.outputHeight)
    }

    @Test
    fun horizontalWithoutRotationStaysHorizontal() {
        val geometry = renderGeometry(1920, 1080, 0)
        assertEquals(1920, geometry.outputWidth)
        assertEquals(1080, geometry.outputHeight)
    }

    @Test
    fun metadataRotationNinetySwapsDimensions() {
        val geometry = renderGeometry(1920, 1080, 90)
        assertEquals(1080, geometry.outputWidth)
        assertEquals(1920, geometry.outputHeight)
        assertEquals(90, geometry.rotationDegrees)
    }

    @Test
    fun metadataRotationTwoSeventySwapsDimensions() {
        val geometry = renderGeometry(1920, 1080, 270)
        assertEquals(1080, geometry.outputWidth)
        assertEquals(1920, geometry.outputHeight)
        assertEquals(270, geometry.rotationDegrees)
    }

    @Test
    fun rotationOneEightyKeepsDimensions() {
        val geometry = renderGeometry(1080, 1920, 180)
        assertEquals(1080, geometry.outputWidth)
        assertEquals(1920, geometry.outputHeight)
        assertEquals(180, geometry.rotationDegrees)
    }
}
