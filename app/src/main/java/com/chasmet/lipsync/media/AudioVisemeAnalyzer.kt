package com.chasmet.lipsync.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Analyse locale du MP3 et combine le signal audio avec le réseau neuronal
 * personnalisé entraîné sur les mouvements de bouche de l'utilisateur.
 */
class AudioVisemeAnalyzer(context: Context) {

    private val personalModel = runCatching {
        PersonalLipModel.load(context.applicationContext)
    }.getOrNull()

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
        val peakIndex = (sortedEnergy.lastIndex * 0.95f)
            .toInt()
            .coerceIn(0, sortedEnergy.lastIndex)
        val peak = max(sortedEnergy[peakIndex], 0.0001f)

        val frames = rawFrames.mapIndexed { index, raw ->
            val energy = sqrt((raw.rms / peak).coerceIn(0f, 1.4f)).coerceIn(0f, 1f)
            val silenceGate = ((energy - 0.035f) / 0.965f).coerceIn(0f, 1f)
            val fricative = ((raw.zeroCrossingRate - 0.08f) / 0.24f).coerceIn(0f, 1f)
            val transient = (raw.transientRate * 7f).coerceIn(0f, 1f)
            val voiced = (1f - fricative).coerceIn(0f, 1f)

            val signalOpen = (
                silenceGate * (0.20f + energy * 0.78f) * (0.78f + transient * 0.22f)
            ).coerceIn(0f, 1f)
            val signalWidth = (
                silenceGate * (fricative * 0.70f + transient * 0.30f)
            ).coerceIn(0f, 1f)
            val signalRound = (
                silenceGate * voiced * (1f - transient * 0.45f) * 0.85f
            ).coerceIn(0f, 1f)

            val trained = personalModel?.predict(rawFrames, index)
            val signalBlend = personalModel?.signalBlend ?: 1f
            val trainedBlend = 1f - signalBlend

            val openness = if (trained != null) {
                signalOpen * signalBlend + trained[0] * silenceGate * trainedBlend
            } else {
                signalOpen
            }
            val width = if (trained != null) {
                signalWidth * signalBlend + trained[1] * silenceGate * trainedBlend
            } else {
                signalWidth
            }
            val roundness = if (trained != null) {
                signalRound * signalBlend + trained[2] * silenceGate * trainedBlend
            } else {
                signalRound
            }

            VisemeFrame(
                timeUs = raw.timeUs,
                openness = smoothStep((openness * 1.12f).coerceIn(0f, 1f)),
                width = smoothStep(width.coerceIn(0f, 1f)),
                roundness = smoothStep(roundness.coerceIn(0f, 1f))
            )
        }

        val smoothed = frames.mapIndexed { index, frame ->
            val from = (index - 1).coerceAtLeast(0)
            val to = (index + 1).coerceAtMost(frames.lastIndex)
            val window = frames.subList(from, to + 1)
            frame.copy(
                openness = window.map { it.openness }.average().toFloat(),
                width = window.map { it.width }.average().toFloat(),
                roundness = window.map { it.roundness }.average().toFloat()
            )
        }

        return VisemeTimeline(
            frames = smoothed,
            durationUs = smoothed.last().timeUs + 40_000L
        )
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

            frames += AudioFeatureFrame(timeUs, rms, zcr, transient)
            sampleInWindow = 0
            sumSquares = 0.0
            zeroCrossings = 0
            transientSum = 0.0
        }
    }
}

internal fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int {
    return if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
}
