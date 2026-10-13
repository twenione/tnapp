package com.trailnav.core

import kotlin.test.Test

/** Matching checks include the bounded search used for out-and-back routes. */
class MatchingTest {
    @Test
    fun stopResumeR1DoesNotReportReverseDuringGpsNoise() {
        val samples = listOf(
            0.00 to 9567.90, 0.00 to 9567.91, 0.00 to 9567.92, 0.00 to 9567.93, 0.00 to 9567.94,
            0.00 to 9567.95, 0.00 to 9567.96, 0.00 to 9567.97, 0.00 to 9567.98, 0.00 to 9567.99,
            0.00 to 9567.97,
            0.36 to 9567.81,
            0.35 to 9567.40,
            0.18 to 9567.06,
            0.21 to 9567.00,
            0.00 to 9566.97,
            0.44 to 9567.40,
            0.79 to 9568.16,
            0.68 to 9568.70,
            0.00 to 9568.29,
            0.00 to 9568.40,
            0.00 to 9568.67,
            0.00 to 9568.62,
            0.00 to 9568.52,
            0.38 to 9568.29,
            0.41 to 9568.47,
            0.36 to 9568.77,
            0.58 to 9568.96,
            0.74 to 9569.39,
            0.80 to 9569.80,
            0.83 to 9571.25,
        )
        val directions = runDirectionSamples(samples)
        check(directions.none { it == ProgressDirection.REVERSE }) { "directions=$directions" }
        check(directions.last() == ProgressDirection.FORWARD) { "frame 773=${directions.last()}" }
    }

    @Test
    fun reverseReturnsAfterResumeHoldExpires() {
        val samples = List(10) { 0.0 to 9_567.0 } +
            List(20) { index -> 1.0 to 9_567.0 - (index + 1) }
        val directions = runDirectionSamples(samples)
        val firstReverse = directions.indexOf(ProgressDirection.REVERSE)
        check(firstReverse in 13..15) { "first reverse after ${firstReverse - 10} resumed seconds; directions=$directions" }
    }

    @Test
    fun uninterruptedDirectionChangeKeepsTheOriginalReverseFrame() {
        val samples = List(8) { index -> 1.0 to (9_500.0 + index) } +
            List(12) { index -> 1.0 to (9_507.0 - index) }
        val withoutHold = runDirectionSamples(samples, GuideConfig(directionResumeHoldSeconds = 0.0))
        val withDefaultHold = runDirectionSamples(samples)
        check(withDefaultHold == withoutHold) { "default=$withDefaultHold zeroHold=$withoutHold" }
        check(withDefaultHold.indexOf(ProgressDirection.REVERSE) >= 8)
    }

    @Test
    fun zeroSecondResumeHoldRestoresR1ReverseFrames() {
        val samples = r1Samples()
        val directions = runDirectionSamples(samples, GuideConfig(directionResumeHoldSeconds = 0.0))
        check(directions[11] == ProgressDirection.REVERSE) { "frame 754=${directions[11]}" }
        check(directions[12] == ProgressDirection.REVERSE) { "frame 755=${directions[12]}" }
    }

    @Test
    fun stationaryFrameResetsEmaToZero() {
        val route = directionRoute()
        val previous = GuideState.initial(route).copy(
            lastMatch = MatchResult(0.0, 100.0, 0, EnuPoint(100.0, 0.0), ProgressDirection.FORWARD),
            emaDeltaMeters = -4.0,
        )
        val current = MatchResult(0.0, 101.0, 0, EnuPoint(101.0, 0.0))
        val stoppedFrame = SensorFrame(1_000L, 0.0, 100.0 / 111_195.0802335329, 5f, 0f, null)

        check(RouteMatcher.updatedEma(previous, current, stoppedFrame, GuideConfig()) == 0.0)
        check(RouteMatcher.withDirection(previous, current, stoppedFrame, GuideConfig()).direction == ProgressDirection.STATIONARY)
    }

    @Test
    fun boundedSearchPreservesDirection() {
    val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.001"/><trkpt lat="10.001" lon="20.001"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
    val route = RouteModel.fromGpx(xml)
    val config = GuideConfig()
    val first = RouteMatcher.nearest(route, SensorFrame(0, 10.0, 20.0005, 5f, 1f, null), null, config)
    check(first != null)
    val returnFrame = SensorFrame(1_000, 10.001, 20.0005, 5f, 1f, null)
    val bounded = RouteMatcher.nearest(route, returnFrame, route.totalLengthMeters * 0.65, config)
    check(bounded != null && bounded.projectedMeters > route.totalLengthMeters * 0.5)
    val boundedMatch = requireNotNull(bounded)
    val state = GuideState.initial(route).copy(lastMatch = first)
    val directed = RouteMatcher.withDirection(state, boundedMatch, returnFrame, config)
    check(directed.direction == ProgressDirection.FORWARD)
    }

    private fun r1Samples(): List<Pair<Double, Double>> = listOf(
        0.00 to 9567.90, 0.00 to 9567.91, 0.00 to 9567.92, 0.00 to 9567.93, 0.00 to 9567.94,
        0.00 to 9567.95, 0.00 to 9567.96, 0.00 to 9567.97, 0.00 to 9567.98, 0.00 to 9567.99,
        0.00 to 9567.97, 0.36 to 9567.81, 0.35 to 9567.40, 0.18 to 9567.06, 0.21 to 9567.00,
        0.00 to 9566.97, 0.44 to 9567.40, 0.79 to 9568.16, 0.68 to 9568.70, 0.00 to 9568.29,
        0.00 to 9568.40, 0.00 to 9568.67, 0.00 to 9568.62, 0.00 to 9568.52, 0.38 to 9568.29,
        0.41 to 9568.47, 0.36 to 9568.77, 0.58 to 9568.96, 0.74 to 9569.39, 0.80 to 9569.80,
        0.83 to 9571.25,
    )

    private fun directionRoute(): RouteModel = RouteModel.fromGpx(
        "<gpx><trk><trkseg><trkpt lat=\"0.0\" lon=\"0.0\"/>" +
            "<trkpt lat=\"0.0\" lon=\"0.3\"/></trkseg></trk></gpx>"
    )

    private fun runDirectionSamples(
        samples: List<Pair<Double, Double>>,
        config: GuideConfig = GuideConfig(),
    ): List<ProgressDirection> {
        val route = directionRoute()
        var state = GuideState.initial(route)
        return samples.mapIndexed { index, (speed, distance) ->
            val result = guide(
                state,
                SensorFrame(
                    timestamp = index * 1_000L,
                    lat = 0.0,
                    lon = distance / 111_195.0802335329,
                    accuracy = 5f,
                    speed = speed.toFloat(),
                    gpsBearing = null,
                ),
                config,
            )
            state = result.nextState
            state.direction
        }
    }
}
