package com.trailnav.app

/** App destination derived from the service state persisted by the service. */
internal enum class AppScreen { HOME, GUIDE }

internal fun screenFor(serviceState: String?): AppScreen =
    if (serviceState == NavigationPreferences.STATE_RUNNING) AppScreen.GUIDE else AppScreen.HOME

/** Counts taps for a hidden entry and fires at most once before reset. */
internal class TapCounter(private val target: Int = 7) {
    private var count = 0
    private var opened = false

    init {
        require(target > 0)
    }

    fun tap(): Boolean {
        if (opened) return false
        count += 1
        if (count < target) return false
        opened = true
        return true
    }

    fun reset() {
        count = 0
        opened = false
    }
}
