package com.chasmet.lipsync.media

import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedMouthQualityGuardTest {
    @Test
    fun burntWhiteMouthIsCappedAndReported() {
        val size = Wav2LipEngine.IMAGE_SIZE
        val source = rgba(size, 92, 66, 52)
        val generated = rgba(size, 92, 66, 52)
        val mouth = CanonicalMouthRegion(0.5f, 0.31f, 0.18f, 0.09f)
        val cx = (mouth.centerX * size).toInt()
        val cy = (mouth.centerY * size).toInt()
        for (y in cy - 12..cy + 12) {
            for (x in cx - 30..cx + 30) {
                val index = (y * size + x) * 4
                generated.put(index, 0xff.toByte())
                generated.put(index + 1, 0xff.toByte())
                generated.put(index + 2, 0xff.toByte())
            }
        }

        val guarded = GeneratedMouthQualityGuard(DentalAppearanceProfile.CONSERVATIVE_DEFAULT)
            .apply(source, GeneratedFace(generated, 0.8f, mouth))
        val center = (cy * size + cx) * 4
        val red = guarded.rgba.get(center).toInt() and 0xff

        assertTrue(red < 255)
        assertTrue(guarded.quality.toothAreaRatio > 0f)
        assertTrue(guarded.quality.corrected)
    }

    private fun rgba(size: Int, red: Int, green: Int, blue: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(size * size * 4).apply {
            repeat(size * size) {
                put(red.toByte())
                put(green.toByte())
                put(blue.toByte())
                put(0xff.toByte())
            }
            position(0)
        }
    }
}
