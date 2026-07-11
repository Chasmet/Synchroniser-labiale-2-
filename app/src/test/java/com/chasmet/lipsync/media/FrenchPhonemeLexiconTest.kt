package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchPhonemeLexiconTest {

    @Test
    fun `bonjour est decompose comme il se prononce`() {
        assertEquals(
            listOf("b", "on", "j", "ou", "r"),
            FrenchPhonemeLexicon.symbolsForWord("bonjour")
        )
    }

    @Test
    fun `bonjour contient les formes visibles attendues`() {
        val tokens = FrenchPhonemeLexicon.tokensForWord("bonjour")
        assertEquals(FrenchLipShape.BILABIAL, tokens.first().shape)
        assertEquals(FrenchLipShape.ROUND, tokens[1].shape)
        assertEquals(FrenchLipShape.POSTALVEOLAR, tokens[2].shape)
        assertEquals(FrenchLipShape.ROUND_TIGHT, tokens[3].shape)
        assertTrue(tokens.first().closureLeadUs >= 40_000L)
    }

    @Test
    fun `les accents sont normalises localement`() {
        val tokens = FrenchPhonemeLexicon.tokensForWord("été")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.any { it.shape == FrenchLipShape.MID })
    }
}
