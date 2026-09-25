package com.trailnav.core

import kotlin.test.Test
import kotlin.math.cos
import java.time.Instant

/** Off-route hysteresis, accuracy filtering, arrival, and re-announcement checks. */
class GuidanceTest {
    @Test
    fun hysteresisAccuracyArrivalAndReannouncement() {
    val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.002"/></trkseg></trk></gpx>"""
    val route = RouteModel.fromGpx(xml)
    val config = GuideConfig(
        offRouteEnterDwellSeconds = 2.0,
        offRouteExitDwellSeconds = 2.0,
        minimumSessionSecondsBeforeArrival = 0.0,
    )
    var state = GuideState.initial(route)
    val filtered = guide(state, SensorFrame(0, 10.0, 20.0, 51f, 1f, null), config)
    check(filtered.guidance == null && filtered.nextState.sessionStartTimestamp == 0L)
    state = guide(state, SensorFrame(0, 10.0, 20.0, 5f, 1f, null), config).nextState
    val far1 = guide(state, SensorFrame(1_000, 10.0005, 20.0, 5f, 1f, null), config)
    check(!far1.nextState.offRoute)
    val far2 = guide(far1.nextState, SensorFrame(3_000, 10.0005, 20.0, 5f, 1f, null), config)
    check(far2.nextState.offRoute && far2.guidance is Guidance.OffRoute)
    val near1 = guide(far2.nextState, SensorFrame(4_000, 10.0, 20.0005, 5f, 1f, null), config)
    check(near1.nextState.offRoute)
    val near2 = guide(near1.nextState, SensorFrame(6_000, 10.0, 20.0005, 5f, 1f, null), config)
    check(!near2.nextState.offRoute)
    val middle = guide(GuideState.initial(route), SensorFrame(0, 10.0, 20.001, 5f, 1f, null), config)
    check(middle.guidance == null)
    val end = guide(middle.nextState, SensorFrame(1_000, 10.0, 20.002, 5f, 1f, null), config)
    check(end.guidance == Guidance.Arrived && end.nextState.arrived)
    }

    @Test
    fun expiredEnterCandidateDoesNotCreateFalseOffRoute() {
        val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>"""
        val route = RouteModel.fromGpx(xml)
        val config = GuideConfig()
        val metersPerDegreeLon = 6371008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east26Meters = 26.0 / metersPerDegreeLon
        val onRouteLat = 10.0005
        val onRouteLon = 20.0
        val offRouteLon = onRouteLon + east26Meters

        var state = GuideState.initial(route)
        val firstSpike = guide(
            state,
            SensorFrame(0L, onRouteLat, offRouteLon, 5f, 1f, null),
            config
        )
        check(!firstSpike.nextState.offRoute)
        check(firstSpike.nextState.candidateOffRouteSince == 0L)
        state = firstSpike.nextState

        val briefRecovery = guide(
            state,
            SensorFrame(1_000L, onRouteLat, onRouteLon, 5f, 1f, null),
            config
        )
        check(!briefRecovery.nextState.offRoute)
        check(briefRecovery.nextState.candidateOffRouteSince == 0L)
        state = briefRecovery.nextState

        val longRecovery = guide(
            state,
            SensorFrame(10_800_000L, onRouteLat, onRouteLon, 5f, 1f, null),
            config
        )
        check(!longRecovery.nextState.offRoute)
        check(longRecovery.nextState.candidateOffRouteSince == null)
        state = longRecovery.nextState

        val lateSpike = guide(
            state,
            SensorFrame(10_800_001L, onRouteLat, offRouteLon, 5f, 1f, null),
            config
        )
        check(!lateSpike.nextState.offRoute)
        check(lateSpike.nextState.candidateOffRouteSince == 10_800_001L)
        check(lateSpike.guidance !is Guidance.OffRoute)
    }

    @Test
    fun arrivalNearEndIsBlockedDuringStartupGuard() {
        val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.002"/></trkseg></trk></gpx>"""
        val route = RouteModel.fromGpx(xml)
        val config = GuideConfig()
        val nearEnd = SensorFrame(0L, 10.0, 20.0019, 5f, 1f, null)

        val immediate = guide(GuideState.initial(route), nearEnd, config)
        check(immediate.nextState.sessionStartTimestamp == 0L)
        check(immediate.guidance != Guidance.Arrived) {
            "a first frame near 95% progress must not arrive immediately"
        }

        val beforeMinimum = guide(
            immediate.nextState,
            nearEnd.copy(timestamp = 5_000L),
            config,
        )
        check(beforeMinimum.guidance != Guidance.Arrived)

        val afterMinimum = guide(
            beforeMinimum.nextState,
            nearEnd.copy(timestamp = 10_000L),
            config,
        )
        check(afterMinimum.guidance == Guidance.Arrived)
        check(afterMinimum.nextState.arrived)
    }

