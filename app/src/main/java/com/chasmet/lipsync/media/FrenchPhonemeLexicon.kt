package com.chasmet.lipsync.media

import java.text.Normalizer

internal data class FrenchPhonemeToken(
    val symbol: String,
    val shape: FrenchLipShape,
    val durationWeight: Float,
    val closureLeadUs: Long = 0L
)

/** Conversion locale des mots français en phonèmes visibles. */
internal object FrenchPhonemeLexicon {

    fun tokensForWord(source: String): List<FrenchPhonemeToken> {
        val word = normalize(source)
        if (word.isBlank()) return emptyList()
        val symbols = EXCEPTIONS[word] ?: parse(word)
        return symbols.map(::token)
    }

    internal fun symbolsForWord(source: String): List<String> =
        tokensForWord(source).map { it.symbol }

    private fun parse(word: String): List<String> {
        val output = mutableListOf<String>()
        var index = 0
        while (index < word.length) {
            val rest = word.substring(index)
            val last = index == word.lastIndex
            when {
                rest.startsWith("eaux") -> { output += "o"; index += 4 }
                rest.startsWith("tion") -> { output += listOf("s", "i", "on"); index += 4 }
                rest.startsWith("eau") -> { output += "o"; index += 3 }
                rest.startsWith("oin") -> { output += listOf("w", "in"); index += 3 }
                rest.startsWith("ain") || rest.startsWith("ein") -> { output += "in"; index += 3 }
                rest.startsWith("oeu") -> { output += "eu"; index += 3 }
                rest.startsWith("ou") -> { output += "ou"; index += 2 }
                rest.startsWith("oi") -> { output += listOf("w", "a"); index += 2 }
                rest.startsWith("on") || rest.startsWith("om") -> { output += "on"; index += 2 }
                rest.startsWith("an") || rest.startsWith("en") ||
                    rest.startsWith("am") || rest.startsWith("em") -> {
                    output += "an"; index += 2
                }
                rest.startsWith("in") || rest.startsWith("un") ||
                    rest.startsWith("yn") || rest.startsWith("um") -> {
                    output += "in"; index += 2
                }
                rest.startsWith("au") -> { output += "o"; index += 2 }
                rest.startsWith("eu") -> { output += "eu"; index += 2 }
                rest.startsWith("ai") || rest.startsWith("ei") -> { output += "e"; index += 2 }
                (rest.startsWith("er") || rest.startsWith("ez")) && index + 2 == word.length -> {
                    output += "e"; index += 2
                }
                rest.startsWith("ch") || rest.startsWith("sh") -> { output += "sh"; index += 2 }
                rest.startsWith("ph") -> { output += "f"; index += 2 }
                rest.startsWith("gn") -> { output += "ny"; index += 2 }
                rest.startsWith("qu") -> { output += "k"; index += 2 }
                rest.startsWith("th") -> { output += "t"; index += 2 }
                else -> {
                    val c = word[index]
                    val next = word.getOrNull(index + 1)
                    when (c) {
                        'a' -> output += "a"
                        'e' -> if (!(last && word.length > 2)) output += "e"
                        'i', 'y' -> output += "i"
                        'o' -> output += "o"
                        'u' -> output += "u"
                        'c' -> output += if (next in setOf('e', 'i', 'y')) "s" else "k"
                        'g' -> output += if (next in setOf('e', 'i', 'y')) "j" else "g"
                        'q' -> output += "k"
                        'x' -> if (!last) output += listOf("k", "s")
                        'h' -> Unit
                        else -> if (!(last && c in SILENT_FINALS)) output += c.toString()
                    }
                    index++
                }
            }
        }
        return output.ifEmpty { listOf("neutral") }
    }

    private fun token(symbol: String): FrenchPhonemeToken {
        val shape = when (symbol) {
            "p", "b", "m" -> FrenchLipShape.BILABIAL
            "f", "v" -> FrenchLipShape.LABIODENTAL
            "a", "an" -> FrenchLipShape.OPEN
            "e", "eu" -> FrenchLipShape.MID
            "i", "in", "y" -> FrenchLipShape.WIDE
            "o", "on" -> FrenchLipShape.ROUND
            "u", "ou", "w" -> FrenchLipShape.ROUND_TIGHT
            "sh", "j" -> FrenchLipShape.POSTALVEOLAR
            else -> FrenchLipShape.CONSONANT
        }
        val weight = when (shape) {
            FrenchLipShape.OPEN, FrenchLipShape.MID, FrenchLipShape.WIDE,
            FrenchLipShape.ROUND, FrenchLipShape.ROUND_TIGHT -> 1.55f
            FrenchLipShape.BILABIAL -> 0.82f
            FrenchLipShape.LABIODENTAL -> 0.95f
            FrenchLipShape.POSTALVEOLAR -> 0.90f
            else -> 0.70f
        }
        return FrenchPhonemeToken(
            symbol = symbol,
            shape = shape,
            durationWeight = weight,
            closureLeadUs = if (shape == FrenchLipShape.BILABIAL) 42_000L else 0L
        )
    }

    private fun normalize(source: String): String {
        val expanded = source.lowercase().replace("œ", "oe")
        return Normalizer.normalize(expanded, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^a-z]".toRegex(), "")
    }

    private val SILENT_FINALS = setOf('d', 'g', 'p', 's', 't', 'x', 'z')
    private val EXCEPTIONS = mapOf(
        "bonjour" to listOf("b", "on", "j", "ou", "r"),
        "bonsoir" to listOf("b", "on", "s", "w", "a", "r"),
        "salut" to listOf("s", "a", "l", "u"),
        "merci" to listOf("m", "e", "r", "s", "i"),
        "oui" to listOf("w", "i"),
        "non" to listOf("n", "on"),
        "france" to listOf("f", "r", "an", "s"),
        "paris" to listOf("p", "a", "r", "i")
    )
}
