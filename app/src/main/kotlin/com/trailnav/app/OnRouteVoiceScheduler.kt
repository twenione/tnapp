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
    private val enabled = enabled && intervalSeconds > 0L
    private val intervalMillis = (if (intervalSeconds > 0L) intervalSeconds else 1L) * 1_000L
    private var lastAnnouncementAtMillis: Long? = null

    /**
     * Returns true exactly when a prompt should be spoken for this frame.
     * Off-route, arrival, and invalid accuracy frames reset the period.
     * A suppressed frame consumes a due slot so a reverse status cannot cause
     * an immediate prompt on the next 1 Hz frame.
     */
    fun onFrame(
        timestampMillis: Long,
        onRoute: Boolean,
        arrived: Boolean = false,
        suppressAnnouncement: Boolean = false,
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
        return !suppressAnnouncement
    }

    fun reset() {
        lastAnnouncementAtMillis = null
    }
}
