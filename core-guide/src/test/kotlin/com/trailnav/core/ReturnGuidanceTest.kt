package com.trailnav.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ReturnGuidanceTest {
    @Test
    fun compassCoversEightSectorsAndD254Boundaries() {
        val cases = listOf(
            0.0 to Compass8.N,
            45.0 to Compass8.NE,
            90.0 to Compass8.E,
            135.0 to Compass8.SE,
            180.0 to Compass8.S,
            225.0 to Compass8.SW,
            270.0 to Compass8.W,
            315.0 to Compass8.NW,
            22.5 to Compass8.NE,
            337.5 to Compass8.N,
            22.4 to Compass8.N,
            337.4 to Compass8.NW,
        )
        for ((bearing, expected) in cases) {
            val radians = Math.toRadians(bearing)
            val route = routeAlong(localPoint(0.0, 0.0), localPoint(cos(radians) * 1_000.0, -sin(radians) * 1_000.0))
            val user = localPoint(-sin(radians) * 100.0, -cos(radians) * 100.0)
            val result = request(route, user)
            assertEquals(expected, result.compass, "bearing=$bearing degrees")
        }
    }

    @Test
    fun straightDistanceUsesTenMeterRoundingAndTenMeterFloor() {
        val cases = listOf(184.0 to 180, 185.0 to 190, 3.0 to 10)
        for ((distance, expected) in cases) {
            val route = routeAlong(localPoint(0.0, 0.0), localPoint(1_000.0, 0.0))
            val result = request(route, localPoint(0.0, -distance))
            assertEquals(expected, result.straightMeters, "distance=$distance m")
        }
    }

    @Test
    fun twoHundredMeterHintIsOncePerSegmentAndBelowThresholdTrajectoryStillExists() {
        val config = config()
        val route = lineRoute()
        val point = localPoint(2_500.0, 200.0)
        val timestamp = 100_000L
        val state = offRouteState(
            route,
            point,
            timestamp,
            config,
            lastAnnouncementAt = timestamp - 1_000L,
            lastAnnouncementDistance = 200.0,
            anchor = localPoint(2_500.0, 0.0),
        )

        val first = Engine.guide(state, frame(timestamp, point), config)
        assertEquals(Guidance.ReturnHint, first.guidance)
        assertTrue(first.nextState.returnHintConsumed)
        assertFalse(first.nextState.returnHintPending)
        assertEquals(timestamp - 1_000L, first.nextState.lastAnnouncementAt)

        val below = localPoint(2_500.0, 150.0)
        val second = Engine.guide(first.nextState, frame(timestamp + 1_000L, below), config)
        val aboveAgain = localPoint(2_500.0, 210.0)
        val third = Engine.guide(second.nextState, frame(timestamp + 2_000L, aboveAgain), config)
        assertTrue(second.guidance !is Guidance.ReturnHint)
        assertTrue(third.guidance !is Guidance.ReturnHint)
        assertTrue(third.guidance !is Guidance.OffRoute || !third.guidance.returnHint)
        assertTrue(third.nextState.returnHintConsumed)

        val shortWalk = request(
            route,
            localPoint(2_500.0, 30.0),
            anchor = localPoint(2_500.0, 0.0),
            trajectory = listOf(ReturnTrajectoryPoint(localPoint(2_500.0, 30.0), timestamp)),
        )
        assertEquals(30, shortWalk.backtrackMeters, "a continuous A is present even below 200 m")
    }

    @Test
    fun returnHintJoinsAnExistingAnnouncementAndKeepsOldReannounceRules() {
        val config = config()
        val route = lineRoute()
        val timestamp = 100_000L
        val point = localPoint(2_500.0, 200.0)
        val merged = Engine.guide(
            offRouteState(route, point, timestamp, config, lastAnnouncementAt = null, lastAnnouncementDistance = null),
            frame(timestamp, point),
            config,
        )
        val offRoute = assertIs<Guidance.OffRoute>(merged.guidance)
        assertTrue(offRoute.returnHint)
        assertEquals("true", merged.reason.details["returnHint"])
        assertEquals("200.0", merged.reason.details["distanceMeters"])

        val cases = listOf(
            Triple(30.0, 30.0, 60_000L), // 60 seconds
            Triple(30.0, 61.0, 1_000L), // more than twice the distance
            Triple(60.0, 29.0, 1_000L), // less than half the distance
        )
        for ((lastDistance, currentDistance, elapsedMillis) in cases) {
            val currentTime = timestamp + elapsedMillis
            val current = localPoint(2_500.0, currentDistance)
            val result = Engine.guide(
                offRouteState(
                    route,
                    current,
                    currentTime,
                    config,
                    lastAnnouncementAt = currentTime - elapsedMillis,
                    lastAnnouncementDistance = lastDistance,
                ),
                frame(currentTime, current),
                config,
            )
            assertIs<Guidance.OffRoute>(result.guidance)
            assertFalse((result.guidance as Guidance.OffRoute).returnHint)
        }

        val noExistingAnnouncement = Engine.guide(
            offRouteState(route, point, timestamp, config, lastAnnouncementAt = timestamp - 1_000L, lastAnnouncementDistance = 200.0),
            frame(timestamp, point),
            config,
        )
        assertEquals(Guidance.ReturnHint, noExistingAnnouncement.guidance)
        assertEquals(timestamp - 1_000L, noExistingAnnouncement.nextState.lastAnnouncementAt)
    }

    @Test
    fun higherPrioritySunsetLeavesReturnHintPendingUntilNextFrame() {
        val route = lineRoute()
        val point = localPoint(2_500.0, 200.0)
        val config = config().copy(sunsetEnabled = true)
        var timestamp = 16L * 60L * 60L * 1_000L
        var first: GuideResult? = null
        while (timestamp <= 20L * 60L * 60L * 1_000L && first == null) {
            val seeded = offRouteState(
                route,
                point,
                timestamp,
                config,
                lastAnnouncementAt = timestamp - 1_000L,
                lastAnnouncementDistance = 200.0,
            ).copy(startupEnabled = true, startupResolved = false, direction = ProgressDirection.UNKNOWN, sunsetEvaluated = false)
            val result = Engine.guide(seeded, frame(timestamp, point), config)
            if (result.guidance is Guidance.Sunset && result.nextState.returnHintPending) first = result
            timestamp += 60_000L
        }
        val deferred = assertNotNull(first, "a synthetic sunset candidate should preempt the return hint")
        assertTrue(deferred.nextState.returnHintPending)
        assertFalse(deferred.nextState.returnHintConsumed)

        val resumedTimestamp = assertNotNull(deferred.nextState.lastTimestamp) + 1_000L
        val resumedState = deferred.nextState.copy(
            startupResolved = true,
            direction = ProgressDirection.FORWARD,
            lastMatch = deferred.nextState.lastMatch?.copy(direction = ProgressDirection.FORWARD),
        )
        val resumed = Engine.guide(resumedState, frame(resumedTimestamp, point), config)
        assertEquals(Guidance.ReturnHint, resumed.guidance)
        assertTrue(resumed.nextState.returnHintConsumed)
    }

    @Test
    fun buttonActivatesAfterOffRouteDwellStaysStableAndClearsOncePerSegment() {
        val route = lineRoute()
        val config = config()
        var state = GuideState.initial(route)
        fun step(timestamp: Long, point: GeoPoint): GuideResult {
            val result = Engine.guide(state, frame(timestamp, point), config)
            state = result.nextState
            return result
        }

        step(0L, localPoint(100.0, 0.0))
        assertFalse(state.returnButtonActive)
        step(1_000L, localPoint(100.0, 26.0))
        assertFalse(state.offRoute)
        step(20_000L, localPoint(100.0, 28.0))
        assertFalse(state.offRoute)
        val entered = step(21_000L, localPoint(100.0, 30.0))
        assertTrue(state.offRoute)
        assertTrue(state.returnButtonActive)
        assertTrue(entered.guidance is Guidance.OffRoute)
        assertEquals(1, state.systemEvents.count { it.kind == "return.available" })

        step(22_000L, localPoint(100.0, 24.0))
        step(23_000L, localPoint(100.0, 40.0))
        step(24_000L, localPoint(100.0, 26.0))
        step(25_000L, localPoint(100.0, 190.0))
        assertTrue(state.returnButtonActive)
        assertEquals(1, state.systemEvents.count { it.kind == "return.available" })

        step(26_000L, localPoint(100.0, 14.0))
        assertTrue(state.offRoute)
        val returned = step(36_000L, localPoint(100.0, 14.0))
        assertFalse(state.offRoute)
        assertFalse(state.returnButtonActive)
        assertEquals(1, state.systemEvents.count { it.kind == "return.cleared" })
        assertEquals("returned", state.systemEvents.single { it.kind == "return.cleared" }.fields["reason"])
        assertTrue(returned.nextState.returnAnchor != null)

        step(37_000L, localPoint(100.0, 30.0))
        val secondEntry = step(57_000L, localPoint(100.0, 30.0))
        assertTrue(secondEntry.nextState.returnButtonActive)
        assertEquals(2, state.systemEvents.count { it.kind == "return.available" })
        assertFalse(state.returnHintConsumed)
        val secondHint = step(58_000L, localPoint(100.0, 200.0))
        assertTrue((secondHint.guidance as? Guidance.OffRoute)?.returnHint == true)
        assertTrue(state.returnHintConsumed)

        step(59_000L, localPoint(100.0, 14.0))
        step(69_000L, localPoint(100.0, 14.0))
        assertEquals(2, state.systemEvents.count { it.kind == "return.cleared" })
        assertEquals(2, state.systemEvents.count { it.kind == "return.available" })
    }

    @Test
    fun requestIsDisabledOnRouteWhileWaitingOrBeforeDirectionIsKnown() {
        val route = lineRoute()
        val config = config()
        val state = offRouteState(route, localPoint(2_500.0, 100.0), 100_000L, config)
        assertNull(Engine.returnGuidance(state.copy(returnButtonActive = false), config))
        assertNull(Engine.returnGuidance(state.copy(waitingForRoute = true), config))
        assertNull(Engine.returnGuidance(state.copy(direction = ProgressDirection.UNKNOWN), config))
        assertNull(Engine.returnGuidance(state.copy(startupEnabled = true, startupResolved = false), config))
        assertNull(Engine.returnGuidance(state.copy(offRoute = false), config))
        val unknownDirectionFrame = Engine.guide(
            state.copy(startupEnabled = true, startupResolved = false, returnAvailableEmitted = false),
            frame(100_000L, localPoint(2_500.0, 100.0)),
            config,
        )
        assertFalse(unknownDirectionFrame.nextState.returnButtonActive)
        val directionKnown = Engine.guide(
            unknownDirectionFrame.nextState.copy(startupResolved = true, direction = ProgressDirection.FORWARD),
            frame(101_000L, localPoint(2_500.0, 100.0)),
            config,
        )
        assertTrue(directionKnown.nextState.returnButtonActive)
        assertEquals(1, directionKnown.nextState.systemEvents.count { it.kind == "return.available" })
    }

    @Test
    fun arrivalAndRematchClearAnActiveReturnSegment() {
        val route = lineRoute()
        val config = config().copy(minimumSessionSecondsBeforeArrival = 0.0)
        val previous = project(route, 4_900.0).copy(direction = ProgressDirection.FORWARD)
        val arrivalState = GuideState.initial(route).copy(
            hasEnteredRoute = true,
            lastMatch = previous,
            firstMatchProgressMeters = previous.projectedMeters,
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
            offRoute = true,
            offRouteSince = 0L,
            returnButtonActive = true,
            returnAvailableEmitted = true,
            returnCurrentLocation = pointAt(4_900.0),
            returnAnchor = ReturnTrajectoryPoint(pointAt(4_900.0), 0L),
            arrivalBaselineRemainingMeters = 100.0,
            advanceHighWaterMeters = 4_900.0,
            advanceCreditedMeters = 100.0,
        )
        val arrived = Engine.guide(arrivalState, frame(20_000L, pointAt(4_990.0)), config)
        assertEquals(Guidance.Arrived, arrived.guidance)
        assertFalse(arrived.nextState.returnButtonActive)
        assertEquals("arrived", arrived.nextState.systemEvents.single { it.kind == "return.cleared" }.fields["reason"])

        val shortRoute = lineRoute(1_000.0)
        val rematchConfig = config().copy(positionGapRematchSeconds = 120.0, taggingOffRouteDwellSeconds = 600.0)
        val rematch = rematchSeed(shortRoute, rematchConfig).copy(
            offRoute = true,
            offRouteSince = 0L,
            returnButtonActive = true,
            returnAvailableEmitted = true,
            returnAnchor = ReturnTrajectoryPoint(pointAt(900.0), 0L),
            returnCurrentLocation = pointAt(960.0),
            rematchPending = true,
            rematchPendingReason = "test-rematch",
        )
        val rematched = Engine.guide(rematch, frame(30_000L, pointAt(970.0), speed = 0f), rematchConfig)
        assertEquals(1, rematched.nextState.systemEvents.count { it.kind == "route.rematched" })
        assertFalse(rematched.nextState.returnButtonActive)
        assertEquals("rematch", rematched.nextState.systemEvents.single { it.kind == "return.cleared" }.fields["reason"])
        assertNull(Engine.returnGuidance(rematched.nextState, rematchConfig))
    }

    @Test
    fun backtrackIsAvailableForShortContinuousTracksAndAbsentAfterGapOrCap() {
        val route = lineRoute()
        val config = config().copy(positionGapRematchSeconds = 30.0, taggingOffRouteDwellSeconds = 600.0)
        val anchor = localPoint(2_500.0, 0.0)
        val current = localPoint(2_500.0, 30.0)
        val short = request(
            route,
            current,
            anchor = anchor,
            trajectory = listOf(ReturnTrajectoryPoint(current, 100_000L)),
        )
        assertEquals(30, short.backtrackMeters)

        val gapTimestamp = 100_000L
        val withTrack = offRouteState(
            route,
            current,
            gapTimestamp,
            config,
            anchor = anchor,
            lastAnnouncementAt = gapTimestamp,
            lastAnnouncementDistance = 30.0,
        )
        val afterGap = Engine.guide(withTrack, frame(gapTimestamp + 30_000L, current), config)
        assertEquals("position-gap", afterGap.nextState.returnTrajectoryUnavailableReason)
        assertNull(assertNotNull(Engine.returnGuidance(afterGap.nextState, config)).guidance.let { (it as Guidance.ReturnGuide).backtrackMeters })

        val capTimestamp = 500_000L
        val capCurrent = localPoint(2_500.0, 2_105.0)
        val capTrack = (1..400).map { index ->
            ReturnTrajectoryPoint(localPoint(2_500.0, index * 5.0), capTimestamp - 400_000L + index * 1_000L)
        }
        val capSeed = offRouteState(
            route,
            capCurrent,
            capTimestamp,
            config.copy(positionGapRematchSeconds = 30.0),
            anchor = localPoint(2_500.0, 0.0),
            trajectory = capTrack,
            lastAnnouncementAt = capTimestamp,
            lastAnnouncementDistance = 2_105.0,
        )
        val capped = Engine.guide(capSeed, frame(capTimestamp + 1_000L, capCurrent), config)
        assertTrue(
            capped.nextState.returnTrajectoryTruncated,
            "guidance=${capped.guidance}, rule=${capped.reason.rule}, offRoute=${capped.nextState.offRoute}, timestamp=${capped.nextState.lastTimestamp}, truncated=${capped.nextState.returnTrajectoryTruncated}, reason=${capped.nextState.returnTrajectoryUnavailableReason}, samples=${capped.nextState.returnTrajectory.size}",
        )
        assertEquals("truncated", capped.nextState.returnTrajectoryUnavailableReason)
        val cappedRequest = assertNotNull(Engine.returnGuidance(capped.nextState, config)).guidance as Guidance.ReturnGuide
        assertNull(cappedRequest.backtrackMeters)

        val startedOffRoute = request(route, current)
        assertNull(startedOffRoute.backtrackMeters)
    }

    @Test
    fun repeatedRequestsUseLatestPositionWithoutChangingState() {
        val route = lineRoute()
        val config = config()
        val firstPoint = localPoint(2_500.0, 100.0)
        val state = offRouteState(route, firstPoint, 100_000L, config)
        val first = assertNotNull(Engine.returnGuidance(state, config))
        val repeated = assertNotNull(Engine.returnGuidance(state, config))
        assertEquals(first, repeated)
        assertEquals(state, first.nextState)
        val firstGuide = first.guidance as Guidance.ReturnGuide

        val latestPoint = localPoint(2_500.0, 250.0)
        val latestMatch = match(route, latestPoint, config).copy(direction = ProgressDirection.FORWARD)
        val latestState = state.copy(returnCurrentLocation = latestPoint, lastMatch = latestMatch, lastTimestamp = 101_000L)
        val latest = assertNotNull(Engine.returnGuidance(latestState, config)).guidance as Guidance.ReturnGuide
        assertEquals(250, latest.straightMeters)
        assertTrue(latest.straightMeters > firstGuide.straightMeters)
        assertEquals(latestState, Engine.returnGuidance(latestState, config)?.nextState)
    }

    @Test
    fun configDefaultsAndRejectsVoiceThresholdBelowOffRouteEntry() {
        assertEquals(200.0, GuideConfig().returnGuideTriggerMeters)
        assertFailsWith<IllegalArgumentException> { GuideConfig(returnGuideTriggerMeters = 24.9) }
        assertTrue("returnGuideTriggerMeters" in GuideConfig.sensitivityRequired)
    }

    private fun config(): GuideConfig = GuideConfig(
        sunsetEnabled = false,
        sunriseEnabled = false,
        returnGuideTriggerMeters = 200.0,
        offRouteEnterDwellSeconds = 20.0,
        offRouteExitDwellSeconds = 10.0,
        reannounceIntervalSeconds = 60.0,
        positionGapRematchSeconds = 120.0,
        taggingOffRouteDwellSeconds = 600.0,
    )

    private fun request(
        route: RouteModel,
        current: GeoPoint,
        anchor: GeoPoint? = null,
        trajectory: List<ReturnTrajectoryPoint> = emptyList(),
    ): Guidance.ReturnGuide {
        val config = config()
        val state = offRouteState(route, current, 100_000L, config, anchor = anchor, trajectory = trajectory)
        return assertIs<Guidance.ReturnGuide>(assertNotNull(Engine.returnGuidance(state, config)).guidance)
    }

    private fun offRouteState(
        route: RouteModel,
        current: GeoPoint,
        timestamp: Long,
        config: GuideConfig,
        lastAnnouncementAt: Long? = timestamp,
        lastAnnouncementDistance: Double? = match(route, current, config).distanceMeters,
        anchor: GeoPoint? = null,
        trajectory: List<ReturnTrajectoryPoint> = anchor?.let {
            listOf(ReturnTrajectoryPoint(current, timestamp - 1L))
        } ?: emptyList(),
    ): GuideState {
        val currentMatch = match(route, current, config).copy(direction = ProgressDirection.FORWARD)
        return GuideState.initial(route).copy(
            hasEnteredRoute = true,
            waitingDecisionEvaluated = true,
            lastMatch = currentMatch,
            firstMatchProgressMeters = currentMatch.projectedMeters,
            lastTimestamp = timestamp - 1L,
            sessionStartTimestamp = timestamp - 30_000L,
            direction = ProgressDirection.FORWARD,
            offRoute = true,
            offRouteSince = timestamp - 1L,
            lastAnnouncementAt = lastAnnouncementAt,
            lastAnnouncementDistance = lastAnnouncementDistance,
            returnButtonActive = true,
            returnAvailableEmitted = true,
            returnAnchor = anchor?.let { ReturnTrajectoryPoint(it, timestamp - 10_000L) },
            returnTrajectory = trajectory,
            returnCurrentLocation = current,
        )
    }

    private fun rematchSeed(route: RouteModel, config: GuideConfig): GuideState {
        val samples = (0..4).map { index ->
            val progress = 920.0 + index * 10.0
            StartTrajectorySample(pointAt(progress), 25_000L + index * 1_000L, 960.0 + index * 10.0, 1.0)
        }
        val old = project(route, 500.0).copy(direction = ProgressDirection.FORWARD)
        return GuideState.initial(route).copy(
            hasEnteredRoute = true,
            lastMatch = old,
            firstMatchProgressMeters = old.projectedMeters,
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
            trajectorySamples = samples,
            trajectoryWalkedMeters = samples.last().walkedMeters,
            trajectoryMetersConfig = config.startTrajectoryMeters,
            trajectoryFrameCount = samples.size,
            trajectoryStartTimestampMillis = samples.first().timestampMillis,
            lastTrajectoryTimestampMillis = 29_000L,
            lastTrajectoryPoint = pointAt(960.0),
            lastTrajectorySpeedMps = 0.0,
            lastTrajectoryAccuracyMeters = 1.0,
            arrivalBaselineRemainingMeters = route.totalLengthMeters - old.projectedMeters,
            advanceHighWaterMeters = old.projectedMeters,
        )
    }

    private fun lineRoute(lengthMeters: Double = 5_000.0): RouteModel {
        val points = (0..(lengthMeters / 50.0).toInt()).map { index -> localPoint(index * 50.0, 0.0) }
        return routeFromPoints(points)
    }

    private fun routeAlong(first: GeoPoint, second: GeoPoint): RouteModel = routeFromPoints(listOf(first, second))

    private fun routeFromPoints(points: List<GeoPoint>): RouteModel {
        val track = points.joinToString("") { point -> "<trkpt lat=\"${point.lat}\" lon=\"${point.lon}\"/>" }
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>")
    }

    private fun match(route: RouteModel, point: GeoPoint, config: GuideConfig): MatchResult =
        RouteMatcher.nearest(route, frame(0L, point), previousS = null, config = config)!!

    private fun project(route: RouteModel, progressMeters: Double): MatchResult {
        val segment = route.cumulativeMeters.indexOfLast { it <= progressMeters }
            .coerceIn(0, route.points.lastIndex - 1)
        val fraction = (progressMeters - route.cumulativeMeters[segment]) /
            (route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment])
        val start = route.points[segment]
        val end = route.points[segment + 1]
        return MatchResult(
            distanceMeters = 0.0,
            projectedMeters = progressMeters,
            segmentIndex = segment,
            projectedPoint = EnuPoint(
                start.east + (end.east - start.east) * fraction,
                start.north + (end.north - start.north) * fraction,
            ),
            direction = ProgressDirection.FORWARD,
        )
    }

    private fun pointAt(progressMeters: Double): GeoPoint = localPoint(progressMeters, 0.0)

    private fun localPoint(eastMeters: Double, northMeters: Double): GeoPoint = GeoPoint(
        Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
        Math.toDegrees(eastMeters / EARTH_RADIUS_METERS),
    )

    private fun frame(timestamp: Long, point: GeoPoint, speed: Float = 1f): SensorFrame =
        SensorFrame(timestamp, point.lat, point.lon, 1f, speed, null)

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
