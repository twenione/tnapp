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
    // TEMPORARY (TASK-043): replace with field-data threshold and an on/off setting (D-R6)
    val shakeCooldownMillis: Long = 300_000L,
    // Provisional field-test baseline: walking-like 3.0-6.5 m/s² samples do
    // not trigger; a deliberate shake must reach 8.0 m/s² four times in the
    // window. Revisit after separate normal-walk and deliberate-shake runs.
    val shakeThresholdMetersPerSecondSquared: Double = 8.0,
    val shakeHitsRequired: Int = 4,
    val shakeWindowMillis: Long = 700L,
)

/** Stable wire fields recorded with each session's on-demand settings. */
fun OnDemandConfig.toWireMap(): Map<String, String> = linkedMapOf(
    "media_button_enabled" to mediaButtonEnabled.toString(),
    "shake_enabled" to shakeEnabled.toString(),
    "debounce_ms" to debounceMillis.toString(),
    "shake_threshold" to shakeThresholdMetersPerSecondSquared.toString(),
    "shake_hits" to shakeHitsRequired.toString(),
    "shake_window_ms" to shakeWindowMillis.toString(),
    "shake_cooldown_ms" to shakeCooldownMillis.toString(),
)

/** One aggregated, source-specific cooldown suppression record. */
data class ShakeCooldownSuppression(
    val count: Int,
    val remainingMillis: Long,
) {
    fun toWireMap(): Map<String, String> = linkedMapOf(
        "source" to OnDemandSource.SHAKE.wireName,
        "reason" to "cooldown",
        "count" to count.toString(),
        "remaining_ms" to remainingMillis.toString(),
    )
}

/** Pure one-minute accumulator for cooldown suppressions. */
class ShakeCooldownSuppressionAggregator(private val windowMillis: Long = 60_000L) {
    init {
        require(windowMillis > 0L)
    }

    private var windowStartedAtMillis: Long? = null
    private var count = 0
    private var lastRemainingMillis = 0L

    fun record(timestampMillis: Long, remainingMillis: Long): ShakeCooldownSuppression? {
        val startedAt = windowStartedAtMillis
        if (startedAt == null) {
            begin(timestampMillis, remainingMillis)
            return null
        }
        if (timestampMillis - startedAt < windowMillis) {
            count += 1
            lastRemainingMillis = remainingMillis
            return null
        }
        val completed = snapshot()
        begin(timestampMillis, remainingMillis)
        return completed
    }

    fun flush(): ShakeCooldownSuppression? {
        if (count == 0) return null
        return snapshot().also {
            windowStartedAtMillis = null
            count = 0
            lastRemainingMillis = 0L
        }
    }

    private fun begin(timestampMillis: Long, remainingMillis: Long) {
        windowStartedAtMillis = timestampMillis
        count = 1
        lastRemainingMillis = remainingMillis
    }

    private fun snapshot() = ShakeCooldownSuppression(count, lastRemainingMillis)
}

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
    private val shakeSuppressions = ShakeCooldownSuppressionAggregator()
    private val readySuppressions = ArrayDeque<ShakeCooldownSuppression>()
    private var lastAcceptedShakeAtMillis: Long? = null

    fun accept(source: OnDemandSource, timestampMillis: Long): OnDemandSource? {
        val enabled = when (source) {
            OnDemandSource.MEDIA_BUTTON -> config.mediaButtonEnabled
            OnDemandSource.SHAKE -> config.shakeEnabled
        }
        if (!enabled) return null

        if (source == OnDemandSource.SHAKE && config.shakeCooldownMillis > 0L) {
            val lastShake = lastAcceptedShakeAtMillis
            if (lastShake != null) {
                val elapsed = timestampMillis - lastShake
                if (elapsed < config.shakeCooldownMillis) {
                    shakeSuppressions.record(
                        timestampMillis,
                        config.shakeCooldownMillis - elapsed,
                    )?.let(readySuppressions::addLast)
                    return null
                }
            }
        }

        if (!debouncer.accept(timestampMillis)) return null
        if (source == OnDemandSource.SHAKE) {
            lastAcceptedShakeAtMillis = timestampMillis
            shakeSuppressions.flush()?.let(readySuppressions::addLast)
        }
        return source
    }

    fun takeSuppressedEvents(): List<ShakeCooldownSuppression> = buildList {
        while (readySuppressions.isNotEmpty()) add(readySuppressions.removeFirst())
    }

    fun flushSuppressedEvents(): List<ShakeCooldownSuppression> {
        shakeSuppressions.flush()?.let(readySuppressions::addLast)
        return takeSuppressedEvents()
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
