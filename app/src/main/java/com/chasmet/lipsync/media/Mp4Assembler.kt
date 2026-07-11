package com.chasmet.lipsync.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

class Mp4Assembler {

    fun combine(
        videoOnly: File,
        audioM4a: File,
        outputMp4: File,
        maxDurationUs: Long,
        outputAspectRatio: OutputAspectRatio
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false

        try {
            videoExtractor.setDataSource(videoOnly.absolutePath)
            audioExtractor.setDataSource(audioM4a.absolutePath)

            val videoSourceTrack = findTrack(videoExtractor, "video/")
            val audioSourceTrack = findTrack(audioExtractor, "audio/")
            val videoFormat = videoExtractor.getTrackFormat(videoSourceTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioSourceTrack)

            val encodedWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val encodedHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            check(encodedWidth == outputAspectRatio.outputWidth &&
                encodedHeight == outputAspectRatio.outputHeight) {
                "Dimensions vidéo intermédiaire inattendues : ${encodedWidth} × ${encodedHeight}"
            }
            val orientationHint = finalOrientationHint(
                encodedWidth = encodedWidth,
                encodedHeight = encodedHeight,
                aspectRatio = outputAspectRatio
            )

            /* Supprime toute ancienne rotation transportée par le format intermédiaire. */
            videoFormat.setInteger(MediaFormat.KEY_ROTATION, 0)

            muxer = MediaMuxer(
                outputMp4.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            /* Les pixels ont déjà été réorientés et dimensionnés pendant le rendu. */
            muxer.setOrientationHint(orientationHint)

            val videoTargetTrack = muxer.addTrack(videoFormat)
            val audioTargetTrack = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            copyTrack(
                extractor = videoExtractor,
                sourceTrack = videoSourceTrack,
                muxer = muxer,
                targetTrack = videoTargetTrack,
                format = videoFormat,
                maxDurationUs = maxDurationUs
            )
            copyTrack(
                extractor = audioExtractor,
                sourceTrack = audioSourceTrack,
                muxer = muxer,
                targetTrack = audioTargetTrack,
                format = audioFormat,
                maxDurationUs = maxDurationUs
            )
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }

        verifyOutputAspectRatio(outputMp4, outputAspectRatio)
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        sourceTrack: Int,
        muxer: MediaMuxer,
        targetTrack: Int,
        format: MediaFormat,
        maxDurationUs: Long
    ) {
        extractor.selectTrack(sourceTrack)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_BUFFER_SIZE)
        } else DEFAULT_BUFFER_SIZE
        val buffer = ByteBuffer.allocateDirect(bufferSize)
        val info = MediaCodec.BufferInfo()
        var firstSampleTimeUs = -1L

        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val sampleTimeUs = extractor.sampleTime
            if (firstSampleTimeUs < 0L) firstSampleTimeUs = sampleTimeUs
            val normalizedTimeUs = (sampleTimeUs - firstSampleTimeUs).coerceAtLeast(0L)
            if (normalizedTimeUs > maxDurationUs) break

            info.set(
                0,
                size,
                normalizedTimeUs,
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
            )
            buffer.position(0)
            buffer.limit(size)
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(sourceTrack)
    }

    private fun verifyOutputAspectRatio(file: File, expected: OutputAspectRatio) {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = findTrack(extractor, "video/")
            val videoFormat = extractor.getTrackFormat(videoTrack)
            val encodedWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val encodedHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            check(encodedWidth == expected.outputWidth && encodedHeight == expected.outputHeight) {
                "Dimensions encodées incorrectes : ${encodedWidth} × ${encodedHeight}"
            }

            retriever.setDataSource(file.absolutePath)
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0

            val displayed = displayedDimensions(encodedWidth, encodedHeight, rotation)
            val expectedPortrait = expected == OutputAspectRatio.PORTRAIT_9_16
            val actualPortrait = displayed.height > displayed.width
            check(actualPortrait == expectedPortrait) {
                "Le format final ne correspond pas à ${expected.label} " +
                    "(${displayed.width} × ${displayed.height}, rotation $rotation°)"
            }

            val actualRatio = displayed.width.toDouble() / displayed.height.toDouble()
            val expectedRatio = expected.outputWidth.toDouble() / expected.outputHeight.toDouble()
            check(abs(actualRatio - expectedRatio) <= RATIO_TOLERANCE) {
                "Le ratio final est incorrect : ${displayed.width} × ${displayed.height} " +
                    "au lieu de ${expected.outputWidth} × ${expected.outputHeight}"
            }
        } finally {
            runCatching { extractor.release() }
            runCatching { retriever.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        return (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: error("Piste $prefix introuvable")
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 1_048_576
        const val RATIO_TOLERANCE = 0.02
    }
}
