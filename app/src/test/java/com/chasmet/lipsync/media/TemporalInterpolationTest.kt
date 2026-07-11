package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TemporalInterpolationTest {

    @Test
    fun mouthTrackInterpolatesBetweenTwoDetections() {
        val start = MouthRegion(0.30f, 0.35f, 0.18f, 0.08f)
        val end = MouthRegion(0.50f, 0.45f, 0.22f, 0.12f)
        val track = MouthTrack(
            keyframes = listOf(
                MouthKeyframe(0L, start),
                MouthKeyframe(1_000_000L, end)
            ),
            fallback = start
        )

        val middle = track.regionAt(500_000L)

        assertEquals(0.40f, middle.centerX, 0.0001f)
        assertEquals(0.40f, middle.centerY, 0.0001f)
        assertEquals(0.20f, middle.width, 0.0001f)
        assertEquals(0.10f, middle.height, 0.0001f)
    }

    @Test
    fun mouthTrackKeepsBoundaryDetections() {
        val start = MouthRegion(0.30f, 0.35f, 0.18f, 0.08f)
        val end = MouthRegion(0.50f, 0.45f, 0.22f, 0.12f)
        val track = MouthTrack(
            keyframes = listOf(
                MouthKeyframe(100_000L, start),
                MouthKeyframe(900_000L, end)
            ),
            fallback = MouthRegion.DEFAULT
        )

        assertEquals(start, track.regionAt(0L))
        assertEquals(end, track.regionAt(1_500_000L))
    }

    @Test
    fun visemeTimelineInterpolatesInsteadOfJumping() {
        val timeline = VisemeTimeline(
            frames = listOf(
                VisemeFrame(0L, 0f, 0.20f, 0.80f),
                VisemeFrame(40_000L, 1f, 0.80f, 0.20f)
            ),
            durationUs = 80_000L
        )

        val middle = timeline.frameAt(20_000L)

        assertEquals(0.50f, middle.openness, 0.0001f)
        assertEquals(0.50f, middle.width, 0.0001f)
        assertEquals(0.50f, middle.roundness, 0.0001f)
    }

    @Test
    fun emptyMouthTrackUsesFallback() {
        val fallback = MouthRegion(0.42f, 0.38f, 0.19f, 0.09f)
        val track = MouthTrack(emptyList(), fallback)

        assertEquals(fallback, track.regionAt(2_000_000L))
    }
}
