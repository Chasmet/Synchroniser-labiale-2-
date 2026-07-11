package com.chasmet.lipsync.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max


data class GeneratedFace(
    val rgba: ByteBuffer,
    val audioActivity: Float,
    val canonicalMouth: CanonicalMouthRegion = CanonicalMouthRegion.DEFAULT,
    val quality: GeneratedQualityMetrics = GeneratedQualityMetrics()
)

/**
 * Exécute le générateur Wav2Lip 256×256 directement dans l'APK. Le graphe est
 * mappé depuis l'asset non compressé afin d'éviter une seconde copie de 205 Mio.
 * NNAPI puis XNNPACK sont proposés à ONNX Runtime, avec repli CPU automatique.
 */
class Wav2LipEngine(context: Context) : AutoCloseable {

    private val environment = OrtEnvironment.getEnvironment("lipsync-generative-v8")
    private val options = OrtSession.SessionOptions()
    private val assetDescriptor = context.assets.openFd(MODEL_ASSET)
    private val assetStream = FileInputStream(assetDescriptor.fileDescriptor)
    private val modelBuffer = assetStream.channel.map(
        FileChannel.MapMode.READ_ONLY,
        assetDescriptor.startOffset,
        assetDescriptor.length
    )
    private val session: OrtSession
    private val videoBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VIDEO_FLOATS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val melBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MEL_FLOATS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val videoTensor: OnnxTensor
    private val melTensor: OnnxTensor
    private val rgbaOutput = ByteBuffer
        .allocateDirect(IMAGE_SIZE * IMAGE_SIZE * RGBA_CHANNELS)
        .order(ByteOrder.nativeOrder())
    private var previousPrediction: FloatArray? = null
    private var previousMel: FloatArray? = null
    private val qualityGuard = GeneratedMouthQualityGuard(
        DentalAppearanceProfile.load(context.applicationContext)
    )

