package com.trailnav.app

/** Sources that can request an on-demand navigation status. */
enum class OnDemandSource(val wireName: String) {
    MEDIA_BUTTON("media_button"),
    SHAKE("shake");
}

/** Pure trigger settings kept at the app boundary; these are not engine parameters. */
data class OnDemandConfig(
    val mediaButtonEnabled: Boolean = true,
    val shakeEnabled: Boolean = true,
    val debounceMillis: Long = 1_500L,
    // Provisional field-test baseline: walking-like 3.0-6.5 m/s² samples do
    // not trigger; a deliberate shake must reach 8.0 m/s² four times in the
    // window. Revisit after separate normal-walk and deliberate-shake runs.
    val shakeThresholdMetersPerSecondSquared: Double = 8.0,
    val shakeHitsRequired: Int = 4,
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

/** Pure source gate used by the media-button and shake entry points. */
class OnDemandRequestRouter(private val config: OnDemandConfig) {
    private val debouncer = OnDemandDebouncer(config.debounceMillis)

    fun accept(source: OnDemandSource, timestampMillis: Long): OnDemandSource? {
        val enabled = when (source) {
            OnDemandSource.MEDIA_BUTTON -> config.mediaButtonEnabled
            OnDemandSource.SHAKE -> config.shakeEnabled
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

/**
 * Bounded per-minute shake telemetry. Raw samples never leave this object;
 * only aggregate values are emitted by the service.
 */
data class ShakeStatsSnapshot(
    val minuteIndex: Long,
    val sampleCount: Int,
    val maximumDeviation: Double,
    val p95Deviation: Double,
    val thresholdExceedances: Int,
)

class ShakeStats(
    private val thresholdMetersPerSecondSquared: Double,
    private val bucketMillis: Long = 60_000L,
) {
    private var minuteIndex: Long? = null
    private val samples = ArrayList<Double>()
    private var thresholdExceedances = 0

    fun record(timestampMillis: Long, deviationMetersPerSecondSquared: Double): ShakeStatsSnapshot? {
        val currentMinute = timestampMillis / bucketMillis
        val previousMinute = minuteIndex
        if (previousMinute == null) {
            minuteIndex = currentMinute
        } else if (currentMinute != previousMinute) {
            val completed = snapshot(previousMinute)
            minuteIndex = currentMinute
            samples.clear()
            thresholdExceedances = 0
            add(deviationMetersPerSecondSquared)
            return completed
        }
        add(deviationMetersPerSecondSquared)
        return null
    }

    fun flush(): ShakeStatsSnapshot? {
        val currentMinute = minuteIndex ?: return null
        if (samples.isEmpty()) return null
        val completed = snapshot(currentMinute)
        minuteIndex = null
        samples.clear()
        thresholdExceedances = 0
        return completed
    }

    private fun add(value: Double) {
        if (!value.isFinite()) return
        samples += value
        if (value >= thresholdMetersPerSecondSquared) thresholdExceedances += 1
    }

    private fun snapshot(index: Long): ShakeStatsSnapshot {
        val ordered = samples.sorted()
        val p95Index = (kotlin.math.ceil(ordered.size * 0.95).toInt() - 1).coerceIn(0, ordered.lastIndex)
        return ShakeStatsSnapshot(
            minuteIndex = index,
            sampleCount = ordered.size,
            maximumDeviation = ordered.lastOrNull() ?: 0.0,
            p95Deviation = ordered.getOrElse(p95Index) { 0.0 },
            thresholdExceedances = thresholdExceedances,
        )
    }
}
