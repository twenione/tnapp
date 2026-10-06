package com.trailnav.app

/** Holds the paused-only recovery affordance without changing guidance pause state. */
internal class PausedRecoveryNotificationState {
    private var recoveredWhilePaused = false

    fun onRecovery(paused: Boolean) {
        if (paused) recoveredWhilePaused = true
    }

    fun onResume() {
        recoveredWhilePaused = false
    }

    fun onPause() {
        recoveredWhilePaused = false
    }

    fun onNewOffRouteEntry() {
        recoveredWhilePaused = false
    }

    fun recoveryTextOrNull(): String? = if (recoveredWhilePaused) RECOVERY_VOICE_PROMPT else null
}
