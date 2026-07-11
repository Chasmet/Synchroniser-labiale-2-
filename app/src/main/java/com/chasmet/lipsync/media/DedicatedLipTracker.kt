package com.chasmet.lipsync.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Point 2D indépendant d'Android afin de tester le traqueur sur la JVM. */
internal data class LipPoint(
    val x: Float,
    val y: Float
)

/** Rectangle du visage dans l'image de détection, origine en haut à gauche. */
internal data class FaceBoundsPx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

internal enum class LipTrackingSource {
    CONTOUR,
    LANDMARKS,
    PREDICTION,
    FACE_FALLBACK
}

internal data class LipTrackingResult(
    val region: MouthRegion,
    val confidence: Float,
    val source: LipTrackingSource,
    val reliable: Boolean
)

/**
 * Traqueur labial spécialisé.
 *
 * Il combine le contour supérieur et inférieur des lèvres, les trois repères
 * classiques de ML Kit, une validation géométrique dans le bas du visage et
 * une prédiction temporelle à vitesse constante. Une observation douteuse
 * n'est jamais utilisée directement : le traqueur conserve brièvement la
 * dernière position fiable, puis revient sur une zone prudente du visage.
 */
internal class DedicatedLipTracker {

    private data class Candidate(
        val region: MouthRegion,
        val score: Float,
        val source: LipTrackingSource
    )

    private var lastAccepted: MouthRegion? = null
    private var lastAcceptedTimeUs: Long = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var velocityWidth = 0f
    private var velocityHeight = 0f
    private var missingObservations = 0

