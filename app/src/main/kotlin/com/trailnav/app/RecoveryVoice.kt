package com.trailnav.app

internal const val RECOVERY_VOICE_PROMPT = "경로로 복귀했습니다."

/** Returns true only on the falling edge from a confirmed off-route state. */
internal fun isOffRouteRecovery(previousOffRoute: Boolean, currentOffRoute: Boolean): Boolean =
    previousOffRoute && !currentOffRoute

/** Returns the recovery prompt only for the off-route falling edge. */
internal fun recoveryVoicePrompt(previousOffRoute: Boolean, currentOffRoute: Boolean): String? =
    if (isOffRouteRecovery(previousOffRoute, currentOffRoute)) RECOVERY_VOICE_PROMPT else null
