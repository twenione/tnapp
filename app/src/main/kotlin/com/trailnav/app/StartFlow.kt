package com.trailnav.app

internal sealed interface LocationGateAction {
    data object Proceed : LocationGateAction
    data object PromptUser : LocationGateAction
    data object Blocked : LocationGateAction
}

internal object LocationGate {
    fun decide(
        settingsOk: Boolean,
        resolvable: Boolean,
        serviceEnabled: Boolean,
    ): LocationGateAction = when {
        settingsOk -> LocationGateAction.Proceed
        resolvable -> LocationGateAction.PromptUser
        serviceEnabled -> LocationGateAction.Proceed
        else -> LocationGateAction.Blocked
    }
}

internal sealed interface StartModeAction {
    data class StartDirectly(val roundTrip: Boolean) : StartModeAction
    data object AskUser : StartModeAction
}

internal object StartModeGate {
    fun decide(closed: Boolean?): StartModeAction =
        if (closed == true) StartModeAction.StartDirectly(roundTrip = false)
        else StartModeAction.AskUser
}
