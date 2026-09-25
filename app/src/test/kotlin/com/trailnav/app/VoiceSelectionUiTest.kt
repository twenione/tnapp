package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class VoiceSelectionUiTest {
    @Test
    fun intervalSelectionHighlightsExactlyOneChoice() {
        val choices = VoiceSelectionUi.INTERVAL_CHOICES.map { it.first }
        val selected = choices.filter { VoiceSelectionUi.intervalSelected(180L, it) }
        assertTrue(selected == listOf(180L))
        assertEquals(listOf(0L, 60L, 180L, 300L, 600L), choices)
    }
}
