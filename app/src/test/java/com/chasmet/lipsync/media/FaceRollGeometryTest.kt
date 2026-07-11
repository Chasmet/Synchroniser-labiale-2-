package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceRollGeometryTest {
    @Test
    fun interpolationUsesShortestAngle() {
        val start = FaceRegion(0.5f, 0.5f, 0.4f, 0.5f, 170f)
        val end = FaceRegion(0.5f, 0.5f, 0.4f, 0.5f, -170f)

        assertEquals(180f, kotlin.math.abs(start.interpolate(end, 0.5f).rollDegrees), 0.001f)
    }

    @Test
    fun metadataRotationAlsoRotatesRoll() {
        val encoded = FaceRegion(0.4f, 0.6f, 0.3f, 0.5f, 12f).displayToEncoded(90)

        assertEquals(102f, encoded.rollDegrees, 0.001f)
        assertEquals(0.4f, encoded.centerX, 0.001f)
        assertEquals(0.4f, encoded.centerY, 0.001f)
    }
}
