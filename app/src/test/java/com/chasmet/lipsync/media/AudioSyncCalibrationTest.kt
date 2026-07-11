package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncCalibrationTest {

    @Test
    fun fallbackLookAheadMatchesMeasuredDelay() {
        val profile = TemporalMotionProfile.DEFAULT
        val lookAheadMs = profile.lookAheadFrames * 40

        assertEquals(14, profile.lookAheadFrames)
        assertTrue(lookAheadMs in 520..600)
    }

    @Test
    fun calibratedProfileReactsFasterAndAvoidsPermanentPuckering() {
        val profile = TemporalMotionProfile.DEFAULT

        assertTrue(profile.attackFactor > profile.releaseFactor)
        assertTrue(profile.closureStrength >= 0.85f)
        assertTrue(profile.roundnessGain < profile.opennessGain)
    }
}
