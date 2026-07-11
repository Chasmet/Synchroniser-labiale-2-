package com.chasmet.lipsync.media

import android.net.Uri
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class SelectedMedia(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long
)

enum class OutputAspectRatio(
    val label: String,
    val outputWidth: Int,
    val outputHeight: Int
) {
    PORTRAIT_9_16("9:16", 720, 1280),
    LANDSCAPE_16_9("16:9", 1280, 720)
}

data class MouthRegion(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
) {
    fun interpolate(other: MouthRegion, amount: Float): MouthRegion {
        val t = amount.coerceIn(0f, 1f)
        fun mix(start: Float, end: Float): Float = start + (end - start) * t
        return MouthRegion(
            centerX = mix(centerX, other.centerX),
            centerY = mix(centerY, other.centerY),
            width = mix(width, other.width),
            height = mix(height, other.height)
        )
    }

    companion object {
        val DEFAULT = MouthRegion(
            centerX = 0.5f,
            centerY = 0.36f,
            width = 0.24f,
            height = 0.10f
        )
    }
}

data class MouthKeyframe(
    val timeUs: Long,
    val region: MouthRegion,
    val confidence: Float = 1f
)

/**
 * Rectangle du visage suivi, exprimé dans les coordonnées normalisées de la
 * trame décodée (origine en bas à gauche). Le moteur génératif utilise toute
 * cette zone comme contexte et ne remplace ensuite que la partie labiale.
 */
data class FaceRegion(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rollDegrees: Float = 0f
) {
    fun interpolate(other: FaceRegion, amount: Float): FaceRegion {
        val t = amount.coerceIn(0f, 1f)
        fun mix(start: Float, end: Float): Float = start + (end - start) * t
        return FaceRegion(
            centerX = mix(centerX, other.centerX),
            centerY = mix(centerY, other.centerY),
            width = mix(width, other.width),
            height = mix(height, other.height),
            rollDegrees = interpolateAngleDegrees(rollDegrees, other.rollDegrees, t)
        )
    }

    companion object {
        val DEFAULT = FaceRegion(
            centerX = 0.5f,
            centerY = 0.48f,
            width = 0.42f,
            height = 0.54f,
            rollDegrees = 0f
        )
    }
}

/** Zone labiale exprimée dans le carré 256 × 256 du visage redressé. */
data class CanonicalMouthRegion(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float
) {
    companion object {
        val DEFAULT = CanonicalMouthRegion(0.5f, 0.31f, 0.20f, 0.09f)
    }
}

/**
 * Convertit la bouche réelle vers le repère du crop envoyé à Wav2Lip. Les
 * dimensions de MouthRegion sont des diamètres complets : elles ne doivent pas
 * être réutilisées comme rayons, faute de quoi le masque déborde sur le visage.
 */
internal fun MouthRegion.inCanonicalFace(
    face: FaceRegion,
    sourceAspect: Float
): CanonicalMouthRegion {
    val safeAspect = sourceAspect.coerceIn(0.25f, 4f)
    val safeFaceWidth = face.width.coerceAtLeast(0.001f)
    val safeFaceHeight = face.height.coerceAtLeast(0.001f)
    val radians = -face.rollDegrees * (PI.toFloat() / 180f)
    val c = cos(radians)
    val s = sin(radians)
    val metricX = (centerX - face.centerX) * safeAspect
    val metricY = centerY - face.centerY
    val canonicalX = c * metricX - s * metricY
    val canonicalY = s * metricX + c * metricY

    return CanonicalMouthRegion(
        centerX = (0.5f + canonicalX / (safeFaceWidth * safeAspect))
            .coerceIn(0.12f, 0.88f),
        centerY = (0.5f + canonicalY / safeFaceHeight).coerceIn(0.10f, 0.82f),
        radiusX = (width * 0.52f / safeFaceWidth).coerceIn(0.075f, 0.34f),
        radiusY = (height * 0.48f / safeFaceHeight).coerceIn(0.035f, 0.22f)
    )
}

data class FaceKeyframe(
    val timeUs: Long,
    val region: FaceRegion,
    val confidence: Float = 1f
)

