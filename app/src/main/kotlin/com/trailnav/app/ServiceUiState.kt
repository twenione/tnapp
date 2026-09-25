package com.trailnav.app

internal fun shouldResetRibbon(previous: String?, current: String): Boolean =
    current == NavigationPreferences.STATE_IDLE &&
        previous in setOf(NavigationPreferences.STATE_RUNNING, NavigationPreferences.STATE_PAUSED)
