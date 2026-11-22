package com.trailnav.core

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun closedRoutePredicateUsesTheSharedHundredMeterBoundary() {
        val config = GuideConfig(roundTripLoopMaxGapMeters = 100.0)
        fun routeWithGap(meters: Double): RouteModel {
            val longitude = Math.toDegrees(meters / 6_371_008.8)
            return RouteModel.fromGpx(
                """<gpx><trk><trkseg><trkpt lat="0" lon="0"/><trkpt lat="0" lon="$longitude"/></trkseg></trk></gpx>""",
                config,
            )
        }

        listOf(0.0, 5.7, 100.0).forEach { gap ->
            assertTrue(isClosedRoute(routeWithGap(gap), config), "gap=$gap")
        }
        listOf(100.1, 840.8).forEach { gap ->
            assertFalse(isClosedRoute(routeWithGap(gap), config), "gap=$gap")
        }
        assertFalse(
            isClosedRoute(routeWithGap(840.8).copy(roundTripTurnaroundMeters = 840.8), config),
            "a constructed open round-trip route keeps its leg-aware wording",
        )
    }

    @Test
    fun defaultTurnNowDistanceIsTenMetersWithoutChangingTurnAheadDistance() {
        val config = GuideConfig()

        assertEquals(10.0, config.turnNowDistanceMeters)
        assertEquals(60.0, config.turnAheadDistanceMeters)
    }

    @Test
    fun rightTurnAheadThenNowIsAnnouncedOnce() {
        val route = route()
        assertTrue(route.turns.isNotEmpty(), "synthetic right-angle route must contain a turn")
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0, 20.0, 5f, 1f, null)).nextState
        val ahead = guide(state, SensorFrame(1_000L, 10.0013, 20.0, 5f, 1f, null), GuideConfig())
        assertIs<Guidance.TurnAhead>(ahead.guidance)
        assertEquals(Side.RIGHT, (ahead.guidance as Guidance.TurnAhead).side)
        assertEquals(0, (ahead.guidance as Guidance.TurnAhead).turnIndex)
        assertEquals("turn.ahead", ahead.reason.rule)
        assertEquals((ahead.guidance as Guidance.TurnAhead).turnIndex.toString(), ahead.reason.details["turnIndex"])
        state = ahead.nextState
        val repeatedAhead = guide(state, SensorFrame(1_500L, 10.00135, 20.0, 5f, 1f, null), GuideConfig())
        assertNull(repeatedAhead.guidance, "a turn-ahead announcement is consumed per turn index")
        state = repeatedAhead.nextState
        val outsideNowDistance = guide(state, SensorFrame(1_750L, 10.0017, 20.0, 5f, 1f, null), GuideConfig())
        assertTrue(outsideNowDistance.guidance !is Guidance.TurnNow)
        state = outsideNowDistance.nextState
        val now = guide(state, SensorFrame(2_000L, 10.00175, 20.0, 5f, 1f, null), GuideConfig())
        assertIs<Guidance.TurnNow>(now.guidance)
        assertTrue(now.reason.details.getValue("remainingMeters").toDouble() <= GuideConfig().turnNowDistanceMeters)
        assertEquals(Side.RIGHT, (now.guidance as Guidance.TurnNow).side)
        assertEquals(0, (now.guidance as Guidance.TurnNow).turnIndex)
        assertEquals("turn.now", now.reason.rule)
        assertEquals((now.guidance as Guidance.TurnNow).turnIndex.toString(), now.reason.details["turnIndex"])
        val turnIndex = (now.guidance as Guidance.TurnNow).turnIndex
        assertFalse(turnPassed(now.nextState, turnIndex))
        val afterTurn = guide(now.nextState, SensorFrame(2_500L, 10.0018, 20.0001, 5f, 1f, null), GuideConfig())
        assertTrue(turnPassed(afterTurn.nextState, turnIndex))
        val repeated = guide(afterTurn.nextState, SensorFrame(3_000L, 10.0018, 20.0002, 5f, 1f, null), GuideConfig())
        assertNull(repeated.guidance)
    }

    @Test
    fun turnNowUsesTheExactTenMeterBoundaryAndDoesNotRepeatInsideIt() {
        val route = route()
        val config = GuideConfig(eventMinIntervalSeconds = 0.0)
        val turn = route.turns.first()
        val turnPoint = route.points[1]
        val origin = route.sourcePoints.first()
        fun frameAtRemaining(remainingMeters: Double, timestampMillis: Long): SensorFrame {
            val north = turnPoint.north - remainingMeters
            var latitude = origin.lat + Math.toDegrees(north / 6_371_008.8)
            while (turn.s - route.toEnu(GeoPoint(latitude, origin.lon)).north > remainingMeters) {
                latitude = Math.nextUp(latitude)
            }
            return SensorFrame(timestampMillis, latitude, origin.lon, 5f, 1f, null)
        }

        val starting = guide(GuideState.initial(route), frameAtRemaining(turn.s, 0L), config)
        val outside = guide(starting.nextState, frameAtRemaining(10.01, 1_000L), config)
        assertIs<Guidance.TurnAhead>(outside.guidance)
        assertTrue(outside.reason.details.getValue("remainingMeters").toDouble() > 10.0)

        val atBoundary = guide(outside.nextState, frameAtRemaining(10.0, 2_000L), config)
        assertIs<Guidance.TurnNow>(atBoundary.guidance)
        val remainingAtBoundary = atBoundary.reason.details.getValue("remainingMeters").toDouble()
        assertTrue(remainingAtBoundary <= 10.0)
        assertTrue(kotlin.math.abs(remainingAtBoundary - 10.0) < 1e-6)

        val justInside = guide(atBoundary.nextState, frameAtRemaining(9.99, 3_000L), config)
        assertNull(justInside.guidance, "turn-now is announced once even after crossing inside the boundary")
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
    fun stationaryAndOffRouteFramesDoNotAnnounceTurns() {
        val route = route()
        val stationary = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0012, 20.0, 5f, 0f, null),
        )
        assertTrue(stationary.guidance !is Guidance.TurnAhead && stationary.guidance !is Guidance.TurnNow)

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
    fun routeStatusSharesNextTurnSelectionForCurrentRoute() {
        val route = route()
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0, 20.0, 5f, 1f, null)).nextState
        state = guide(state, SensorFrame(1_000L, 10.0013, 20.0, 5f, 1f, null)).nextState
        val status = routeStatus(state)
        assertTrue(status != null && status.onRoute)
        assertEquals(ProgressDirection.FORWARD, status?.direction)
        assertTrue(status?.nextTurn?.distanceMeters ?: 0.0 > 0.0)
    }

    private fun adjacentTurnsConfig(mergeGapMeters: Double = GuideConfig().turnMergeGapMeters) = GuideConfig(
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
            GeoPoint(startLat + 100.0 / metersPerDegreeLatitude, startLon + 15.0 / metersPerDegreeLongitude),
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
        assertEquals(1, (atSecondTurn.guidance as Guidance.TurnNow).turnIndex)
        assertEquals("1", atSecondTurn.reason.details["turnIndex"])
    }

    @Test
    fun turnsBeyondMergeGapBothAnnounceAhead() {
        val config = adjacentTurnsConfig()
        val route = adjacentTurnsRoute(70.0, config)
        val secondTurn = afterFirstTurn(route, config, expectedGapMeters = 70.0)
        assertIs<Guidance.TurnAhead>(secondTurn.guidance)
        assertEquals(1, (secondTurn.guidance as Guidance.TurnAhead).turnIndex)
        assertEquals("1", secondTurn.reason.details["turnIndex"])
    }

    @Test
    fun defaultMergeGapIs60MetersAndSuppresses47ButAllows70() {
        assertEquals(60.0, GuideConfig().turnMergeGapMeters)

        val config = adjacentTurnsConfig()
        val shortGapRoute = adjacentTurnsRoute(47.0, config)
        assertNull(
            afterFirstTurn(shortGapRoute, config, expectedGapMeters = 47.0).guidance,
            "the second ahead cue is merged at the observed 47 m gap",
        )

        val longGapRoute = adjacentTurnsRoute(70.0, config)
        val secondTurn = afterFirstTurn(longGapRoute, config, expectedGapMeters = 70.0)
        assertIs<Guidance.TurnAhead>(secondTurn.guidance)
        assertEquals(1, (secondTurn.guidance as Guidance.TurnAhead).turnIndex)
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
