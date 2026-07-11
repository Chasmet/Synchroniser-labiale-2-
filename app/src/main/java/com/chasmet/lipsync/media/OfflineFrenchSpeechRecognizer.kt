package com.chasmet.lipsync.media

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SpeechAnalysis(
    val guidance: SpeechGuidance,
    val activity: VoiceActivityTimeline
)

internal class OfflineFrenchSpeechRecognizer(
    private val context: Context
) {

    fun analyze(
        audioFile: File,
        startUs: Long,
        maxDurationUs: Long
    ): SpeechAnalysis {
        val decoded = AudioPcmDecoder().decodeMono(
            audioFile = audioFile,
            startUs = startUs,
            maxDurationUs = maxDurationUs
        )
        val samples16Khz = AudioPcmDecoder.resampleLinear(
            decoded.samples,
            decoded.sampleRate,
            TARGET_SAMPLE_RATE
        )
        val activity = VoiceActivityDetector.analyze(samples16Khz, TARGET_SAMPLE_RATE)
        if (samples16Khz.isEmpty() || activity.speechCoverage < MIN_SPEECH_COVERAGE) {
            return SpeechAnalysis(SpeechGuidance.EMPTY, activity)
        }

        val guidance = runCatching {
            recognize(samples16Khz)
        }.getOrElse {
            SpeechGuidance.EMPTY.copy(engine = "Vosk indisponible, guidage audio conservé")
        }
        return SpeechAnalysis(guidance, activity)
    }

    private fun recognize(samples16Khz: FloatArray): SpeechGuidance {
        val modelDirectory = VoskModelInstaller.ensureInstalled(context)
        val model = Model(modelDirectory.absolutePath)
        val recognizer = Recognizer(model, TARGET_SAMPLE_RATE.toFloat())
        val words = mutableListOf<RecognizedWord>()
        try {
            recognizer.setWords(true)
            val pcmChunk = ByteArray(CHUNK_SAMPLES * 2)
            var sampleOffset = 0
            while (sampleOffset < samples16Khz.size) {
                val sampleCount = minOf(CHUNK_SAMPLES, samples16Khz.size - sampleOffset)
                val byteCount = writePcm16(samples16Khz, sampleOffset, sampleCount, pcmChunk)
                if (recognizer.acceptWaveForm(pcmChunk, byteCount)) {
                    parseWords(recognizer.result, words)
                }
                sampleOffset += sampleCount
            }
            parseWords(recognizer.finalResult, words)
        } finally {
            runCatching { recognizer.close() }
            runCatching { model.close() }
        }

        val uniqueWords = words
            .distinctBy { "${it.text}:${it.startUs / 10_000L}:${it.endUs / 10_000L}" }
            .sortedBy { it.startUs }
        val averageConfidence = if (uniqueWords.isEmpty()) 0f else {
            uniqueWords.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
        }
        val wordsAccepted = uniqueWords.isNotEmpty() &&
            averageConfidence >= MIN_AVERAGE_CONFIDENCE

        val proportionalCues = if (wordsAccepted) {
            FrenchVisemeMapper.build(uniqueWords)
        } else emptyList()
        val aligned = if (wordsAccepted) {
            AcousticPhonemeAligner.align(samples16Khz, uniqueWords)
        } else {
            PhonemeAlignmentResult(emptyList(), 0f)
        }
        val useAlignment = aligned.cues.isNotEmpty() &&
            aligned.confidence >= MIN_ALIGNMENT_CONFIDENCE
        val cues = if (useAlignment) aligned.cues else proportionalCues
        val accepted = wordsAccepted && cues.isNotEmpty()

        return SpeechGuidance(
            transcript = uniqueWords.joinToString(" ") { it.text },
            words = uniqueWords,
            cues = cues,
            averageConfidence = averageConfidence,
            accepted = accepted,
            engine = when {
                accepted && useAlignment -> "Vosk + alignement phonétique français"
                accepted -> "Vosk + minutage phonétique proportionnel"
                else -> "Transcription rejetée : confiance insuffisante"
            },
            alignmentConfidence = if (useAlignment) aligned.confidence else {
                averageConfidence * FALLBACK_ALIGNMENT_FACTOR
            },
            alignedPhonemeCount = cues.size
        )
    }

    private fun parseWords(json: String, output: MutableList<RecognizedWord>) {
        val result = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return
        for (index in 0 until result.length()) {
            val item = result.optJSONObject(index) ?: continue
            val text = item.optString("word").trim()
            if (text.isBlank()) continue
            val startSeconds = item.optDouble("start", -1.0)
            val endSeconds = item.optDouble("end", -1.0)
            if (startSeconds < 0.0 || endSeconds <= startSeconds) continue
            output += RecognizedWord(
                text = text,
                startUs = (startSeconds * 1_000_000.0).toLong(),
                endUs = (endSeconds * 1_000_000.0).toLong(),
                confidence = item.optDouble("conf", 0.0).toFloat().coerceIn(0f, 1f)
            )
        }
    }

    private fun writePcm16(
        samples: FloatArray,
        offset: Int,
        count: Int,
        target: ByteArray
    ): Int {
        val buffer = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            val value = (samples[offset + index].coerceIn(-1f, 1f) * 32767f).toInt()
            buffer.putShort(value.toShort())
        }
        return count * 2
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600
        const val MIN_SPEECH_COVERAGE = 0.015f
        const val MIN_AVERAGE_CONFIDENCE = 0.48f
        const val MIN_ALIGNMENT_CONFIDENCE = 0.30f
        const val FALLBACK_ALIGNMENT_FACTOR = 0.55f
    }
}
