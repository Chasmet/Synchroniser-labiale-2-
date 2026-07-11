package com.chasmet.lipsync.media

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

internal data class PhonemeAlignmentResult(
    val cues: List<PhonemeCue>,
    val confidence: Float
)

/**
 * Aligne les phonèmes d'un mot Vosk sur les événements acoustiques locaux.
 *
 * Les mots donnent une fenêtre fiable. À l'intérieur, l'énergie, les passages
 * par zéro, les hautes fréquences et les attaques déplacent les frontières des
 * phonèmes. Ce traitement reste léger et entièrement local sur Android.
 */
internal object AcousticPhonemeAligner {

    fun align(
        samples16Khz: FloatArray,
        words: List<RecognizedWord>
    ): PhonemeAlignmentResult {
        if (samples16Khz.isEmpty() || words.isEmpty()) {
            return PhonemeAlignmentResult(emptyList(), 0f)
        }
        val features = buildFeatures(samples16Khz)
        if (features.isEmpty()) return PhonemeAlignmentResult(emptyList(), 0f)

        val cues = words
            .filter { it.endUs > it.startUs && it.confidence >= MIN_WORD_CONFIDENCE }
            .flatMap { alignWord(it, features) }
            .sortedBy { it.startUs }
        val confidence = if (cues.isEmpty()) 0f else {
            cues.map { it.alignmentConfidence }.average().toFloat().coerceIn(0f, 1f)
        }
        return PhonemeAlignmentResult(cues, confidence)
    }

    private fun alignWord(
        word: RecognizedWord,
        features: List<FrameFeatures>
    ): List<PhonemeCue> {
        val tokens = FrenchPhonemeLexicon.tokensForWord(word.text)
        if (tokens.isEmpty()) return emptyList()

        val firstFrame = floor(word.startUs.toDouble() / FRAME_US).toInt()
            .coerceIn(0, features.lastIndex)
        val lastExclusive = ceil(word.endUs.toDouble() / FRAME_US).toInt()
            .coerceIn(firstFrame + 1, features.size)
        val availableFrames = lastExclusive - firstFrame
        if (availableFrames <= 0) return emptyList()

        val usableTokens = if (tokens.size <= availableFrames) {
            tokens
        } else {
            tokens.filterIndexed { index, _ -> index % 2 == 0 }
                .take(availableFrames)
                .ifEmpty { listOf(tokens.first()) }
        }
        val boundaries = initialBoundaries(firstFrame, lastExclusive, usableTokens)
        refineBoundaries(boundaries, usableTokens, features)

        return usableTokens.mapIndexed { index, token ->
            val startFrame = boundaries[index]
            val endFrame = boundaries[index + 1].coerceAtLeast(startFrame + 1)
            val startUs = max(word.startUs, startFrame * FRAME_US)
            val endUs = if (index == usableTokens.lastIndex) {
                word.endUs.coerceAtLeast(startUs + 1L)
            } else {
                minOf(word.endUs, endFrame * FRAME_US).coerceAtLeast(startUs + 1L)
            }
            val acousticScore = segmentScore(
                token = token,
                startFrame = startFrame,
                endFrame = endFrame,
                features = features
            )
            PhonemeCue(
                startUs = startUs,
                endUs = endUs,
                shape = token.shape,
                confidence = word.confidence.coerceIn(0f, 1f),
                phoneme = token.symbol,
                sourceWord = word.text,
                alignmentConfidence = (
                    word.confidence * (0.52f + acousticScore * 0.48f)
                    ).coerceIn(0f, 1f)
            )
        }
    }

    private fun initialBoundaries(
        firstFrame: Int,
        lastExclusive: Int,
        tokens: List<FrenchPhonemeToken>
    ): IntArray {
        val span = (lastExclusive - firstFrame).coerceAtLeast(tokens.size)
        val totalWeight = tokens.sumOf { it.durationWeight.toDouble() }.toFloat()
            .coerceAtLeast(0.001f)
        val boundaries = IntArray(tokens.size + 1)
        boundaries[0] = firstFrame
        boundaries[tokens.size] = lastExclusive
        var cumulative = 0f
        for (index in 1 until tokens.size) {
            cumulative += tokens[index - 1].durationWeight
            val target = firstFrame + (span * cumulative / totalWeight).toInt()
            boundaries[index] = target.coerceIn(
                boundaries[index - 1] + 1,
                lastExclusive - (tokens.size - index)
            )
        }
        return boundaries
    }

    private fun refineBoundaries(
        boundaries: IntArray,
        tokens: List<FrenchPhonemeToken>,
        features: List<FrameFeatures>
    ) {
        for (index in 1 until boundaries.lastIndex) {
            val minimum = boundaries[index - 1] + 1
            val maximum = boundaries.last() - (tokens.size - index)
            if (minimum > maximum) continue
            val target = boundaries[index]
            val from = (target - SEARCH_RADIUS_FRAMES).coerceAtLeast(minimum)
            val to = (target + SEARCH_RADIUS_FRAMES).coerceAtMost(maximum)
            var bestFrame = target.coerceIn(from, to)
            var bestScore = Float.NEGATIVE_INFINITY
            for (frame in from..to) {
                val score = boundaryScore(
                    frame = frame,
                    previous = tokens[index - 1],
                    next = tokens[index],
                    features = features
                ) - abs(frame - target) * 0.025f
                if (score > bestScore) {
                    bestScore = score
                    bestFrame = frame
                }
            }
            boundaries[index] = bestFrame
        }
    }

