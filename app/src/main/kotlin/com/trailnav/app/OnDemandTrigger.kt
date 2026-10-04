package com.trailnav.app

/** Sources that can request an on-demand navigation status. */
enum class OnDemandSource(val wireName: String) {
    SHAKE("shake");
}

/** Pure trigger settings kept at the app boundary; these are not engine parameters. */
data class OnDemandConfig(
    val shakeEnabled: Boolean = true,
    val debounceMillis: Long = 1_500L,
    // 정지 게이트 전제: 멈춰서 연속으로 흔들 때 중복 방지 (D-069)
    val shakeCooldownMillis: Long = 60_000L,
    // Provisional field-test baseline: walking-like 3.0-6.5 m/s² samples do
    // not trigger; a deliberate shake must reach 8.0 m/s² four times in the
    // window. Revisit after separate normal-walk and deliberate-shake runs.
    val shakeThresholdMetersPerSecondSquared: Double = 8.0,
    val shakeHitsRequired: Int = 4,
    val shakeWindowMillis: Long = 700L,
    val stopGateSpeedThresholdMps: Float = 0.5f,
    val stopGateSettleMillis: Long = 3_000L,
    val stopGateStaleMillis: Long = 5_000L,
    val stopGateMinAccuracyMeters: Float = 50f,
)

/** Stable wire fields recorded with each session's on-demand settings. */
fun OnDemandConfig.toWireMap(): Map<String, String> = linkedMapOf(
    "shake_enabled" to shakeEnabled.toString(),
    "debounce_ms" to debounceMillis.toString(),
    "shake_threshold" to shakeThresholdMetersPerSecondSquared.toString(),
    "shake_hits" to shakeHitsRequired.toString(),
    "shake_window_ms" to shakeWindowMillis.toString(),
    "shake_cooldown_ms" to shakeCooldownMillis.toString(),
    "stop_gate" to "temporary",
    "stop_gate_speed_mps" to stopGateSpeedThresholdMps.toString(),
    "stop_gate_settle_ms" to stopGateSettleMillis.toString(),
    "stop_gate_stale_ms" to stopGateStaleMillis.toString(),
    "stop_gate_min_accuracy_m" to stopGateMinAccuracyMeters.toString(),
)

data class OnDemandSessionPlan(
    val config: OnDemandConfig,
    val configEventFields: Map<String, String>,
    val registerShakeListener: Boolean,
)

/** Resolves the saved toggle once so runtime, session evidence, and sensor wiring agree. */
fun createOnDemandSessionPlan(shakeEnabled: Boolean): OnDemandSessionPlan {
    val config = OnDemandConfig(shakeEnabled = shakeEnabled)
    return OnDemandSessionPlan(
        config = config,
        configEventFields = config.toWireMap() + mapOf(
            "shake_sampling" to if (config.shakeEnabled) "SENSOR_DELAY_GAME" else "disabled",
            "shake_stats" to if (config.shakeEnabled) "per-minute-aggregates" else "disabled",
        ),
        registerShakeListener = config.shakeEnabled,
    )
}

enum class StopGateReason(val wireName: String) {
    MOVING("stop-gate-moving"),
    SETTLING("stop-gate-settling"),
    SPEED_UNAVAILABLE("stop-gate-speed-unavailable"),
}

data class StopGateVerdict(val reason: StopGateReason?) {
    val open: Boolean get() = reason == null
}

/**
 * Temporary, fail-closed gate for shake requests. It uses only trusted recent
 * location speed and accuracy; sensor samples and wall-clock time are excluded.
 */