data class FaceTrack(
    val keyframes: List<FaceKeyframe>,
    val fallback: FaceRegion
) {
    init {
        require(keyframes.zipWithNext().all { (first, second) ->
            first.timeUs <= second.timeUs
        }) { "Les repères de visage doivent être triés" }
    }

    fun regionAt(timeUs: Long): FaceRegion {
        if (keyframes.isEmpty()) return fallback
        if (timeUs <= keyframes.first().timeUs) return keyframes.first().region
        if (timeUs >= keyframes.last().timeUs) return keyframes.last().region

        var low = 0
        var high = keyframes.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                keyframes[middle].timeUs < timeUs -> low = middle + 1
                keyframes[middle].timeUs > timeUs -> high = middle - 1
                else -> return keyframes[middle].region
            }
        }

        val before = keyframes[high.coerceIn(0, keyframes.lastIndex)]
        val after = keyframes[low.coerceIn(0, keyframes.lastIndex)]
        val span = (after.timeUs - before.timeUs).coerceAtLeast(1L)
        val amount = (timeUs - before.timeUs).toFloat() / span.toFloat()
        return before.region.interpolate(after.region, amount)
    }

    fun confidenceAt(timeUs: Long): Float {
        if (keyframes.isEmpty()) return 0f
        val nearest = keyframes.minByOrNull { kotlin.math.abs(it.timeUs - timeUs) }
        return nearest?.confidence?.coerceIn(0f, 1f) ?: 0f
    }
}

data class FaceAnalysis(
    val mouthTrack: MouthTrack,
    val faceTrack: FaceTrack
) {
    val detectionCount: Int
        get() = minOf(mouthTrack.detectionCount, faceTrack.keyframes.size)
}

/**
 * Position de la bouche suivie dans toute la vidéo.
 * Les images intermédiaires sont interpolées pour éviter les sauts visibles.
 */
data class MouthTrack(
    val keyframes: List<MouthKeyframe>,
    val fallback: MouthRegion
) {
    init {
        require(keyframes.zipWithNext().all { (first, second) ->
            first.timeUs <= second.timeUs
        }) { "Les repères de bouche doivent être triés" }
    }

    val detectionCount: Int
        get() = keyframes.size

    fun regionAt(timeUs: Long): MouthRegion {
        if (keyframes.isEmpty()) return fallback
        if (timeUs <= keyframes.first().timeUs) return keyframes.first().region
        if (timeUs >= keyframes.last().timeUs) return keyframes.last().region

        var low = 0
        var high = keyframes.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                keyframes[middle].timeUs < timeUs -> low = middle + 1
                keyframes[middle].timeUs > timeUs -> high = middle - 1
                else -> return keyframes[middle].region
            }
        }

        val before = keyframes[high.coerceIn(0, keyframes.lastIndex)]
        val after = keyframes[low.coerceIn(0, keyframes.lastIndex)]
        val span = (after.timeUs - before.timeUs).coerceAtLeast(1L)
        val amount = (timeUs - before.timeUs).toFloat() / span.toFloat()
        return before.region.interpolate(after.region, amount)
    }
}

/**
 * Convertit une zone repérée sur l'image affichée vers les coordonnées de la
 * trame réellement décodée. Les coordonnées utilisent une origine en bas à gauche.
 */
internal fun MouthRegion.displayToEncoded(rotationDegrees: Int): MouthRegion {
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val converted = when (normalizedRotation) {
        90 -> MouthRegion(
            centerX = 1f - centerY,
            centerY = centerX,
            width = height,
            height = width
        )
        180 -> MouthRegion(
            centerX = 1f - centerX,
            centerY = 1f - centerY,
            width = width,
            height = height
        )
        270 -> MouthRegion(
            centerX = centerY,
            centerY = 1f - centerX,
            width = height,
            height = width
        )
        else -> this
    }

    return converted.copy(
        centerX = converted.centerX.coerceIn(0.02f, 0.98f),
        centerY = converted.centerY.coerceIn(0.02f, 0.98f),
        width = converted.width.coerceIn(0.035f, 0.48f),
        height = converted.height.coerceIn(0.025f, 0.32f)
    )
}

