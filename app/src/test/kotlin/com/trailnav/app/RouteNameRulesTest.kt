package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteNameRulesTest {
    @Test
    fun uniqueNameReturnsBaseWhenAvailable() {
        assertEquals("X", RouteNameRules.uniqueName("X", listOf("Other")))
    }

    @Test
    fun uniqueNameAddsNumberedSuffixForTakenName() {
        assertEquals("X (2)", RouteNameRules.uniqueName("X", listOf("X")))
    }

    @Test
    fun uniqueNameSkipsUsedSuffix() {
        assertEquals("X (3)", RouteNameRules.uniqueName("X", listOf("X", "X (2)")))
    }

    @Test
    fun uniqueNameComparisonIgnoresCaseAndTrimsExistingNames() {
        assertEquals("X (2)", RouteNameRules.uniqueName("X", listOf("  x  ")))
    }

    @Test
    fun uniqueNameKeepsAtMost60CodePointsWithSuffix() {
        val base = "X".repeat(RouteNameRules.MAX_LENGTH)
        val result = RouteNameRules.uniqueName(base, listOf(base))

        assertEquals("X".repeat(56) + " (2)", result)
        assertEquals(RouteNameRules.MAX_LENGTH, RouteNameRules.codePointCount(result))
    }

    @Test
    fun uniqueNameDoesNotSplitEmojiAtTheCodePointBoundary() {
        val base = "A".repeat(55) + "😀" + "B".repeat(3) + "😀"
        assertEquals(RouteNameRules.MAX_LENGTH, RouteNameRules.codePointCount(base))

        val result = RouteNameRules.uniqueName(base, listOf(base))

        assertEquals("A".repeat(55) + "😀" + " (2)", result)
        assertEquals(RouteNameRules.MAX_LENGTH, RouteNameRules.codePointCount(result))
        assertTrue(result.contains("😀"))
    }

    @Test
    fun uniqueNameReturnsBaseWhenExistingIsEmpty() {
        assertEquals("Trail", RouteNameRules.uniqueName("Trail", emptyList()))
    }
}
