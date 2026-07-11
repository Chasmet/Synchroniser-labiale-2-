package com.chasmet.lipsync.media

import kotlin.math.min

/**
 * Produit un minutage proportionnel de secours. L'alignement acoustique remplace
 * ensuite ces frontières lorsque le signal audio est exploitable.
 */
internal object FrenchVisemeMapper {

    fun build(words: List<RecognizedWord>): List<PhonemeCue> {
        return words
            .filter { it.endUs > it.startUs && it.confidence >= MIN_WORD_CONFIDENCE }
            .flatMap(::wordToCues)
            .sortedBy { it.startUs }
    }

    internal fun shapesForWord(source: String): List<FrenchLipShape> {
        return collapseDuplicates(
            FrenchPhonemeLexicon.tokensForWord(source).map { it.shape }
        )
    }

    internal fun phonemesForWord(source: String): List<String> =
        FrenchPhonemeLexicon.symbolsForWord(source)

    private fun wordToCues(word: RecognizedWord): List<PhonemeCue> {
        val tokens = FrenchPhonemeLexicon.tokensForWord(word.text)
        if (tokens.isEmpty()) return emptyList()
        val totalWeight = tokens.sumOf { it.durationWeight.toDouble() }.toFloat()
            .coerceAtLeast(0.001f)
        val duration = (word.endUs - word.startUs).coerceAtLeast(1L)
        var cursor = word.startUs
        return tokens.mapIndexed { index, token ->
            val end = if (index == tokens.lastIndex) {
                word.endUs
            } else {
                cursor + (duration * (token.durationWeight / totalWeight)).toLong()
                    .coerceAtLeast(1L)
            }
            PhonemeCue(
                startUs = cursor,
                endUs = min(end, word.endUs).coerceAtLeast(cursor + 1L),
                shape = token.shape,
                confidence = word.confidence.coerceIn(0f, 1f),
                phoneme = token.symbol,
                sourceWord = word.text,
                alignmentConfidence = word.confidence * FALLBACK_ALIGNMENT_FACTOR
            ).also { cursor = it.endUs }
        }
    }

    private fun collapseDuplicates(input: List<FrenchLipShape>): List<FrenchLipShape> {
        if (input.isEmpty()) return input
        val output = mutableListOf(input.first())
        input.drop(1).forEach { shape ->
            if (shape != output.last()) output += shape
        }
        return output
    }

    private const val MIN_WORD_CONFIDENCE = 0.38f
    private const val FALLBACK_ALIGNMENT_FACTOR = 0.55f
}
