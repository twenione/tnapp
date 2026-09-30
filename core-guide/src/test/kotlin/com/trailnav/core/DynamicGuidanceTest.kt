package com.trailnav.core

import java.time.Instant
import kotlin.test.Test

/** Phase 3 C event threshold, gating, and consumption checks. */
class DynamicGuidanceTest {
    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun pointXml(index: Int): String =
        "<trkpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"/>"

    private val route = RouteModel.fromGpx(
        "<gpx><trk><trkseg>" +
            (0..12).joinToString("") { index -> "<trkpt lat=\"${10.0 + index * 0.003}\" lon=\"20.0\"/>" } +
            "</trkseg></trk></gpx>"
    )

    @Test
    fun individualEventDefaultsAreDisabledExceptSunset() {
        val config = GuideConfig()
        check(!config.milestoneEnabled)
        check(!config.elapsedEnabled)
        check(!config.remainingEnabled)
        check(!config.slopeEnabled)
        check(!config.elevationEnabled)
        check(!config.waypointEnabled)
        check(config.sunsetEnabled)
        check(!config.sunriseEnabled)
    }

    @Test
    fun milestoneThreshold() {
        val config = GuideConfig(milestoneEnabled = true, milestoneIntervalMeters = 200.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Milestone)
        check(result.reason.rule == "event.milestone")
    }

    @Test
    fun remainingThresholds() {
        val config = GuideConfig(remainingEnabled = true, remainingAnnounceMeters = listOf(3000.0, 2800.0, 2400.0), eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.008, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0095, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Remaining)
    }

