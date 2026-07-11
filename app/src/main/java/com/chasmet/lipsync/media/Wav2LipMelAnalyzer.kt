package com.chasmet.lipsync.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.jtransforms.fft.FloatFFT_1D
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mél-spectrogramme compatible avec le prétraitement du Wav2Lip original :
 * 16 kHz, préaccentuation 0,97, STFT 800/200, 80 bandes Mel Slaney et plage
 * normalisée [-4, 4]. Les valeurs sont rangées [bande, temps].
 */
data class MelTimeline(
    val values: FloatArray,
    val frameCount: Int,
    val durationUs: Long
) {
    init {
        require(frameCount >= 0)
        require(values.size == MEL_BANDS * frameCount)
    }

    fun chunkAt(timeUs: Long): FloatArray {
        val result = FloatArray(MEL_BANDS * MEL_STEP_SIZE) { SILENCE_VALUE }
        if (frameCount == 0) return result
        val startFrame = floor(
            timeUs.coerceAtLeast(0L).toDouble() * MEL_FRAMES_PER_SECOND / 1_000_000.0
        ).toInt()

        for (band in 0 until MEL_BANDS) {
            val sourceOffset = band * frameCount
            val targetOffset = band * MEL_STEP_SIZE
            for (column in 0 until MEL_STEP_SIZE) {
                val sourceFrame = (startFrame + column).coerceIn(0, frameCount - 1)
                result[targetOffset + column] = values[sourceOffset + sourceFrame]
            }
        }
        return result
    }

    companion object {
        const val MEL_BANDS = 80
        const val MEL_STEP_SIZE = 16
        const val MEL_FRAMES_PER_SECOND = 80.0
        const val SILENCE_VALUE = -4f
    }
}

class Wav2LipMelAnalyzer {

    fun analyze(
        audioFile: File,
        startUs: Long = 0L,
        maxDurationUs: Long = Long.MAX_VALUE
    ): MelTimeline {
        val decoded = decodeMono(
            audioFile = audioFile,
            startUs = startUs.coerceAtLeast(0L),
            maxDurationUs = maxDurationUs.coerceAtLeast(100_000L)
        )
        if (decoded.samples.isEmpty()) {
            throw IllegalStateException("Le fichier audio ne contient aucun son exploitable")
        }
        val resampled = resampleLinear(decoded.samples, decoded.sampleRate, TARGET_SAMPLE_RATE)
        return buildMelTimeline(resampled)
    }

