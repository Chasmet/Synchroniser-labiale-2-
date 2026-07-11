package com.chasmet.lipsync.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import kotlin.math.abs
import kotlin.math.min

data class RenderReport(
    val generatedFrames: Int,
    val fallbackFrames: Int,
    val engineName: String,
    val averageQuality: Float,
    val protectedFrames: Int
)

class VideoLipSyncProcessor {

    fun process(
        context: Context,
        inputVideo: File,
        outputVideoOnly: File,
        timeline: VisemeTimeline,
        melTimeline: MelTimeline,
        faceAnalysis: FaceAnalysis,
        outputAspectRatio: OutputAspectRatio,
        onProgress: (Float, Int, Int) -> Unit
    ): RenderReport {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var generativeEngine: Wav2LipEngine? = null
        var generatedFrames = 0
        var fallbackFrames = 0
        var protectedFrames = 0
        var qualitySum = 0.0

        try {
            extractor.setDataSource(inputVideo.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("Aucune piste vidéo trouvée")

            extractor.selectTrack(videoTrack)
            val inputFormat = extractor.getTrackFormat(videoTrack)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Format vidéo inconnu")
            val encodedWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val encodedHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val geometry = renderGeometry(
                encodedWidth = encodedWidth,
                encodedHeight = encodedHeight,
                rotationDegrees = readRotation(inputVideo) ?: 0,
                aspectRatio = outputAspectRatio
            )
            val frameRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
            val sourceDurationUs = inputFormat.getLongOrDefault(
                MediaFormat.KEY_DURATION,
                timeline.durationUs
            )
            val targetDurationUs = min(sourceDurationUs, timeline.durationUs)
                .coerceAtLeast(100_000L)
            val totalBlocks = ((targetDurationUs + BLOCK_DURATION_US - 1L) / BLOCK_DURATION_US)
                .toInt()
                .coerceAtLeast(1)

            val outputFormat = MediaFormat.createVideoFormat(
                VIDEO_MIME,
                geometry.outputWidth,
                geometry.outputHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    chooseBitrate(geometry.outputWidth, geometry.outputHeight, frameRate)
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceIn(15, 60))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoderCodec = MediaCodec.createEncoderByType(VIDEO_MIME).also { codec ->
                codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoder = encoderCodec
            val encoderInputSurface = encoderCodec.createInputSurface()
            val encoderSurface = InputSurface(encoderInputSurface).also { it.makeCurrent() }
            inputSurface = encoderSurface
            encoderCodec.start()

            val decoderSurface = OutputSurface(
                outputWidth = geometry.outputWidth,
                outputHeight = geometry.outputHeight,
                rotationDegrees = geometry.rotationDegrees,
                sourceAspect = encodedWidth.toFloat() / encodedHeight.coerceAtLeast(1).toFloat(),
                viewportX = geometry.viewportX,
                viewportY = geometry.viewportY,
                viewportWidth = geometry.viewportWidth,
                viewportHeight = geometry.viewportHeight
            )
            outputSurface = decoderSurface
            generativeEngine = runCatching {
                Wav2LipEngine(context.applicationContext)
            }.getOrNull()
            val decoderCodec = MediaCodec.createDecoderByType(inputMime).also { codec ->
                codec.configure(inputFormat, decoderSurface.surface, null, 0)
                codec.start()
            }
            decoder = decoderCodec

            muxer = MediaMuxer(
                outputVideoOnly.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var encoderEosSignalled = false
            var outputTrack = -1
            var firstPresentationUs = -1L
            var previousFace: FaceRegion? = null
            var consecutiveGenerationFailures = 0
            val timeoutUs = 10_000L

            while (!encoderDone) {
                if (!inputDone) {
                    val inputIndex = decoderCodec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoderCodec.getInputBuffer(inputIndex)
                            ?: error("Tampon vidéo indisponible")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > targetDurationUs) {
                            decoderCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoderCodec.queueInputBuffer(
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

                var decoderOutputAvailable = !decoderDone
                var encoderOutputAvailable = true
                while (decoderOutputAvailable || encoderOutputAvailable) {
                    val encoderStatus = encoderCodec.dequeueOutputBuffer(encoderInfo, timeoutUs)
                    when {
                        encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            encoderOutputAvailable = false
                        }
                        encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Le format vidéo a changé deux fois" }
                            outputTrack = muxer.addTrack(encoderCodec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encoderStatus >= 0 -> {
                            val encodedData = encoderCodec.getOutputBuffer(encoderStatus)
                                ?: error("Tampon vidéo encodé indisponible")
                            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encoderInfo.size = 0
                            }
                            if (encoderInfo.size > 0) {
                                check(muxerStarted) { "Le multiplexeur vidéo n'est pas démarré" }
                                encodedData.position(encoderInfo.offset)
                                encodedData.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(outputTrack, encodedData, encoderInfo)
                            }
                            encoderDone = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoderCodec.releaseOutputBuffer(encoderStatus, false)
                        }
                    }

                    if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue
                    if (!decoderOutputAvailable) break

                    val decoderStatus = decoderCodec.dequeueOutputBuffer(decoderInfo, timeoutUs)
                    when {
                        decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            decoderOutputAvailable = false
                        }
                        decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        decoderStatus >= 0 -> {
                            val endOfStream = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val canRender = decoderInfo.size > 0 &&
                                decoderInfo.presentationTimeUs <= targetDurationUs
                            decoderCodec.releaseOutputBuffer(decoderStatus, canRender)

                            if (canRender) {
                                decoderSurface.awaitNewImage()
                                if (firstPresentationUs < 0L) {
                                    firstPresentationUs = decoderInfo.presentationTimeUs
                                }
                                val normalizedTimeUs = (
                                    decoderInfo.presentationTimeUs - firstPresentationUs
                                    ).coerceAtLeast(0L)
                                val viseme = timeline.frameAt(normalizedTimeUs)
                                val mouthRegion = faceAnalysis.mouthTrack.regionAt(normalizedTimeUs)
                                val faceRegion = faceAnalysis.faceTrack.regionAt(normalizedTimeUs)
                                val faceConfidence = faceAnalysis.faceTrack
                                    .confidenceAt(normalizedTimeUs)
                                val temporalStability = faceStability(previousFace, faceRegion)
                                val canonicalMouth = mouthRegion.inCanonicalFace(
                                    face = faceRegion,
                                    sourceAspect = encodedWidth.toFloat() /
                                        encodedHeight.coerceAtLeast(1).toFloat()
                                )
                                val engine = generativeEngine
                                val generatedFace = if (engine != null && faceConfidence >= 0.22f) {
                                    runCatching {
                                        val crop = decoderSurface.readFaceCrop(faceRegion)
                                        engine.infer(
                                            sourceRgbaBottomUp = crop,
                                            melChunk = melTimeline.chunkAt(normalizedTimeUs),
                                            temporalStability = temporalStability,
                                            canonicalMouth = canonicalMouth
                                        )
                                    }.onFailure {
                                        consecutiveGenerationFailures++
                                    }.getOrNull()
                                } else {
                                    engine?.resetTemporalState()
                                    null
                                }

                                if (generatedFace != null) {
                                    generatedFrames++
                                    qualitySum += generatedFace.quality.confidence.toDouble()
                                    if (generatedFace.quality.protectedFrame) protectedFrames++
                                    consecutiveGenerationFailures = 0
                                } else {
                                    fallbackFrames++
                                    if (consecutiveGenerationFailures >= 2) {
                                        runCatching { generativeEngine?.close() }
                                        generativeEngine = null
                                    }
                                }
                                decoderSurface.drawImage(
                                    mouth = mouthRegion,
                                    viseme = viseme,
                                    face = faceRegion,
                                    generatedFace = generatedFace,
                                    faceConfidence = faceConfidence
                                )
                                previousFace = faceRegion
                                encoderSurface.setPresentationTime(normalizedTimeUs * 1_000L)
                                encoderSurface.swapBuffers()

                                val progress = (normalizedTimeUs.toDouble() / targetDurationUs)
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                                val block = (normalizedTimeUs / BLOCK_DURATION_US)
                                    .toInt()
                                    .plus(1)
                                    .coerceAtMost(totalBlocks)
                                onProgress(progress, block, totalBlocks)
                            }

                            if (endOfStream || decoderInfo.presentationTimeUs >= targetDurationUs) {
                                decoderDone = true
                                decoderOutputAvailable = false
                            }
                        }
                    }
                }

                if (decoderDone && !encoderEosSignalled) {
                    encoderCodec.signalEndOfInputStream()
                    encoderEosSignalled = true
                }
            }

            onProgress(1f, totalBlocks, totalBlocks)
            return RenderReport(
                generatedFrames = generatedFrames,
                fallbackFrames = fallbackFrames,
                engineName = if (generatedFrames > 0) {
                    "Wav2Lip 256 local"
                } else {
                    "Moteur Pro v4 de secours"
                },
                averageQuality = if (generatedFrames > 0) {
                    (qualitySum / generatedFrames).toFloat().coerceIn(0f, 1f)
                } else 0f,
                protectedFrames = protectedFrames
            )
        } finally {
            runCatching { generativeEngine?.close() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun faceStability(previous: FaceRegion?, current: FaceRegion): Float {
        if (previous == null) return 0f
        val centerMotion = abs(current.centerX - previous.centerX) +
            abs(current.centerY - previous.centerY)
        val sizeMotion = abs(current.width - previous.width) +
            abs(current.height - previous.height)
        val rollMotion = abs(normalizeAngleDegrees(current.rollDegrees - previous.rollDegrees)) / 45f
        return (1f - centerMotion * 6f - sizeMotion * 3f - rollMotion * 0.35f)
            .coerceIn(0f, 1f)
    }

    private fun chooseBitrate(width: Int, height: Int, frameRate: Int): Int {
        val calculated = width.toLong() * height.toLong() * frameRate.coerceAtLeast(24) / 6L
        return calculated.coerceIn(2_000_000L, 14_000_000L).toInt()
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
        const val VIDEO_MIME = "video/avc"
        const val BLOCK_DURATION_US = 30_000_000L
    }
}

private fun MediaFormat.getLongOrDefault(key: String, fallback: Long): Long {
    return if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback
}