    @Test
    fun minimumEventInterval() {
        val intervalRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg>" +
                "<trkpt lat=\"10.0\" lon=\"20.0\"/>" +
                "<trkpt lat=\"10.001\" lon=\"20.0\"/>" +
                "<trkpt lat=\"10.002\" lon=\"20.0\"/>" +
                "<trkpt lat=\"10.003\" lon=\"20.0\"/>" +
                "</trkseg></trk></gpx>"
        )
        val config = GuideConfig(milestoneEnabled = true, milestoneIntervalMeters = 100.0, eventMinIntervalSeconds = 60.0)
        var state = GuideState.initial(intervalRoute)
        state = guide(state, SensorFrame(0L, 10.0001, 20.0, 5f, 1f, null), config).nextState
        val first = guide(state, SensorFrame(1_000L, 10.0011, 20.0, 5f, 1f, null), config)
        check(first.guidance is Guidance.Milestone)
        val second = guide(first.nextState, SensorFrame(2_000L, 10.0019, 20.0, 5f, 1f, null), config)
        check(second.guidance !is Guidance.Milestone)
    }

    @Test
    fun toggleMilestoneOnAndOff() {
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                milestoneEnabled = enabled,
                milestoneIntervalMeters = 200.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), configured)
            return guide(first.nextState, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Milestone)
        check(enabled.reason.details["event"] == "E1")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Milestone)
        check(disabled.nextState.consumedMilestoneIndices.isNotEmpty())
    }
    @Test
    fun sunsetThresholds() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val atThirtyTwoMinutes = SensorFrame(epoch("2026-09-21T09:00:00Z"), 37.5665, 126.9780, 5f, 0f, null)
        val first = guide(GuideState.initial(sunsetRoute), atThirtyTwoMinutes)
        check(first.guidance is Guidance.Sunset)
        check(first.reason.rule == "event.sunset")
        check(first.reason.details["event"] == "E7")
        check(first.reason.details["startAnnouncement"] == "true")
        check(first.reason.details["thresholdMinutes"] == "60")
        check(60 in first.nextState.consumedSunsetThresholds && 30 !in first.nextState.consumedSunsetThresholds)

        val second = guide(first.nextState, atThirtyTwoMinutes.copy(timestamp = epoch("2026-09-21T09:10:00Z")))
        check(second.guidance is Guidance.Sunset)
        check(second.reason.details["thresholdMinutes"] == "30")
        val after = guide(second.nextState, atThirtyTwoMinutes.copy(timestamp = epoch("2026-09-21T09:40:00Z")))
        check(after.guidance is Guidance.Sunset)
        check((after.guidance as Guidance.Sunset).afterSunset)
    }

    @Test
    fun sunsetWorksWithPeriodicDisabledAndAccuracyRejected() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val result = guide(
            GuideState.initial(sunsetRoute),
            SensorFrame(epoch("2026-09-21T09:00:00Z"), 37.5665, 126.9780, 80f, 0f, null),
            GuideConfig(),
        )
        check(result.guidance is Guidance.Sunset)
        check(result.reason.rule == "input.accuracy-filter")
        check(result.reason.details["event"] == "E7")
    }

    @Test
    fun sunsetAfterSunsetSpeaksOnce() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val first = guide(
            GuideState.initial(sunsetRoute),
            SensorFrame(epoch("2026-09-21T09:40:00Z"), 37.5665, 126.9780, 5f, 0f, null),
        )
        check(first.guidance is Guidance.Sunset)
        check((first.guidance as Guidance.Sunset).afterSunset)
        check(first.reason.details["afterSunset"] == "true")
        val second = guide(first.nextState, SensorFrame(epoch("2026-09-21T09:41:40Z"), 37.5665, 126.9780, 5f, 0f, null))
        check(second.guidance !is Guidance.Sunset)
    }

    @Test
    fun elapsedThreshold() {
        val config = GuideConfig(elapsedEnabled = true, eventMinIntervalSeconds = 0.0)
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config)
        val second = guide(first.nextState, SensorFrame(3_600_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(second.guidance is Guidance.Elapsed)
        check((second.guidance as Guidance.Elapsed).hours == 1)
    }

    private fun peakEventRoute(): RouteModel {
        val elevations = listOf(
            0, 0, 0, 10, 20, 30, 40, 50, 60, 70,
            80, 80, 80, 80, 80, 70, 60, 50, 40, 30, 20, 20, 20,
        )
        val track = elevations.mapIndexed { index, elevation ->
            "<trkpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
        }.joinToString("")
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>").also {
            check(it.elevationUse.used && it.peaks.isNotEmpty())
        }
    }

    private fun peakStateAt(route: RouteModel, progressMeters: Double): GuideState {
        val segment = route.cumulativeMeters.indexOfLast { it <= progressMeters }
            .coerceIn(0, route.points.lastIndex - 1)
        return GuideState.initial(route).copy(
            lastMatch = MatchResult(0.0, progressMeters, segment, route.points[segment], ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
        )
    }

    private fun frameAtProgress(progressMeters: Double, timestamp: Long) =
        SensorFrame(timestamp, 10.0 + progressMeters / 111_195.0, 20.0, 5f, 1f, null)

    @Test
    fun peakEventFiresWithinLeadDistanceAndConsumesOnce() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val targetProgress = peak.s - 75.0

        fun announce(leadMeters: Double): GuideResult {
            val config = GuideConfig(
                slopeEnabled = true,
                peakAnnounceLeadMeters = leadMeters,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            return guide(peakStateAt(route, targetProgress), frameAtProgress(targetProgress, 1_000L), config)
        }

        val withinDefaultLead = announce(100.0)
        val slope = withinDefaultLead.guidance as? Guidance.Slope
        check(slope != null)
        check(slope.kind == SlopeKind.ASCENT)
        check(slope.deltaMeters == peak.climbMeters)
        check(withinDefaultLead.reason.details["event"] == "E4")
        check(withinDefaultLead.reason.details["peakIndex"] == "0")
        check(withinDefaultLead.reason.details["peakS"] == peak.s.toString())
        check(withinDefaultLead.reason.details["elevationMeters"] == peak.elevationMeters.toString())
        check(withinDefaultLead.reason.details["climbMeters"] == peak.climbMeters.toString())

        val outsideShorterLead = announce(50.0)
        check(outsideShorterLead.guidance !is Guidance.Slope)

        val config = GuideConfig(slopeEnabled = true, peakAnnounceLeadMeters = 100.0, eventMinIntervalSeconds = 0.0, sunsetEnabled = false)
        val repeated = guide(
            withinDefaultLead.nextState,
            frameAtProgress(peak.s - 25.0, 2_000L),
            config,
        )
        check(repeated.guidance !is Guidance.Slope)
    }

    @Test
    fun waypointWindow() {
        val xml = "<gpx><wpt lat=\"10.001\" lon=\"20.0\"><name>View</name></wpt>" +
            "<trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk></gpx>"
        val routeWithWaypoint = RouteModel.fromGpx(xml)
        val config = GuideConfig(waypointEnabled = true, eventMinIntervalSeconds = 0.0, waypointAnnounceLeadMeters = 75.0)
        val primed = GuideState.initial(routeWithWaypoint).copy(
            lastMatch = MatchResult(0.0, 0.0, 0, routeWithWaypoint.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
        )
        val result = guide(primed, SensorFrame(1_000L, 10.00045, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Waypoint)
    }

    @Test
    fun remainingBeatsSlopeWhenThresholdsCrossTogether() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val targetProgress = peak.s - 50.0
        val remainingThreshold = route.totalLengthMeters - targetProgress + 1.0
        val config = GuideConfig(
            remainingEnabled = true,
            slopeEnabled = true,
            eventMinIntervalSeconds = 0.0,
            remainingAnnounceMeters = listOf(remainingThreshold),
            peakAnnounceLeadMeters = 100.0,
            sunsetEnabled = false,
        )
        val previous = peakStateAt(route, targetProgress - 10.0)
        val result = guide(previous, frameAtProgress(targetProgress, 1_000L), config)
        check(result.guidance is Guidance.Remaining) {
            "guidance=" + result.guidance + " reason=" + result.reason + " remaining=" +
                (route.totalLengthMeters - result.nextState.lastMatch!!.projectedMeters) +
                " target=" + targetProgress + " threshold=" + remainingThreshold
        }
        check(result.reason.details["event"] == "E3")
        check(0 in result.nextState.consumedSlopeIndices) {
            "E4 is consumed even when the higher-priority E3 cue wins"
        }
    }

    @Test
    fun offRouteConsumesPeriodicThresholds() {
        val config = GuideConfig(
            milestoneEnabled = true,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
            milestoneIntervalMeters = 50.0,
            offRouteEnterDwellSeconds = 0.0,
            offRouteExitDwellSeconds = 0.0,
        )
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config)
        val off = guide(first.nextState, SensorFrame(1_000L, 10.002, 20.0004, 5f, 1f, null), config)
        check(off.nextState.offRoute)
        check(off.nextState.consumedMilestoneIndices.isNotEmpty())
        val recovered = guide(off.nextState, SensorFrame(2_000L, 10.002, 20.0, 5f, 1f, null), config)
        check(!recovered.nextState.offRoute)
        check(recovered.guidance !is Guidance.Milestone)
    }

    @Test
    fun offRouteGateDoesNotRecordPeriodicAnnouncement() {
        val config = GuideConfig(
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
            milestoneIntervalMeters = 50.0,
            offRouteEnterDwellSeconds = 0.0,
        )
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config)
        val off = guide(first.nextState, SensorFrame(1_000L, 10.002, 20.0004, 5f, 1f, null), config)
        check(off.nextState.offRoute)
        check(off.nextState.lastPeriodicEventAt == null)
    }

    @Test
    fun reverseGateSuppressesSlopeAnnouncement() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val config = GuideConfig(
            slopeEnabled = true,
            peakAnnounceLeadMeters = 100.0,
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val reverseState = peakStateAt(route, peak.s + 20.0).copy(
            direction = ProgressDirection.REVERSE,
            emaDeltaMeters = -40.0,
            reverseSince = 0L,
        )
        val result = guide(reverseState, frameAtProgress(peak.s - 10.0, 1_000L), config)
        check(result.nextState.direction == ProgressDirection.REVERSE)
        check(result.guidance !is Guidance.Slope)
        check(0 in result.nextState.consumedSlopeIndices)
    }

    @Test
    fun milestoneConsumptionQueuePreventsRefire() {
        val config = GuideConfig(milestoneEnabled = true, sunsetEnabled = false, eventMinIntervalSeconds = 0.0, milestoneIntervalMeters = 100.0)
        val primed = GuideState.initial(route).copy(
            lastMatch = MatchResult(0.0, 0.0, 0, route.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
        )
        val first = guide(primed, SensorFrame(1_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(first.guidance is Guidance.Milestone)
        val back = guide(first.nextState, SensorFrame(2_000L, 10.0005, 20.0, 5f, 1f, null), config)
        val forward = guide(back.nextState, SensorFrame(3_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(forward.guidance !is Guidance.Milestone)
    }

    @Test
    fun peakThresholdDoesNotRefireAfterConsumption() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val config = GuideConfig(slopeEnabled = true, peakAnnounceLeadMeters = 100.0, eventMinIntervalSeconds = 0.0, sunsetEnabled = false)
        val state = peakStateAt(route, peak.s - 50.0).copy(consumedSlopeIndices = setOf(0))
        val result = guide(state, frameAtProgress(peak.s - 25.0, 1_000L), config)
        check(result.guidance !is Guidance.Slope)
    }

    @Test
    fun eventPriorityKeepsRemainingAboveSlope() {
        check(EventPriority.REMAINING > EventPriority.SLOPE)
    }

    @Test
    fun toggleElapsedOnAndOff() {
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                elapsedEnabled = enabled,
                elapsedAnnounceIntervalSeconds = 60.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), configured)
            return guide(first.nextState, SensorFrame(60_000L, 10.0005, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Elapsed)
        check(enabled.reason.details["event"] == "E2")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Elapsed)
        check(disabled.nextState.consumedElapsedIndices.isNotEmpty())
    }

    @Test
    fun toggleRemainingOnAndOff() {
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                remainingEnabled = enabled,
                remainingAnnounceMeters = listOf(3_000.0),
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            val first = guide(GuideState.initial(route), SensorFrame(0L, 10.008, 20.0, 5f, 1f, null), configured)
            return guide(first.nextState, SensorFrame(1_000L, 10.0095, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Remaining)
        check(enabled.reason.details["event"] == "E3")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Remaining)
        check(3_000.0 in disabled.nextState.consumedRemainingThresholds)
    }

    @Test
    fun toggleSlopeOnAndOff() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        fun run(enabled: Boolean): GuideResult {
            val config = GuideConfig(
                slopeEnabled = enabled,
                peakAnnounceLeadMeters = 75.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            return guide(peakStateAt(route, peak.s - 50.0), frameAtProgress(peak.s - 50.0, 1_000L), config)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Slope)
        check(enabled.reason.details["event"] == "E4")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Slope)
        check(0 in disabled.nextState.consumedSlopeIndices)
    }

    @Test
    fun toggleWaypointOnAndOff() {
        val waypointRoute = RouteModel.fromGpx(
            "<gpx><wpt lat=\"10.001\" lon=\"20.0\"><name>View</name></wpt><trk><trkseg>" +
                "<trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.002\" lon=\"20.0\"/>" +
                "</trkseg></trk></gpx>"
        )
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                waypointEnabled = enabled,
                waypointAnnounceLeadMeters = 100.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            var state = guide(GuideState.initial(waypointRoute), SensorFrame(0L, 10.0, 20.0, 5f, 1f, null), configured).nextState
            return guide(state, SensorFrame(1_000L, 10.0006, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Waypoint)
        check(enabled.reason.details["event"] == "E6")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Waypoint)
        check(0 in disabled.nextState.consumedWaypointIndices)
    }

    @Test
    fun toggleSunsetOnAndOff() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val frame = SensorFrame(epoch("2026-09-21T09:00:00Z"), 37.5665, 126.9780, 5f, 0f, null)
        val enabled = guide(GuideState.initial(sunsetRoute), frame, GuideConfig(sunsetEnabled = true))
        check(enabled.guidance is Guidance.Sunset)
        check(enabled.reason.details["event"] == "E7")
        val disabled = guide(GuideState.initial(sunsetRoute), frame, GuideConfig(sunsetEnabled = false))
        check(disabled.guidance !is Guidance.Sunset)
    }

    @Test
    fun reverseDirectionSuppressesMilestoneRemainingSlopeAndWaypoint() {
        val milestoneConfig = GuideConfig(
            milestoneEnabled = true,
            milestoneIntervalMeters = 100.0,
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(route.totalLengthMeters - 200.0),
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val oldProgress = 100.0
        val primed = GuideState.initial(route).copy(
            lastMatch = MatchResult(0.0, oldProgress, 0, route.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            emaDeltaMeters = -500.0,
            direction = ProgressDirection.REVERSE,
            reverseSince = 0L,
        )
        val distanceEvents = guide(
            primed,
            SensorFrame(1_000L, 10.00225, 20.0, 5f, 1f, null),
            milestoneConfig,
        )
        check(distanceEvents.nextState.direction == ProgressDirection.REVERSE)
        check(distanceEvents.guidance !is Guidance.Milestone)
        check(distanceEvents.guidance !is Guidance.Remaining)

        val slopeRoute = peakEventRoute()
        val slope = slopeRoute.peaks.first()
        val slopeConfig = GuideConfig(
            slopeEnabled = true,
            peakAnnounceLeadMeters = 75.0,
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val slopeState = GuideState.initial(slopeRoute).copy(
            lastMatch = MatchResult(0.0, slope.s - 100.0, 0, slopeRoute.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            emaDeltaMeters = -100.0,
            direction = ProgressDirection.REVERSE,
            reverseSince = 0L,
        )
        val slopeResult = guide(
            slopeState,
            SensorFrame(1_000L, 10.0 + (slope.s - 20.0) / 111_195.0, 20.0, 5f, 1f, null),
            slopeConfig,
        )
        check(slopeResult.nextState.direction == ProgressDirection.REVERSE)
        check(slopeResult.guidance !is Guidance.Slope)

        val waypointRoute = RouteModel.fromGpx(
            "<gpx><wpt lat=\"10.002\" lon=\"20.0\"><name>View</name></wpt><trk><trkseg>" +
                "<trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.004\" lon=\"20.0\"/>" +
                "</trkseg></trk></gpx>"
        )
        val waypoint = waypointRoute.waypoints.single()
        val waypointConfig = GuideConfig(
            waypointEnabled = true,
            waypointAnnounceLeadMeters = 100.0,
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val waypointState = GuideState.initial(waypointRoute).copy(
            lastMatch = MatchResult(0.0, waypoint.s - 80.0, 0, waypointRoute.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            emaDeltaMeters = -100.0,
            direction = ProgressDirection.REVERSE,
            reverseSince = 0L,
        )
        val waypointResult = guide(
            waypointState,
            SensorFrame(1_000L, 10.0 + (waypoint.s - 20.0) / 111_195.0, 20.0, 5f, 1f, null),
            waypointConfig,
        )
        check(waypointResult.nextState.direction == ProgressDirection.REVERSE)
        check(waypointResult.guidance !is Guidance.Waypoint)
    }
}
