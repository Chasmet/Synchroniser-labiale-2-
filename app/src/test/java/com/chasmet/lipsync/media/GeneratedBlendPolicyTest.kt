package com.chasmet.lipsync.media

import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedBlendPolicyTest {
    @Test
    fun poorPredictionCannotDominateSourceFrame() {
        val protected = GeneratedBlendPolicy.strength(1f, 1f, 0.15f, true)
        val clean = GeneratedBlendPolicy.strength(1f, 1f, 0.95f, false)

        assertTrue(protected <= 0.62f)
        assertTrue(clean > protected)
        assertTrue(clean <= 0.94f)
    }
}
