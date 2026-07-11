package com.chasmet.lipsync.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Le réseau personnel n'est utilisé fortement que lorsqu'il est cohérent avec
 * l'analyse fréquentielle courante. Cela évite une grande ouverture retardée
 * ou isolée lorsque le modèle rencontre une voix différente de son entraînement.
 */
internal fun adaptiveTrainedBlend(
    configuredSignalBlend: Float,
    signal: FloatArray,
    trained: FloatArray,
    speechGate: Float
): Float {
    if (signal.size < 3 || trained.size < 3) return 0f
    val gate = speechGate.coerceIn(0f, 1f)
    if (gate <= 0.01f) return 0f
    val disagreement = maxOf(
        abs(signal[0] - trained[0] * gate),
        abs(signal[1] - trained[1] * gate) * 0.78f,
        abs(signal[2] - trained[2] * gate) * 0.72f
    )
    val normalized = ((disagreement - 0.07f) / 0.48f).coerceIn(0f, 1f)
    val smoothDisagreement = normalized * normalized * (3f - 2f * normalized)
    val agreement = 1f - smoothDisagreement
    val maximumModelWeight = (1f - configuredSignalBlend.coerceIn(0f, 1f))
        .coerceAtMost(0.34f)
    return (maximumModelWeight * gate * agreement * agreement).coerceIn(0f, 0.34f)
}

/**
 * Moteur hybride local : réseau neuronal personnel, analyse fréquentielle,
 * anticipation temporelle et détection des fermetures rapides des lèvres.
 */
class AudioVisemeAnalyzer(context: Context) {

    private val personalModel = runCatching {
        PersonalLipModel.load(context.applicationContext)
    }.getOrNull()

    private val temporalProfile = runCatching {
        TemporalMotionProfile.load(context.applicationContext)
    }.getOrDefault(TemporalMotionProfile.DEFAULT)

