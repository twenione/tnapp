package com.trailnav.core

import kotlin.test.Test

/** Phase 3 C event threshold, gating, and consumption checks. */
class DynamicGuidanceTest {
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
    fun sunsetThresholdsUseLocalDayAndStartAnnouncement() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val atThirtyTwoMinutes = SensorFrame(1_789_981_200_000L, 37.5665, 126.9780, 5f, 0f, null)
        val first = guide(GuideState.initial(sunsetRoute), atThirtyTwoMinutes)
        check(first.guidance is Guidance.Sunset)
        check(first.reason.rule == "event.sunset")
        check(first.reason.details["event"] == "E7")
        check(first.reason.details["startAnnouncement"] == "true")
        check(first.reason.details["thresholdMinutes"] == "60")
        check(first.nextState.consumedSunsetThresholds.containsAll(listOf(30, 60)))

        val second = guide(first.nextState, atThirtyTwoMinutes.copy(timestamp = 1_789_981_800_000L))
        check(second.guidance is Guidance.Sunset)
        check(second.reason.details["thresholdMinutes"] == "30")
        val after = guide(second.nextState, atThirtyTwoMinutes.copy(timestamp = 1_789_984_000_000L))
        check(after.guidance !is Guidance.Sunset)
    }

    @Test
    fun sunsetWorksWithPeriodicDisabledAndAccuracyRejected() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val result = guide(
            GuideState.initial(sunsetRoute),
            SensorFrame(1_789_981_200_000L, 37.5665, 126.9780, 80f, 0f, null),
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
            SensorFrame(1_789_984_000_000L, 37.5665, 126.9780, 5f, 0f, null),
        )
        check(first.guidance is Guidance.Sunset)
        check((first.guidance as Guidance.Sunset).afterSunset)
        check(first.reason.details["afterSunset"] == "true")
        val second = guide(first.nextState, SensorFrame(1_789_984_100_000L, 37.5665, 126.9780, 5f, 0f, null))
        check(second.guidance !is Guidance.Sunset)
    }

}
