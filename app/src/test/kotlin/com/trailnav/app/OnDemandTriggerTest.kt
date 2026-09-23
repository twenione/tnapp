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
}
