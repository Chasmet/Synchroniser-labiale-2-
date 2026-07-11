package com.chasmet.lipsync.media

import java.text.Normalizer
import kotlin.math.min

internal object FrenchVisemeMapper {

    fun build(words: List<RecognizedWord>): List<PhonemeCue> {
        return words
            .filter { it.endUs > it.startUs && it.confidence >= MIN_WORD_CONFIDENCE }
            .flatMap(::wordToCues)
            .sortedBy { it.startUs }
    }

    internal fun shapesForWord(source: String): List<FrenchLipShape> {
        val word = normalize(source)
        if (word.isBlank()) return emptyList()
        val shapes = mutableListOf<FrenchLipShape>()
        var index = 0
        while (index < word.length) {
            val remaining = word.substring(index)
            when {
                remaining.startsWith("eau") -> {
                    shapes += FrenchLipShape.ROUND
                    index += 3
                }
                remaining.startsWith("oin") -> {
                    shapes += FrenchLipShape.ROUND
                    shapes += FrenchLipShape.WIDE
                    index += 3
                }
                remaining.startsWith("ou") -> {
                    shapes += FrenchLipShape.ROUND_TIGHT
                    index += 2
                }
                remaining.startsWith("oi") -> {
                    shapes += FrenchLipShape.ROUND_TIGHT
                    shapes += FrenchLipShape.OPEN
                    index += 2
                }
                remaining.startsWith("au") -> {
                    shapes += FrenchLipShape.ROUND
                    index += 2
                }
                remaining.startsWith("on") || remaining.startsWith("om") -> {
                    shapes += FrenchLipShape.ROUND
                    index += 2
                }
                remaining.startsWith("an") || remaining.startsWith("en") ||
                    remaining.startsWith("am") || remaining.startsWith("em") -> {
                    shapes += FrenchLipShape.OPEN
                    index += 2
                }
                remaining.startsWith("ain") || remaining.startsWith("ein") -> {
                    shapes += FrenchLipShape.WIDE
                    index += 3
                }
                remaining.startsWith("in") -> {
                    shapes += FrenchLipShape.WIDE
                    index += 2
                }
                remaining.startsWith("ch") || remaining.startsWith("sh") ||
                    remaining.startsWith("ge") || remaining.startsWith("gi") -> {
                    shapes += FrenchLipShape.POSTALVEOLAR
                    index += 2
                }
                remaining.startsWith("ph") -> {
                    shapes += FrenchLipShape.LABIODENTAL
                    index += 2
                }
                remaining.startsWith("gn") -> {
                    shapes += FrenchLipShape.CONSONANT
                    index += 2
                }
                else -> {
                    val character = word[index]
                    val isLast = index == word.lastIndex
                    val shape = when (character) {
                        'm', 'b', 'p' -> FrenchLipShape.BILABIAL
                        'f', 'v' -> FrenchLipShape.LABIODENTAL
                        'a' -> FrenchLipShape.OPEN
                        'e' -> if (isLast && word.length > 2) null else FrenchLipShape.MID
                        'i', 'y' -> FrenchLipShape.WIDE
                        'o' -> FrenchLipShape.ROUND
                        'u' -> FrenchLipShape.ROUND_TIGHT
                        'j' -> FrenchLipShape.POSTALVEOLAR
                        'c', 'd', 'g', 'k', 'l', 'n', 'q', 'r', 's', 't', 'x', 'z' -> {
                            if (isLast && character in SILENT_FINALS) null
                            else FrenchLipShape.CONSONANT
                        }
                        else -> null
                    }
                    if (shape != null) shapes += shape
                    index++
                }
            }
        }
        return collapseDuplicates(shapes)
    }

    private fun wordToCues(word: RecognizedWord): List<PhonemeCue> {
        val shapes = shapesForWord(word.text)
        if (shapes.isEmpty()) return emptyList()
        val weights = shapes.map(::durationWeight)
        val totalWeight = weights.sum().coerceAtLeast(0.001f)
        val duration = (word.endUs - word.startUs).coerceAtLeast(1L)
        var cursor = word.startUs
        return shapes.mapIndexed { index, shape ->
            val end = if (index == shapes.lastIndex) {
                word.endUs
            } else {
                cursor + (duration * (weights[index] / totalWeight)).toLong().coerceAtLeast(1L)
            }
            PhonemeCue(
                startUs = cursor,
                endUs = min(end, word.endUs).coerceAtLeast(cursor + 1L),
                shape = shape,
                confidence = word.confidence.coerceIn(0f, 1f)
            ).also { cursor = it.endUs }
        }
    }

    private fun durationWeight(shape: FrenchLipShape): Float = when (shape) {
        FrenchLipShape.OPEN,
        FrenchLipShape.MID,
        FrenchLipShape.WIDE,
        FrenchLipShape.ROUND,
        FrenchLipShape.ROUND_TIGHT -> 1.45f
        FrenchLipShape.BILABIAL -> 0.82f
        FrenchLipShape.LABIODENTAL -> 0.92f
        FrenchLipShape.POSTALVEOLAR -> 0.85f
        FrenchLipShape.CONSONANT -> 0.68f
        FrenchLipShape.REST -> 0.50f
    }

    private fun collapseDuplicates(input: List<FrenchLipShape>): List<FrenchLipShape> {
        if (input.isEmpty()) return input
        val output = mutableListOf(input.first())
        input.drop(1).forEach { shape ->
            if (shape != output.last()) output += shape
        }
        return output
    }

    private fun normalize(source: String): String {
        return Normalizer.normalize(source.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^a-z]".toRegex(), "")
    }

    private val SILENT_FINALS = setOf('d', 's', 't', 'x', 'z')
    private const val MIN_WORD_CONFIDENCE = 0.38f
}
