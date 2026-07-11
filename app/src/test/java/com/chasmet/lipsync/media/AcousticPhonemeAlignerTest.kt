package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AcousticPhonemeAlignerTest {

    @Test
    fun `bonjour produit cinq phonemes ordonnes`() {
        val samples = syntheticBonjour()
        val result = AcousticPhonemeAligner.align(
            samples16Khz = samples,
            words = listOf(RecognizedWord("bonjour", 0L, 800_000L, 0.92f))
        )

        assertEquals(listOf("b", "on", "j", "ou", "r"), result.cues.map { it.phoneme })
        assertEquals(FrenchLipShape.BILABIAL, result.cues.first().shape)
        assertEquals(FrenchLipShape.ROUND_TIGHT, result.cues[3].shape)
        assertTrue(result.cues.zipWithNext().all { (first, second) ->
            first.startUs < first.endUs && first.endUs <= second.startUs
        })
        assertTrue(result.confidence > 0.30f)
    }

    @Test
    fun `un signal vide ne produit aucun alignement`() {
        val result = AcousticPhonemeAligner.align(
            FloatArray(0),
            listOf(RecognizedWord("bonjour", 0L, 800_000L, 0.9f))
        )
        assertTrue(result.cues.isEmpty())
        assertEquals(0f, result.confidence)
    }

    private fun syntheticBonjour(): FloatArray {
        val sampleRate = 16_000
        val samples = FloatArray((sampleRate * 0.8).toInt())
        fun voiced(fromMs: Int, toMs: Int, frequency: Double, amplitude: Float) {
            val from = fromMs * sampleRate / 1_000
            val to = toMs * sampleRate / 1_000
            for (index in from until to.coerceAtMost(samples.size)) {
                samples[index] = (sin(2.0 * PI * frequency * index / sampleRate) * amplitude).toFloat()
            }
        }
        voiced(70, 260, 185.0, 0.65f)
        for (index in 260 * sampleRate / 1_000 until 360 * sampleRate / 1_000) {
            val sign = if (index % 2 == 0) 1f else -1f
            samples[index] = sign * 0.28f
        }
        voiced(360, 630, 165.0, 0.62f)
        voiced(630, 800, 220.0, 0.38f)
        return samples
    }
}
