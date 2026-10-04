package com.trailnav.app

import com.trailnav.core.Guidance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnDemandTriggerTest {
    @Test
    fun walkingLikeSamplesDoNotTriggerAndFourHitsDo() {
        val config = OnDemandConfig()
        assertEquals(true, config.shakeEnabled)
        assertEquals(60_000L, config.shakeCooldownMillis)
        assertEquals(8.0, config.shakeThresholdMetersPerSecondSquared)
        assertEquals(4, config.shakeHitsRequired)
        val detector = ShakeDetector(config)
        assertFalse(detector.onSample(0L, 3.0))
        assertFalse(detector.onSample(250L, 6.5))
        assertFalse(detector.onSample(500L, 7.9))
        assertFalse(detector.onSample(750L, 8.0))
        assertFalse(detector.onSample(800L, 8.1))
        assertFalse(detector.onSample(850L, 8.2))
        assertTrue(detector.onSample(900L, 8.3))
    }

    @Test
    fun nonContiguousWalkingLikeSamplesDoNotTrigger() {
        val detector = ShakeDetector(OnDemandConfig())
        assertFalse(detector.onSample(0L, 8.0))
        assertFalse(detector.onSample(701L, 8.0))
        assertFalse(detector.onSample(1_402L, 8.0))
        assertFalse(detector.onSample(2_103L, 8.0))
    }

    @Test
    fun onlyTurnNowFlushesVoiceQueue() {
        assertTrue(shouldFlushVoiceQueue(Guidance.TurnNow(com.trailnav.core.Side.RIGHT)))
        assertFalse(shouldFlushVoiceQueue(Guidance.OffRoute(30.0, "forward")))
        assertFalse(shouldFlushVoiceQueue(Guidance.Status("상태")))
        assertFalse(shouldFlushVoiceQueue(Guidance.TurnAhead(20.0, com.trailnav.core.Side.LEFT)))
        assertFalse(shouldFlushVoiceQueue(Guidance.Sunset(30)))
        assertFalse(shouldFlushVoiceQueue(null))
    }

    @Test
    fun hitsOutsideWindowDoNotCombine() {
        val detector = ShakeDetector(OnDemandConfig(shakeWindowMillis = 700L))
        assertFalse(detector.onSample(0L, 3.0))
        assertFalse(detector.onSample(701L, 3.0))
    }

    @Test
    fun debouncerSuppressesOnlyTheConfiguredWindow() {
        val debouncer = OnDemandDebouncer(1_500L)
        assertTrue(debouncer.accept(0L))
        assertFalse(debouncer.accept(1_499L))
        assertTrue(debouncer.accept(1_500L))
    }

    @Test
    fun requestRouterRejectsDisabledShakeAndDebouncesEnabledShake() {
        val disabled = OnDemandRequestRouter(OnDemandConfig(shakeEnabled = false))
        assertEquals(null, disabled.accept(OnDemandSource.SHAKE, 0L))

        val enabled = OnDemandRequestRouter(OnDemandConfig(shakeCooldownMillis = 0L))
        assertEquals(OnDemandSource.SHAKE, enabled.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(null, enabled.accept(OnDemandSource.SHAKE, 1_000L))
        assertEquals(OnDemandSource.SHAKE, enabled.accept(OnDemandSource.SHAKE, 1_500L))
    }

    @Test
    fun shakeStatsEmitsOnlyAggregatesAtMinuteBoundary() {
        val stats = ShakeStats(thresholdMetersPerSecondSquared = 10.0)
        assertTrue(stats.record(0L, 3.0) == null)
        assertTrue(stats.record(10_000L, 12.0) == null)
        assertTrue(stats.record(20_000L, 8.0) == null)
        val completed = stats.record(60_000L, 4.0)
        assertTrue(completed != null)
        assertTrue(completed.sampleCount == 3)
        assertTrue(completed.maximumDeviation == 12.0)
        assertTrue(completed.p95Deviation == 12.0)
        assertTrue(completed.thresholdExceedances == 1)
        assertTrue(stats.flush()?.sampleCount == 1)
    }

    @Test
    fun shakeCooldownUsesInclusiveOneMinuteBoundary() {
        val router = OnDemandRequestRouter(OnDemandConfig())

        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(null, router.accept(OnDemandSource.SHAKE, 1_500L))
        assertEquals(null, router.accept(OnDemandSource.SHAKE, 59_999L))
        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 60_000L))
    }

    @Test
    fun rejectedShakeDoesNotExtendCooldown() {
        val router = OnDemandRequestRouter(OnDemandConfig())

        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(null, router.accept(OnDemandSource.SHAKE, 20_000L))
        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 60_000L))
    }

    @Test
    fun newRouterStartsWithAnEmptyShakeCooldown() {
        val firstSession = OnDemandRequestRouter(OnDemandConfig())
        assertEquals(OnDemandSource.SHAKE, firstSession.accept(OnDemandSource.SHAKE, 0L))

        val nextSession = OnDemandRequestRouter(OnDemandConfig())
        assertEquals(OnDemandSource.SHAKE, nextSession.accept(OnDemandSource.SHAKE, 0L))
    }

    @Test
    fun zeroShakeCooldownPreservesDebounceOnlyBehavior() {
        val router = OnDemandRequestRouter(OnDemandConfig(shakeCooldownMillis = 0L))

        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(null, router.accept(OnDemandSource.SHAKE, 1_499L))
        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 1_500L))
    }

    @Test
    fun detectorAndStatsContinueDuringRouterCooldown() {
        val config = OnDemandConfig()
        val router = OnDemandRequestRouter(config)
        val detector = ShakeDetector(config)
        val stats = ShakeStats(config.shakeThresholdMetersPerSecondSquared)
        val samples = listOf(
            0L to 8.0, 150L to 8.1, 300L to 8.2, 450L to 8.3,
            1_500L to 8.0, 1_650L to 8.1, 1_800L to 8.2, 1_950L to 8.3,
        )
        val routed = mutableListOf<OnDemandSource?>()
        var detections = 0
        for ((timestamp, deviation) in samples) {
            stats.record(timestamp, deviation)
            if (detector.onSample(timestamp, deviation)) {
                detections += 1
                routed += router.accept(OnDemandSource.SHAKE, timestamp)
            }
        }

        assertEquals(2, detections)
        assertEquals(listOf(OnDemandSource.SHAKE, null), routed)
        val completed = stats.record(60_000L, 6.0)
        assertEquals(8, completed?.sampleCount)
        assertEquals(8, completed?.thresholdExceedances)
    }

    @Test
    fun cooldownSuppressionsAggregateForOneMinuteAndRetainLastRemainingTime() {
        val aggregator = ShakeCooldownSuppressionAggregator()

        assertEquals(null, aggregator.record(10_000L, 290_000L))
        assertEquals(null, aggregator.record(30_000L, 270_000L))
        assertEquals(null, aggregator.record(69_999L, 230_001L))
        assertEquals(
            ShakeCooldownSuppression(count = 3, remainingMillis = 230_001L),
            aggregator.record(70_000L, 230_000L),
        )
        assertEquals(null, aggregator.record(129_999L, 170_001L))
        assertEquals(
            ShakeCooldownSuppression(count = 2, remainingMillis = 170_001L),
            aggregator.flush(),
        )
        assertEquals(null, aggregator.flush())
    }

    @Test
    fun onDemandConfigWireFieldsIncludeTemporaryStopGateAndOneMinuteCooldown() {
        val fields = OnDemandConfig().toWireMap()

        assertEquals("60000", fields["shake_cooldown_ms"])
        assertEquals("1500", fields["debounce_ms"])
        assertEquals("8.0", fields["shake_threshold"])
        assertEquals("4", fields["shake_hits"])
        assertEquals("700", fields["shake_window_ms"])
        assertEquals("temporary", fields["stop_gate"])
        assertEquals("0.5", fields["stop_gate_speed_mps"])
        assertEquals("3000", fields["stop_gate_settle_ms"])
        assertEquals("5000", fields["stop_gate_stale_ms"])
        assertEquals("50.0", fields["stop_gate_min_accuracy_m"])
        assertEquals(
            setOf(
                "shake_enabled",
                "debounce_ms",
                "shake_threshold",
                "shake_hits",
                "shake_window_ms",
                "shake_cooldown_ms",
                "stop_gate",
                "stop_gate_speed_mps",
                "stop_gate_settle_ms",
                "stop_gate_stale_ms",
                "stop_gate_min_accuracy_m",
            ),
            fields.keys,
        )
    }

    @Test
    fun stopGateUsesSpeedBoundaryAndThreeSecondDwell() {
        val gate = StopGate()
        gate.onLocation(0L, 0.49f, 50f)
        assertEquals(StopGateReason.SETTLING, gate.check(2_999L).reason)
        assertTrue(gate.check(3_000L).open)

        gate.onLocation(3_001L, 0.5f, 10f)
        assertEquals(StopGateReason.MOVING, gate.check(3_001L).reason)
        gate.onLocation(3_002L, 0.51f, 10f)
        assertEquals(StopGateReason.MOVING, gate.check(3_002L).reason)
    }

    @Test
    fun movementRestartsStopGateDwellAndUntrustedSamplesDoNotResetIt() {
        val gate = StopGate()
        gate.onLocation(0L, 0.1f, 10f)
        gate.onLocation(1_000L, 0.5f, 10f)
        gate.onLocation(2_000L, 0.1f, 10f)
        gate.onLocation(3_000L, null, 5f)
        assertEquals(StopGateReason.SETTLING, gate.check(4_999L).reason)
        assertTrue(gate.check(5_000L).open)
    }

    @Test
    fun stopGateFailsClosedWithoutTrustedSpeedAndExpiresStaleState() {
        val noFix = StopGate()
        assertEquals(StopGateReason.SPEED_UNAVAILABLE, noFix.check(0L).reason)
        noFix.onLocation(0L, Float.NaN, 1f)
        assertEquals(StopGateReason.SPEED_UNAVAILABLE, noFix.check(0L).reason)
        noFix.onLocation(1L, 0.1f, 50.1f)
        assertEquals(StopGateReason.SPEED_UNAVAILABLE, noFix.check(1L).reason)

        val stale = StopGate()
        stale.onLocation(0L, 0.1f, 10f)
        assertEquals(StopGateReason.SPEED_UNAVAILABLE, stale.check(5_001L).reason)
        stale.onLocation(6_000L, 0.1f, 10f)
        assertEquals(StopGateReason.SETTLING, stale.check(6_001L).reason)
    }

    @Test
    fun stopGateRestartsDwellAfterTrustedEvidenceGap() {
        val gate = StopGate()
        gate.onLocation(0L, 0.1f, 10f)
        gate.onLocation(5_001L, 0.1f, 10f)

        assertEquals(StopGateReason.SETTLING, gate.check(5_001L).reason)
        assertTrue(gate.check(8_001L).open)
    }

    @Test
    fun blockedShakeDoesNotConsumeCooldownOrDebounceAndDisabledShakeIsSilent() {
        val gate = StopGate()
        val router = OnDemandRequestRouter(OnDemandConfig())
        gate.onLocation(0L, 0.5f, 5f)
        assertEquals(
            StopGateReason.MOVING,
            routeOnDemandRequest(OnDemandSource.SHAKE, 1_000L, gate, router).stopGateReason,
        )

        gate.onLocation(2_000L, 0.1f, 5f)
        val accepted = routeOnDemandRequest(OnDemandSource.SHAKE, 5_000L, gate, router)
        assertEquals(OnDemandSource.SHAKE, accepted.acceptedSource)
        assertEquals(null, accepted.stopGateReason)

        val disabledRouter = OnDemandRequestRouter(OnDemandConfig(shakeEnabled = false))
        assertEquals(
            OnDemandRouteDecision(),
            routeOnDemandRequest(OnDemandSource.SHAKE, 6_000L, StopGate(), disabledRouter),
        )
    }

    @Test
    fun stopGateSuppressionsAggregateOneMinutePerReason() {
        val aggregator = StopGateSuppressionAggregator()
        assertEquals(null, aggregator.record(0L, StopGateReason.MOVING))
        assertEquals(null, aggregator.record(10_000L, StopGateReason.MOVING))
        assertEquals(null, aggregator.record(20_000L, StopGateReason.SETTLING))
        assertEquals(
            StopGateSuppression(StopGateReason.MOVING, count = 2),
            aggregator.record(60_000L, StopGateReason.MOVING),
        )
        assertEquals(
            listOf(
                StopGateSuppression(StopGateReason.MOVING, count = 1),
                StopGateSuppression(StopGateReason.SETTLING, count = 1),
            ),
            aggregator.flush(),
        )
        assertEquals(
            mapOf("source" to "shake", "reason" to "stop-gate-speed-unavailable", "count" to "3"),
            StopGateSuppression(StopGateReason.SPEED_UNAVAILABLE, count = 3).toWireMap(),
        )
    }
}