class StopGate(
    private val speedThresholdMps: Float = 0.5f,
    private val settleMillis: Long = 3_000L,
    private val staleMillis: Long = 5_000L,
    private val minAccuracyMeters: Float = 50f,
) {
    init {
        require(speedThresholdMps.isFinite() && speedThresholdMps >= 0f)
        require(settleMillis >= 0L)
        require(staleMillis >= 0L)
        require(minAccuracyMeters.isFinite() && minAccuracyMeters >= 0f)
    }

    private var latestTrustedAtMillis: Long? = null
    private var latestTrustedStopped: Boolean? = null
    private var stoppedSinceMillis: Long? = null

    fun onLocation(arrivalElapsedMillis: Long, speedMps: Float?, accuracyMeters: Float) {
        val previousTrustedAt = latestTrustedAtMillis
        if (previousTrustedAt != null &&
            elapsedSince(arrivalElapsedMillis, previousTrustedAt)?.let { it > staleMillis } == true
        ) {
            clearTrustedState()
        }

        val trusted = speedMps != null && speedMps.isFinite() && speedMps >= 0f &&
            accuracyMeters.isFinite() && accuracyMeters >= 0f && accuracyMeters <= minAccuracyMeters
        if (trusted) {
            val stopped = speedMps!! < speedThresholdMps
            latestTrustedAtMillis = arrivalElapsedMillis
            latestTrustedStopped = stopped
            if (stopped) {
                if (stoppedSinceMillis == null) stoppedSinceMillis = arrivalElapsedMillis
            } else {
                stoppedSinceMillis = null
            }
            return
        }
    }

    fun check(nowElapsedMillis: Long): StopGateVerdict {
        val latest = latestTrustedAtMillis ?: return StopGateVerdict(StopGateReason.SPEED_UNAVAILABLE)
        val age = elapsedSince(nowElapsedMillis, latest)
        if (age == null || age > staleMillis) {
            clearTrustedState()
            return StopGateVerdict(StopGateReason.SPEED_UNAVAILABLE)
        }
        if (latestTrustedStopped != true) return StopGateVerdict(StopGateReason.MOVING)
        val stoppedSince = stoppedSinceMillis ?: return StopGateVerdict(StopGateReason.SPEED_UNAVAILABLE)
        val stoppedFor = elapsedSince(nowElapsedMillis, stoppedSince)
            ?: return StopGateVerdict(StopGateReason.SPEED_UNAVAILABLE)
        return if (stoppedFor >= settleMillis) StopGateVerdict(null)
        else StopGateVerdict(StopGateReason.SETTLING)
    }

    private fun clearTrustedState() {
        latestTrustedAtMillis = null
        latestTrustedStopped = null
        stoppedSinceMillis = null
    }

    private fun elapsedSince(now: Long, then: Long): Long? =
        if (now >= then) now - then else null
}

data class StopGateSuppression(
    val reason: StopGateReason,
    val count: Int,
) {
    fun toWireMap(): Map<String, String> = linkedMapOf(
        "source" to OnDemandSource.SHAKE.wireName,
        "reason" to reason.wireName,
        "count" to count.toString(),
    )
}

/** Aggregates blocked shake requests for one minute per stop-gate reason. */
class StopGateSuppressionAggregator(private val windowMillis: Long = 60_000L) {
    init {
        require(windowMillis > 0L)
    }

    private data class Bucket(var startedAtMillis: Long, var count: Int)

    private val buckets = linkedMapOf<StopGateReason, Bucket>()

    fun record(timestampMillis: Long, reason: StopGateReason): StopGateSuppression? {
        val bucket = buckets[reason]
        if (bucket == null) {
            buckets[reason] = Bucket(timestampMillis, 1)
            return null
        }
        if (elapsedSince(timestampMillis, bucket.startedAtMillis)?.let { it >= windowMillis } == true) {
            val completed = StopGateSuppression(reason, bucket.count)
            bucket.startedAtMillis = timestampMillis
            bucket.count = 1
            return completed
        }
        bucket.count += 1
        return null
    }

    fun flush(): List<StopGateSuppression> = buckets.map { (reason, bucket) ->
        StopGateSuppression(reason, bucket.count)
    }.also { buckets.clear() }

    private fun elapsedSince(now: Long, then: Long): Long? =
        if (now >= then) now - then else null
}

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

/** Pure source gate used by the shake entry point. */
class OnDemandRequestRouter(private val config: OnDemandConfig) {
    private val debouncer = OnDemandDebouncer(config.debounceMillis)
    private val shakeSuppressions = ShakeCooldownSuppressionAggregator()
    private val readySuppressions = ArrayDeque<ShakeCooldownSuppression>()
    private var lastAcceptedShakeAtMillis: Long? = null

    fun isEnabled(source: OnDemandSource): Boolean =
        source == OnDemandSource.SHAKE && config.shakeEnabled

    fun accept(source: OnDemandSource, timestampMillis: Long): OnDemandSource? {
        if (!isEnabled(source)) return null

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

data class OnDemandRouteDecision(
    val acceptedSource: OnDemandSource? = null,
    val stopGateReason: StopGateReason? = null,
)

/** Checks the stop gate before debounce/cooldown so a blocked request consumes neither. */
fun routeOnDemandRequest(
    source: OnDemandSource,
    timestampMillis: Long,
    stopGate: StopGate,
    router: OnDemandRequestRouter,
): OnDemandRouteDecision {
    if (!router.isEnabled(source)) return OnDemandRouteDecision()
    if (source == OnDemandSource.SHAKE) {
        val verdict = stopGate.check(timestampMillis)
        if (!verdict.open) return OnDemandRouteDecision(stopGateReason = verdict.reason)
    }
    return OnDemandRouteDecision(acceptedSource = router.accept(source, timestampMillis))
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
