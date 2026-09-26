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
        assertEquals(true, config.mediaButtonEnabled)
        assertEquals(true, config.shakeEnabled)
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
    fun requestRouterRejectsDisabledSourcesAndDebouncesMedia() {
        val router = OnDemandRequestRouter(OnDemandConfig(shakeEnabled = false))
        assertTrue(router.accept(OnDemandSource.MEDIA_BUTTON, 0L) == OnDemandSource.MEDIA_BUTTON)
        assertTrue(router.accept(OnDemandSource.MEDIA_BUTTON, 1_000L) == null)
        assertTrue(router.accept(OnDemandSource.SHAKE, 2_000L) == null)
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
    fun shakeCooldownUsesInclusiveFiveMinuteBoundary() {
        val router = OnDemandRequestRouter(OnDemandConfig())

        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(null, router.accept(OnDemandSource.SHAKE, 1_500L))
        assertEquals(null, router.accept(OnDemandSource.SHAKE, 299_999L))
        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 300_000L))
    }

    @Test
    fun rejectedShakeDoesNotExtendCooldown() {
        val router = OnDemandRequestRouter(OnDemandConfig())

        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(null, router.accept(OnDemandSource.SHAKE, 200_000L))
        assertEquals(OnDemandSource.SHAKE, router.accept(OnDemandSource.SHAKE, 300_000L))
    }

    @Test
    fun mediaButtonIsIndependentOfShakeCooldownButSharesDebounce() {
        val duringCooldown = OnDemandRequestRouter(OnDemandConfig())
        assertEquals(OnDemandSource.SHAKE, duringCooldown.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(OnDemandSource.MEDIA_BUTTON, duringCooldown.accept(OnDemandSource.MEDIA_BUTTON, 1_500L))

        val mediaFirst = OnDemandRequestRouter(OnDemandConfig())
        assertEquals(OnDemandSource.MEDIA_BUTTON, mediaFirst.accept(OnDemandSource.MEDIA_BUTTON, 0L))
        assertEquals(OnDemandSource.SHAKE, mediaFirst.accept(OnDemandSource.SHAKE, 2_000L))

        val sharedDebounce = OnDemandRequestRouter(OnDemandConfig())
        assertEquals(OnDemandSource.SHAKE, sharedDebounce.accept(OnDemandSource.SHAKE, 0L))
        assertEquals(null, sharedDebounce.accept(OnDemandSource.MEDIA_BUTTON, 1_499L))
        assertEquals(OnDemandSource.MEDIA_BUTTON, sharedDebounce.accept(OnDemandSource.MEDIA_BUTTON, 1_500L))
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
    fun onDemandConfigWireFieldsIncludeTemporaryCooldown() {
        val fields = OnDemandConfig().toWireMap()

        assertEquals("300000", fields["shake_cooldown_ms"])
        assertEquals("1500", fields["debounce_ms"])
        assertEquals("8.0", fields["shake_threshold"])
        assertEquals("4", fields["shake_hits"])
        assertEquals("700", fields["shake_window_ms"])
    }
}
