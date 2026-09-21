package com.trailnav.core

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnGuidanceTest {
    private fun route(secondLon: Double = 20.0018): RouteModel = RouteModel.fromGpx(
        """<gpx><trk><trkseg>
            <trkpt lat="10.0" lon="20.0"/>
            <trkpt lat="10.0018" lon="20.0"/>
            <trkpt lat="10.0018" lon="$secondLon"/>
        </trkseg></trk></gpx>""".trimIndent()
    )

    @Test
    fun rightTurnAheadThenNowIsAnnouncedOnce() {
        val route = route()
        assertTrue(route.turns.isNotEmpty(), "synthetic right-angle route must contain a turn")
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0, 20.0, 5f, 1f, null)).nextState
        val ahead = guide(state, SensorFrame(1_000L, 10.0013, 20.0, 5f, 1f, null), GuideConfig())
        assertIs<Guidance.TurnAhead>(ahead.guidance)
        assertEquals(Side.RIGHT, (ahead.guidance as Guidance.TurnAhead).side)
        assertEquals("turn.ahead", ahead.reason.rule)
        state = ahead.nextState
        val repeatedAhead = guide(state, SensorFrame(1_500L, 10.00135, 20.0, 5f, 1f, null), GuideConfig())
        assertNull(repeatedAhead.guidance, "a turn-ahead announcement is consumed per turn index")
        state = repeatedAhead.nextState
        val now = guide(state, SensorFrame(2_000L, 10.0017, 20.0, 5f, 1f, null), GuideConfig())
        assertIs<Guidance.TurnNow>(now.guidance)
        assertEquals(Side.RIGHT, (now.guidance as Guidance.TurnNow).side)
        assertEquals("turn.now", now.reason.rule)
        val repeated = guide(now.nextState, SensorFrame(3_000L, 10.00175, 20.0, 5f, 1f, null), GuideConfig())
        assertNull(repeated.guidance)
    }

    @Test
    fun leftTurnUsesIndependentIndexAndSide() {
        val route = route(19.9982)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0, 20.0, 5f, 1f, null)).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0013, 20.0, 5f, 1f, null))
        assertIs<Guidance.TurnAhead>(result.guidance)
        assertEquals(Side.LEFT, (result.guidance as Guidance.TurnAhead).side)
    }

    @Test
    fun reverseStationaryAndOffRouteFramesDoNotAnnounceTurns() {
        val route = route()
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0, 20.0, 5f, 1f, null)).nextState
        state = guide(state, SensorFrame(1_000L, 10.0013, 20.0, 5f, 1f, null)).nextState
        val reverse = guide(state, SensorFrame(2_000L, 10.0014, 20.0, 5f, 1f, null))
        assertTrue(reverse.guidance !is Guidance.TurnAhead && reverse.guidance !is Guidance.TurnNow)

        val metersPerDegreeLon = 6_371_008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east30 = 30.0 / metersPerDegreeLon
        val offRouteConfig = GuideConfig(offRouteEnterDwellSeconds = 0.0)
        var offState = GuideState.initial(route)
        offState = guide(offState, SensorFrame(0L, 10.0, 20.0 + east30, 5f, 1f, null), offRouteConfig).nextState
        val off = guide(offState, SensorFrame(1_000L, 10.0013, 20.0 + east30, 5f, 1f, null), offRouteConfig)
        assertTrue(off.guidance !is Guidance.TurnAhead && off.guidance !is Guidance.TurnNow)
    }

    @Test
    fun routeStatusSharesNextTurnSelectionAndReverseHidesIt() {
        val route = route()
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0, 20.0, 5f, 1f, null)).nextState
        state = guide(state, SensorFrame(1_000L, 10.0013, 20.0, 5f, 1f, null)).nextState
        val status = routeStatus(state)
        assertTrue(status != null && status.onRoute)
        assertEquals(ProgressDirection.FORWARD, status?.direction)
        assertTrue(status?.nextTurn?.distanceMeters ?: 0.0 > 0.0)
        val reverseState = guide(state, SensorFrame(2_000L, 10.0008, 20.0, 5f, 1f, null)).nextState
        assertEquals(ProgressDirection.REVERSE, reverseState.direction)
        assertNull(routeStatus(reverseState)?.nextTurn)
    }

    @Test
    fun knownStraightBranchLimitationHasNoTurn() {
        val straight = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.001" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>"""
        )
        assertTrue(straight.turns.isEmpty(), "known limitation: a straight GPX has no geometric turn to announce")
    }
}
