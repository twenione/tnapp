package com.trailnav.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class E5GoldenAssertionTest {
    private val expectedPositions = listOf(100.0, 200.0)

    @Test
    fun extraEventWithinLegacyToleranceFailsExactCount() {
        val actual = listOf(100.0, 200.0, 300.0)

        // The old ±1 count rule accepted this exact extra-event input.
        assertTrue(abs(actual.size - expectedPositions.size) <= 1)
        assertFailsWith<IllegalStateException> {
            E5EventCountSupport.assertMatchesGolden("extra-event", 2, expectedPositions, actual)
        }
    }

    @Test
    fun missingEventFailsExactCount() {
        val actual = listOf(100.0)
        assertFailsWith<IllegalStateException> {
            E5EventCountSupport.assertMatchesGolden("missing-event", 2, expectedPositions, actual)
        }
    }

    @Test
    fun positionBeyondToleranceFails() {
        assertFailsWith<IllegalStateException> {
            E5EventCountSupport.assertMatchesGolden("position-2.6m", 1, listOf(100.0), listOf(102.6))
        }
    }

    @Test
    fun positionWithinTolerancePasses() {
        E5EventCountSupport.assertMatchesGolden("position-2.4m", 1, listOf(100.0), listOf(102.4))
    }

    @Test
    fun exactGoldenCountAndPositionsPass() {
        E5EventCountSupport.assertMatchesGolden("unchanged", 2, expectedPositions, expectedPositions)
    }
}
