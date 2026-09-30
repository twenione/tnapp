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
        val reverseRoute = route.copy(turns = listOf(TurnPoint(150.0, Side.RIGHT, 90.0)))
        var state = guide(GuideState.initial(reverseRoute), SensorFrame(0L, 10.0012, 20.0, 5f, 1f, null)).nextState
        val reverse = guide(state, SensorFrame(1_000L, 10.0011, 20.0, 5f, 1f, null), GuideConfig(emaAlpha = 1.0))
        assertTrue(reverse.guidance !is Guidance.TurnAhead && reverse.guidance !is Guidance.TurnNow)

        val metersPerDegreeLon = 6_371_008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east20 = 20.0 / metersPerDegreeLon
        val offsetConfig = GuideConfig(offRouteEnterDwellSeconds = 100.0)
        var offsetState = GuideState.initial(route)
        offsetState = guide(offsetState, SensorFrame(0L, 10.0, 20.0, 5f, 1f, null), offsetConfig).nextState
        val offset = guide(offsetState, SensorFrame(1_000L, 10.0013, 20.0 + east20, 5f, 1f, null), offsetConfig)
        assertTrue(offset.guidance !is Guidance.TurnAhead && offset.guidance !is Guidance.TurnNow)

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
        val reverseState = guide(state, SensorFrame(2_000L, 10.0008, 20.0, 5f, 1f, null), GuideConfig(emaAlpha = 1.0)).nextState
        assertEquals(ProgressDirection.REVERSE, reverseState.direction)
        assertNull(routeStatus(reverseState)?.nextTurn)
    }

    private fun adjacentTurnsConfig(mergeGapMeters: Double = 40.0) = GuideConfig(
        sunsetEnabled = false,
        eventMinIntervalSeconds = 0.0,
        turnLookbackMeters = 5.0,
        turnLookaheadMeters = 5.0,
        turnAngleThresholdDegrees = 45.0,
        turnMergeGapMeters = mergeGapMeters,
        douglasPeuckerEpsilonMeters = 0.0,
    )

    private fun adjacentTurnsRoute(gapMeters: Double, config: GuideConfig): RouteModel {
        val metersPerDegreeLatitude = 111_195.0
        val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(Math.toRadians(10.0))
        val startLat = 10.0
        val startLon = 20.0
        val points = listOf(
            GeoPoint(startLat, startLon),
            GeoPoint(startLat + 50.0 / metersPerDegreeLatitude, startLon),
            GeoPoint(startLat + 100.0 / metersPerDegreeLatitude, startLon),
            GeoPoint(startLat + 100.0 / metersPerDegreeLatitude, startLon + 5.0 / metersPerDegreeLongitude),
            GeoPoint(startLat + 100.0 / metersPerDegreeLatitude, startLon + gapMeters / metersPerDegreeLongitude),
            GeoPoint(startLat + 150.0 / metersPerDegreeLatitude, startLon + gapMeters / metersPerDegreeLongitude),
            GeoPoint(startLat + 200.0 / metersPerDegreeLatitude, startLon + gapMeters / metersPerDegreeLongitude),
        )
        val track = points.joinToString("") { point ->
            "<trkpt lat=\"${point.lat}\" lon=\"${point.lon}\"/>"
        }
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>", config)
    }

    private fun frameAt(route: RouteModel, pointIndex: Int, timestamp: Long) =
        SensorFrame(timestamp, route.sourcePoints[pointIndex].lat, route.sourcePoints[pointIndex].lon, 5f, 1f, null)

    private fun afterFirstTurn(route: RouteModel, config: GuideConfig, expectedGapMeters: Double = 27.0): GuideResult {
        assertTrue(route.turns.size >= 2, "synthetic route must contain both right-angle turns")
        assertTrue(kotlin.math.abs((route.turns[1].s - route.turns[0].s) - expectedGapMeters) < 2.0)
        var result = guide(GuideState.initial(route), frameAt(route, 0, 0L), config)
        result = guide(result.nextState, frameAt(route, 1, 1_000L), config)
        assertIs<Guidance.TurnAhead>(result.guidance)
        assertEquals("0", result.reason.details["turnIndex"])
        result = guide(result.nextState, frameAt(route, 2, 2_000L), config)
        assertIs<Guidance.TurnNow>(result.guidance)
        assertEquals("0", result.reason.details["turnIndex"])
        return guide(result.nextState, frameAt(route, 3, 3_000L), config)
    }

    @Test
    fun adjacentTurnsWithinMergeGapSuppressTheSecondAheadButNotTheSecondNow() {
        val config = adjacentTurnsConfig()
        val route = adjacentTurnsRoute(27.0, config)
        val onShortLeg = afterFirstTurn(route, config)
        assertNull(onShortLeg.guidance, "the second turn's ahead cue is merged into the first")
        val atSecondTurn = guide(onShortLeg.nextState, frameAt(route, 4, 4_000L), config)
        assertIs<Guidance.TurnNow>(atSecondTurn.guidance)
        assertEquals("1", atSecondTurn.reason.details["turnIndex"])
    }

    @Test
    fun turnsBeyondMergeGapBothAnnounceAhead() {
        val config = adjacentTurnsConfig()
        val route = adjacentTurnsRoute(50.0, config)
        val secondTurn = afterFirstTurn(route, config, expectedGapMeters = 50.0)
        assertIs<Guidance.TurnAhead>(secondTurn.guidance)
        assertEquals("1", secondTurn.reason.details["turnIndex"])
    }

    @Test
    fun mergeGapConfigChangesSuppression() {
        val defaultConfig = adjacentTurnsConfig()
        val defaultRoute = adjacentTurnsRoute(27.0, defaultConfig)
        assertNull(afterFirstTurn(defaultRoute, defaultConfig).guidance)

        val smallerGapConfig = adjacentTurnsConfig(20.0)
        val smallerGapRoute = adjacentTurnsRoute(27.0, smallerGapConfig)
        val secondTurn = afterFirstTurn(smallerGapRoute, smallerGapConfig)
        assertIs<Guidance.TurnAhead>(secondTurn.guidance)
        assertEquals("1", secondTurn.reason.details["turnIndex"])
    }

    @Test
    fun knownStraightBranchLimitationHasNoTurn() {
        val straight = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.001" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>"""
        )
        assertTrue(straight.turns.isEmpty(), "known limitation: a straight GPX has no geometric turn to announce")
    }
}
