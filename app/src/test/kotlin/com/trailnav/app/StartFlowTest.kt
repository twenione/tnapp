package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals

class StartFlowTest {
    @Test
    fun locationGateProceedsWhenSettingsAreSatisfied() {
        assertEquals(
            LocationGateAction.Proceed,
            LocationGate.decide(settingsOk = true, resolvable = false, serviceEnabled = false),
        )
    }

    @Test
    fun locationGatePromptsForResolvableFailure() {
        assertEquals(
            LocationGateAction.PromptUser,
            LocationGate.decide(settingsOk = false, resolvable = true, serviceEnabled = false),
        )
    }

    @Test
    fun locationGateUsesEnabledServiceForUnresolvableFailure() {
        assertEquals(
            LocationGateAction.Proceed,
            LocationGate.decide(settingsOk = false, resolvable = false, serviceEnabled = true),
        )
    }

    @Test
    fun locationGateBlocksWhenServiceIsDisabledAndFailureIsUnresolvable() {
        assertEquals(
            LocationGateAction.Blocked,
            LocationGate.decide(settingsOk = false, resolvable = false, serviceEnabled = false),
        )
    }

    @Test
    fun closedRouteStartsDirectlyAsOneWay() {
        assertEquals(StartModeAction.StartDirectly(roundTrip = false), StartModeGate.decide(closed = true))
    }

    @Test
    fun openOrUnknownRouteAsksForStartMode() {
        assertEquals(StartModeAction.AskUser, StartModeGate.decide(closed = false))
        assertEquals(StartModeAction.AskUser, StartModeGate.decide(closed = null))
    }
}
