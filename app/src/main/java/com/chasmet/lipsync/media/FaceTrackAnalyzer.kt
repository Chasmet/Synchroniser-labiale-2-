package com.chasmet.lipsync.media

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Analyse le visage sur toute la durée de la vidéo et produit une chronologie
 * interpolable de la bouche. Cette approche évite d'appliquer l'animation à une
 * position fixe lorsque la tête bouge.
 */
class FaceTrackAnalyzer {

    private data class VideoGeometry(
        val encodedWidth: Int,
        val encodedHeight: Int,
        val rotationDegrees: Int
    )

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()
    )

    suspend fun analyze(videoFile: File): MouthTrack {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val durationUs = durationMs * 1_000L
            val geometry = readGeometry(videoFile, retriever)
            val sampleTimesUs = buildSampleTimes(durationUs)
            val rawKeyframes = mutableListOf<MouthKeyframe>()

            for (timeUs in sampleTimesUs) {
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue

                val scaled = scaleForDetection(bitmap)
                try {
                    val face = detectLargestFace(scaled)
                    if (face != null) {
                        val detected = mouthRegion(face, scaled.width, scaled.height)
                        val rotationToEncoded = if (isDisplayOriented(scaled, geometry)) {
                            geometry.rotationDegrees
                        } else {
                            0
                        }
                        rawKeyframes += MouthKeyframe(
                            timeUs = timeUs,
                            region = detected.displayToEncoded(rotationToEncoded),
                            confidence = detectionConfidence(face)
                        )
                    }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                }
            }

            if (rawKeyframes.isEmpty()) {
                throw IllegalStateException(
                    "Aucun visage détecté. Utilise une vidéo frontale, nette et bien éclairée."
                )
            }

            val fallback = medianRegion(rawKeyframes.map { it.region })
            val smoothed = smoothAndRejectOutliers(rawKeyframes, fallback)
            MouthTrack(keyframes = smoothed, fallback = fallback)
        } finally {
            runCatching { retriever.release() }
            detector.close()
        }
    }

    private fun buildSampleTimes(durationUs: Long): List<Long> {
        if (durationUs <= 0L) return listOf(0L)
        val estimatedInterval = ceil(
            durationUs.toDouble() / (MAX_TRACK_SAMPLES - 1).coerceAtLeast(1)
        ).toLong()
        val intervalUs = max(MIN_TRACK_INTERVAL_US, estimatedInterval)
        val result = mutableListOf<Long>()
        var timeUs = 0L
        while (timeUs < durationUs && result.size < MAX_TRACK_SAMPLES) {
            result += timeUs
            timeUs += intervalUs
        }
        if (result.lastOrNull() != durationUs && result.size < MAX_TRACK_SAMPLES) {
            result += durationUs
        }
        return result.distinct()
    }

    private fun smoothAndRejectOutliers(
        keyframes: List<MouthKeyframe>,
        fallback: MouthRegion
    ): List<MouthKeyframe> {
        val ordered = keyframes.sortedBy { it.timeUs }
        if (ordered.size == 1) return ordered

        val result = mutableListOf<MouthKeyframe>()
        var previous = fallback
        ordered.forEachIndexed { index, frame ->
            val candidate = frame.region
            val centerJump = abs(candidate.centerX - previous.centerX) +
                abs(candidate.centerY - previous.centerY)
            val widthRatio = ratio(candidate.width, previous.width)
            val heightRatio = ratio(candidate.height, previous.height)
            val rejected = index > 0 && (
                centerJump > MAX_CENTER_JUMP ||
                    widthRatio > MAX_SIZE_RATIO ||
                    heightRatio > MAX_SIZE_RATIO
                )

            val accepted = if (rejected) previous else candidate
            val alpha = when {
                index == 0 -> 1f
                frame.confidence >= 0.85f -> 0.52f
                frame.confidence >= 0.60f -> 0.38f
                else -> 0.24f
            }
            val filtered = previous.interpolate(accepted, alpha).copy(
                centerX = previous.interpolate(accepted, alpha).centerX.coerceIn(0.02f, 0.98f),
                centerY = previous.interpolate(accepted, alpha).centerY.coerceIn(0.02f, 0.98f),
                width = previous.interpolate(accepted, alpha).width.coerceIn(0.035f, 0.48f),
                height = previous.interpolate(accepted, alpha).height.coerceIn(0.025f, 0.32f)
            )
            result += frame.copy(region = filtered, confidence = if (rejected) 0.15f else frame.confidence)
            previous = filtered
        }
        return result
    }

    private fun ratio(first: Float, second: Float): Float {
        val minimum = min(first, second).coerceAtLeast(0.0001f)
        return max(first, second) / minimum
    }

    private fun detectionConfidence(face: Face): Float {
        val landmarkCount = listOf(
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM
        ).count { face.getLandmark(it) != null }
        val landmarkScore = landmarkCount / 3f
        val posePenalty = (
            abs(face.headEulerAngleY) / 55f + abs(face.headEulerAngleZ) / 65f
            ).coerceIn(0f, 0.65f)
        return (0.35f + landmarkScore * 0.65f - posePenalty).coerceIn(0.10f, 1f)
    }

    private fun readGeometry(
        videoFile: File,
        retriever: MediaMetadataRetriever
    ): VideoGeometry {
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
            val format = videoTrack?.let(extractor::getTrackFormat)
            VideoGeometry(
                encodedWidth = format?.getIntegerOrDefault(MediaFormat.KEY_WIDTH, 0) ?: 0,
                encodedHeight = format?.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, 0) ?: 0,
                rotationDegrees = rotation
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun isDisplayOriented(bitmap: Bitmap, geometry: VideoGeometry): Boolean {
        val rotation = ((geometry.rotationDegrees % 360) + 360) % 360
        if (rotation !in setOf(90, 270)) return false
        if (geometry.encodedWidth <= 0 || geometry.encodedHeight <= 0) return true

        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val encodedRatio = geometry.encodedWidth.toFloat() / geometry.encodedHeight.coerceAtLeast(1)
        val displayedRatio = geometry.encodedHeight.toFloat() / geometry.encodedWidth.coerceAtLeast(1)
        return abs(bitmapRatio - displayedRatio) <= abs(bitmapRatio - encodedRatio)
    }

    private suspend fun detectLargestFace(bitmap: Bitmap): Face? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = suspendCancellableCoroutine<List<Face>> { continuation ->
            detector.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
    }

    private fun scaleForDetection(source: Bitmap): Bitmap {
        val maxDimension = max(source.width, source.height)
        if (maxDimension <= DETECTION_MAX_DIMENSION) return source
        val scale = DETECTION_MAX_DIMENSION.toFloat() / maxDimension.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun mouthRegion(face: Face, width: Int, height: Int): MouthRegion {
        val left = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val right = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val bottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

        val fallbackCenter = PointF(
            face.boundingBox.centerX().toFloat(),
            face.boundingBox.top + face.boundingBox.height() * 0.72f
        )

        val centerX = if (left != null && right != null) {
            (left.x + right.x) / 2f
        } else fallbackCenter.x

        val centerY = if (left != null && right != null && bottom != null) {
            ((left.y + right.y) / 2f + bottom.y) / 2f
        } else fallbackCenter.y

        val mouthWidthPx = if (left != null && right != null) {
            max(24f, right.x - left.x)
        } else {
            face.boundingBox.width() * 0.38f
        }

        val mouthHeightPx = if (bottom != null && left != null && right != null) {
            max(14f, (bottom.y - min(left.y, right.y)) * 2.2f)
        } else {
            face.boundingBox.height() * 0.16f
        }

        return MouthRegion(
            centerX = (centerX / width).coerceIn(0.05f, 0.95f),
            centerY = (1f - centerY / height).coerceIn(0.05f, 0.95f),
            width = (mouthWidthPx / width * 1.80f).coerceIn(0.08f, 0.45f),
            height = (mouthHeightPx / height * 1.95f).coerceIn(0.04f, 0.28f)
        )
    }

    private fun medianRegion(regions: List<MouthRegion>): MouthRegion {
        fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2f
            } else sorted[middle]
        }

        return MouthRegion(
            centerX = median(regions.map { it.centerX }),
            centerY = median(regions.map { it.centerY }),
            width = median(regions.map { it.width }),
            height = median(regions.map { it.height })
        )
    }

    private companion object {
        const val DETECTION_MAX_DIMENSION = 1_080
        const val MIN_TRACK_INTERVAL_US = 400_000L
        const val MAX_TRACK_SAMPLES = 220
        const val MAX_CENTER_JUMP = 0.24f
        const val MAX_SIZE_RATIO = 2.25f
    }
}
