package com.trailnav.app

/** Sources that can request an on-demand navigation status. */
enum class OnDemandSource(val wireName: String) {
    MEDIA_BUTTON("media_button"),
    SHAKE("shake"),
    NOTIFICATION("notification");

    companion object {
        fun fromWire(value: String?): OnDemandSource = entries.firstOrNull { it.wireName == value } ?: NOTIFICATION
    }
}

/** Pure trigger settings kept at the app boundary; these are not engine parameters. */
data class OnDemandConfig(
    val mediaButtonEnabled: Boolean = true,
    val shakeEnabled: Boolean = true,
    val notificationEnabled: Boolean = true,
    val debounceMillis: Long = 1_500L,
    val shakeThresholdMetersPerSecondSquared: Double = 2.5,
    val shakeHitsRequired: Int = 2,
    val shakeWindowMillis: Long = 700L,
)

/** Debounces all trigger sources without reading a wall clock. */
class OnDemandDebouncer(private val debounceMillis: Long) {
    private var lastAcceptedAt: Long? = null

    fun accept(timestampMillis: Long): Boolean {
        val previous = lastAcceptedAt
        if (previous != null && timestampMillis - previous < debounceMillis) return false
        lastAcceptedAt = timestampMillis
        return true
    }
}

/** Pure source gate used by media, shake, and notification entry points. */
class OnDemandRequestRouter(private val config: OnDemandConfig) {
    private val debouncer = OnDemandDebouncer(config.debounceMillis)

    fun accept(source: OnDemandSource, timestampMillis: Long): OnDemandSource? {
        val enabled = when (source) {
            OnDemandSource.MEDIA_BUTTON -> config.mediaButtonEnabled
            OnDemandSource.SHAKE -> config.shakeEnabled
            OnDemandSource.NOTIFICATION -> config.notificationEnabled
        }
        return if (enabled && debouncer.accept(timestampMillis)) source else null
    }
}

/**
 * Pure accelerometer classifier. The service supplies elapsed timestamps and the
 * acceleration magnitude after gravity removal.
 */
class ShakeDetector(private val config: OnDemandConfig) {
    private val hits = ArrayDeque<Long>()

    fun onSample(timestampMillis: Long, magnitudeMetersPerSecondSquared: Double): Boolean {
        while (hits.isNotEmpty() && timestampMillis - hits.first() > config.shakeWindowMillis) hits.removeFirst()
        if (magnitudeMetersPerSecondSquared < config.shakeThresholdMetersPerSecondSquared) return false
        hits.addLast(timestampMillis)
        while (hits.size > config.shakeHitsRequired) hits.removeFirst()
        if (hits.size < config.shakeHitsRequired) return false
        hits.clear()
        return true
    }
}
