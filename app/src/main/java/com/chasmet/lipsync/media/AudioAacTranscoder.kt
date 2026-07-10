package com.chasmet.lipsync.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.util.ArrayDeque

class AudioAacTranscoder {

    private data class PcmChunk(
        val bytes: ByteArray,
        var offset: Int = 0
    )

    fun transcode(
        inputAudio: File,
        outputM4a: File,
        startUs: Long,
        maxDurationUs: Long
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(inputAudio.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("Aucune piste audio à convertir")

            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val sourceFormat = extractor.getTrackFormat(track)
            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Format audio inconnu")
            val sampleRate = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val channelCount = sourceFormat.getIntegerOrDefault(
                MediaFormat.KEY_CHANNEL_COUNT,
                1
            ).coerceIn(1, 2)

            sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoderCodec = MediaCodec.createDecoderByType(sourceMime).also { codec ->
                codec.configure(sourceFormat, null, null, 0)
                codec.start()
            }
            decoder = decoderCodec

            val encoderFormat = MediaFormat.createAudioFormat(
                AAC_MIME,
                sampleRate,
                channelCount
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val encoderCodec = MediaCodec.createEncoderByType(AAC_MIME).also { codec ->
                codec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
            encoder = encoderCodec

            muxer = MediaMuxer(
                outputM4a.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            val pendingPcm = ArrayDeque<PcmChunk>()
            val timeoutUs = 10_000L
            val bytesPerSampleFrame = channelCount * 2L
            val targetEndUs = startUs + maxDurationUs

            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderInputDone = false
            var encoderOutputDone = false
            var totalPcmBytes = 0L
            var audioTrack = -1

            while (!encoderOutputDone) {
                if (!decoderInputDone) {
                    val inputIndex = decoderCodec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoderCodec.getInputBuffer(inputIndex)
                            ?: error("Tampon de décodage audio indisponible")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        val sampleTime = extractor.sampleTime
                        if (size < 0 || sampleTime > targetEndUs) {
                            decoderCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputDone = true
                        } else {
                            decoderCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                sampleTime,
                                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            )
                            extractor.advance()
                        }
                    }
                }

                if (!decoderOutputDone) {
                    when (val outputIndex = decoderCodec.dequeueOutputBuffer(decoderInfo, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer = decoderCodec.getOutputBuffer(outputIndex)
                            val isAfterStart = decoderInfo.presentationTimeUs >= startUs
                            val isBeforeEnd = decoderInfo.presentationTimeUs < targetEndUs
                            if (buffer != null && decoderInfo.size > 0 && isAfterStart && isBeforeEnd) {
                                buffer.position(decoderInfo.offset)
                                buffer.limit(decoderInfo.offset + decoderInfo.size)
                                val data = ByteArray(decoderInfo.size)
                                buffer.get(data)
                                pendingPcm.add(PcmChunk(data))
                            }
                            decoderOutputDone = decoderInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || !isBeforeEnd
                            decoderCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                if (!encoderInputDone) {
                    val inputIndex = encoderCodec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoderCodec.getInputBuffer(inputIndex)
                            ?: error("Tampon d'encodage audio indisponible")
                        inputBuffer.clear()

                        val chunk = pendingPcm.peekFirst()
                        if (chunk != null) {
                            val remaining = chunk.bytes.size - chunk.offset
                            val writeSize = minOf(remaining, inputBuffer.remaining())
                            inputBuffer.put(chunk.bytes, chunk.offset, writeSize)
                            chunk.offset += writeSize
                            if (chunk.offset >= chunk.bytes.size) pendingPcm.removeFirst()

                            val presentationUs = totalPcmBytes * 1_000_000L /
                                (sampleRate * bytesPerSampleFrame)
                            encoderCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                writeSize,
                                presentationUs,
                                0
                            )
                            totalPcmBytes += writeSize
                        } else if (decoderOutputDone) {
                            val presentationUs = totalPcmBytes * 1_000_000L /
                                (sampleRate * bytesPerSampleFrame)
                            encoderCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            encoderInputDone = true
                        }
                    }
                }

                var drainEncoder = true
                while (drainEncoder) {
                    when (val outputIndex = encoderCodec.dequeueOutputBuffer(encoderInfo, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> drainEncoder = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Le format AAC a changé deux fois" }
                            audioTrack = muxer.addTrack(encoderCodec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val encoded = encoderCodec.getOutputBuffer(outputIndex)
                                ?: error("Tampon AAC indisponible")
                            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encoderInfo.size = 0
                            }
                            if (encoderInfo.size > 0) {
                                check(muxerStarted) { "Le multiplexeur audio n'est pas démarré" }
                                encoded.position(encoderInfo.offset)
                                encoded.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(audioTrack, encoded, encoderInfo)
                            }
                            encoderOutputDone = encoderInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoderCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val AAC_MIME = "audio/mp4a-latm"
    }
}
