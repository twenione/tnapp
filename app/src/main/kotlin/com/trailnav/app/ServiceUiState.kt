package com.trailnav.app

internal fun shouldResetRibbon(previous: String?, current: String): Boolean =
    current == NavigationPreferences.STATE_IDLE &&
        previous == NavigationPreferences.STATE_RUNNING
