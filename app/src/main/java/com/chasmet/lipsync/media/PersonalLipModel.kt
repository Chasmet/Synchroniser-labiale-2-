package com.chasmet.lipsync.media

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.tanh

internal data class AudioFeatureFrame(
    val timeUs: Long,
    val rms: Float,
    val zeroCrossingRate: Float,
    val transientRate: Float
)

/**
 * Réseau neuronal personnel entraîné sur les vidéos fournies par l'utilisateur.
 * Les vidéos sources ne sont jamais intégrées à l'APK : seuls les poids appris le sont.
 */
internal class PersonalLipModel private constructor(
    private val offsets: IntArray,
    private val inputMean: FloatArray,
    private val inputScale: FloatArray,
    private val layers: List<Layer>,
    val signalBlend: Float,
    val name: String
) {
    private data class Layer(
        val activation: String,
        val weights: Array<FloatArray>,
        val bias: FloatArray
    )

    fun predict(frames: List<AudioFeatureFrame>, index: Int): FloatArray {
        require(frames.isNotEmpty()) { "Aucune donnée audio pour le modèle personnel" }
        val input = FloatArray(inputMean.size)
        var cursor = 0

        offsets.forEach { offset ->
            val frame = frames[(index + offset).coerceIn(0, frames.lastIndex)]
            input[cursor++] = frame.rms
            input[cursor++] = frame.zeroCrossingRate
            input[cursor++] = frame.transientRate
        }

        val previous = frames[(index - 1).coerceAtLeast(0)]
        val current = frames[index.coerceIn(0, frames.lastIndex)]
        val next = frames[(index + 1).coerceAtMost(frames.lastIndex)]
        input[cursor++] = (next.rms - previous.rms) / 2f
        input[cursor++] = (next.zeroCrossingRate - previous.zeroCrossingRate) / 2f
        input[cursor++] = (next.transientRate - previous.transientRate) / 2f
        input[cursor++] = ln((1f + current.rms * 100f).toDouble()).toFloat()
        input[cursor] = current.transientRate / (current.rms + 0.0001f)

        var activations = FloatArray(input.size) { i ->
            (input[i] - inputMean[i]) / inputScale[i].coerceAtLeast(0.000001f)
        }

        layers.forEach { layer ->
            val output = FloatArray(layer.bias.size)
            for (column in output.indices) {
                var value = layer.bias[column]
                for (row in activations.indices) {
                    value += activations[row] * layer.weights[row][column]
                }
                output[column] = when (layer.activation) {
                    "tanh" -> tanh(value.toDouble()).toFloat()
                    else -> value
                }
            }
            activations = output
        }

        return FloatArray(3) { indexOutput ->
            activations.getOrElse(indexOutput) { 0f }.coerceIn(0f, 1f)
        }
    }

    companion object {
        private const val ASSET_NAME = "chk_personal_lip_model_v1.json"

        fun load(context: Context): PersonalLipModel {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val offsets = root.getJSONArray("feature_context_offsets").toIntArray()
            val inputMean = root.getJSONArray("input_mean").toFloatArray()
            val inputScale = root.getJSONArray("input_scale").toFloatArray()
            require(inputMean.size == inputScale.size) { "Modèle personnel incomplet" }

            val layersJson = root.getJSONArray("layers")
            val layers = buildList {
                for (layerIndex in 0 until layersJson.length()) {
                    val layerJson = layersJson.getJSONObject(layerIndex)
                    val weights = if (layerJson.has("weights_q8_base64")) {
                        decodeQuantizedWeights(layerJson)
                    } else {
                        val weightsJson = layerJson.getJSONArray("weights")
                        Array(weightsJson.length()) { row ->
                            weightsJson.getJSONArray(row).toFloatArray()
                        }
                    }
                    val bias = layerJson.getJSONArray("bias").toFloatArray()
                    require(weights.isNotEmpty() && weights[0].size == bias.size) {
                        "Couche neuronale invalide"
                    }
                    add(
                        Layer(
                            activation = layerJson.getString("activation"),
                            weights = weights,
                            bias = bias
                        )
                    )
                }
            }

            require(layers.isNotEmpty()) { "Aucune couche dans le modèle personnel" }
            require(layers.first().weights.size == inputMean.size) {
                "Taille d'entrée invalide pour le modèle personnel"
            }

            return PersonalLipModel(
                offsets = offsets,
                inputMean = inputMean,
                inputScale = inputScale,
                layers = layers,
                signalBlend = root.optDouble("blend_with_signal_model", 0.60).toFloat()
                    .coerceIn(0f, 1f),
                name = root.optString("name", "Modèle personnel")
            )
        }

        private fun decodeQuantizedWeights(layerJson: JSONObject): Array<FloatArray> {
            val rows = layerJson.getInt("rows")
            val columns = layerJson.getInt("cols")
            val scale = layerJson.getDouble("weight_scale").toFloat()
            require(rows > 0 && columns > 0 && scale > 0f) { "Poids quantifiés invalides" }

            val bytes = Base64.decode(
                layerJson.getString("weights_q8_base64"),
                Base64.DEFAULT
            )
            require(bytes.size == rows * columns) { "Taille des poids quantifiés invalide" }

            return Array(rows) { row ->
                FloatArray(columns) { column ->
                    bytes[row * columns + column].toInt() * scale
                }
            }
        }

        private fun JSONArray.toFloatArray(): FloatArray {
            return FloatArray(length()) { index -> getDouble(index).toFloat() }
        }

        private fun JSONArray.toIntArray(): IntArray {
            return IntArray(length()) { index -> getInt(index) }
        }
    }
}