    fun update(
        contourPoints: List<LipPoint>,
        landmarkPoints: List<LipPoint>,
        faceBounds: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int,
        timeUs: Long
    ): LipTrackingResult {
        require(imageWidth > 0 && imageHeight > 0)

        val contourCandidate = candidateFromContour(
            points = contourPoints,
            face = faceBounds,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        val landmarkCandidate = candidateFromLandmarks(
            points = landmarkPoints,
            face = faceBounds,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )

        val candidate = listOfNotNull(contourCandidate, landmarkCandidate)
            .maxByOrNull { it.score }
            ?.let { it.copy(score = it.score * continuityScore(it.region, faceBounds, imageWidth, imageHeight, timeUs)) }

        val threshold = when (candidate?.source) {
            LipTrackingSource.CONTOUR -> MIN_CONTOUR_SCORE
            LipTrackingSource.LANDMARKS -> MIN_LANDMARK_SCORE
            else -> 1f
        }

        if (candidate != null && candidate.score >= threshold) {
            return accept(candidate, faceBounds, imageWidth, imageHeight, timeUs)
        }

        return predictOrFallback(faceBounds, imageWidth, imageHeight, timeUs)
    }

    fun reset() {
        lastAccepted = null
        lastAcceptedTimeUs = 0L
        velocityX = 0f
        velocityY = 0f
        velocityWidth = 0f
        velocityHeight = 0f
        missingObservations = 0
    }

    private fun candidateFromContour(
        points: List<LipPoint>,
        face: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int
    ): Candidate? {
        val valid = points.filter { point ->
            point.x.isFinite() && point.y.isFinite() &&
                point.x in (face.left - face.width * 0.06f)..(face.right + face.width * 0.06f) &&
                point.y in (face.top + face.height * 0.42f)..(face.bottom + face.height * 0.04f)
        }
        if (valid.size < MIN_CONTOUR_POINTS) return null

        val minX = valid.minOf { it.x }
        val maxX = valid.maxOf { it.x }
        val minY = valid.minOf { it.y }
        val maxY = valid.maxOf { it.y }
        val rawWidth = maxX - minX
        val rawHeight = maxY - minY
        if (rawWidth < 6f || rawHeight < 2f) return null

        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val widthRatio = rawWidth / face.width
        val heightRatio = rawHeight / face.height
        val verticalRatio = (centerY - face.top) / face.height
        val horizontalRatio = abs(centerX - face.centerX) / (face.width * 0.5f)

        if (widthRatio !in 0.12f..0.74f) return null
        if (heightRatio !in 0.018f..0.34f) return null
        if (verticalRatio !in 0.50f..0.94f) return null
        if (horizontalRatio > 0.82f) return null

        val pointScore = (valid.size / IDEAL_CONTOUR_POINTS.toFloat()).coerceIn(0f, 1f)
        val verticalScore = centeredScore(verticalRatio, 0.73f, 0.25f)
        val horizontalScore = (1f - horizontalRatio).coerceIn(0f, 1f)
        val widthScore = centeredScore(widthRatio, 0.38f, 0.28f)
        val heightScore = centeredScore(heightRatio, 0.11f, 0.16f)
        val score = (
            pointScore * 0.25f +
                verticalScore * 0.25f +
                horizontalScore * 0.16f +
                widthScore * 0.20f +
                heightScore * 0.14f
            ).coerceIn(0f, 1f)

        return Candidate(
            region = regionFromPixels(
                centerX = centerX,
                centerY = centerY,
                widthPx = min(rawWidth * 1.34f, face.width * 0.66f),
                heightPx = min(max(rawHeight * 1.70f, face.height * 0.075f), face.height * 0.29f),
                imageWidth = imageWidth,
                imageHeight = imageHeight
            ),
            score = score,
            source = LipTrackingSource.CONTOUR
        )
    }

    private fun candidateFromLandmarks(
        points: List<LipPoint>,
        face: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int
    ): Candidate? {
        if (points.size < 3) return null
        val left = points[0]
        val right = points[1]
        val bottom = points[2]
        if (!listOf(left, right, bottom).all { it.x.isFinite() && it.y.isFinite() }) return null

        val minX = min(left.x, right.x)
        val maxX = max(left.x, right.x)
        val topY = min(left.y, right.y)
        val rawWidth = maxX - minX
        val rawHeight = (bottom.y - topY).coerceAtLeast(1f)
        val centerX = (left.x + right.x) * 0.5f
        val centerY = ((left.y + right.y) * 0.5f + bottom.y) * 0.5f
        val widthRatio = rawWidth / face.width
        val verticalRatio = (centerY - face.top) / face.height

        if (rawWidth < 8f || widthRatio !in 0.12f..0.75f) return null
        if (verticalRatio !in 0.50f..0.94f) return null

        val symmetry = (1f - abs(left.y - right.y) / (face.height * 0.20f)).coerceIn(0f, 1f)
        val verticalScore = centeredScore(verticalRatio, 0.73f, 0.27f)
        val widthScore = centeredScore(widthRatio, 0.38f, 0.30f)
        val score = (symmetry * 0.30f + verticalScore * 0.38f + widthScore * 0.32f)
            .coerceIn(0f, LANDMARK_MAX_SCORE)

        return Candidate(
            region = regionFromPixels(
                centerX = centerX,
                centerY = centerY,
                widthPx = min(rawWidth * 1.32f, face.width * 0.66f),
                heightPx = min(max(rawHeight * 2.15f, face.height * 0.10f), face.height * 0.28f),
                imageWidth = imageWidth,
                imageHeight = imageHeight
            ),
            score = score,
            source = LipTrackingSource.LANDMARKS
        )
    }

    private fun continuityScore(
        candidate: MouthRegion,
        face: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int,
        timeUs: Long
    ): Float {
        val previous = lastAccepted ?: return 1f
        val predicted = predictRegion(previous, timeUs)
        val faceWidthNormalized = face.width / imageWidth.toFloat().coerceAtLeast(1f)
        val faceHeightNormalized = face.height / imageHeight.toFloat().coerceAtLeast(1f)
        val dx = abs(candidate.centerX - predicted.centerX) / faceWidthNormalized.coerceAtLeast(0.001f)
        val dy = abs(candidate.centerY - predicted.centerY) / faceHeightNormalized.coerceAtLeast(0.001f)
        val sizeChange = max(
            ratio(candidate.width, predicted.width),
            ratio(candidate.height, predicted.height)
        )

        if (dx > 0.48f || dy > 0.48f || sizeChange > 2.05f) return 0.12f
        val motionScore = (1f - max(dx, dy) / 0.48f).coerceIn(0f, 1f)
        val sizeScore = (1f - (sizeChange - 1f) / 1.05f).coerceIn(0f, 1f)
        return (0.56f + motionScore * 0.30f + sizeScore * 0.14f).coerceIn(0f, 1f)
    }

    private fun accept(
        candidate: Candidate,
        face: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int,
        timeUs: Long
    ): LipTrackingResult {
        val previous = lastAccepted
        val filtered = if (previous == null) {
            candidate.region
        } else {
            val predicted = predictRegion(previous, timeUs)
            val alpha = (0.38f + candidate.score * 0.42f).coerceIn(0.42f, 0.80f)
            predicted.interpolate(candidate.region, alpha)
        }
        val safe = clampToFace(filtered, face, imageWidth, imageHeight)

        if (previous != null && lastAcceptedTimeUs > 0L) {
            val dt = ((timeUs - lastAcceptedTimeUs).coerceAtLeast(1L) / 1_000_000f)
                .coerceIn(0.02f, 1.2f)
            val blend = 0.44f
            velocityX = mix(velocityX, (safe.centerX - previous.centerX) / dt, blend)
            velocityY = mix(velocityY, (safe.centerY - previous.centerY) / dt, blend)
            velocityWidth = mix(velocityWidth, (safe.width - previous.width) / dt, blend)
            velocityHeight = mix(velocityHeight, (safe.height - previous.height) / dt, blend)
        }

        lastAccepted = safe
        lastAcceptedTimeUs = timeUs
        missingObservations = 0
        return LipTrackingResult(
            region = safe,
            confidence = candidate.score.coerceIn(0f, 1f),
            source = candidate.source,
            reliable = true
        )
    }

    private fun predictOrFallback(
        face: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int,
        timeUs: Long
    ): LipTrackingResult {
        val previous = lastAccepted
        if (previous != null && missingObservations < MAX_PREDICTED_OBSERVATIONS) {
            missingObservations++
            val predicted = clampToFace(
                predictRegion(previous, timeUs),
                face,
                imageWidth,
                imageHeight
            )
            val confidence = (0.55f * pow(PREDICTION_DECAY, missingObservations))
                .coerceIn(0.12f, 0.55f)
            return LipTrackingResult(
                region = predicted,
                confidence = confidence,
                source = LipTrackingSource.PREDICTION,
                reliable = confidence >= 0.24f
            )
        }

        missingObservations++
        velocityX *= 0.35f
        velocityY *= 0.35f
        velocityWidth *= 0.35f
        velocityHeight *= 0.35f
        return LipTrackingResult(
            region = fallbackRegion(face, imageWidth, imageHeight),
            confidence = 0.12f,
            source = LipTrackingSource.FACE_FALLBACK,
            reliable = false
        )
    }

    private fun predictRegion(previous: MouthRegion, timeUs: Long): MouthRegion {
        if (lastAcceptedTimeUs <= 0L) return previous
        val dt = ((timeUs - lastAcceptedTimeUs).coerceAtLeast(0L) / 1_000_000f)
            .coerceIn(0f, MAX_PREDICTION_SECONDS)
        return previous.copy(
            centerX = previous.centerX + velocityX * dt,
            centerY = previous.centerY + velocityY * dt,
            width = previous.width + velocityWidth * dt,
            height = previous.height + velocityHeight * dt
        )
    }

    private fun fallbackRegion(
        face: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int
    ): MouthRegion {
        return regionFromPixels(
            centerX = face.centerX,
            centerY = face.top + face.height * 0.73f,
            widthPx = face.width * 0.38f,
            heightPx = face.height * 0.14f,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    private fun clampToFace(
        region: MouthRegion,
        face: FaceBoundsPx,
        imageWidth: Int,
        imageHeight: Int
    ): MouthRegion {
        val faceLeft = face.left / imageWidth.toFloat()
        val faceRight = face.right / imageWidth.toFloat()
        val faceBottom = 1f - face.bottom / imageHeight.toFloat()
        val faceTop = 1f - face.top / imageHeight.toFloat()
        val maxWidth = (faceRight - faceLeft) * 0.68f
        val maxHeight = (faceTop - faceBottom) * 0.32f
        return region.copy(
            centerX = region.centerX.coerceIn(
                faceLeft + (faceRight - faceLeft) * 0.12f,
                faceRight - (faceRight - faceLeft) * 0.12f
            ),
            centerY = region.centerY.coerceIn(
                faceBottom + (faceTop - faceBottom) * 0.05f,
                faceTop - (faceTop - faceBottom) * 0.44f
            ),
            width = region.width.coerceIn(0.035f, maxWidth.coerceAtLeast(0.035f)),
            height = region.height.coerceIn(0.025f, maxHeight.coerceAtLeast(0.025f))
        )
    }

    private fun regionFromPixels(
        centerX: Float,
        centerY: Float,
        widthPx: Float,
        heightPx: Float,
        imageWidth: Int,
        imageHeight: Int
    ): MouthRegion {
        return MouthRegion(
            centerX = (centerX / imageWidth.toFloat()).coerceIn(0.02f, 0.98f),
            centerY = (1f - centerY / imageHeight.toFloat()).coerceIn(0.02f, 0.98f),
            width = (widthPx / imageWidth.toFloat()).coerceIn(0.035f, 0.48f),
            height = (heightPx / imageHeight.toFloat()).coerceIn(0.025f, 0.32f)
        )
    }

    private fun centeredScore(value: Float, ideal: Float, tolerance: Float): Float {
        return (1f - abs(value - ideal) / tolerance.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    }

    private fun ratio(first: Float, second: Float): Float {
        val minimum = min(first, second).coerceAtLeast(0.0001f)
        return max(first, second) / minimum
    }

    private fun mix(start: Float, end: Float, amount: Float): Float {
        val t = amount.coerceIn(0f, 1f)
        return start + (end - start) * t
    }

    private fun pow(value: Float, exponent: Int): Float {
        var result = 1f
        repeat(exponent.coerceAtLeast(0)) { result *= value }
        return result
    }

    private companion object {
        const val MIN_CONTOUR_POINTS = 12
        const val IDEAL_CONTOUR_POINTS = 32
        const val MIN_CONTOUR_SCORE = 0.48f
        const val MIN_LANDMARK_SCORE = 0.40f
        const val LANDMARK_MAX_SCORE = 0.74f
        const val MAX_PREDICTED_OBSERVATIONS = 5
        const val PREDICTION_DECAY = 0.76f
        const val MAX_PREDICTION_SECONDS = 0.42f
    }
}
