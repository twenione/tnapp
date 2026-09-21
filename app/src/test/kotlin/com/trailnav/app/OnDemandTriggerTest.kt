package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnDemandTriggerTest {
    @Test
    fun walkingLikeSamplesDoNotTriggerAndTwoHitsDo() {
        val detector = ShakeDetector(OnDemandConfig())
        assertFalse(detector.onSample(0L, 0.8))
        assertFalse(detector.onSample(300L, 2.6))
        assertTrue(detector.onSample(500L, 2.5))
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
}
