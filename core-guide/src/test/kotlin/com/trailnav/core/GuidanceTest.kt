package com.trailnav.core

import kotlin.test.Test
import kotlin.math.abs
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

        var state = GuideState.initial(route).copy(hasEnteredRoute = true)
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
    fun approachGuidanceFiresBeforeFirstRouteEntryInsteadOfOffRoute() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.004"/></trkseg></trk></gpx>""")
        val config = GuideConfig(sunsetEnabled = false)
        val metersPerDegreeLat = 6_371_008.8 * Math.PI / 180.0
        val south85Lat = 10.0 - 85.0 / metersPerDegreeLat
        var state = GuideState.initial(route)
        val results = listOf(0L, 20_000L, 60_000L).map { timestamp ->
            val result = guide(state, SensorFrame(timestamp, south85Lat, 20.002, 5f, 1f, null), config)
            state = result.nextState
            result
        }

        val firstApproach = results.first().guidance as? Guidance.Approach
        check(firstApproach != null) { "85 m before first entry should announce approach, got ${results.first().guidance}" }
        check(abs(firstApproach.distanceMeters - 85.0) < 1.0)
        check(abs(firstApproach.bearingDegrees) < 1.0) { "a user south of the route should be directed north" }
        check(results.none { it.guidance is Guidance.OffRoute }) {
            "pre-entry must not emit OFF_ROUTE after the enter dwell; got ${results.map { it.guidance }}"
        }
        check(results.count { it.guidance is Guidance.Approach } == 2)
        check(!state.hasEnteredRoute)
    }

    @Test
    fun firstEntryFlipsPermanentlyAndOffRouteDetectionResumesAfter() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.004"/></trkseg></trk></gpx>""")
        val config = GuideConfig(
            offRouteEnterDwellSeconds = 1.0,
            reannounceIntervalSeconds = 1.0,
            sunsetEnabled = false,
        )
        val metersPerDegreeLat = 6_371_008.8 * Math.PI / 180.0
        val middleLon = 20.002
        val approach = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0 - 85.0 / metersPerDegreeLat, middleLon, 5f, 1f, null),
            config,
        )
        val entry = guide(
            approach.nextState,
            SensorFrame(1_000L, 10.0 + 14.0 / metersPerDegreeLat, middleLon, 5f, 1f, null),
            config,
        )
        check(entry.nextState.hasEnteredRoute) { "entry below 15 m must permanently set hasEnteredRoute" }
        val departureStart = guide(
            entry.nextState,
            SensorFrame(2_000L, 10.0 + 30.0 / metersPerDegreeLat, middleLon, 5f, 1f, null),
            config,
        )
        check(!departureStart.nextState.offRoute)
        val departed = guide(
            departureStart.nextState,
            SensorFrame(3_000L, 10.0 + 30.0 / metersPerDegreeLat, middleLon, 5f, 1f, null),
            config,
        )
        check(departed.nextState.hasEnteredRoute)
        check(departed.nextState.offRoute)
        check(departed.guidance is Guidance.OffRoute) {
            "after first entry, the normal off-route dwell and announcement must resume; got ${departed.guidance}"
        }
    }

    @Test
    fun approachReannounceRespectsInterval() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.004"/></trkseg></trk></gpx>""")
        val config = GuideConfig(sunsetEnabled = false)
        val metersPerDegreeLat = 6_371_008.8 * Math.PI / 180.0
        val first = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0 - 85.0 / metersPerDegreeLat, 20.002, 5f, 1f, null),
            config,
        )
        check(first.guidance is Guidance.Approach)
        val beforeInterval = guide(
            first.nextState,
            SensorFrame(59_999L, 10.0 - 85.0 / metersPerDegreeLat, 20.002, 5f, 1f, null),
            config,
        )
        check(beforeInterval.guidance == null)
        check(beforeInterval.reason.rule == "approach.holding")
        val afterInterval = guide(
            beforeInterval.nextState,
            SensorFrame(60_000L, 10.0 - 85.0 / metersPerDegreeLat, 20.002, 5f, 1f, null),
            config,
        )
        check(afterInterval.guidance is Guidance.Approach)
    }

    @Test
    fun approachBearingPointsFromUserTowardRoute() {
        val southRoute = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.004"/></trkseg></trk></gpx>""")
        val metersPerDegreeLat = 6_371_008.8 * Math.PI / 180.0
        val fromSouth = guide(
            GuideState.initial(southRoute),
            SensorFrame(0L, 10.0 - 85.0 / metersPerDegreeLat, 20.002, 5f, 1f, null),
            GuideConfig(sunsetEnabled = false),
        ).guidance as? Guidance.Approach
        check(fromSouth != null)
        check(abs(fromSouth.bearingDegrees) < 1.0) { "south-of-route bearing should be north (0°), got ${fromSouth.bearingDegrees}" }

        val eastRoute = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.004" lon="20.0"/></trkseg></trk></gpx>""")
        val metersPerDegreeLon = 6_371_008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val fromEast = guide(
            GuideState.initial(eastRoute),
            SensorFrame(0L, 10.002, 20.0 + 85.0 / metersPerDegreeLon, 5f, 1f, null),
            GuideConfig(sunsetEnabled = false),
        ).guidance as? Guidance.Approach
        check(fromEast != null)
        check(abs(fromEast.bearingDegrees - 270.0) < 1.0) {
            "east-of-route bearing should be west (270°), got ${fromEast.bearingDegrees}"
        }
    }

    @Test
    fun approachHoldsE7SafetyPriorityDuringPreEntry() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.004"/></trkseg></trk></gpx>""")
        val config = GuideConfig(
            sunsetEnabled = true,
            sunsetAnnounceMinutes = listOf(60),
            reannounceIntervalSeconds = 21_600.0,
        )
        val metersPerDegreeLat = 6_371_008.8 * Math.PI / 180.0
        val south85Lat = 10.0 - 85.0 / metersPerDegreeLat
        val beforeThreshold = guide(
            GuideState.initial(route),
            SensorFrame(epoch("2000-06-21T15:30:00Z"), south85Lat, 20.002, 5f, 1f, null),
            config,
        )
        check(beforeThreshold.guidance is Guidance.Approach) {
            "expected pre-entry approach, got ${beforeThreshold.guidance}; rule=${beforeThreshold.reason.rule}; distance=${beforeThreshold.nextState.lastMatch?.distanceMeters}"
        }
        val crossedThreshold = guide(
            beforeThreshold.nextState,
            SensorFrame(epoch("2000-06-21T16:15:00Z"), south85Lat, 20.002, 5f, 1f, null),
            config,
        )
        check(crossedThreshold.guidance is Guidance.Sunset) {
            "E7 must win over a not-yet-due approach reannouncement, got ${crossedThreshold.guidance}"
        }
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
        val first = guide(GuideState.initial(route).copy(hasEnteredRoute = true), SensorFrame(0L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
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
            GuideState.initial(route).copy(hasEnteredRoute = true),
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
        val first = guide(GuideState.initial(route).copy(hasEnteredRoute = true), SensorFrame(0L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
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
    fun reverseEventWinsOverStatusOnSameFrameAndStatusFollows() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
            <trkpt lat="10.0" lon="20.0"/><trkpt lat="10.004" lon="20.0"/>
        </trkseg></trk></gpx>""")
        val config = GuideConfig(
            elapsedEnabled = true,
            elapsedAnnounceIntervalSeconds = 120.0,
            eventMinIntervalSeconds = 3_600.0,
            reverseWarningDwellSeconds = 60.0,
            sunsetEnabled = false,
            minimumSessionSecondsBeforeArrival = 0.0,
        )
        var state = guide(GuideState.initial(route), SensorFrame(0L, 10.002, 20.0, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(60_000L, 10.0018, 20.0, 5f, 1f, null), config).nextState
        val eventFrame = guide(state, SensorFrame(120_000L, 10.0016, 20.0, 5f, 1f, null), config)
        check(eventFrame.nextState.direction == ProgressDirection.REVERSE)
        check(eventFrame.guidance is Guidance.Elapsed) { "periodic event must win the first reverse-dwell frame" }
        check(!eventFrame.nextState.reverseStatusIssued) { "status must remain eligible after the event wins" }
        val followingFrame = guide(eventFrame.nextState, SensorFrame(121_000L, 10.0014, 20.0, 5f, 1f, null), config)
        check(followingFrame.guidance is Guidance.Status)
        check(followingFrame.nextState.reverseStatusIssued)
    }

    @Test
    fun reverseStatusReissuedAfterStationary() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
            <trkpt lat="10.0" lon="20.0"/><trkpt lat="10.004" lon="20.0"/>
        </trkseg></trk></gpx>""")
        val config = GuideConfig(
            reverseWarningDwellSeconds = 2.0,
            sunsetEnabled = false,
            minimumSessionSecondsBeforeArrival = 0.0,
        )
        var state = guide(GuideState.initial(route), SensorFrame(0L, 10.002, 20.0, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(1_000L, 10.0018, 20.0, 5f, 1f, null), config).nextState
        val firstStatus = guide(state, SensorFrame(3_000L, 10.0016, 20.0, 5f, 1f, null), config)
        check(firstStatus.guidance is Guidance.Status)
        check(firstStatus.nextState.reverseStatusIssued)

        val stationary = guide(firstStatus.nextState, SensorFrame(4_000L, 10.0016, 20.0, 5f, 0f, null), config)
        check(stationary.nextState.direction == ProgressDirection.STATIONARY) {
            "speed=0 must classify stationary; got direction=${stationary.nextState.direction}, state.stationary=${stationary.nextState.stationary}, reason=${stationary.reason.rule}"
        }
        check(!stationary.nextState.reverseStatusIssued)
        state = guide(stationary.nextState, SensorFrame(5_000L, 10.0014, 20.0, 5f, 1f, null), config).nextState
        val secondStatus = guide(state, SensorFrame(7_000L, 10.0012, 20.0, 5f, 1f, null), config)
        check(secondStatus.guidance is Guidance.Status)
        check(secondStatus.nextState.reverseStatusIssued)
    }

    @Test
    fun sunsetDoesNotRefireAfterLeavingReverse() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
            <trkpt lat="0.0" lon="0.0"/><trkpt lat="0.02" lon="0.0"/>
        </trkseg></trk></gpx>""")
        val config = GuideConfig(
            sunsetEnabled = true,
            sunsetAnnounceMinutes = listOf(30),
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            minimumSessionSecondsBeforeArrival = 0.0,
        )
        var state = guide(GuideState.initial(route), SensorFrame(epoch("2001-09-21T17:14:00Z"), 0.0075, 0.0, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(epoch("2001-09-21T17:22:00Z"), 0.0095, 0.0, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(epoch("2001-09-21T17:24:00Z"), 0.0085, 0.0, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(epoch("2001-09-21T17:25:00Z"), 0.0075, 0.0, 5f, 1f, null), config).nextState
        val reverseCrossing = guide(state, SensorFrame(epoch("2001-09-21T17:29:00Z"), 0.0065, 0.0, 5f, 1f, null), config)
        check(reverseCrossing.nextState.direction == ProgressDirection.REVERSE) {
            "expected reverse crossing, got ${reverseCrossing.nextState.direction}; reason=${reverseCrossing.reason.rule}, guidance=${reverseCrossing.guidance}"
        }
        check(reverseCrossing.guidance is Guidance.Sunset)
        val forwardAgain = guide(
            reverseCrossing.nextState,
            SensorFrame(epoch("2001-09-21T17:30:00Z"), 0.0085, 0.0, 5f, 1f, null),
            config,
        )
        check(forwardAgain.nextState.direction == ProgressDirection.FORWARD)
        check(forwardAgain.guidance !is Guidance.Sunset)
        check(30 in forwardAgain.nextState.consumedSunsetThresholds)
    }

    @Test
    fun reverseAllowsElapsedAndSunsetEventsAfterDwell() {
        val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
            <trkpt lat="0.0" lon="0.0"/><trkpt lat="0.02" lon="0.0"/>
        </trkseg></trk></gpx>""")
        val config = GuideConfig(
            elapsedEnabled = true,
            elapsedAnnounceIntervalSeconds = 1.0,
            eventMinIntervalSeconds = 0.0,
            reverseWarningDwellSeconds = 60.0,
            sunsetEnabled = false,
            minimumSessionSecondsBeforeArrival = 0.0,
        )
        val start = guide(
            GuideState.initial(route),
            SensorFrame(0L, 0.0100, 0.0, 5f, 1f, null),
            config,
        )
        val reverse = guide(
            start.nextState,
            SensorFrame(61_000L, 0.0090, 0.0, 5f, 1f, null),
            config,
        )
        check(reverse.guidance is Guidance.Elapsed) {
            "elapsed event must be allowed in reverse after the dwell"
        }

        val sunsetConfig = config.copy(
            elapsedEnabled = false,
            sunsetEnabled = true,
            reverseWarningDwellSeconds = 60.0,
        )
        val sunsetStart = guide(
            GuideState.initial(route),
            SensorFrame(epoch("2001-09-21T17:23:00Z"), 0.0100, 0.0, 5f, 1f, null),
            sunsetConfig,
        )
        var state = guide(
            sunsetStart.nextState,
            SensorFrame(epoch("2001-09-21T17:23:30Z"), 0.0105, 0.0, 5f, 1f, null),
            sunsetConfig,
        ).nextState
        state = guide(
            state,
            SensorFrame(epoch("2001-09-21T17:24:30Z"), 0.0100, 0.0, 5f, 1f, null),
            sunsetConfig,
        ).nextState
        val reverseSunset = guide(
            state,
            SensorFrame(epoch("2001-09-21T17:29:00Z"), 0.0095, 0.0, 5f, 1f, null),
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
        val first = guide(GuideState.initial(route).copy(hasEnteredRoute = true), SensorFrame(0L, 10.0005, 20.0 + east30Meters, 5f, 1f, null), config)
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
