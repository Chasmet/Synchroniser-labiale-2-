package com.chasmet.lipsync.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouthPlacementGuardTest {

    private val face = FaceRegion(
        centerX = 0.50f,
        centerY = 0.55f,
        width = 0.34f,
        height = 0.42f
    )

    @Test
    fun `une bouche cohérente à l'intérieur du visage est acceptée`() {
        val mouth = MouthRegion(
            centerX = 0.50f,
            centerY = 0.47f,
            width = 0.095f,
            height = 0.055f
        )

        assertTrue(MouthPlacementGuard.isSafe(mouth, face))
        assertTrue(MouthPlacementGuard.confidence(mouth, face) > 0.35f)
    }

    @Test
    fun `une zone en dehors du visage est rejetée`() {
        val misplaced = MouthRegion(
            centerX = 0.50f,
            centerY = 0.86f,
            width = 0.095f,
            height = 0.055f
        )

        assertFalse(MouthPlacementGuard.isSafe(misplaced, face))
    }

    @Test
    fun `une zone beaucoup trop grande est rejetée`() {
        val oversized = MouthRegion(
            centerX = 0.50f,
            centerY = 0.47f,
            width = 0.30f,
            height = 0.30f
        )

        assertFalse(MouthPlacementGuard.isSafe(oversized, face))
    }
}
