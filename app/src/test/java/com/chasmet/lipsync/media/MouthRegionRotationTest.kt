package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MouthRegionRotationTest {

    @Test
    fun rotationZeroConserveLaZone() {
        val source = MouthRegion(0.25f, 0.30f, 0.20f, 0.08f)

        val result = source.displayToEncoded(0)

        assertRegion(result, 0.25f, 0.30f, 0.20f, 0.08f)
    }

    @Test
    fun rotation90ConvertitLePortraitVersLaTrameEncodee() {
        val source = MouthRegion(0.25f, 0.30f, 0.20f, 0.08f)

        val result = source.displayToEncoded(90)

        assertRegion(result, 0.70f, 0.25f, 0.08f, 0.20f)
    }

    @Test
    fun rotation180InverseLesDeuxAxes() {
        val source = MouthRegion(0.25f, 0.30f, 0.20f, 0.08f)

        val result = source.displayToEncoded(180)

        assertRegion(result, 0.75f, 0.70f, 0.20f, 0.08f)
    }

    @Test
    fun rotation270ConvertitLePortraitInverse() {
        val source = MouthRegion(0.25f, 0.30f, 0.20f, 0.08f)

        val result = source.displayToEncoded(270)

        assertRegion(result, 0.30f, 0.75f, 0.08f, 0.20f)
    }

    private fun assertRegion(
        actual: MouthRegion,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float
    ) {
        assertEquals(centerX, actual.centerX, 0.0001f)
        assertEquals(centerY, actual.centerY, 0.0001f)
        assertEquals(width, actual.width, 0.0001f)
        assertEquals(height, actual.height, 0.0001f)
    }
}