    init {
        check(assetDescriptor.length == MODEL_SIZE_BYTES) {
            "Modèle génératif incomplet (${assetDescriptor.length} octets)"
        }
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setMemoryPatternOptimization(true)
        options.setCPUArenaAllocator(true)
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        options.setIntraOpNumThreads(threads)
        options.setInterOpNumThreads(1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                options.addNnapi(
                    EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.USE_NCHW)
                )
            }
        }
        runCatching {
            options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        }

        session = environment.createSession(modelBuffer, options)
        check(session.inputNames.containsAll(setOf(MEL_INPUT, VIDEO_INPUT))) {
            "Le modèle génératif n'a pas les entrées Wav2Lip attendues"
        }
        check(session.outputNames.contains(FRAME_OUTPUT)) {
            "Le modèle génératif n'a pas la sortie Wav2Lip attendue"
        }
        videoTensor = OnnxTensor.createTensor(
            environment,
            videoBuffer,
            longArrayOf(1, VIDEO_CHANNELS.toLong(), IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        )
        melTensor = OnnxTensor.createTensor(
            environment,
            melBuffer,
            longArrayOf(1, 1, MelTimeline.MEL_BANDS.toLong(), MelTimeline.MEL_STEP_SIZE.toLong())
        )
    }

    fun infer(
        sourceRgbaBottomUp: ByteBuffer,
        melChunk: FloatArray,
        temporalStability: Float,
        canonicalMouth: CanonicalMouthRegion = CanonicalMouthRegion.DEFAULT
    ): GeneratedFace {
        require(sourceRgbaBottomUp.remaining() >= IMAGE_SIZE * IMAGE_SIZE * RGBA_CHANNELS)
        require(melChunk.size == MEL_FLOATS)

        packVideoInput(sourceRgbaBottomUp, videoBuffer)
        for (index in melChunk.indices) melBuffer.put(index, melChunk[index])

        val inputs = mapOf(
            MEL_INPUT to melTensor,
            VIDEO_INPUT to videoTensor
        )
        val rawPrediction = session.run(inputs, setOf(FRAME_OUTPUT)).use { result ->
            val tensor = result.get(FRAME_OUTPUT).orElseThrow {
                IllegalStateException("Sortie générative absente")
            } as OnnxTensor
            val floats = tensor.floatBuffer
                ?: throw IllegalStateException("Sortie générative non flottante")
            FloatArray(OUTPUT_FLOATS).also { floats.get(it) }
        }

        val audioActivity = melActivity(melChunk)
        val prediction = stabilizePrediction(
            current = rawPrediction,
            mel = melChunk,
            audioActivity = audioActivity,
            temporalStability = temporalStability
        )
        colorMatchLowerFace(sourceRgbaBottomUp, prediction)
        predictionToRgbaBottomUp(prediction, rgbaOutput)
        return qualityGuard.apply(
            sourceRgba = sourceRgbaBottomUp,
            generated = GeneratedFace(
                rgba = rgbaOutput.duplicate().apply { position(0) },
                audioActivity = audioActivity,
                canonicalMouth = canonicalMouth
            )
        )
    }

    fun resetTemporalState() {
        previousPrediction = null
        previousMel = null
        qualityGuard.reset()
    }

    internal fun packVideoInput(sourceRgbaBottomUp: ByteBuffer, target: FloatBuffer) {
        Wav2LipTensorCodec.packVideoInput(sourceRgbaBottomUp, target)
    }

    private fun stabilizePrediction(
        current: FloatArray,
        mel: FloatArray,
        audioActivity: Float,
        temporalStability: Float
    ): FloatArray {
        val previous = previousPrediction
        val oldMel = previousMel
        val melChange = if (oldMel == null) 1f else {
            mel.indices.sumOf { index -> abs(mel[index] - oldMel[index]).toDouble() }
                .div(mel.size * 8.0)
                .toFloat()
                .coerceIn(0f, 1f)
        }
        val stable = temporalStability.coerceIn(0f, 1f)
        val currentWeight = when {
            previous == null -> 1f
            audioActivity < 0.06f -> 0.94f
            melChange > 0.20f -> 0.92f
            else -> (0.78f + (1f - stable) * 0.16f).coerceIn(0.78f, 0.94f)
        }
        if (previous != null && currentWeight < 1f) {
            for (index in current.indices) {
                current[index] = sanitize(current[index]) * currentWeight +
                    sanitize(previous[index]) * (1f - currentWeight)
            }
        } else {
            for (index in current.indices) current[index] = sanitize(current[index])
        }
        previousPrediction = current.copyOf()
        previousMel = mel.copyOf()
        return current
    }

    private fun colorMatchLowerFace(sourceRgbaBottomUp: ByteBuffer, prediction: FloatArray) {
        val source = sourceRgbaBottomUp.duplicate().apply { position(0) }
        val plane = IMAGE_SIZE * IMAGE_SIZE
        val sourceMean = DoubleArray(3)
        val predictionMean = DoubleArray(3)
        var count = 0

        for (modelY in IMAGE_SIZE / 2 until IMAGE_SIZE * 9 / 10 step 3) {
            val sourceY = IMAGE_SIZE - 1 - modelY
            for (x in IMAGE_SIZE / 7 until IMAGE_SIZE * 6 / 7 step 3) {
                val sourcePixel = (sourceY * IMAGE_SIZE + x) * RGBA_CHANNELS
                val pixel = modelY * IMAGE_SIZE + x
                sourceMean[0] += (source.get(sourcePixel + 2).toInt() and 0xff) / 255.0
                sourceMean[1] += (source.get(sourcePixel + 1).toInt() and 0xff) / 255.0
                sourceMean[2] += (source.get(sourcePixel).toInt() and 0xff) / 255.0
                predictionMean[0] += sanitize(prediction[pixel])
                predictionMean[1] += sanitize(prediction[plane + pixel])
                predictionMean[2] += sanitize(prediction[plane * 2 + pixel])
                count++
            }
        }
        if (count == 0) return

        val offsets = FloatArray(3) { channel ->
            (((sourceMean[channel] - predictionMean[channel]) / count) * COLOR_MATCH_STRENGTH)
                .toFloat()
                .coerceIn(-MAX_COLOR_OFFSET, MAX_COLOR_OFFSET)
        }
        for (pixel in 0 until plane) {
            prediction[pixel] = (sanitize(prediction[pixel]) + offsets[0]).coerceIn(0f, 1f)
            prediction[plane + pixel] =
                (sanitize(prediction[plane + pixel]) + offsets[1]).coerceIn(0f, 1f)
            prediction[plane * 2 + pixel] =
                (sanitize(prediction[plane * 2 + pixel]) + offsets[2]).coerceIn(0f, 1f)
        }
    }

    internal fun predictionToRgbaBottomUp(prediction: FloatArray, output: ByteBuffer) {
        Wav2LipTensorCodec.predictionToRgbaBottomUp(prediction, output)
    }

    private fun melActivity(mel: FloatArray): Float {
        var peak = MelTimeline.SILENCE_VALUE
        var upperEnergy = 0f
        mel.forEach { value ->
            peak = max(peak, value)
            upperEnergy += ((value + 4f) / 8f).coerceIn(0f, 1f)
        }
        val mean = upperEnergy / mel.size.coerceAtLeast(1)
        return max(((peak + 3.65f) / 5.25f).coerceIn(0f, 1f), mean * 0.72f)
    }

    private fun sanitize(value: Float): Float {
        return if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }

    override fun close() {
        runCatching { melTensor.close() }
        runCatching { videoTensor.close() }
        runCatching { session.close() }
        runCatching { options.close() }
        runCatching { assetStream.close() }
        runCatching { assetDescriptor.close() }
    }

    companion object {
        const val MODEL_ASSET = "wav2lip_256.onnx"
        const val MODEL_SIZE_BYTES = 214_402_122L
        const val MODEL_SHA256 = "bfeb0ab1ef3097f456f6fdcd506d3b32ee8a42f762a6722b42d0f1ca5b64e83c"
        const val IMAGE_SIZE = 256
        private const val RGBA_CHANNELS = 4
        private const val VIDEO_CHANNELS = 6
        private const val VIDEO_FLOATS = VIDEO_CHANNELS * IMAGE_SIZE * IMAGE_SIZE
        private const val OUTPUT_FLOATS = 3 * IMAGE_SIZE * IMAGE_SIZE
        private const val MEL_FLOATS = MelTimeline.MEL_BANDS * MelTimeline.MEL_STEP_SIZE
        private const val MEL_INPUT = "mel_spectrogram"
        private const val VIDEO_INPUT = "video_frames"
        private const val FRAME_OUTPUT = "predicted_frames"
        private const val COLOR_MATCH_STRENGTH = 0.32
        private const val MAX_COLOR_OFFSET = 0.09f
    }
}

