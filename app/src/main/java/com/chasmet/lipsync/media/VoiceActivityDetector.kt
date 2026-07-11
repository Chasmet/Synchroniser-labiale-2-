package com.chasmet.lipsync.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class VoiceSegment(
    val startUs: Long,
    val endUs: Long,
    val confidence: Float
)

data class VoiceActivityTimeline(
    val segments: List<VoiceSegment>,
    val durationUs: Long
) {
    fun isSpeechAt(timeUs: Long): Boolean {
        if (segments.isEmpty()) return false
        var low = 0
        var high = segments.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val segment = segments[middle]
            when {
                timeUs < segment.startUs -> high = middle - 1
                timeUs >= segment.endUs -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    val speechCoverage: Float
        get() {
            if (durationUs <= 0L) return 0f
            val speechUs = segments.sumOf { (it.endUs - it.startUs).coerceAtLeast(0L) }
            return (speechUs.toDouble() / durationUs).toFloat().coerceIn(0f, 1f)
        }

    companion object {
        val EMPTY = VoiceActivityTimeline(emptyList(), 0L)
    }
}

internal object VoiceActivityDetector {

    fun analyze(samples: FloatArray, sampleRate: Int): VoiceActivityTimeline {
        if (samples.isEmpty() || sampleRate <= 0) return VoiceActivityTimeline.EMPTY
        val frameSize = max(1, sampleRate * FRAME_MS / 1_000)
        val frameCount = (samples.size + frameSize - 1) / frameSize
        val energies = FloatArray(frameCount)
        val zcrValues = FloatArray(frameCount)

        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * frameSize
            val end = minOf(samples.size, start + frameSize)
            var squares = 0.0
            var crossings = 0
            var previous = if (start < end) samples[start] else 0f
            for (index in start until end) {
                val sample = samples[index]
                squares += sample * sample
                if (index > start && (sample >= 0f) != (previous >= 0f)) crossings++
                previous = sample
            }
            val count = (end - start).coerceAtLeast(1)
            energies[frameIndex] = sqrt(squares / count).toFloat()
            zcrValues[frameIndex] = crossings.toFloat() / count
        }

        val sorted = energies.sorted()
        val noiseFloor = percentile(sorted, 0.20f)
        val speechPeak = percentile(sorted, 0.95f)
        if (speechPeak < MIN_ABSOLUTE_SPEECH_RMS) {
            return VoiceActivityTimeline(emptyList(), samples.size * 1_000_000L / sampleRate)
        }
        val dynamicRange = (speechPeak - noiseFloor).coerceAtLeast(0.0001f)
        val threshold = max(
            MIN_ABSOLUTE_SPEECH_RMS,
            noiseFloor + dynamicRange * 0.12f
        )
        val strongThreshold = noiseFloor + dynamicRange * 0.34f

        val speechFlags = BooleanArray(frameCount)
        val confidences = FloatArray(frameCount)
        var hangover = 0
        for (index in 0 until frameCount) {
            val energy = energies[index]
            val energyConfidence = ((energy - threshold) / dynamicRange).coerceIn(0f, 1f)
            val zcrPenalty = ((zcrValues[index] - 0.34f) / 0.22f).coerceIn(0f, 1f)
            val directSpeech = energy >= threshold && (zcrPenalty < 0.92f || energy >= strongThreshold)
            if (directSpeech) hangover = HANGOVER_FRAMES else hangover = (hangover - 1).coerceAtLeast(0)
            speechFlags[index] = directSpeech || hangover > 0
            confidences[index] = (energyConfidence * (1f - zcrPenalty * 0.45f)).coerceIn(0f, 1f)
        }

        val rawSegments = mutableListOf<VoiceSegment>()
        var startFrame = -1
        var confidenceSum = 0f
        var confidenceCount = 0
        fun closeSegment(endFrameExclusive: Int) {
            if (startFrame < 0) return
            val length = endFrameExclusive - startFrame
            if (length >= MIN_SPEECH_FRAMES) {
                rawSegments += VoiceSegment(
                    startUs = startFrame * FRAME_MS * 1_000L,
                    endUs = endFrameExclusive * FRAME_MS * 1_000L,
                    confidence = if (confidenceCount == 0) 0f else {
                        (confidenceSum / confidenceCount).coerceIn(0f, 1f)
                    }
                )
            }
            startFrame = -1
            confidenceSum = 0f
            confidenceCount = 0
        }

        speechFlags.indices.forEach { index ->
            if (speechFlags[index]) {
                if (startFrame < 0) startFrame = index
                confidenceSum += confidences[index]
                confidenceCount++
            } else {
                closeSegment(index)
            }
        }
        closeSegment(frameCount)

        val merged = mutableListOf<VoiceSegment>()
        rawSegments.forEach { segment ->
            val previous = merged.lastOrNull()
            if (previous != null && segment.startUs - previous.endUs <= MERGE_GAP_US) {
                val firstDuration = (previous.endUs - previous.startUs).coerceAtLeast(1L)
                val secondDuration = (segment.endUs - segment.startUs).coerceAtLeast(1L)
                val weightedConfidence = (
                    previous.confidence * firstDuration + segment.confidence * secondDuration
                    ) / (firstDuration + secondDuration)
                merged[merged.lastIndex] = VoiceSegment(
                    startUs = previous.startUs,
                    endUs = segment.endUs,
                    confidence = weightedConfidence.coerceIn(0f, 1f)
                )
            } else {
                merged += segment
            }
        }

        val durationUs = samples.size * 1_000_000L / sampleRate
        return VoiceActivityTimeline(
            segments = merged.map { segment ->
                segment.copy(
                    startUs = (segment.startUs - PAD_US).coerceAtLeast(0L),
                    endUs = (segment.endUs + PAD_US).coerceAtMost(durationUs)
                )
            },
            durationUs = durationUs
        )
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = (sorted.lastIndex * fraction.coerceIn(0f, 1f)).toInt()
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private const val FRAME_MS = 20
    private const val HANGOVER_FRAMES = 7
    private const val MIN_SPEECH_FRAMES = 3
    private const val MIN_ABSOLUTE_SPEECH_RMS = 0.0045f
    private const val MERGE_GAP_US = 140_000L
    private const val PAD_US = 45_000L
}
