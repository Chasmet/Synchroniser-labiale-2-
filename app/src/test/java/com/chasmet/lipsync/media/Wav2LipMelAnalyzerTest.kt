package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class Wav2LipMelAnalyzerTest {

    @Test
    fun melChunkUsesExactEightyHertzTimeline() {
        val frameCount = 64
        val values = FloatArray(MelTimeline.MEL_BANDS * frameCount) { index ->
            val band = index / frameCount
            val frame = index % frameCount
            band * 1_000f + frame
        }
        val timeline = MelTimeline(values, frameCount, 800_000L)

        val chunk = timeline.chunkAt(250_000L)

        for (band in 0 until MelTimeline.MEL_BANDS) {
            for (column in 0 until MelTimeline.MEL_STEP_SIZE) {
                assertEquals(
                    band * 1_000f + 20f + column,
                    chunk[band * MelTimeline.MEL_STEP_SIZE + column],
                    0.0001f
                )
            }
        }
    }

    @Test
    fun silenceMatchesWav2LipNormalizedFloor() {
        val timeline = Wav2LipMelAnalyzer().buildMelTimeline(FloatArray(16_000))

        assertEquals(81, timeline.frameCount)
        assertEquals(1_000_000L, timeline.durationUs)
        assertTrue(timeline.values.all { it == MelTimeline.SILENCE_VALUE })
    }

    @Test
    fun voicedToneProducesFiniteNonSilentMelEnergy() {
        val samples = FloatArray(16_000) { index ->
            (0.35 * sin(2.0 * PI * 440.0 * index / 16_000.0)).toFloat()
        }

        val timeline = Wav2LipMelAnalyzer().buildMelTimeline(samples)

        assertTrue(timeline.values.all { it.isFinite() && it in -4f..4f })
        assertTrue(timeline.values.max() > -1.5f)
    }

    @Test
    fun slaneyMelScaleRoundTripsReferenceFrequencies() {
        val analyzer = Wav2LipMelAnalyzer()
        listOf(55f, 1_000f, 7_600f).forEach { frequency ->
            val restored = analyzer.slaneyMelToHz(analyzer.hzToSlaneyMel(frequency))
            assertEquals(frequency, restored, frequency * 0.0002f)
        }
        val filters = analyzer.buildMelFilterBank()
        assertEquals(MelTimeline.MEL_BANDS * 401, filters.size)
        assertTrue(filters.all { it >= 0f && it.isFinite() })
        assertTrue(filters.any { it > 0f })
    }

    @Test
    fun tensorPackingUsesBgrMaskAndVerticalFlip() {
        val size = Wav2LipEngine.IMAGE_SIZE
        val plane = size * size
        val rgba = ByteBuffer.allocateDirect(plane * 4).order(ByteOrder.nativeOrder())
        repeat(plane) {
            rgba.put(10).put(20).put(30).put(0xff.toByte())
        }
        // Ligne texture 0 = bas ; ligne 255 = haut.
        rgba.put(0, 20)
        rgba.put(1, 40)
        rgba.put(2, 60)
        val topPixel = (size - 1) * size * 4
        rgba.put(topPixel, 200.toByte())
        rgba.put(topPixel + 1, 100.toByte())
        rgba.put(topPixel + 2, 50.toByte())
        rgba.position(0)
        val packed = ByteBuffer.allocateDirect(plane * 6 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        Wav2LipTensorCodec.packVideoInput(rgba, packed)

        assertEquals(50f / 255f, packed.get(0), 0.0001f)
        assertEquals(100f / 255f, packed.get(plane), 0.0001f)
        assertEquals(200f / 255f, packed.get(plane * 2), 0.0001f)
        assertEquals(50f / 255f, packed.get(plane * 3), 0.0001f)
        val bottomModelPixel = plane - size
        assertEquals(0f, packed.get(bottomModelPixel), 0.0001f)
        assertEquals(60f / 255f, packed.get(plane * 3 + bottomModelPixel), 0.0001f)
    }

    @Test
    fun generatedBgrOutputBecomesBottomUpRgba() {
        val size = Wav2LipEngine.IMAGE_SIZE
        val plane = size * size
        val prediction = FloatArray(plane * 3)
        val modelTop = 0
        val modelBottom = plane - size
        prediction[modelTop] = 0.10f
        prediction[plane + modelTop] = 0.20f
        prediction[plane * 2 + modelTop] = 0.30f
        prediction[modelBottom] = 0.40f
        prediction[plane + modelBottom] = 0.50f
        prediction[plane * 2 + modelBottom] = 0.60f
        val rgba = ByteBuffer.allocateDirect(plane * 4).order(ByteOrder.nativeOrder())

        Wav2LipTensorCodec.predictionToRgbaBottomUp(prediction, rgba)

        assertEquals(153, rgba.get(0).toInt() and 0xff)
        assertEquals(128, rgba.get(1).toInt() and 0xff)
        assertEquals(102, rgba.get(2).toInt() and 0xff)
        val textureTop = (size - 1) * size * 4
        assertEquals(77, rgba.get(textureTop).toInt() and 0xff)
        assertEquals(51, rgba.get(textureTop + 1).toInt() and 0xff)
        assertEquals(26, rgba.get(textureTop + 2).toInt() and 0xff)
    }
}
