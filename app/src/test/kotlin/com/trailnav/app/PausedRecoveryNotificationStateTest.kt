package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PausedRecoveryNotificationStateTest {
    @Test
    fun recoveryWhilePausedChangesNotificationWithoutSpeakingOrResuming() {
        val state = PausedRecoveryNotificationState()
        state.onRecovery(paused = true)
        assertEquals(RECOVERY_VOICE_PROMPT, state.recoveryTextOrNull())
        assertFalse(shouldSpeakVoice(ending = false, paused = true, kind = VoiceKind.RECOVERY))
    }

    @Test
    fun resumeClearsRecoveryAffordance() {
        val state = PausedRecoveryNotificationState()
        state.onRecovery(paused = true)
        state.onResume()
        assertNull(state.recoveryTextOrNull())
    }

    @Test
    fun pausingAgainClearsRecoveryAffordance() {
        val state = PausedRecoveryNotificationState()
        state.onRecovery(paused = true)
        state.onPause()
        assertNull(state.recoveryTextOrNull())
    }

    @Test
    fun aNewOffRouteEntryClearsRecoveryAffordance() {
        val state = PausedRecoveryNotificationState()
        state.onRecovery(paused = true)
        state.onNewOffRouteEntry()
        assertNull(state.recoveryTextOrNull())
    }
}
