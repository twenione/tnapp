package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.GuideConfig
import com.trailnav.core.Side
import com.trailnav.core.RouteModel
import com.trailnav.core.SlopeKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceAndGpsTest {
    @Test
    fun periodicVoiceModeMigratesLegacyValuesToTone() {
        assertEquals(NavigationPreferences.PeriodicVoiceMode.OFF,
            NavigationPreferences.PeriodicVoiceMode.fromWire("OFF"))
        assertEquals(NavigationPreferences.PeriodicVoiceMode.TONE,
            NavigationPreferences.PeriodicVoiceMode.fromWire("PROMPT"))
        assertEquals(NavigationPreferences.PeriodicVoiceMode.TONE,
            NavigationPreferences.PeriodicVoiceMode.fromWire("TONE"))
        assertEquals(NavigationPreferences.PeriodicVoiceMode.TONE,
            NavigationPreferences.PeriodicVoiceMode.fromWire("unknown"))
    }
    @Test
    fun pauseAvailabilityPromptsOnceAtFiveMinutesAndResetsAfterRecovery() {
        val tracker = GuidancePauseAvailability()
        assertFalse(tracker.onFrame(offRoute = true, nowMillis = 0L))
        assertFalse(tracker.onFrame(offRoute = true, nowMillis = 299_999L))
        assertTrue(tracker.onFrame(offRoute = true, nowMillis = 300_000L))
        assertFalse(tracker.onFrame(offRoute = true, nowMillis = 600_000L))
        assertFalse(tracker.onFrame(offRoute = false, nowMillis = 601_000L))
        assertFalse(tracker.onFrame(offRoute = true, nowMillis = 601_000L))
        assertTrue(tracker.onFrame(offRoute = true, nowMillis = 901_000L))
    }

    @Test
    fun pausedVoiceGateAllowsSunsetButSuppressesAutomaticVoices() {
        VoiceKind.values().filterNot { it == VoiceKind.SUNSET }.forEach { kind ->
            assertFalse(GuidanceVoicePolicy.decide(paused = true, kind = kind).allowed)
            assertEquals("paused", GuidanceVoicePolicy.decide(paused = true, kind = kind).suppressionReason)
            assertTrue(GuidanceVoicePolicy.decide(paused = false, kind = kind).allowed)
        }
        assertTrue(GuidanceVoicePolicy.decide(paused = true, kind = VoiceKind.SUNSET).allowed)
        assertNull(GuidanceVoicePolicy.decide(paused = true, kind = VoiceKind.SUNSET).suppressionReason)
        assertFalse(shouldSpeakVoice(ending = true, paused = false, kind = VoiceKind.SUNSET))
    }

    @Test
    fun onlySunsetGuidanceMapsToThePauseExemptVoiceKind() {
        val automaticGuidance = listOf<Guidance?>(
            Guidance.Milestone(1_000.0),
            Guidance.Elapsed(1),
            Guidance.Remaining(2_000.0),
            Guidance.Slope(SlopeKind.ASCENT, 25.0),
            Guidance.Elevation(300.0),
            Guidance.Waypoint(1, "망경대", 100.0),
            Guidance.Sunrise(10),
            Guidance.TurnAhead(50.0, Side.LEFT),
            Guidance.TurnNow(Side.RIGHT),
            Guidance.OffRoute(30.0, "right"),
            Guidance.Arrived,
            Guidance.Status("상태 안내"),
            null,
        )

        automaticGuidance.forEach { guidance ->
            assertEquals(VoiceKind.GUIDANCE, voiceKindFor(guidance))
            assertFalse(shouldSpeakVoice(ending = false, paused = true, kind = voiceKindFor(guidance)))
        }
        val sunset = Guidance.Sunset(minutesRemaining = 30)
        assertEquals(VoiceKind.SUNSET, voiceKindFor(sunset))
        assertTrue(shouldSpeakVoice(ending = false, paused = true, kind = voiceKindFor(sunset)))
    }

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
    fun reverseFrameStillSpeaksAtConfiguredInterval() {
        val scheduler = OnRouteVoiceScheduler(enabled = true, intervalSeconds = 60L)
        scheduler.onFrame(0L, onRoute = true)
        assertTrue(scheduler.onFrame(60_000L, onRoute = true))
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
            "GPS 신호를 찾는 중입니다.",
            GpsSignalEvent.NoFixTimeout.toSpeechPrompt(),
        )
        assertEquals(
            "GPS 신호가 일시적으로 끊겼습니다.",
            GpsSignalEvent.ProviderError.toSpeechPrompt(),
        )
        assertEquals(
            "GPS 신호가 약합니다.",
            GpsSignalEvent.WeakSignal(80f).toSpeechPrompt(),
        )
    }
}
