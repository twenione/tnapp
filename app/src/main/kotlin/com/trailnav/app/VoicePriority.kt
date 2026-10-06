package com.trailnav.app

import com.trailnav.core.Guidance

internal enum class VoicePriority { STATE_TRANSITION, TIME_CRITICAL, NORMAL }

internal fun priorityFor(guidance: Guidance?, reasonRule: String?, recovery: Boolean): VoicePriority = when {
    recovery -> VoicePriority.STATE_TRANSITION
    guidance == Guidance.Arrived -> VoicePriority.STATE_TRANSITION
    guidance is Guidance.OffRoute && reasonRule == "off-route.enter" -> VoicePriority.STATE_TRANSITION
    guidance is Guidance.TurnNow -> VoicePriority.TIME_CRITICAL
    else -> VoicePriority.NORMAL
}

internal fun isProtected(
    guidance: Guidance?,
    recovery: Boolean,
    reasonRule: String? = null,
): Boolean =
    priorityFor(guidance, reasonRule, recovery) == VoicePriority.STATE_TRANSITION ||
        guidance is Guidance.Remaining || guidance is Guidance.Sunset

internal fun onDemandVoicePriority(guidance: Guidance): VoicePriority =
    priorityFor(guidance, reasonRule = "on-demand.route-status", recovery = false)
