package com.trailnav.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnPassTest {
    private val baseRoute = RouteModel.fromGpx(
        """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>"""
    )
    private val turn = TurnPoint(s = 100.0, side = Side.RIGHT, angleDegrees = 90.0)

    private fun state(projectedMeters: Double, turns: List<TurnPoint> = listOf(turn)): GuideState {
        val route = baseRoute.copy(turns = turns)
        return GuideState.initial(route).copy(
            lastMatch = MatchResult(
                distanceMeters = 0.0,
                projectedMeters = projectedMeters,
                segmentIndex = 0,
                projectedPoint = EnuPoint(0.0, projectedMeters),
                direction = ProgressDirection.FORWARD,
            ),
        )
    }

    @Test
    fun positionBeforeTurnHasNotPassed() {
        assertFalse(turnPassed(state(99.999), 0))
    }

    @Test
    fun positionAtTurnHasPassed() {
        assertTrue(turnPassed(state(100.0), 0))
    }

    @Test
    fun positionAfterTurnHasPassed() {
        assertTrue(turnPassed(state(100.001), 0))
    }

    @Test
    fun missingMatchHasNotPassed() {
        assertFalse(turnPassed(GuideState.initial(baseRoute.copy(turns = listOf(turn))), 0))
    }

    @Test
    fun invalidTurnIndexesThrow() {
        assertFailsWith<IllegalArgumentException> { turnPassed(state(100.0), -1) }
        assertFailsWith<IllegalArgumentException> { turnPassed(state(100.0), 1) }
    }
}