internal object Wav2LipTensorCodec {
    private const val CHANNELS_RGBA = 4

    fun packVideoInput(sourceRgbaBottomUp: ByteBuffer, target: FloatBuffer) {
        val source = sourceRgbaBottomUp.duplicate().apply { position(0) }
        val size = Wav2LipEngine.IMAGE_SIZE
        val plane = size * size
        require(source.remaining() >= plane * CHANNELS_RGBA)
        require(target.capacity() >= plane * 6)

        for (modelY in 0 until size) {
            val sourceY = size - 1 - modelY
            val isMasked = modelY >= size / 2
            for (x in 0 until size) {
                val pixel = modelY * size + x
                val sourcePixel = (sourceY * size + x) * CHANNELS_RGBA
                val red = (source.get(sourcePixel).toInt() and 0xff) / 255f
                val green = (source.get(sourcePixel + 1).toInt() and 0xff) / 255f
                val blue = (source.get(sourcePixel + 2).toInt() and 0xff) / 255f

                target.put(pixel, if (isMasked) 0f else blue)
                target.put(plane + pixel, if (isMasked) 0f else green)
                target.put(plane * 2 + pixel, if (isMasked) 0f else red)
                target.put(plane * 3 + pixel, blue)
                target.put(plane * 4 + pixel, green)
                target.put(plane * 5 + pixel, red)
            }
        }
    }

    fun predictionToRgbaBottomUp(prediction: FloatArray, output: ByteBuffer) {
        val size = Wav2LipEngine.IMAGE_SIZE
        val plane = size * size
        require(prediction.size >= plane * 3)
        require(output.capacity() >= plane * CHANNELS_RGBA)
        output.clear()
        for (textureY in 0 until size) {
            val modelY = size - 1 - textureY
            for (x in 0 until size) {
                val pixel = modelY * size + x
                val blue = sanitize(prediction[pixel])
                val green = sanitize(prediction[plane + pixel])
                val red = sanitize(prediction[plane * 2 + pixel])
                output.put((red * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                output.put((green * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                output.put((blue * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                output.put(0xff.toByte())
            }
        }
        output.flip()
    }

    private fun sanitize(value: Float): Float {
        return if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }
}
