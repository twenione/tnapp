package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToneAndLifecycleTest {
    @Test
    fun periodicAndAcknowledgementWaveformsHaveRequiredShape() {
        assertEquals(1_200, ToneSynth.periodicSignal().size)
        val acknowledgement = ToneSynth.acknowledgement()
        assertEquals(1_840, acknowledgement.size)
        assertTrue(acknowledgement.take(640).any { it != 0.toShort() })
        assertTrue(acknowledgement.drop(640).take(560).all { it == 0.toShort() })
        assertTrue(acknowledgement.drop(1_200).any { it != 0.toShort() })
    }

    @Test
    fun ribbonResetsOnlyAtIdleTransition() {
        assertTrue(shouldResetRibbon(NavigationPreferences.STATE_RUNNING, NavigationPreferences.STATE_IDLE))
        assertFalse(shouldResetRibbon(null, NavigationPreferences.STATE_IDLE))
        assertFalse(shouldResetRibbon(NavigationPreferences.STATE_IDLE, NavigationPreferences.STATE_IDLE))
    }

    @Test
    fun endPhraseIsExactAndDestinationWordingIsConsistent() {
        assertEquals("안내를 종료합니다.", GuidancePhrases.ended())
        assertTrue(GuidancePhrases.remaining(25.0).startsWith("목적지까지"))
    }
}
