package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppScreenFlowTest {
    @Test
    fun activeServiceStatesOpenGuideAndAllOthersReturnHome() {
        assertEquals(AppScreen.GUIDE, screenFor(NavigationPreferences.STATE_RUNNING))
        assertEquals(AppScreen.HOME, screenFor("PAUSED"))
        assertEquals(AppScreen.HOME, screenFor(NavigationPreferences.STATE_IDLE))
        assertEquals(AppScreen.HOME, screenFor(null))
        assertEquals(AppScreen.HOME, screenFor("UNKNOWN"))
    }

    @Test
    fun tapCounterOpensOnceAtTargetAndCanBeReset() {
        val counter = TapCounter(target = 7)
        repeat(6) { assertFalse(counter.tap()) }
        assertTrue(counter.tap())
        assertFalse(counter.tap())
        counter.reset()
        repeat(6) { assertFalse(counter.tap()) }
        assertTrue(counter.tap())
    }
}
