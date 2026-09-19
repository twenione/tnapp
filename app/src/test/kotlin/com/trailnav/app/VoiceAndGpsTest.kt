package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.GuideConfig
import com.trailnav.core.RouteModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
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
    fun guideRecoveryFallingEdgeSpeaksOnceAndStableOnRouteDoesNotRepeat() {
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg>
                <trkpt lat="10.000000" lon="20.000000"/>
                <trkpt lat="10.001000" lon="20.000000"/>
                <trkpt lat="10.002000" lon="20.000000"/>
            </trkseg></trk></gpx>""".trimIndent(),
        )
        val config = GuideConfig(
            offRouteEnterDwellSeconds = 0.0,
            offRouteExitDwellSeconds = 0.0,
        )
        val session = GuideSession(route, config)
        val onRoute = session.accept(location(latitude = 10.001000, eastMeters = 0.0))
        val offRoute = session.accept(location(latitude = 10.001000, eastMeters = 30.0))
        val recovered = session.accept(location(latitude = 10.001000, eastMeters = 5.0))
        val stable = session.accept(location(latitude = 10.001000, eastMeters = 5.0))

        assertFalse(onRoute.result.nextState.offRoute)
        assertTrue(offRoute.result.nextState.offRoute)
        assertFalse(recovered.result.nextState.offRoute)
        assertEquals(
            RECOVERY_VOICE_PROMPT,
            recoveryVoicePrompt(offRoute.result.nextState.offRoute, recovered.result.nextState.offRoute),
        )
        assertFalse(isOffRouteRecovery(recovered.result.nextState.offRoute, stable.result.nextState.offRoute))
        assertNull(recoveryVoicePrompt(recovered.result.nextState.offRoute, stable.result.nextState.offRoute))
    }

    @Test
    fun runningServiceCanApplyNewCadenceSelection() {
        val scheduler = OnRouteVoiceScheduler(enabled = false, intervalSeconds = 0L)
        assertFalse(scheduler.onFrame(0L, onRoute = true))
        scheduler.configure(enabled = true, intervalSeconds = 60L)
        assertFalse(scheduler.onFrame(1_000L, onRoute = true))
        assertTrue(scheduler.onFrame(61_000L, onRoute = true))
    }

    private fun location(latitude: Double, eastMeters: Double): TrailLocation {
        val longitude = 20.0 + Math.toDegrees(
            eastMeters / (EARTH_RADIUS_METERS * kotlin.math.cos(Math.toRadians(latitude))),
        )
        return TrailLocation(40_000L, latitude, longitude, 5f, 1f, null, "test")
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
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

    @Test
    fun gpsSignalPromptsDoNotSuggestPermissionForGrantedButUnavailableSignal() {
        assertEquals(
            "GPS 신호를 찾는 중입니다. 실외로 이동하면 더 빨리 잡힙니다.",
            GpsSignalEvent.NoFixTimeout.toSpeechPrompt(),
        )
        assertEquals(
            "GPS 신호가 일시적으로 끊겼습니다. 실외로 이동하거나 잠시 기다려 주세요.",
            GpsSignalEvent.ProviderError.toSpeechPrompt(),
        )
        assertEquals(
            "GPS 신호가 약합니다. 안내 정확도가 떨어질 수 있습니다.",
            GpsSignalEvent.WeakSignal(80f).toSpeechPrompt(),
        )
    }
}
