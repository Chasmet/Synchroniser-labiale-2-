package com.chasmet.lipsync.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Analyse le visage sur toute la durée de la vidéo et produit deux chronologies :
 * le visage complet et la bouche suivie par un traqueur labial spécialisé.
 */
class FaceTrackAnalyzer(private val context: Context) {

    private data class VideoGeometry(
        val encodedWidth: Int,
        val encodedHeight: Int,
        val rotationDegrees: Int
    )

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    suspend fun analyze(videoFile: File): FaceAnalysis {
        val retriever = MediaMetadataRetriever()
        val lipTracker = DedicatedLipTracker()
        val faceMesh = runCatching {
            MediaPipeFaceMeshTracker(context.applicationContext)
        }.getOrNull()
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
            val rawFaceKeyframes = mutableListOf<FaceKeyframe>()
            var targetTrackingId: Int? = null
            var previousDisplayRegion: MouthRegion? = null

            for (timeUs in sampleTimesUs) {
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue

                val scaled = scaleForDetection(bitmap)
                try {
                    val rotationToEncoded = if (isDisplayOriented(scaled, geometry)) {
                        geometry.rotationDegrees
                    } else {
                        0
                    }
                    val meshDetection = faceMesh?.let { tracker ->
                        runCatching { tracker.detect(scaled, timeUs) }.getOrNull()
                    }
                    if (meshDetection != null) {
                        val mouth = meshDetection.mouth
                            .displayToEncoded(rotationToEncoded)
                        val face = meshDetection.face
                            .displayToEncoded(rotationToEncoded)
                        previousDisplayRegion = meshDetection.mouth
                        rawKeyframes += MouthKeyframe(
                            timeUs = timeUs,
                            region = mouth,
                            confidence = meshDetection.confidence
                        )
                        rawFaceKeyframes += FaceKeyframe(
                            timeUs = timeUs,
                            region = face,
                            confidence = meshDetection.confidence
                        )
                        continue
                    }

                    val faces = detectFaces(scaled)
                    val face = selectTargetFace(
                        faces = faces,
                        imageWidth = scaled.width,
                        imageHeight = scaled.height,
                        previousRegion = previousDisplayRegion,
                        targetTrackingId = targetTrackingId
                    )
                    if (face != null) {
                        val newTrackingId = face.trackingId
                        if (
                            targetTrackingId != null &&
                            newTrackingId != null &&
                            targetTrackingId != newTrackingId
                        ) {
                            lipTracker.reset()
                        }

                        val detectedFace = faceRegion(face, scaled.width, scaled.height)
                        val lipResult = lipTracker.update(
                            contourPoints = lipContourPoints(face),
                            landmarkPoints = lipLandmarkPoints(face),
                            faceBounds = faceBounds(face),
                            imageWidth = scaled.width,
                            imageHeight = scaled.height,
                            timeUs = timeUs
                        )
                        val detected = lipResult.region
                        previousDisplayRegion = detected
                        targetTrackingId = newTrackingId ?: targetTrackingId

                        val faceConfidence = detectionConfidence(face)
                        val mouthConfidence = (
                            lipResult.confidence * 0.76f + faceConfidence * 0.24f
                            ).coerceIn(0f, 1f)

                        rawKeyframes += MouthKeyframe(
                            timeUs = timeUs,
                            region = detected.displayToEncoded(rotationToEncoded),
                            confidence = mouthConfidence
                        )
                        rawFaceKeyframes += FaceKeyframe(
                            timeUs = timeUs,
                            region = detectedFace.displayToEncoded(rotationToEncoded),
                            confidence = faceConfidence
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
            val faceFallback = medianFaceRegion(rawFaceKeyframes.map { it.region })
            val smoothedFaces = smoothAndRejectFaceOutliers(rawFaceKeyframes, faceFallback)
            FaceAnalysis(
                mouthTrack = MouthTrack(keyframes = smoothed, fallback = fallback),
                faceTrack = FaceTrack(keyframes = smoothedFaces, fallback = faceFallback)
            )
        } finally {
            runCatching { faceMesh?.close() }
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
                frame.confidence >= 0.85f -> 0.62f
                frame.confidence >= 0.60f -> 0.48f
                frame.confidence >= 0.35f -> 0.32f
                else -> 0.18f
            }
            val interpolated = previous.interpolate(accepted, alpha)
            val filtered = interpolated.copy(
                centerX = interpolated.centerX.coerceIn(0.02f, 0.98f),
                centerY = interpolated.centerY.coerceIn(0.02f, 0.98f),
                width = interpolated.width.coerceIn(0.035f, 0.48f),
                height = interpolated.height.coerceIn(0.025f, 0.32f)
            )
            result += frame.copy(
                region = filtered,
                confidence = if (rejected) 0.12f else frame.confidence
            )
            previous = filtered
        }
        return result
    }

    private fun ratio(first: Float, second: Float): Float {
        val minimum = min(first, second).coerceAtLeast(0.0001f)
        return max(first, second) / minimum
    }

    private fun smoothAndRejectFaceOutliers(
        keyframes: List<FaceKeyframe>,
        fallback: FaceRegion
    ): List<FaceKeyframe> {
        val ordered = keyframes.sortedBy { it.timeUs }
        if (ordered.size == 1) return ordered

        val result = mutableListOf<FaceKeyframe>()
        var previous = fallback
        ordered.forEachIndexed { index, frame ->
            val candidate = frame.region
            val centerJump = abs(candidate.centerX - previous.centerX) +
                abs(candidate.centerY - previous.centerY)
            val rejected = index > 0 && (
                centerJump > MAX_FACE_CENTER_JUMP ||
                    ratio(candidate.width, previous.width) > MAX_FACE_SIZE_RATIO ||
                    ratio(candidate.height, previous.height) > MAX_FACE_SIZE_RATIO
                )
            val accepted = if (rejected) previous else candidate
            val alpha = when {
                index == 0 -> 1f
                frame.confidence >= 0.85f -> 0.48f
                frame.confidence >= 0.60f -> 0.34f
                else -> 0.22f
            }
            val interpolated = previous.interpolate(accepted, alpha)
            val filtered = interpolated.copy(
                centerX = interpolated.centerX.coerceIn(0.01f, 0.99f),
                centerY = interpolated.centerY.coerceIn(0.01f, 0.99f),
                width = interpolated.width.coerceIn(0.12f, 0.96f),
                height = interpolated.height.coerceIn(0.15f, 0.98f)
            )
            result += frame.copy(
                region = filtered,
                confidence = if (rejected) 0.15f else frame.confidence
            )
            previous = filtered
        }
        return result
    }

    private fun detectionConfidence(face: Face): Float {
        val landmarkCount = listOf(
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM
        ).count { face.getLandmark(it) != null }
        val landmarkScore = landmarkCount / 3f
        val contourScore = (lipContourPoints(face).size / 32f).coerceIn(0f, 1f)
        val posePenalty = (
            abs(face.headEulerAngleY) / 55f + abs(face.headEulerAngleZ) / 65f
            ).coerceIn(0f, 0.65f)
        return (
            0.22f + landmarkScore * 0.28f + contourScore * 0.50f - posePenalty
            ).coerceIn(0.08f, 1f)
    }

    private fun lipContourPoints(face: Face): List<LipPoint> {
        return LIP_CONTOURS.flatMap { contourType ->
            face.getContour(contourType)?.points.orEmpty()
        }.map { LipPoint(it.x, it.y) }
    }

    private fun lipLandmarkPoints(face: Face): List<LipPoint> {
        val left = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return emptyList()
        val right = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return emptyList()
        val bottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return emptyList()
        return listOf(
            LipPoint(left.x, left.y),
            LipPoint(right.x, right.y),
            LipPoint(bottom.x, bottom.y)
        )
    }

    private fun faceBounds(face: Face): FaceBoundsPx {
        val box = face.boundingBox
        return FaceBoundsPx(
            left = box.left.toFloat(),
            top = box.top.toFloat(),
            right = box.right.toFloat(),
            bottom = box.bottom.toFloat()
        )
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

    private suspend fun detectFaces(bitmap: Bitmap): List<Face> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            detector.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    /**
     * Verrouille le même visage d'une image à l'autre. Le premier choix favorise
     * le grand visage central ; les choix suivants privilégient l'identifiant,
     * le contour des lèvres et la continuité spatiale.
     */
    private fun selectTargetFace(
        faces: List<Face>,
        imageWidth: Int,
        imageHeight: Int,
        previousRegion: MouthRegion?,
        targetTrackingId: Int?
    ): Face? {
        if (faces.isEmpty()) return null
        if (targetTrackingId != null) {
            faces.firstOrNull { it.trackingId == targetTrackingId }?.let { return it }
        }

        val imageArea = (imageWidth.toFloat() * imageHeight.toFloat()).coerceAtLeast(1f)
        return faces.maxByOrNull { face ->
            val area = face.boundingBox.width() * face.boundingBox.height() / imageArea
            val centerX = face.boundingBox.centerX().toFloat() / imageWidth.coerceAtLeast(1)
            val centerY = face.boundingBox.centerY().toFloat() / imageHeight.coerceAtLeast(1)
            val centerPrior = (
                1f - (abs(centerX - 0.5f) * 1.35f + abs(centerY - 0.42f) * 0.55f)
                ).coerceIn(0f, 1f)
            val landmarkBonus = listOf(
                FaceLandmark.MOUTH_LEFT,
                FaceLandmark.MOUTH_RIGHT,
                FaceLandmark.MOUTH_BOTTOM
            ).count { face.getLandmark(it) != null } / 3f
            val contourBonus = (lipContourPoints(face).size / 32f).coerceIn(0f, 1f)

            if (previousRegion == null) {
                area * 4.2f + centerPrior * 0.62f +
                    landmarkBonus * 0.28f + contourBonus * 0.62f
            } else {
                val candidate = approximateMouthRegion(face, imageWidth, imageHeight)
                val centerDistance = abs(candidate.centerX - previousRegion.centerX) +
                    abs(candidate.centerY - previousRegion.centerY)
                val continuity = (1f - centerDistance / 0.42f).coerceIn(0f, 1f)
                val widthContinuity = (
                    min(candidate.width, previousRegion.width) /
                        max(candidate.width, previousRegion.width).coerceAtLeast(0.001f)
                    ).coerceIn(0f, 1f)
                continuity * 2.4f + widthContinuity * 0.82f + area * 1.6f +
                    centerPrior * 0.18f + landmarkBonus * 0.18f + contourBonus * 0.52f
            }
        }
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

    /** Estimation rapide utilisée uniquement pour choisir le bon visage. */
    private fun approximateMouthRegion(face: Face, width: Int, height: Int): MouthRegion {
        val contours = lipContourPoints(face)
        if (contours.size >= 12) {
            val minX = contours.minOf { it.x }
            val maxX = contours.maxOf { it.x }
            val minY = contours.minOf { it.y }
            val maxY = contours.maxOf { it.y }
            return MouthRegion(
                centerX = ((minX + maxX) * 0.5f / width).coerceIn(0.05f, 0.95f),
                centerY = (1f - (minY + maxY) * 0.5f / height).coerceIn(0.05f, 0.95f),
                width = ((maxX - minX) * 1.34f / width).coerceIn(0.045f, 0.30f),
                height = ((maxY - minY) * 1.70f / height).coerceIn(0.022f, 0.18f)
            )
        }

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
            width = min(
                mouthWidthPx * 1.32f,
                face.boundingBox.width() * 0.62f
            ).div(width.toFloat().coerceAtLeast(1f)).coerceIn(0.045f, 0.30f),
            height = min(
                mouthHeightPx * 1.28f,
                face.boundingBox.height() * 0.24f
            ).div(height.toFloat().coerceAtLeast(1f)).coerceIn(0.022f, 0.18f)
        )
    }

    private fun faceRegion(face: Face, width: Int, height: Int): FaceRegion {
        val box = face.boundingBox
        val safeWidth = width.toFloat().coerceAtLeast(1f)
        val safeHeight = height.toFloat().coerceAtLeast(1f)
        val expandedWidth = box.width() * FACE_WIDTH_SCALE
        val expandedHeight = box.height() * FACE_HEIGHT_SCALE
        val centerX = box.centerX().toFloat()
        val centerY = box.centerY() + box.height() * FACE_CENTER_Y_SHIFT

        return FaceRegion(
            centerX = (centerX / safeWidth).coerceIn(0.01f, 0.99f),
            centerY = (1f - centerY / safeHeight).coerceIn(0.01f, 0.99f),
            width = (expandedWidth / safeWidth).coerceIn(0.12f, 0.96f),
            height = (expandedHeight / safeHeight).coerceIn(0.15f, 0.98f),
            rollDegrees = faceRollDegrees(face)
        )
    }

    private fun faceRollDegrees(face: Face): Float {
        val left = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val right = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        if (left == null || right == null) return normalizeAngleDegrees(-face.headEulerAngleZ)
        return normalizeAngleDegrees(
            (atan2(-(right.y - left.y), right.x - left.x) * 180f / kotlin.math.PI.toFloat())
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

    private fun medianFaceRegion(regions: List<FaceRegion>): FaceRegion {
        fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2f
            } else sorted[middle]
        }

        return FaceRegion(
            centerX = median(regions.map { it.centerX }),
            centerY = median(regions.map { it.centerY }),
            width = median(regions.map { it.width }),
            height = median(regions.map { it.height }),
            rollDegrees = circularMeanDegrees(regions.map { it.rollDegrees })
        )
    }

    private fun circularMeanDegrees(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val radians = values.map { it * kotlin.math.PI / 180.0 }
        val x = radians.sumOf { cos(it) }
        val y = radians.sumOf { sin(it) }
        return normalizeAngleDegrees((atan2(y, x) * 180.0 / kotlin.math.PI).toFloat())
    }

    private companion object {
        val LIP_CONTOURS = listOf(
            FaceContour.UPPER_LIP_TOP,
            FaceContour.UPPER_LIP_BOTTOM,
            FaceContour.LOWER_LIP_TOP,
            FaceContour.LOWER_LIP_BOTTOM
        )
        const val DETECTION_MAX_DIMENSION = 1_080
        const val MIN_TRACK_INTERVAL_US = 100_000L
        const val MAX_TRACK_SAMPLES = 720
        const val MAX_CENTER_JUMP = 0.20f
        const val MAX_SIZE_RATIO = 1.95f
        const val MAX_FACE_CENTER_JUMP = 0.28f
        const val MAX_FACE_SIZE_RATIO = 1.85f
        const val FACE_WIDTH_SCALE = 1.00f
        const val FACE_HEIGHT_SCALE = 1.08f
        const val FACE_CENTER_Y_SHIFT = 0.020f
    }
}
