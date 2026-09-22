package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSelectionUiTest {
    @Test
    fun intervalSelectionHighlightsExactlyOneChoice() {
        val choices = listOf(0L, 60L, 180L, 300L)
        val selected = choices.filter { VoiceSelectionUi.intervalSelected(180L, it) }
        assertTrue(selected == listOf(180L))
    }

    @Test
    fun modeSelectionClearsWhenPeriodicVoiceIsOff() {
        assertTrue(
            VoiceSelectionUi.modeSelected(
                enabled = true,
                mode = NavigationPreferences.PeriodicVoiceMode.PROMPT,
                candidate = NavigationPreferences.PeriodicVoiceMode.PROMPT,
            ),
        )
        assertFalse(
            VoiceSelectionUi.modeSelected(
                enabled = false,
                mode = NavigationPreferences.PeriodicVoiceMode.OFF,
                candidate = NavigationPreferences.PeriodicVoiceMode.PROMPT,
            ),
        )
    }

    @Test
    fun promptLabelIsGeneralGuidanceAndOffIsNotAVisibleModeChoice() {
        assertTrue(VoiceSelectionUi.modeLabel(NavigationPreferences.PeriodicVoiceMode.PROMPT) == "일반안내")
        assertTrue(VoiceSelectionUi.modeLabel(NavigationPreferences.PeriodicVoiceMode.TONE) == "신호음")
    }
}