    private fun decodeMono(
        audioFile: File,
        startUs: Long,
        maxDurationUs: Long
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("Aucune piste audio trouvée")

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Format audio inconnu")
            val endUs = if (maxDurationUs == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                (startUs + maxDurationUs).coerceAtLeast(startUs)
            }

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val samples = FloatAccumulator(
                initialCapacity = min(
                    8_000_000L,
                    maxDurationUs.coerceAtMost(180_000_000L) * sampleRate / 1_000_000L
                ).toInt().coerceAtLeast(16_000)
            )
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
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime >= endUs) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                max(startUs, min(sampleTime, endUs)),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                sampleTime,
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
                        val format = codec.outputFormat
                        sampleRate = format.getIntegerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE,
                            sampleRate
                        )
                        channels = format.getIntegerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            channels
                        )
                        pcmEncoding = format.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            consumePcm(
                                buffer = outputBuffer.slice().order(ByteOrder.nativeOrder()),
                                pcmEncoding = pcmEncoding,
                                channels = channels,
                                sampleRate = sampleRate,
                                presentationTimeUs = info.presentationTimeUs,
                                startUs = startUs,
                                endUs = endUs,
                                output = samples
                            )
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            return DecodedAudio(sampleRate, samples.toArray())
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun consumePcm(
        buffer: ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        sampleRate: Int,
        presentationTimeUs: Long,
        startUs: Long,
        endUs: Long,
        output: FloatAccumulator
    ) {
        val safeChannels = channels.coerceAtLeast(1)
        val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameCount = buffer.remaining() / (bytesPerSample * safeChannels)
        if (frameCount <= 0) return

        val firstFrame = ceil(
            (startUs - presentationTimeUs).coerceAtLeast(0L).toDouble() * sampleRate /
                1_000_000.0
        ).toInt().coerceIn(0, frameCount)
        val lastFrameExclusive = if (endUs == Long.MAX_VALUE) {
            frameCount
        } else {
            ceil(
                (endUs - presentationTimeUs).coerceAtLeast(0L).toDouble() * sampleRate /
                    1_000_000.0
            ).toInt().coerceIn(firstFrame, frameCount)
        }

        for (frame in firstFrame until lastFrameExclusive) {
            var mono = 0f
            for (channel in 0 until safeChannels) {
                val sampleIndex = frame * safeChannels + channel
                mono += if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    buffer.getFloat(sampleIndex * 4)
                } else {
                    buffer.getShort(sampleIndex * 2) / 32768f
                }
            }
            output.add((mono / safeChannels).coerceIn(-1f, 1f))
        }
    }

    internal fun buildMelTimeline(samples16Khz: FloatArray): MelTimeline {
        val emphasized = FloatArray(samples16Khz.size)
        if (samples16Khz.isNotEmpty()) {
            emphasized[0] = samples16Khz[0]
            for (index in 1 until samples16Khz.size) {
                emphasized[index] = samples16Khz[index] -
                    PREEMPHASIS * samples16Khz[index - 1]
            }
        }

        val frameCount = 1 + samples16Khz.size / HOP_SIZE
        val melValues = FloatArray(MelTimeline.MEL_BANDS * frameCount)
        val fft = FloatFFT_1D(N_FFT.toLong())
        val fftBuffer = FloatArray(N_FFT)
        val magnitudes = FloatArray(N_FFT / 2 + 1)
        val window = FloatArray(WIN_SIZE) { index ->
            (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * index / WIN_SIZE)).toFloat()
        }
        val melFilters = buildMelFilterBank()

        for (frameIndex in 0 until frameCount) {
            val sourceStart = frameIndex * HOP_SIZE - N_FFT / 2
            for (sampleIndex in 0 until N_FFT) {
                val sourceIndex = sourceStart + sampleIndex
                fftBuffer[sampleIndex] = if (sourceIndex in emphasized.indices) {
                    emphasized[sourceIndex] * window[sampleIndex]
                } else {
                    0f
                }
            }
            fft.realForward(fftBuffer)
            magnitudes[0] = abs(fftBuffer[0])
            for (bin in 1 until N_FFT / 2) {
                val real = fftBuffer[bin * 2]
                val imaginary = fftBuffer[bin * 2 + 1]
                magnitudes[bin] = sqrt(real * real + imaginary * imaginary)
            }
            magnitudes[N_FFT / 2] = abs(fftBuffer[1])

            for (band in 0 until MelTimeline.MEL_BANDS) {
                var melAmplitude = 0f
                val filterOffset = band * magnitudes.size
                for (bin in magnitudes.indices) {
                    melAmplitude += melFilters[filterOffset + bin] * magnitudes[bin]
                }
                val decibels = 20f * log10(max(MIN_AMPLITUDE, melAmplitude)) - REF_LEVEL_DB
                val normalized = (
                    2f * MAX_ABS_VALUE * ((decibels - MIN_LEVEL_DB) / -MIN_LEVEL_DB) -
                        MAX_ABS_VALUE
                    ).coerceIn(-MAX_ABS_VALUE, MAX_ABS_VALUE)
                melValues[band * frameCount + frameIndex] = normalized
            }
        }

        return MelTimeline(
            values = melValues,
            frameCount = frameCount,
            durationUs = samples16Khz.size * 1_000_000L / TARGET_SAMPLE_RATE
        )
    }

    internal fun buildMelFilterBank(): FloatArray {
        val fftFrequencies = FloatArray(N_FFT / 2 + 1) { bin ->
            bin * TARGET_SAMPLE_RATE.toFloat() / N_FFT
        }
        val melMin = hzToSlaneyMel(MEL_FMIN)
        val melMax = hzToSlaneyMel(MEL_FMAX)
        val melPoints = FloatArray(MelTimeline.MEL_BANDS + 2) { index ->
            melMin + (melMax - melMin) * index / (MelTimeline.MEL_BANDS + 1)
        }
        val hzPoints = FloatArray(melPoints.size) { index ->
            slaneyMelToHz(melPoints[index])
        }
        val filters = FloatArray(MelTimeline.MEL_BANDS * fftFrequencies.size)

        for (band in 0 until MelTimeline.MEL_BANDS) {
            val lowerSpan = (hzPoints[band + 1] - hzPoints[band]).coerceAtLeast(1.0e-7f)
            val upperSpan = (hzPoints[band + 2] - hzPoints[band + 1]).coerceAtLeast(1.0e-7f)
            val areaNormalization = 2f /
                (hzPoints[band + 2] - hzPoints[band]).coerceAtLeast(1.0e-7f)
            val offset = band * fftFrequencies.size
            for (bin in fftFrequencies.indices) {
                val lower = (fftFrequencies[bin] - hzPoints[band]) / lowerSpan
                val upper = (hzPoints[band + 2] - fftFrequencies[bin]) / upperSpan
                filters[offset + bin] = max(0f, min(lower, upper)) * areaNormalization
            }
        }
        return filters
    }

    internal fun hzToSlaneyMel(frequencyHz: Float): Float {
        val frequency = frequencyHz.coerceAtLeast(0f)
        val linearMel = frequency / MEL_FREQUENCY_SPACING
        if (frequency < MEL_LOG_THRESHOLD_HZ) return linearMel
        return MEL_LOG_THRESHOLD_MEL +
            (ln(frequency / MEL_LOG_THRESHOLD_HZ) / MEL_LOG_STEP).toFloat()
    }

    internal fun slaneyMelToHz(mel: Float): Float {
        if (mel < MEL_LOG_THRESHOLD_MEL) return mel * MEL_FREQUENCY_SPACING
        return MEL_LOG_THRESHOLD_HZ *
            kotlin.math.exp(MEL_LOG_STEP * (mel - MEL_LOG_THRESHOLD_MEL)).toFloat()
    }

    internal fun resampleLinear(
        samples: FloatArray,
        sourceRate: Int,
        targetRate: Int
    ): FloatArray {
        if (samples.isEmpty() || sourceRate <= 0 || targetRate <= 0) return FloatArray(0)
        if (sourceRate == targetRate) return samples.copyOf()
        val targetSize = floor(samples.size.toDouble() * targetRate / sourceRate)
            .toInt()
            .coerceAtLeast(1)
        return FloatArray(targetSize) { targetIndex ->
            val sourcePosition = targetIndex.toDouble() * sourceRate / targetRate
            val left = floor(sourcePosition).toInt().coerceIn(0, samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            samples[left] + (samples[right] - samples[left]) * fraction
        }
    }

    private data class DecodedAudio(
        val sampleRate: Int,
        val samples: FloatArray
    )

    private class FloatAccumulator(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1))
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val N_FFT = 800
        const val WIN_SIZE = 800
        const val HOP_SIZE = 200
        const val PREEMPHASIS = 0.97f
        const val MEL_FMIN = 55f
        const val MEL_FMAX = 7_600f
        const val MIN_LEVEL_DB = -100f
        const val REF_LEVEL_DB = 20f
        const val MAX_ABS_VALUE = 4f
        const val MIN_AMPLITUDE = 1.0e-5f
        const val MEL_FREQUENCY_SPACING = 200f / 3f
        const val MEL_LOG_THRESHOLD_HZ = 1_000f
        const val MEL_LOG_THRESHOLD_MEL = MEL_LOG_THRESHOLD_HZ / MEL_FREQUENCY_SPACING
        val MEL_LOG_STEP = (ln(6.4) / 27.0).toFloat()
    }
}