    @Test
    fun subsecondOffRouteReannounceDoesNotBypassDwell() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>""")
        val config = GuideConfig(offRouteEnterDwellSeconds = 0.0, reannounceIntervalSeconds = 60.0)
        val metersPerDegreeLon = 6371008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east30Meters = 30.0 / metersPerDegreeLon
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
        check(first.guidance is Guidance.OffRoute)
        val second = guide(first.nextState, SensorFrame(999L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
        check(second.guidance == null) { "999 ms must not satisfy a 60 second reannounce interval" }
        val afterDwell = guide(second.nextState, SensorFrame(60_000L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
        check(afterDwell.guidance is Guidance.OffRoute) { "a full 60 second interval must permit re-announcement" }
    }

    @Test
    fun offRouteReannouncesWhenApproachingByHalfDistanceBeforeInterval() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>""")
        val config = GuideConfig(offRouteEnterDwellSeconds = 0.0, offRouteExitDwellSeconds = 10.0, reannounceIntervalSeconds = 60.0)
        val metersPerDegreeLon = 6371008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east30Meters = 30.0 / metersPerDegreeLon
        val east14Meters = 14.0 / metersPerDegreeLon
        val first = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0005, 20.0 + east30Meters, 5f, 1f, null),
            config,
        )
        check(first.guidance is Guidance.OffRoute)
        val approaching = guide(
            first.nextState,
            SensorFrame(1_000L, 10.0005, 20.0 + east14Meters, 5f, 1f, null),
            config,
        )
        check(approaching.nextState.offRoute) { "exit dwell must still hold off-route state" }
        check(approaching.guidance is Guidance.OffRoute) {
            "approaching to below half the last distance must re-announce before the interval"
        }
    }

    @Test
    fun recoveryDwellDoesNotExitAfterSubsecondGap() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>""")
        val config = GuideConfig(offRouteEnterDwellSeconds = 0.0, offRouteExitDwellSeconds = 10.0)
        val metersPerDegreeLon = 6371008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east30Meters = 30.0 / metersPerDegreeLon
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
        check(first.nextState.offRoute)
        val recovery = guide(first.nextState, SensorFrame(999L, 10.0005, 20.0, 5f, 1f, null), config)
        check(recovery.nextState.offRoute) { "999 ms must not satisfy a 10 second recovery dwell" }
        val stillRecovering = guide(recovery.nextState, SensorFrame(1_998L, 10.0005, 20.0, 5f, 1f, null), config)
        check(stillRecovering.nextState.offRoute) { "a second 999 ms frame must still be below the 10 second dwell" }
        val recovered = guide(stillRecovering.nextState, SensorFrame(11_000L, 10.0005, 20.0, 5f, 1f, null), config)
        check(!recovered.nextState.offRoute) { "a full recovery dwell must clear off-route" }
    }

    @Test
    fun reverseWarningDoesNotTriggerAfterSubsecondGap() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>""")
        val config = GuideConfig(reverseWarningDwellSeconds = 60.0)
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0015, 20.0, 5f, 1f, null), config)
        val second = guide(first.nextState, SensorFrame(997L, 10.0010, 20.0, 5f, 1f, null), config)
        val third = guide(second.nextState, SensorFrame(1_994L, 10.0005, 20.0, 5f, 1f, null), config)
        check(third.guidance !is Guidance.Status) { "997 ms must not satisfy a 60 second reverse warning dwell" }
        val afterDwell = guide(third.nextState, SensorFrame(61_000L, 10.0005, 20.0, 5f, 1f, null), config)
        check(afterDwell.guidance is Guidance.Status && (afterDwell.guidance as Guidance.Status).message == "역방향 진행 중") {
            "a full reverse dwell must emit the reverse warning"
        }
    }

    @Test
    fun reverseStatusIsIssuedOnceAndResetsAfterLeavingReverse() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
            <trkpt lat="10.0" lon="20.0"/><trkpt lat="10.004" lon="20.0"/>
        </trkseg></trk></gpx>""")
        val config = GuideConfig(
            reverseWarningDwellSeconds = 2.0,
            periodicEnabled = false,
            sunsetEnabled = false,
            minimumSessionSecondsBeforeArrival = 0.0,
        )
        var state = guide(GuideState.initial(route), SensorFrame(0L, 10.002, 20.0, 5f, 1f, null), config).nextState
        val beforeDwell = guide(state, SensorFrame(1_000L, 10.0018, 20.0, 5f, 1f, null), config)
        check(beforeDwell.guidance !is Guidance.Status)
        state = beforeDwell.nextState

        val firstStatus = guide(state, SensorFrame(3_000L, 10.0016, 20.0, 5f, 1f, null), config)
        check(firstStatus.guidance is Guidance.Status)
        check(firstStatus.nextState.reverseStatusIssued)
        val repeated = guide(firstStatus.nextState, SensorFrame(4_000L, 10.0014, 20.0, 5f, 1f, null), config)
        check(repeated.guidance == null) { "reverse status must not repeat on every frame" }

        val forward = guide(repeated.nextState, SensorFrame(5_000L, 10.0016, 20.0, 5f, 1f, null), config)
        check(forward.nextState.direction == ProgressDirection.FORWARD)
        check(!forward.nextState.reverseStatusIssued)
        val reverseAgain = guide(forward.nextState, SensorFrame(6_000L, 10.0014, 20.0, 5f, 1f, null), config)
        val secondStatus = guide(reverseAgain.nextState, SensorFrame(8_000L, 10.0012, 20.0, 5f, 1f, null), config)
        check(secondStatus.guidance is Guidance.Status)
    }

    @Test
    fun reverseAllowsElapsedAndSunsetEventsAfterDwell() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
            <trkpt lat="37.5665" lon="126.9780"/><trkpt lat="37.5865" lon="126.9780"/>
        </trkseg></trk></gpx>""")
        val config = GuideConfig(
            periodicEnabled = true,
            elapsedEnabled = true,
            elapsedAnnounceIntervalSeconds = 1.0,
            eventMinIntervalSeconds = 0.0,
            reverseWarningDwellSeconds = 60.0,
            sunsetEnabled = false,
            minimumSessionSecondsBeforeArrival = 0.0,
        )
        val start = guide(
            GuideState.initial(route),
            SensorFrame(0L, 37.5765, 126.9780, 5f, 1f, null),
            config,
        )
        val reverse = guide(
            start.nextState,
            SensorFrame(61_000L, 37.5755, 126.9780, 5f, 1f, null),
            config,
        )
        check(reverse.guidance is Guidance.Elapsed) {
            "elapsed event must be allowed in reverse after the dwell"
        }

        val sunsetConfig = config.copy(
            periodicEnabled = false,
            elapsedEnabled = false,
            sunsetEnabled = true,
            reverseWarningDwellSeconds = 60.0,
        )
        val sunsetStart = guide(
            GuideState.initial(route),
            SensorFrame(epoch("2026-09-21T08:59:00Z"), 37.5765, 126.9780, 5f, 1f, null),
            sunsetConfig,
        )
        var state = guide(
            sunsetStart.nextState,
            SensorFrame(epoch("2026-09-21T08:59:30Z"), 37.5770, 126.9780, 5f, 1f, null),
            sunsetConfig,
        ).nextState
        state = guide(
            state,
            SensorFrame(epoch("2026-09-21T09:00:30Z"), 37.5765, 126.9780, 5f, 1f, null),
            sunsetConfig,
        ).nextState
        val reverseSunset = guide(
            state,
            SensorFrame(epoch("2026-09-21T09:05:00Z"), 37.5760, 126.9780, 5f, 1f, null),
            sunsetConfig,
        )
        check(reverseSunset.guidance is Guidance.Sunset) {
            "sunset must be emitted while reverse dwell is active"
        }
    }

    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()

    @Test
    fun enterDwellDoesNotTriggerAfterSubsecondGap() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>""")
        val config = GuideConfig(offRouteEnterDwellSeconds = 20.0)
        val metersPerDegreeLon = 6371008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east30Meters = 30.0 / metersPerDegreeLon
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
        val second = guide(first.nextState, SensorFrame(944L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
        check(!second.nextState.offRoute) { "944 ms must not satisfy a 20 second enter dwell" }
        val afterDwell = guide(second.nextState, SensorFrame(20_000L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
        check(afterDwell.nextState.offRoute) { "a full enter dwell must mark the frame off-route" }
    }

    @Test
    fun arrivalGuardDoesNotTriggerAfterSubsecondGap() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.002"/></trkseg></trk></gpx>""")
        val nearEnd = SensorFrame(0L, 10.0, 20.0019, 5f, 1f, null)
        val first = guide(GuideState.initial(route), nearEnd, GuideConfig())
        val second = guide(first.nextState, nearEnd.copy(timestamp = 944L), GuideConfig())
        check(second.guidance != Guidance.Arrived) { "944 ms must not satisfy a 10 second startup guard" }
        val afterGuard = guide(second.nextState, nearEnd.copy(timestamp = 10_000L), GuideConfig())
        check(afterGuard.guidance == Guidance.Arrived) { "a full startup guard must permit arrival" }
    }
}
