package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DentalVisibilityPolicyTest {
    @Test
    fun lowerTeethCorrectionStaysWeakWhenSmileMatches() {
        assertEquals(0.06f, DentalVisibilityPolicy.lowerTeethSuppression(0.94f, 0.95f), 0.0001f)
    }

    @Test
    fun inventedLowerTeethReceiveStrongerButBoundedCorrection() {
        val correction = DentalVisibilityPolicy.lowerTeethSuppression(0.94f, 0.35f)
        assertTrue(correction > 0.20f)
        assertTrue(correction <= 0.32f)
    }
}
