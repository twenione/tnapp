package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePriorityTest {
    @Test
    fun stateTransitionsAreHighestPriority() {
        assertEquals(VoicePriority.STATE_TRANSITION, priorityFor(Guidance.Arrived, null, false))
        assertEquals(VoicePriority.STATE_TRANSITION, priorityFor(Guidance.OffRoute(30.0, "forward"), "off-route.enter", false))
        assertEquals(VoicePriority.STATE_TRANSITION, priorityFor(Guidance.Status("recovery"), null, true))
        assertTrue(isProtected(Guidance.Arrived, false))
        assertTrue(isProtected(Guidance.OffRoute(30.0, "forward"), false, "off-route.enter"))
        assertTrue(isProtected(Guidance.OffRoute(30.0, "forward"), true))
    }

    @Test
    fun onlyTurnNowIsTimeCritical() {
        assertEquals(VoicePriority.TIME_CRITICAL, priorityFor(Guidance.TurnNow(Side.RIGHT, 1), "turn.now", false))
        assertEquals(VoicePriority.NORMAL, priorityFor(Guidance.TurnAhead(20.0, Side.LEFT, 0), "turn.ahead", false))
    }

    @Test
    fun ordinaryGuidanceAndNonEntryOffRouteAreNormal() {
        assertEquals(VoicePriority.NORMAL, priorityFor(Guidance.OffRoute(30.0, "forward"), "off-route.reannounce", false))
        assertFalse(isProtected(Guidance.OffRoute(30.0, "forward"), false, "off-route.reannounce"))
        assertEquals(VoicePriority.NORMAL, priorityFor(Guidance.Status("status"), "status", false))
        assertEquals(VoicePriority.NORMAL, priorityFor(null, null, false))
    }

    @Test
    fun remainingAndSunsetAreProtectedWithoutChangingTheirPriority() {
        assertEquals(VoicePriority.NORMAL, priorityFor(Guidance.Remaining(500.0, 450.0), "event.remaining", false))
        assertEquals(VoicePriority.NORMAL, priorityFor(Guidance.Sunset(10), "event.sunset", false))
        assertTrue(isProtected(Guidance.Remaining(500.0, 450.0), false))
        assertTrue(isProtected(Guidance.Sunset(10), false))
        assertFalse(isProtected(Guidance.Sunrise(10), false))
        assertFalse(isProtected(Guidance.TurnNow(Side.RIGHT, 0), false))
    }
}
