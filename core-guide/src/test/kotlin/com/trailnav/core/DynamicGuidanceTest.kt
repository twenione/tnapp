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
        val config = GuideConfig(periodicEnabled = true, remainingAnnounceMeters = listOf(3200.0, 2800.0, 2400.0), eventMinIntervalSeconds = 0.0)
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
}
