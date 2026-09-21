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
        val config = GuideConfig(milestoneIntervalMeters = 1000.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.011, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Milestone)
        check(result.reason.rule == "event.milestone")
    }

    @Test
    fun remainingThresholds() {
        val config = GuideConfig(remainingAnnounceMeters = listOf(2000.0, 1000.0, 500.0), eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.022, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Remaining || result.guidance == Guidance.Arrived)
    }

    @Test
    fun periodicGate() {
        val config = GuideConfig(periodicEnabled = false, milestoneIntervalMeters = 500.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.008, 20.0, 5f, 1f, null), config)
        check(result.guidance !is Guidance.Milestone)
        check(result.nextState.consumedMilestoneIndices.isNotEmpty())
    }

    @Test
    fun minimumEventInterval() {
        val config = GuideConfig(milestoneIntervalMeters = 500.0, eventMinIntervalSeconds = 60.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val first = guide(state, SensorFrame(1_000L, 10.008, 20.0, 5f, 1f, null), config)
        check(first.guidance is Guidance.Milestone || first.guidance == Guidance.Arrived)
        val second = guide(first.nextState, SensorFrame(2_000L, 10.012, 20.0, 5f, 1f, null), config)
        check(second.guidance !is Guidance.Milestone)
    }

    @Test
    fun eventToggle() {
        val config = GuideConfig(milestoneEnabled = false, milestoneIntervalMeters = 500.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.008, 20.0, 5f, 1f, null), config)
        check(result.guidance !is Guidance.Milestone)
    }
}
