package com.trailnav.app

import com.trailnav.core.GuideConfig

internal fun locationIntervalMillisFor(waiting: Boolean, config: GuideConfig): Long =
    if (waiting) config.waitingLocationIntervalMillis else 1_000L

internal class LocationIntervalController(
    private val source: LocationSource,
    private val config: GuideConfig,
) {
    private var waiting = false

    fun updateWaitingState(nextWaiting: Boolean) {
        if (waiting == nextWaiting) return
        waiting = nextWaiting
        source.setUpdateIntervalMillis(locationIntervalMillisFor(waiting, config))
    }
}
