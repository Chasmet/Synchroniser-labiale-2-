package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedicatedLipTrackerTest {

    private val face = FaceBoundsPx(
        left = 200f,
        top = 100f,
        right = 600f,
        bottom = 700f
    )

    @Test
    fun `le contour complet des lèvres devient la source prioritaire`() {
        val tracker = DedicatedLipTracker()
        val result = tracker.update(
            contourPoints = realisticContour(),
            landmarkPoints = landmarks(),
            faceBounds = face,
            imageWidth = 800,
            imageHeight = 800,
            timeUs = 0L
        )

        assertEquals(LipTrackingSource.CONTOUR, result.source)
        assertTrue(result.reliable)
        assertTrue(result.confidence > 0.60f)
        assertTrue(result.region.centerX in 0.48f..0.52f)
        assertTrue(result.region.centerY in 0.30f..0.38f)
    }

    @Test
    fun `une fausse détection sur le front est rejetée`() {
        val tracker = DedicatedLipTracker()
        tracker.update(
            contourPoints = realisticContour(),
            landmarkPoints = landmarks(),
            faceBounds = face,
            imageWidth = 800,
            imageHeight = 800,
            timeUs = 0L
        )

        val forehead = List(20) { index ->
            LipPoint(
                x = 330f + (index % 10) * 16f,
                y = 190f + (index / 10) * 12f
            )
        }
        val result = tracker.update(
            contourPoints = forehead,
            landmarkPoints = emptyList(),
            faceBounds = face,
            imageWidth = 800,
            imageHeight = 800,
            timeUs = 100_000L
        )

        assertEquals(LipTrackingSource.PREDICTION, result.source)
        assertTrue(result.region.centerY < 0.45f)
    }

    @Test
    fun `une perte courte conserve la dernière trajectoire fiable`() {
        val tracker = DedicatedLipTracker()
        val first = tracker.update(
            contourPoints = realisticContour(),
            landmarkPoints = landmarks(),
            faceBounds = face,
            imageWidth = 800,
            imageHeight = 800,
            timeUs = 0L
        )
        val missing = tracker.update(
            contourPoints = emptyList(),
            landmarkPoints = emptyList(),
            faceBounds = face,
            imageWidth = 800,
            imageHeight = 800,
            timeUs = 100_000L
        )

        assertEquals(LipTrackingSource.PREDICTION, missing.source)
        assertTrue(missing.reliable)
        assertTrue(kotlin.math.abs(missing.region.centerX - first.region.centerX) < 0.03f)
        assertTrue(kotlin.math.abs(missing.region.centerY - first.region.centerY) < 0.03f)
    }

    @Test
    fun `sans aucune observation le repli reste prudent et non fiable`() {
        val tracker = DedicatedLipTracker()
        val result = tracker.update(
            contourPoints = emptyList(),
            landmarkPoints = emptyList(),
            faceBounds = face,
            imageWidth = 800,
            imageHeight = 800,
            timeUs = 0L
        )

        assertEquals(LipTrackingSource.FACE_FALLBACK, result.source)
        assertFalse(result.reliable)
        assertEquals(0.5f, result.region.centerX, 0.001f)
    }

    private fun realisticContour(): List<LipPoint> {
        val upper = listOf(
            LipPoint(320f, 520f), LipPoint(340f, 510f), LipPoint(365f, 505f),
            LipPoint(390f, 508f), LipPoint(410f, 508f), LipPoint(435f, 505f),
            LipPoint(460f, 510f), LipPoint(480f, 520f)
        )
        val lower = listOf(
            LipPoint(320f, 530f), LipPoint(340f, 545f), LipPoint(365f, 555f),
            LipPoint(390f, 560f), LipPoint(410f, 560f), LipPoint(435f, 555f),
            LipPoint(460f, 545f), LipPoint(480f, 530f)
        )
        return upper + lower
    }

    private fun landmarks(): List<LipPoint> {
        return listOf(
            LipPoint(320f, 525f),
            LipPoint(480f, 525f),
            LipPoint(400f, 560f)
        )
    }
}
