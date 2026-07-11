package com.chasmet.lipsync.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class DecodedPcm(
    val samples: FloatArray,
    val sampleRate: Int
)

internal class AudioPcmDecoder {

    fun decodeMono(
        audioFile: File,
        startUs: Long,
        maxDurationUs: Long
    ): DecodedPcm {
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
            extractor.seekTo(startUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Format audio inconnu")
            val safeStartUs = startUs.coerceAtLeast(0L)
            val endUs = if (maxDurationUs == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                safeStartUs + maxDurationUs.coerceAtLeast(100_000L)
            }

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val expectedSamples = if (maxDurationUs == Long.MAX_VALUE) {
                sampleRate * 60
            } else {
                min(8_000_000L, maxDurationUs * sampleRate / 1_000_000L)
                    .toInt()
                    .coerceAtLeast(sampleRate)
            }
            val output = PcmAccumulator(expectedSamples)
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
                        if (sampleSize < 0 || (endUs != Long.MAX_VALUE && sampleTime >= endUs)) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                if (sampleTime >= 0L) sampleTime else safeStartUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                sampleTime.coerceAtLeast(0L),
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
                        sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = format.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            consumePcm(
                                buffer = buffer.slice().order(ByteOrder.nativeOrder()),
                                encoding = pcmEncoding,
                                channels = channels,
                                sampleRate = sampleRate,
                                presentationTimeUs = info.presentationTimeUs,
                                startUs = safeStartUs,
                                endUs = endUs,
                                output = output
                            )
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            return DecodedPcm(output.toArray(), sampleRate)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun consumePcm(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        sampleRate: Int,
        presentationTimeUs: Long,
        startUs: Long,
        endUs: Long,
        output: PcmAccumulator
    ) {
        val safeChannels = channels.coerceAtLeast(1)
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameCount = buffer.remaining() / (bytesPerSample * safeChannels)
        if (frameCount <= 0) return
        val firstFrame = ceil(
            (startUs - presentationTimeUs).coerceAtLeast(0L).toDouble() * sampleRate /
                1_000_000.0
        ).toInt().coerceIn(0, frameCount)
        val lastFrame = if (endUs == Long.MAX_VALUE) {
            frameCount
        } else {
            ceil(
                (endUs - presentationTimeUs).coerceAtLeast(0L).toDouble() * sampleRate /
                    1_000_000.0
            ).toInt().coerceIn(firstFrame, frameCount)
        }
        for (frame in firstFrame until lastFrame) {
            var mono = 0f
            for (channel in 0 until safeChannels) {
                val sampleIndex = frame * safeChannels + channel
                mono += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    buffer.getFloat(sampleIndex * 4)
                } else {
                    buffer.getShort(sampleIndex * 2) / 32768f
                }
            }
            output.add((mono / safeChannels).coerceIn(-1f, 1f))
        }
    }

    companion object {
        fun resampleLinear(samples: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
            if (samples.isEmpty() || sourceRate <= 0 || targetRate <= 0) return FloatArray(0)
            if (sourceRate == targetRate) return samples.copyOf()
            val targetSize = max(
                1,
                (samples.size.toLong() * targetRate / sourceRate).toInt()
            )
            val ratio = sourceRate.toDouble() / targetRate
            return FloatArray(targetSize) { targetIndex ->
                val sourcePosition = targetIndex * ratio
                val left = sourcePosition.toInt().coerceIn(0, samples.lastIndex)
                val right = min(left + 1, samples.lastIndex)
                val fraction = (sourcePosition - left).toFloat()
                samples[left] + (samples[right] - samples[left]) * fraction
            }
        }
    }

    private class PcmAccumulator(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1_024))
        private var size = 0

        fun add(value: Float) {
            if (size >= values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}
