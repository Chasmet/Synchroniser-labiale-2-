package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalMouthRegionTest {
    @Test
    fun centeredMouthUsesHalfExtentsNotFullDimensions() {
        val mouth = MouthRegion(0.5f, 0.38f, 0.12f, 0.05f)
        val face = FaceRegion(0.5f, 0.5f, 0.40f, 0.50f)

        val canonical = mouth.inCanonicalFace(face, sourceAspect = 9f / 16f)

        assertEquals(0.5f, canonical.centerX, 0.0001f)
        assertEquals(0.26f, canonical.centerY, 0.0001f)
        assertEquals(0.156f, canonical.radiusX, 0.0001f)
        assertEquals(0.048f, canonical.radiusY, 0.0001f)
        assertTrue(canonical.radiusX < mouth.width / face.width)
    }

    @Test
    fun faceRollIsRemovedFromCanonicalCenter() {
        val face = FaceRegion(0.5f, 0.5f, 0.4f, 0.5f, rollDegrees = 30f)
        val mouth = MouthRegion(0.55f, 0.47f, 0.1f, 0.04f)

        val canonical = mouth.inCanonicalFace(face, sourceAspect = 1f)

        assertTrue(canonical.centerX in 0.50f..0.66f)
        assertTrue(canonical.centerY in 0.36f..0.50f)
    }
}
