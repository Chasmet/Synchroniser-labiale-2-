package com.chasmet.lipsync.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveModelBlendTest {

    @Test
    fun silenceAlwaysDisablesThePersonalModel() {
        val weight = adaptiveTrainedBlend(
            configuredSignalBlend = 0.42f,
            signal = floatArrayOf(0f, 0f, 0f),
            trained = floatArrayOf(1f, 1f, 1f),
            speechGate = 0f
        )

        assertEquals(0f, weight, 0.0001f)
    }

    @Test
    fun strongDisagreementMakesTheSignalDominant() {
        val weight = adaptiveTrainedBlend(
            configuredSignalBlend = 0.42f,
            signal = floatArrayOf(0.08f, 0.10f, 0.04f),
            trained = floatArrayOf(0.95f, 0.90f, 0.88f),
            speechGate = 1f
        )

        assertTrue(weight < 0.03f)
    }

    @Test
    fun agreementAllowsTheModelWithoutLettingItDominate() {
        val weight = adaptiveTrainedBlend(
            configuredSignalBlend = 0.42f,
            signal = floatArrayOf(0.55f, 0.32f, 0.18f),
            trained = floatArrayOf(0.57f, 0.34f, 0.20f),
            speechGate = 1f
        )

        assertTrue(weight in 0.25f..0.34f)
    }
}
