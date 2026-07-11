package com.chasmet.lipsync.media

import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGuidanceFusionTest {

    @Test
    fun `un phoneme bilabial ferme les levres`() {
        val base = VisemeTimeline(
            frames = listOf(
                VisemeFrame(0L, 0.70f, 0.60f, 0.20f, 0.05f),
                VisemeFrame(40_000L, 0.70f, 0.60f, 0.20f, 0.05f),
                VisemeFrame(80_000L, 0.70f, 0.60f, 0.20f, 0.05f)
            ),
            durationUs = 120_000L
        )
        val guidance = SpeechGuidance(
            transcript = "p",
            words = listOf(RecognizedWord("p", 0L, 120_000L, 0.95f)),
            cues = listOf(PhonemeCue(0L, 120_000L, FrenchLipShape.BILABIAL, 0.95f)),
            averageConfidence = 0.95f,
            accepted = true,
            engine = "test"
        )
        val activity = VoiceActivityTimeline(
            listOf(VoiceSegment(0L, 120_000L, 1f)),
            120_000L
        )

        val result = SpeechGuidanceFusion.fuse(base, guidance, activity).frames[1]

        assertTrue(result.openness < 0.45f)
        assertTrue(result.closure > 0.45f)
    }

    @Test
    fun `le silence ferme la bouche meme sans transcription`() {
        val base = VisemeTimeline(
            listOf(VisemeFrame(0L, 0.80f, 0.70f, 0.50f, 0.10f)),
            40_000L
        )
        val activity = VoiceActivityTimeline(emptyList(), 40_000L)

        val result = SpeechGuidanceFusion.fuse(base, SpeechGuidance.EMPTY, activity).frames.first()

        assertTrue(result.openness < 0.20f)
        assertTrue(result.closure >= 0.90f)
    }
}
