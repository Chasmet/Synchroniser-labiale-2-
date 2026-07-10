package com.chasmet.lipsync.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class Mp4Assembler {

    fun combine(
        videoOnly: File,
        audioM4a: File,
        outputMp4: File,
        maxDurationUs: Long
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

            muxer = MediaMuxer(
                outputMp4.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            readRotation(videoOnly)?.let { rotation ->
                if (rotation in setOf(90, 180, 270)) muxer.setOrientationHint(rotation)
            }

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
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            )
            buffer.position(0)
            buffer.limit(size)
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(sourceTrack)
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        return (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: error("Piste $prefix introuvable")
    }

    private fun readRotation(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 1_048_576
    }
}
