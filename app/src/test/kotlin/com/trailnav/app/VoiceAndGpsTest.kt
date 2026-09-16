package com.trailnav.app

import com.trailnav.core.Guidance
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceAndGpsTest {
    @Test
    fun reverseStatusIsNeverMappedToSpeech() {
        assertNull(Guidance.Status("역방향 진행 중").toSpeech())
        assertTrue(Guidance.Status("다른 상태").toSpeech() == "다른 상태")
    }

    @Test
    fun onRouteSchedulerSpeaksOnceAtConfiguredInterval() {
        val scheduler = OnRouteVoiceScheduler(enabled = true, intervalSeconds = 60L)
        assertFalse(scheduler.onFrame(0L, onRoute = true))
        assertFalse(scheduler.onFrame(59_999L, onRoute = true))
        assertTrue(scheduler.onFrame(60_000L, onRoute = true))
        assertFalse(scheduler.onFrame(60_001L, onRoute = true))
    }

    @Test
    fun disabledSchedulerAndOffRouteFramesStaySilentAndReset() {
        val disabled = OnRouteVoiceScheduler(enabled = false, intervalSeconds = 60L)
        assertFalse(disabled.onFrame(0L, onRoute = true))
        assertFalse(disabled.onFrame(120_000L, onRoute = true))

        val scheduler = OnRouteVoiceScheduler(enabled = true, intervalSeconds = 60L)
        scheduler.onFrame(0L, onRoute = true)
        assertFalse(scheduler.onFrame(60_000L, onRoute = false))
        assertFalse(scheduler.onFrame(60_001L, onRoute = true))
        assertTrue(scheduler.onFrame(120_001L, onRoute = true))
    }

    @Test
    fun reverseFrameConsumesDueSlotWithoutSpeaking() {
        val scheduler = OnRouteVoiceScheduler(enabled = true, intervalSeconds = 60L)
        scheduler.onFrame(0L, onRoute = true)
        assertFalse(scheduler.onFrame(60_000L, onRoute = true, suppressAnnouncement = true))
        assertFalse(scheduler.onFrame(60_001L, onRoute = true))
    }

    @Test
    fun runningServiceCanApplyNewCadenceSelection() {
        val scheduler = OnRouteVoiceScheduler(enabled = false, intervalSeconds = 0L)
        assertFalse(scheduler.onFrame(0L, onRoute = true))
        scheduler.configure(enabled = true, intervalSeconds = 60L)
        assertFalse(scheduler.onFrame(1_000L, onRoute = true))
        assertTrue(scheduler.onFrame(61_000L, onRoute = true))
    }

    @Test
    fun gpsMonitorEmitsNoFixProviderAndWeakWarningsOnce() {
        val monitor = GpsSignalMonitor()
        monitor.start()
        assertIs<GpsSignalEvent.NoFixTimeout>(monitor.onNoFixTimeout())
        assertNull(monitor.onNoFixTimeout())
        assertNull(monitor.onProviderError())

        monitor.start()
        assertIs<GpsSignalEvent.ProviderError>(monitor.onProviderError())
        assertNull(monitor.onNoFixTimeout())

        monitor.start()
        val weak = monitor.onLocation(TrailLocation(0L, 0.0, 0.0, Float.NaN, null, null, "fake"))
        assertIs<GpsSignalEvent.WeakSignal>(weak)
        assertNull(monitor.onLocation(TrailLocation(1L, 0.0, 0.0, 80f, null, null, "fake")))
    }
}