internal fun FaceRegion.displayToEncoded(rotationDegrees: Int): FaceRegion {
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val converted = when (normalizedRotation) {
        90 -> FaceRegion(
            centerX = 1f - centerY,
            centerY = centerX,
            width = height,
            height = width,
            rollDegrees = normalizeAngleDegrees(rollDegrees + 90f)
        )
        180 -> FaceRegion(
            centerX = 1f - centerX,
            centerY = 1f - centerY,
            width = width,
            height = height,
            rollDegrees = normalizeAngleDegrees(rollDegrees + 180f)
        )
        270 -> FaceRegion(
            centerX = centerY,
            centerY = 1f - centerX,
            width = height,
            height = width,
            rollDegrees = normalizeAngleDegrees(rollDegrees - 90f)
        )
        else -> this
    }

    return converted.copy(
        centerX = converted.centerX.coerceIn(0.01f, 0.99f),
        centerY = converted.centerY.coerceIn(0.01f, 0.99f),
        width = converted.width.coerceIn(0.12f, 0.96f),
        height = converted.height.coerceIn(0.15f, 0.98f)
    )
}

internal fun normalizeAngleDegrees(value: Float): Float {
    var normalized = value % 360f
    if (normalized > 180f) normalized -= 360f
    if (normalized <= -180f) normalized += 360f
    return normalized
}

internal fun interpolateAngleDegrees(start: Float, end: Float, amount: Float): Float {
    val delta = normalizeAngleDegrees(end - start)
    return normalizeAngleDegrees(start + delta * amount.coerceIn(0f, 1f))
}

data class VisemeFrame(
    val timeUs: Long,
    val openness: Float,
    val width: Float,
    val roundness: Float,
    val closure: Float = 0f
) {
    fun interpolate(other: VisemeFrame, targetTimeUs: Long): VisemeFrame {
        val span = (other.timeUs - timeUs).coerceAtLeast(1L)
        val amount = ((targetTimeUs - timeUs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        fun mix(start: Float, end: Float): Float = start + (end - start) * amount
        return VisemeFrame(
            timeUs = targetTimeUs,
            openness = mix(openness, other.openness),
            width = mix(width, other.width),
            roundness = mix(roundness, other.roundness),
            closure = mix(closure, other.closure)
        )
    }
}

data class VisemeTimeline(
    val frames: List<VisemeFrame>,
    val durationUs: Long
) {
    fun frameAt(timeUs: Long): VisemeFrame {
        if (frames.isEmpty()) return VisemeFrame(timeUs, 0f, 0f, 0f, 1f)
        if (timeUs <= frames.first().timeUs) return frames.first().copy(timeUs = timeUs)
        if (timeUs >= frames.last().timeUs) return frames.last().copy(timeUs = timeUs)

        var low = 0
        var high = frames.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                frames[middle].timeUs < timeUs -> low = middle + 1
                frames[middle].timeUs > timeUs -> high = middle - 1
                else -> return frames[middle]
            }
        }

        val before = frames[high.coerceIn(0, frames.lastIndex)]
        val after = frames[low.coerceIn(0, frames.lastIndex)]
        return before.interpolate(after, timeUs)
    }
}

enum class ProcessingStage(val label: String) {
    IDLE("Prêt"),
    PREPARING("Préparation des fichiers"),
    FACE_ANALYSIS("Suivi intelligent du visage"),
    AUDIO_ANALYSIS("Analyse audio temporelle"),
    VIDEO_RENDER("Synchronisation des lèvres"),
    AUDIO_TRANSCODE("Préparation du MP3"),
    ASSEMBLY("Assemblage final"),
    EXPORT("Enregistrement"),
    DONE("Terminé"),
    ERROR("Erreur")
}

data class ProcessingStatus(
    val stage: ProcessingStage = ProcessingStage.IDLE,
    val progress: Float = 0f,
    val message: String = "",
    val currentBlock: Int = 0,
    val totalBlocks: Int = 0
)
