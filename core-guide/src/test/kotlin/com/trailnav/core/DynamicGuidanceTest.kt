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
    fun milestoneThreshold() {
        val config = GuideConfig(periodicEnabled = true, milestoneIntervalMeters = 200.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Milestone)
        check(result.reason.rule == "event.milestone")
    }

    @Test
    fun remainingThresholds() {
        val config = GuideConfig(periodicEnabled = true, remainingAnnounceMeters = listOf(3000.0, 2800.0, 2400.0), eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.008, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0095, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Remaining)
    }

    @Test
    fun periodicGate() {
        val config = GuideConfig(periodicEnabled = false, milestoneIntervalMeters = 200.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), config)
        check(result.guidance !is Guidance.Milestone)
        check(result.nextState.consumedMilestoneIndices.isNotEmpty())
    }

    @Test
    fun minimumEventInterval() {
        val config = GuideConfig(periodicEnabled = true, milestoneIntervalMeters = 200.0, eventMinIntervalSeconds = 60.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val first = guide(state, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), config)
        check(first.guidance is Guidance.Milestone)
        val second = guide(first.nextState, SensorFrame(2_000L, 10.0035, 20.0, 5f, 1f, null), config)
        check(second.guidance !is Guidance.Milestone)
    }

    @Test
    fun eventToggle() {
        val config = GuideConfig(periodicEnabled = true, milestoneEnabled = false, milestoneIntervalMeters = 200.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), config)
        check(result.guidance !is Guidance.Milestone)
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
            GuideConfig(periodicEnabled = false),
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
        val config = GuideConfig(periodicEnabled = true, eventMinIntervalSeconds = 0.0)
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config)
        val second = guide(first.nextState, SensorFrame(3_600_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(second.guidance is Guidance.Elapsed)
        check((second.guidance as Guidance.Elapsed).hours == 1)
    }

    @Test
    fun slopeWindow() {
        val xml = "<gpx><trk><trkseg>" +
            listOf(0, 0, 0, 0, 8, 16, 24, 32).mapIndexed { index, elevation ->
                "<trkpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
            }.joinToString("") + "</trkseg></trk></gpx>"
        val routeWithSlope = RouteModel.fromGpx(xml)
        val config = GuideConfig(periodicEnabled = true, eventMinIntervalSeconds = 0.0, slopeAnnounceLeadMeters = 0.0)
        val primed = GuideState.initial(routeWithSlope).copy(
            lastMatch = MatchResult(0.0, 0.0, 0, routeWithSlope.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
        )
        val result = guide(primed, SensorFrame(1_000L, 10.0009, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Slope)
        val repeat = guide(result.nextState, SensorFrame(1_000L, 10.0, 20.0, 5f, 1f, null),
            GuideConfig(periodicEnabled = true, eventMinIntervalSeconds = 0.0))
        check(repeat.guidance !is Guidance.Slope)
    }

    @Test
    fun elevationSlot() {
        val xml = "<gpx><trk><trkseg>" +
            "<trkpt lat=\"10.0\" lon=\"20.0\"><ele>101</ele></trkpt>" +
            "<trkpt lat=\"10.0005\" lon=\"20.0\"><ele>110</ele></trkpt>" +
            "<trkpt lat=\"10.001\" lon=\"20.0\"><ele>119</ele></trkpt>" +
            "</trkseg></trk></gpx>"
        val routeWithElevation = RouteModel.fromGpx(xml)
        val config = GuideConfig(periodicEnabled = true, eventMinIntervalSeconds = 0.0)
        val frame = SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null)
        val state = guide(GuideState.initial(routeWithElevation), frame, config).nextState
        val first = slotContent(state, config)
        check(first.content is Guidance.Elevation)
        check((first.content as Guidance.Elevation).elevationMeters == 110.0)
        val repeat = slotContent(first.nextState, config)
        check(repeat.content is Guidance.Status)
        check(repeat.nextState == first.nextState)
    }

    @Test
    fun waypointWindow() {
        val xml = "<gpx><wpt lat=\"10.001\" lon=\"20.0\"><name>View</name></wpt>" +
            "<trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk></gpx>"
        val routeWithWaypoint = RouteModel.fromGpx(xml)
        val config = GuideConfig(periodicEnabled = true, eventMinIntervalSeconds = 0.0, waypointAnnounceLeadMeters = 75.0)
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
        val xml = "<gpx><trk><trkseg>" +
            listOf(0, 0, 0, 0, 10, 22, 34, 46).mapIndexed { index, elevation ->
                "<trkpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
            }.joinToString("") + "</trkseg></trk></gpx>"
        val routeWithSlope = RouteModel.fromGpx(xml)
        val config = GuideConfig(
            periodicEnabled = true,
            eventMinIntervalSeconds = 0.0,
            remainingAnnounceMeters = listOf(250.0),
            slopeAnnounceLeadMeters = 100.0,
        )
        var state = guide(GuideState.initial(routeWithSlope), SensorFrame(0L, 10.0, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0013, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Remaining)
        check(result.reason.details["event"] == "E3")
    }

    @Test
    fun offRouteConsumesPeriodicThresholds() {
        val config = GuideConfig(
            periodicEnabled = true,
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
    fun slotFallsBackWhenElevationIsUnavailable() {
        val config = GuideConfig(periodicEnabled = true, sunsetEnabled = false)
        val state = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null),
            config,
        ).nextState
        val result = slotContent(state, config)
        check(result.content is Guidance.Status)
        check(result.nextState == state)
    }

}