    fun analyze(audioFile: File, startUs: Long = 0L): VisemeTimeline {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("Aucune piste audio trouvée dans le MP3")

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Format audio inconnu")

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var collector = WindowCollector(sampleRate, channels)

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            val timeoutUs = 10_000L

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Tampon audio indisponible")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                                } else {
                                    0
                                }
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE,
                            sampleRate
                        )
                        channels = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            channels
                        )
                        pcmEncoding = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        collector = WindowCollector(sampleRate, channels)
                    }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0 && info.presentationTimeUs >= startUs) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            collector.consume(outputBuffer.slice(), pcmEncoding)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            return buildTimeline(collector.finish())
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun buildTimeline(rawFrames: List<AudioFeatureFrame>): VisemeTimeline {
        if (rawFrames.isEmpty()) {
            throw IllegalStateException("Le fichier audio ne contient aucun son exploitable")
        }

        val sortedEnergy = rawFrames.map { it.rms }.sorted()
        val noiseFloor = percentile(sortedEnergy, 0.18f)
        val peak = max(percentile(sortedEnergy, 0.95f), noiseFloor + 0.0001f)
        val targets = ArrayList<VisemeFrame>(rawFrames.size)
        val closures = FloatArray(rawFrames.size)
        val gates = FloatArray(rawFrames.size)

        rawFrames.forEachIndexed { index, raw ->
            val previous = rawFrames[(index - 1).coerceAtLeast(0)]
            val future = rawFrames[
                (index + temporalProfile.lookAheadFrames).coerceAtMost(rawFrames.lastIndex)
            ]

            val energy = normalizeEnergy(raw.rms, noiseFloor, peak)
            val futureEnergy = normalizeEnergy(future.rms, noiseFloor, peak)
            val anticipatedEnergy = max(energy, futureEnergy * 0.82f)
            val silenceGate = (
                (anticipatedEnergy - temporalProfile.minimumSpeechGate) /
                    (1f - temporalProfile.minimumSpeechGate).coerceAtLeast(0.01f)
                ).coerceIn(0f, 1f)
            gates[index] = silenceGate

            val transient = (raw.transientRate * 8.5f).coerceIn(0f, 1f)
            val fricativeFromZcr = ((raw.zeroCrossingRate - 0.055f) / 0.27f)
                .coerceIn(0f, 1f)
            val fricative = (
                fricativeFromZcr * 0.50f + raw.highBand * 0.48f +
                    raw.spectralCentroid * 0.20f
                ).coerceIn(0f, 1f)
            val voiced = (
                raw.voicing * 0.72f + (1f - fricative) * 0.38f
                ).coerceIn(0f, 1f)
            val plosive = (
                raw.spectralFlux * 0.70f + transient * 0.30f
                ) * (1f - fricative * 0.72f)
            val vowelBody = (
                raw.lowBand * 0.30f + raw.midBand * 0.70f + voiced * 0.18f
                ).coerceIn(0f, 1f)
            val roundedVowel = (
                raw.lowBand * (1f - raw.highBand) * (0.70f + raw.spectralTilt * 0.30f)
                ).coerceIn(0f, 1f)
            val wideVowel = (
                raw.midBand * 0.55f + raw.highBand * 0.35f + fricative * 0.42f
                ).coerceIn(0f, 1f)

            var signalOpen = (
                silenceGate * (
                    0.06f + anticipatedEnergy * 0.48f + vowelBody * 0.34f +
                        transient * 0.06f + plosive * 0.08f
                    )
                ).coerceIn(0f, 1f)
            var signalWidth = (
                silenceGate * (wideVowel * 0.74f + transient * 0.22f)
                ).coerceIn(0f, 1f)
            var signalRound = (
                silenceGate * voiced * roundedVowel * (1f - transient * 0.22f)
                ).coerceIn(0f, 1f)

            val previousEnergy = normalizeEnergy(previous.rms, noiseFloor, peak)
            val drop = ((previousEnergy - energy) / (previousEnergy + 0.04f))
                .coerceIn(0f, 1f)
            val rebound = ((futureEnergy - energy) / (futureEnergy + 0.04f))
                .coerceIn(0f, 1f)
            val closure = (
                drop * rebound * (1f - fricative) * max(previousEnergy, futureEnergy)
                ).coerceIn(0f, 1f)
            closures[index] = closure
            signalOpen *= 1f - closure * temporalProfile.closureStrength
            signalWidth *= 1f - closure * temporalProfile.closureStrength * 0.58f
            signalRound *= 1f - closure * temporalProfile.closureStrength * 0.42f

            val trained = personalModel?.predict(rawFrames, index)
            val signalBlend = personalModel?.signalBlend ?: 1f
            val signalValues = floatArrayOf(signalOpen, signalWidth, signalRound)
            val trainedBlend = if (trained != null) {
                adaptiveTrainedBlend(signalBlend, signalValues, trained, silenceGate)
            } else 0f
            val effectiveSignalBlend = 1f - trainedBlend

            val openness = if (trained != null) {
                signalOpen * effectiveSignalBlend + trained[0] * silenceGate * trainedBlend
            } else signalOpen
            val width = if (trained != null) {
                signalWidth * effectiveSignalBlend + trained[1] * silenceGate * trainedBlend
            } else signalWidth
            val roundness = if (trained != null) {
                signalRound * effectiveSignalBlend + trained[2] * silenceGate * trainedBlend
            } else signalRound
            val silenceClosure = (1f - silenceGate) * 0.72f
            val lipClosure = max(
                silenceClosure,
                closure * temporalProfile.closureStrength
            ).coerceIn(0f, 1f)

            targets += VisemeFrame(
                timeUs = raw.timeUs,
                openness = smoothStep(
                    (openness * temporalProfile.opennessGain).coerceIn(0f, 1f)
                ),
                width = smoothStep(
                    (width * temporalProfile.widthGain).coerceIn(0f, 1f)
                ),
                roundness = smoothStep(
                    (roundness * temporalProfile.roundnessGain).coerceIn(0f, 1f)
                ),
                closure = lipClosure
            )
        }

        val temporal = applyTemporalMemory(targets, closures, gates)
        return VisemeTimeline(
            frames = temporal,
            durationUs = temporal.last().timeUs + FRAME_DURATION_US
        )
    }

    private fun applyTemporalMemory(
        targets: List<VisemeFrame>,
        closures: FloatArray,
        gates: FloatArray
    ): List<VisemeFrame> {
        var openState = 0f
        var widthState = 0f
        var roundState = 0f
        var closureState = 1f
        return targets.mapIndexed { index, target ->
            val closureBoost = closures[index] * 0.52f
            val openFactor = when {
                target.openness > openState -> temporalProfile.attackFactor
                closureBoost > 0.12f -> (temporalProfile.releaseFactor + closureBoost)
                    .coerceAtMost(0.92f)
                else -> temporalProfile.releaseFactor
            }
            val widthFactor = if (target.width > widthState) {
                temporalProfile.attackFactor * 0.92f
            } else {
                temporalProfile.releaseFactor * 0.90f
            }
            val roundFactor = if (target.roundness > roundState) {
                temporalProfile.attackFactor * 0.82f
            } else {
                temporalProfile.releaseFactor * 0.78f
            }

            openState += (target.openness - openState) * openFactor
            widthState += (target.width - widthState) * widthFactor
            roundState += (target.roundness - roundState) * roundFactor
            val closureFactor = if (target.closure > closureState) 0.82f else 0.48f
            closureState += (target.closure - closureState) * closureFactor

            if (gates[index] < 0.025f) {
                openState *= 0.18f
                widthState *= 0.18f
                roundState *= 0.18f
                closureState += (0.82f - closureState) * 0.72f
            }

            target.copy(
                openness = openState.coerceIn(0f, 1f),
                width = widthState.coerceIn(0f, 1f),
                roundness = roundState.coerceIn(0f, 1f),
                closure = closureState.coerceIn(0f, 1f)
            )
        }
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = (sorted.lastIndex * fraction.coerceIn(0f, 1f)).toInt()
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun normalizeEnergy(value: Float, floor: Float, peak: Float): Float {
        val normalized = ((value - floor) / (peak - floor).coerceAtLeast(0.0001f))
            .coerceIn(0f, 1.35f)
        return sqrt(normalized).coerceIn(0f, 1f)
    }

    private fun smoothStep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private class WindowCollector(
        private val sampleRate: Int,
        private val channels: Int
    ) {
        private val windowSize = max(1, (sampleRate * 0.040f).toInt())
        private val monoWindow = FloatArray(windowSize)
        private val frames = mutableListOf<AudioFeatureFrame>()
        private var sampleInWindow = 0
        private var totalMonoSamples = 0L
        private var sumSquares = 0.0
        private var zeroCrossings = 0
        private var transientSum = 0.0
        private var previous = 0f
        private var hasPrevious = false
        private var channelCursor = 0
        private var channelSum = 0f
        private val previousSpectrum = FloatArray(SPECTRAL_FREQUENCIES.size)
        private var hasPreviousSpectrum = false

        fun consume(buffer: ByteBuffer, pcmEncoding: Int) {
            buffer.order(ByteOrder.nativeOrder())
            when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val floatBuffer = buffer.asFloatBuffer()
                    while (floatBuffer.hasRemaining()) acceptChannelSample(floatBuffer.get())
                }
                else -> {
                    val shortBuffer = buffer.asShortBuffer()
                    while (shortBuffer.hasRemaining()) {
                        acceptChannelSample(shortBuffer.get() / 32768f)
                    }
                }
            }
        }

        fun finish(): List<AudioFeatureFrame> {
            if (sampleInWindow > 0) flushWindow()
            return frames
        }

        private fun acceptChannelSample(sample: Float) {
            channelSum += sample
            channelCursor++
            if (channelCursor < channels.coerceAtLeast(1)) return

            val mono = channelSum / channels.coerceAtLeast(1)
            channelCursor = 0
            channelSum = 0f
            acceptMonoSample(mono)
        }

        private fun acceptMonoSample(sample: Float) {
            if (sampleInWindow < monoWindow.size) monoWindow[sampleInWindow] = sample
            sumSquares += sample * sample
            if (hasPrevious) {
                if ((sample >= 0f) != (previous >= 0f)) zeroCrossings++
                transientSum += abs(sample - previous)
            }
            previous = sample
            hasPrevious = true
            sampleInWindow++
            totalMonoSamples++

            if (sampleInWindow >= windowSize) flushWindow()
        }

        private fun flushWindow() {
            val count = sampleInWindow.coerceAtLeast(1)
            val rms = sqrt(sumSquares / count).toFloat()
            val zcr = zeroCrossings.toFloat() / count
            val transient = (transientSum / count).toFloat()
            val startSample = totalMonoSamples - sampleInWindow
            val timeUs = startSample * 1_000_000L / sampleRate.coerceAtLeast(1)

            val powers = DoubleArray(SPECTRAL_FREQUENCIES.size) { frequencyIndex ->
                goertzelPower(SPECTRAL_FREQUENCIES[frequencyIndex], count)
            }
            val lowPower = powers[0] + powers[1]
            val midPower = powers[2] + powers[3]
            val highPower = powers[4] + powers[5]
            val selectedPower = (lowPower + midPower + highPower).coerceAtLeast(1.0e-12)
            val low = (lowPower / selectedPower).toFloat().coerceIn(0f, 1f)
            val mid = (midPower / selectedPower).toFloat().coerceIn(0f, 1f)
            val high = (highPower / selectedPower).toFloat().coerceIn(0f, 1f)
            val tilt = ((low - high) / (low + mid + high + 0.0001f)).coerceIn(-1f, 1f)
            val normalizedSpectrum = FloatArray(powers.size) { powerIndex ->
                (powers[powerIndex] / selectedPower).toFloat().coerceIn(0f, 1f)
            }
            var flux = 0f
            if (hasPreviousSpectrum) {
                normalizedSpectrum.indices.forEach { powerIndex ->
                    flux += max(
                        0f,
                        normalizedSpectrum[powerIndex] - previousSpectrum[powerIndex]
                    )
                }
            }
            normalizedSpectrum.copyInto(previousSpectrum)
            hasPreviousSpectrum = true
            val centroid = normalizedSpectrum.indices.sumOf { powerIndex ->
                normalizedSpectrum[powerIndex].toDouble() *
                    SPECTRAL_FREQUENCIES[powerIndex].toDouble()
            }.toFloat() / SPECTRAL_FREQUENCIES.last()
            val zcrNoise = ((zcr - 0.04f) / 0.28f).coerceIn(0f, 1f)
            val voicing = ((low * 0.48f + mid * 0.52f) * (1f - zcrNoise * 0.72f))
                .coerceIn(0f, 1f)

            frames += AudioFeatureFrame(
                timeUs = timeUs,
                rms = rms,
                zeroCrossingRate = zcr,
                transientRate = transient,
                lowBand = low,
                midBand = mid,
                highBand = high,
                spectralTilt = tilt,
                spectralFlux = flux.coerceIn(0f, 1f),
                spectralCentroid = centroid.coerceIn(0f, 1f),
                voicing = voicing
            )
            sampleInWindow = 0
            sumSquares = 0.0
            zeroCrossings = 0
            transientSum = 0.0
        }

        private fun goertzelPower(frequencyHz: Float, count: Int): Double {
            if (count <= 1 || sampleRate <= 0) return 0.0
            val safeFrequency = frequencyHz.coerceAtMost(sampleRate * 0.45f)
            val omega = 2.0 * PI * safeFrequency / sampleRate.toDouble()
            val coefficient = 2.0 * cos(omega)
            var previousOne = 0.0
            var previousTwo = 0.0
            for (index in 0 until count.coerceAtMost(monoWindow.size)) {
                val window = 0.5 - 0.5 * cos(
                    2.0 * PI * index.toDouble() / (count - 1).coerceAtLeast(1).toDouble()
                )
                val current = monoWindow[index] * window +
                    coefficient * previousOne - previousTwo
                previousTwo = previousOne
                previousOne = current
            }
            return previousTwo * previousTwo + previousOne * previousOne -
                coefficient * previousOne * previousTwo
        }
    }

    private companion object {
        const val FRAME_DURATION_US = 40_000L
        val SPECTRAL_FREQUENCIES = floatArrayOf(280f, 560f, 950f, 1_550f, 2_700f, 4_200f)
    }
}

internal fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int {
    return if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
}
