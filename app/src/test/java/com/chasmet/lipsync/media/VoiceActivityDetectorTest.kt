package com.chasmet.lipsync.media

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {

    @Test
    fun `detecte une voix entre deux silences`() {
        val sampleRate = 16_000
        val samples = FloatArray(sampleRate * 2)
        for (index in sampleRate / 2 until sampleRate * 3 / 2) {
            samples[index] = (sin(2.0 * PI * 220.0 * index / sampleRate) * 0.18).toFloat()
        }

        val timeline = VoiceActivityDetector.analyze(samples, sampleRate)

        assertFalse(timeline.isSpeechAt(100_000L))
        assertTrue(timeline.isSpeechAt(1_000_000L))
        assertFalse(timeline.isSpeechAt(1_900_000L))
        assertTrue(timeline.speechCoverage in 0.35f..0.75f)
    }

    @Test
    fun `un silence complet ne devient pas de la parole`() {
        val timeline = VoiceActivityDetector.analyze(FloatArray(16_000), 16_000)
        assertTrue(timeline.segments.isEmpty())
        assertFalse(timeline.isSpeechAt(500_000L))
    }
}
