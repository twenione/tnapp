package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnDemandTriggerTest {
    @Test
    fun walkingLikeSamplesDoNotTriggerAndThreeHitsDo() {
        val config = OnDemandConfig()
        assertEquals(true, config.mediaButtonEnabled)
        assertEquals(true, config.shakeEnabled)
        assertEquals(6.0, config.shakeThresholdMetersPerSecondSquared)
        val detector = ShakeDetector(config)
        assertFalse(detector.onSample(0L, 3.0))
        assertFalse(detector.onSample(250L, 5.9))
        assertFalse(detector.onSample(500L, 4.0))
        assertFalse(detector.onSample(750L, 6.0))
        assertFalse(detector.onSample(800L, 6.1))
        assertTrue(detector.onSample(900L, 6.2))
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
