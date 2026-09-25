package com.trailnav.app

/**
 * Schedules an optional reassurance prompt while the latest engine state is
 * on route. The scheduler deliberately knows only the next state flag and
 * frame time; it does not infer on-route status from a null guidance value.
 */
class OnRouteVoiceScheduler(
    enabled: Boolean,
    intervalSeconds: Long,
) {
    private var enabled = enabled && intervalSeconds > 0L
    private var intervalMillis = intervalMillisFor(intervalSeconds)
    private var lastAnnouncementAtMillis: Long? = null

    /**
     * Returns true exactly when a prompt should be spoken for this frame.
     * Off-route, arrival, and invalid accuracy frames reset the period.
     * Direction is intentionally irrelevant to this scheduler.  The engine
     * owns reverse-event policy; the scheduler only gates on-route cadence.
     */
    fun onFrame(
        timestampMillis: Long,
        onRoute: Boolean,
        arrived: Boolean = false,
    ): Boolean {
        if (!enabled) return false
        if (!onRoute || arrived) {
            reset()
            return false
        }
        val previous = lastAnnouncementAtMillis
        if (previous == null) {
            lastAnnouncementAtMillis = timestampMillis
            return false
        }
        if (timestampMillis < previous) {
            lastAnnouncementAtMillis = timestampMillis
            return false
        }
        if (timestampMillis - previous < intervalMillis) return false
        lastAnnouncementAtMillis = timestampMillis
        return true
    }

    /** Apply a new UI selection to an already running service. */
    fun configure(enabled: Boolean, intervalSeconds: Long) {
        this.enabled = enabled && intervalSeconds > 0L
        intervalMillis = intervalMillisFor(intervalSeconds)
        reset()
    }

    fun reset() {
        lastAnnouncementAtMillis = null
    }

    private fun intervalMillisFor(intervalSeconds: Long): Long =
        (if (intervalSeconds > 0L) intervalSeconds else 1L) * 1_000L
}
