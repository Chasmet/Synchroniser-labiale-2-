package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchVisemeMapperTest {

    @Test
    fun `papa contient deux fermetures bilabiales`() {
        val shapes = FrenchVisemeMapper.shapesForWord("papa")
        assertEquals(2, shapes.count { it == FrenchLipShape.BILABIAL })
        assertTrue(shapes.contains(FrenchLipShape.OPEN))
    }

    @Test
    fun `bonjour commence par une fermeture et contient une voyelle ronde`() {
        val shapes = FrenchVisemeMapper.shapesForWord("bonjour")
        assertEquals(FrenchLipShape.BILABIAL, shapes.first())
        assertTrue(shapes.contains(FrenchLipShape.ROUND))
        assertTrue(shapes.contains(FrenchLipShape.ROUND_TIGHT))
    }

    @Test
    fun `oui produit une bouche ronde puis large`() {
        val shapes = FrenchVisemeMapper.shapesForWord("oui")
        assertEquals(FrenchLipShape.ROUND_TIGHT, shapes.first())
        assertTrue(shapes.contains(FrenchLipShape.WIDE))
    }

    @Test
    fun `un mot peu fiable ne produit aucun repere`() {
        val cues = FrenchVisemeMapper.build(
            listOf(RecognizedWord("paris", 0L, 500_000L, 0.20f))
        )
        assertTrue(cues.isEmpty())
    }
}