    private fun boundaryScore(
        frame: Int,
        previous: FrenchPhonemeToken,
        next: FrenchPhonemeToken,
        features: List<FrameFeatures>
    ): Float {
        val before = features[(frame - 1).coerceIn(0, features.lastIndex)]
        val after = features[frame.coerceIn(0, features.lastIndex)]
        val delta = after.energy - before.energy
        val onset = after.flux
        return when {
            next.shape == FrenchLipShape.BILABIAL -> {
                (-delta).coerceAtLeast(0f) * 0.45f +
                    (1f - after.energy) * 0.35f + onset * 0.20f
            }
            previous.shape == FrenchLipShape.BILABIAL -> {
                delta.coerceAtLeast(0f) * 0.50f + onset * 0.35f +
                    after.energy * 0.15f
            }
            else -> onset * 0.62f + abs(delta) * 0.38f
        }
    }

    private fun segmentScore(
        token: FrenchPhonemeToken,
        startFrame: Int,
        endFrame: Int,
        features: List<FrameFeatures>
    ): Float {
        val from = startFrame.coerceIn(0, features.lastIndex)
        val to = (endFrame - 1).coerceIn(from, features.lastIndex)
        var total = 0f
        var count = 0
        for (index in from..to) {
            val value = features[index]
            total += when (token.shape) {
                FrenchLipShape.BILABIAL ->
                    (1f - value.energy) * 0.42f +
                        (1f - value.zeroCrossing) * 0.22f + value.flux * 0.36f
                FrenchLipShape.LABIODENTAL,
                FrenchLipShape.POSTALVEOLAR ->
                    value.highFrequency * 0.44f + value.zeroCrossing * 0.34f +
                        value.energy * 0.22f
                FrenchLipShape.CONSONANT ->
                    value.flux * 0.38f + value.highFrequency * 0.34f +
                        value.zeroCrossing * 0.28f
                FrenchLipShape.OPEN,
                FrenchLipShape.MID,
                FrenchLipShape.WIDE,
                FrenchLipShape.ROUND,
                FrenchLipShape.ROUND_TIGHT ->
                    value.energy * 0.62f + (1f - value.zeroCrossing) * 0.20f +
                        (1f - value.highFrequency) * 0.18f
                FrenchLipShape.REST -> 1f - value.energy
            }
            count++
        }
        return (total / count.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun buildFeatures(samples: FloatArray): List<FrameFeatures> {
        val frameCount = max(1, 1 + (samples.size - FRAME_SIZE).coerceAtLeast(0) / HOP_SIZE)
        val rawEnergy = FloatArray(frameCount)
        val rawHigh = FloatArray(frameCount)
        val zeroCrossing = FloatArray(frameCount)

        for (frame in 0 until frameCount) {
            val start = frame * HOP_SIZE
            val end = minOf(samples.size, start + FRAME_SIZE)
            var energy = 0.0
            var high = 0.0
            var crossings = 0
            var previous = if (start < samples.size) samples[start] else 0f
            var sampleCount = 0
            for (index in start until end) {
                val value = samples[index]
                energy += value * value
                val difference = value - previous
                high += difference * difference
                if ((value >= 0f) != (previous >= 0f)) crossings++
                previous = value
                sampleCount++
            }
            rawEnergy[frame] = sqrt(energy / sampleCount.coerceAtLeast(1)).toFloat()
            rawHigh[frame] = sqrt(high / sampleCount.coerceAtLeast(1)).toFloat()
            zeroCrossing[frame] = (crossings.toFloat() / sampleCount.coerceAtLeast(1))
                .coerceIn(0f, 1f)
        }

        val low = percentile(rawEnergy, 0.10f)
        val high = percentile(rawEnergy, 0.92f).coerceAtLeast(low + 1.0e-5f)
        return List(frameCount) { index ->
            val energy = ((rawEnergy[index] - low) / (high - low)).coerceIn(0f, 1f)
            val previousEnergy = if (index == 0) energy else {
                ((rawEnergy[index - 1] - low) / (high - low)).coerceIn(0f, 1f)
            }
            val highFrequency = (rawHigh[index] / (rawEnergy[index] * 2.4f + 1.0e-5f))
                .coerceIn(0f, 1f)
            FrameFeatures(
                energy = energy,
                zeroCrossing = (zeroCrossing[index] * 5f).coerceIn(0f, 1f),
                highFrequency = highFrequency,
                flux = abs(energy - previousEnergy).coerceIn(0f, 1f)
            )
        }
    }

    private fun percentile(values: FloatArray, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        val index = (sorted.lastIndex * fraction.coerceIn(0f, 1f)).toInt()
        return sorted[index]
    }

    private data class FrameFeatures(
        val energy: Float,
        val zeroCrossing: Float,
        val highFrequency: Float,
        val flux: Float
    )

    private const val SAMPLE_RATE = 16_000
    private const val FRAME_SIZE = 320
    private const val HOP_SIZE = 160
    private const val FRAME_US = HOP_SIZE * 1_000_000L / SAMPLE_RATE
    private const val SEARCH_RADIUS_FRAMES = 5
    private const val MIN_WORD_CONFIDENCE = 0.34f
}
