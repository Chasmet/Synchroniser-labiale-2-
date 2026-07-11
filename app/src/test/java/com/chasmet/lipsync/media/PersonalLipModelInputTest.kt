package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalLipModelInputTest {
    private val frames = listOf(
        AudioFeatureFrame(0L, 0.10f, 0.20f, 0.30f, 0.40f, 0.50f, 0.60f),
        AudioFeatureFrame(40_000L, 0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f),
        AudioFeatureFrame(80_000L, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f, 0.80f)
    )

    @Test
    fun v3InputIncludesSixAudioFeatures() {
        val input = buildPersonalModelInput(
            frames = frames,
            index = 1,
            offsets = intArrayOf(-1, 0, 1),
            expectedSize = 26
        )

        assertEquals(26, input.size)
        assertEquals(0.10f, input[0], 0.0001f)
        assertEquals(0.60f, input[5], 0.0001f)
        assertEquals(0.20f, input[6], 0.0001f)
        assertEquals(0.80f, input[17], 0.0001f)
        assertEquals(0.10f, input[18], 0.0001f)
    }

    @Test
    fun legacyInputRemainsSupported() {
        val input = buildPersonalModelInput(
            frames = frames,
            index = 1,
            offsets = intArrayOf(-1, 0, 1),
            expectedSize = 14
        )

        assertEquals(14, input.size)
        assertEquals(0.10f, input[0], 0.0001f)
        assertEquals(0.20f, input[1], 0.0001f)
        assertEquals(0.30f, input[2], 0.0001f)
    }
}
