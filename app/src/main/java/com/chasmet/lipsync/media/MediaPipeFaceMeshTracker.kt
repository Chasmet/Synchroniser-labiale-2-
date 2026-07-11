package com.chasmet.lipsync.media

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class FaceMeshDetection(
    val mouth: MouthRegion,
    val face: FaceRegion,
    val confidence: Float,
    val landmarkCount: Int
)

/**
 * Traqueur principal à 478 points. Le modèle est exécuté en mode VIDEO : le
 * lissage et la continuité temporelle de MediaPipe complètent notre filtre de
 * trajectoire, ce qui stabilise les lèvres, le sourire et le roulis du visage.
 */
internal class MediaPipeFaceMeshTracker(context: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.45f)
            .setMinFacePresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
            .setOutputFaceBlendshapes(false)
            .build()
    )

    fun detect(bitmap: Bitmap, timeUs: Long): FaceMeshDetection? {
        val image = BitmapImageBuilder(bitmap).build()
        return try {
            detectionFrom(
                landmarker.detectForVideo(image, (timeUs / 1_000L).coerceAtLeast(0L)),
                bitmap.width,
                bitmap.height
            )
        } finally {
            image.close()
        }
    }

    private fun detectionFrom(
        result: FaceLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ): FaceMeshDetection? {
        val landmarks = result.faceLandmarks().firstOrNull() ?: return null
        if (landmarks.size < MIN_LANDMARKS) return null
        val aspect = imageWidth.toFloat() / imageHeight.coerceAtLeast(1).toFloat()

        val leftCorner = landmarks[MOUTH_LEFT]
        val rightCorner = landmarks[MOUTH_RIGHT]
        val cornerDx = (rightCorner.x() - leftCorner.x()) * aspect
        val cornerDy = -(rightCorner.y() - leftCorner.y())
        val roll = atan2(cornerDy, cornerDx) * 180f / PI.toFloat()
        val radians = -roll * PI.toFloat() / 180f
        val c = cos(radians)
        val s = sin(radians)

        fun canonicalPoint(index: Int, originX: Float, originY: Float): Pair<Float, Float> {
            val point = landmarks[index]
            val x = (point.x() - originX) * aspect
            val y = (1f - point.y()) - originY
            return Pair(c * x - s * y, s * x + c * y)
        }

        val mouthCenterX = LIP_INDICES.map { landmarks[it].x() }.average().toFloat()
        val mouthCenterY = LIP_INDICES.map { 1f - landmarks[it].y() }.average().toFloat()
        val canonicalLips = LIP_INDICES.map { canonicalPoint(it, mouthCenterX, mouthCenterY) }
        val mouthWidth = (
            (canonicalLips.maxOf { it.first } - canonicalLips.minOf { it.first }) /
                aspect.coerceAtLeast(0.001f) * MOUTH_WIDTH_SCALE
            ).coerceIn(0.035f, 0.48f)
        val mouthHeight = (
            (canonicalLips.maxOf { it.second } - canonicalLips.minOf { it.second }) *
                MOUTH_HEIGHT_SCALE
            ).coerceIn(0.025f, 0.32f)

        val leftFace = landmarks[FACE_LEFT]
        val rightFace = landmarks[FACE_RIGHT]
        val topFace = landmarks[FACE_TOP]
        val chinFace = landmarks[FACE_CHIN]
        val faceCenterX = (leftFace.x() + rightFace.x()) * 0.5f
        val faceCenterY = 1f - (topFace.y() + chinFace.y()) * 0.5f
        val faceWidthMetric = hypot(
            (rightFace.x() - leftFace.x()) * aspect,
            rightFace.y() - leftFace.y()
        )
        val faceHeightMetric = hypot(
            (chinFace.x() - topFace.x()) * aspect,
            chinFace.y() - topFace.y()
        )
        val faceWidth = (faceWidthMetric / aspect.coerceAtLeast(0.001f) * FACE_WIDTH_SCALE)
            .coerceIn(0.12f, 0.96f)
        val faceHeight = (faceHeightMetric * FACE_HEIGHT_SCALE).coerceIn(0.15f, 0.98f)

        return FaceMeshDetection(
            mouth = MouthRegion(
                centerX = mouthCenterX.coerceIn(0.02f, 0.98f),
                centerY = mouthCenterY.coerceIn(0.02f, 0.98f),
                width = mouthWidth,
                height = mouthHeight
            ),
            face = FaceRegion(
                centerX = faceCenterX.coerceIn(0.01f, 0.99f),
                centerY = faceCenterY.coerceIn(0.01f, 0.99f),
                width = faceWidth,
                height = faceHeight,
                rollDegrees = normalizeAngleDegrees(roll)
            ),
            confidence = (0.88f + (landmarks.size / 478f).coerceIn(0f, 1f) * 0.11f)
                .coerceIn(0f, 0.99f),
            landmarkCount = landmarks.size
        )
    }

    override fun close() {
        landmarker.close()
    }

    companion object {
        const val MODEL_ASSET = "face_landmarker.task"
        const val MODEL_SIZE_BYTES = 3_758_596L
        const val MODEL_SHA256 =
            "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"

        private const val MIN_LANDMARKS = 468
        private const val MOUTH_LEFT = 61
        private const val MOUTH_RIGHT = 291
        private const val FACE_TOP = 10
        private const val FACE_CHIN = 152
        private const val FACE_LEFT = 234
        private const val FACE_RIGHT = 454
        private const val MOUTH_WIDTH_SCALE = 1.24f
        private const val MOUTH_HEIGHT_SCALE = 1.58f
        private const val FACE_WIDTH_SCALE = 1.08f
        private const val FACE_HEIGHT_SCALE = 1.12f

        private val LIP_INDICES = intArrayOf(
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291,
            308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78,
            191, 80, 81, 82, 13, 312, 311, 310, 415
        )
    }
}
