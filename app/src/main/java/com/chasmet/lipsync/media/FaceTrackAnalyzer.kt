package com.chasmet.lipsync.media

import android.graphics.Bitmap
import android.graphics.PointF
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
import kotlin.math.max
import kotlin.math.min

class FaceTrackAnalyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()
    )

    suspend fun analyze(videoFile: File): MouthRegion {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L

            val sampleTimesUs = buildList {
                add(0L)
                add(500_000L)
                add(1_000_000L)
                if (durationMs > 2_000L) add(2_000_000L)
                if (durationMs > 5_000L) add(5_000_000L)
            }.distinct()

            val regions = mutableListOf<MouthRegion>()
            for (timeUs in sampleTimesUs) {
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                val scaled = scaleForDetection(bitmap)
                val face = detectLargestFace(scaled)
                if (face != null) {
                    regions += mouthRegion(face, scaled.width, scaled.height)
                }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }

            if (regions.isEmpty()) {
                throw IllegalStateException(
                    "Aucun visage détecté. Utilise une vidéo frontale, nette et bien éclairée."
                )
            }

            medianRegion(regions)
        } finally {
            runCatching { retriever.release() }
            detector.close()
        }
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
        if (maxDimension <= 1_280) return source
        val ratio = 1_280f / maxDimension.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
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
            width = (mouthWidthPx / width * 1.65f).coerceIn(0.08f, 0.45f),
            height = (mouthHeightPx / height * 1.75f).coerceIn(0.04f, 0.26f)
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
}
