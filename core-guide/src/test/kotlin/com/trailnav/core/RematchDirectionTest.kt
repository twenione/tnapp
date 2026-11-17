package com.trailnav.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RematchDirectionTest {
    @Test
    fun h8PlausibleLongGapDoesNotResetGoldenArrivalProgress() {
        val route = RouteModel.fromGpx(
            "<gpx><trk><trkseg>" +
                "<trkpt lat=\"37.0\" lon=\"127.0\"/>" +
                "<trkpt lat=\"37.0\" lon=\"127.00168721\"/>" +
                "<trkpt lat=\"37.00134747\" lon=\"127.00168721\"/>" +
                "</trkseg></trk></gpx>",
        )
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false, gapMaxWalkSpeedMps = 2.0)
        var state = GuideState.starting(route, config)
        val samples = listOf(
            0L to GeoPoint(37.0, 127.0),
            15_000L to GeoPoint(37.0, 127.0001687211),
            30_000L to GeoPoint(37.0, 127.0003374422),
            95_000L to GeoPoint(37.0, 127.0010685671),
            140_000L to GeoPoint(37.0, 127.0015747304),
            290_000L to GeoPoint(37.0012576356, 127.0016872112),
        )
        var lastResult: GuideResult? = null
        for ((timestamp, point) in samples) {
            lastResult = Engine.guide(state, frame(timestamp, point, speed = 1f), config)
            state = lastResult.nextState
        }

        assertEquals(Guidance.Arrived, lastResult?.guidance)
        assertTrue(state.arrived)
        assertTrue(state.systemEvents.none { it.kind == "route.rematched" && it.fields["reason"] == "location-gap" })
    }

    @Test
    fun h9UnwalkableGapTriggersGapRematchAndH10ShortGapDoesNotUseThatCondition() {
        val route = lineRoute(1_000.0)
        val config = GuideConfig(
            sunsetEnabled = false,
            sunriseEnabled = false,
            gapMaxWalkSpeedMps = 2.0,
            arrivalWalkMaxSpeedMps = 10.0,
        )

        fun runGap(gapSeconds: Double): GuideState {
            val speed = (400.0 / gapSeconds).toFloat()
            val priorSamples = listOf(StartTrajectorySample(pointAt(0.0), 0L, 0.0, 1.0))
            val seeded = GuideState.initial(route).copy(
                hasEnteredRoute = true,
                lastMatch = project(route, 0.0).copy(direction = ProgressDirection.FORWARD),
                lastTimestamp = 0L,
                sessionStartTimestamp = 0L,
                direction = ProgressDirection.FORWARD,
                trajectorySamples = priorSamples,
                trajectoryWalkedMeters = 0.0,
                trajectoryFrameCount = priorSamples.size,
                trajectoryStartTimestampMillis = 0L,
                lastTrajectoryTimestampMillis = 0L,
                lastTrajectoryPoint = pointAt(0.0),
                lastTrajectorySpeedMps = speed.toDouble(),
                lastTrajectoryAccuracyMeters = 1.0,
                arrivalBaselineRemainingMeters = route.totalLengthMeters,
                advanceHighWaterMeters = 0.0,
            )
            return Engine.guide(
                seeded,
                frame((gapSeconds * 1_000.0).toLong(), pointAt(400.0), speed),
                config,
            ).nextState
        }

        val unwalkableGap = runGap(60.0)
        val h9Rematch = unwalkableGap.systemEvents.firstOrNull { it.kind == "route.rematched" }
        assertTrue(
            h9Rematch != null,
            "H9 expected a gap rematch; events=${unwalkableGap.systemEvents}, pending=${unwalkableGap.rematchPendingReason}, " +
                "samples=${unwalkableGap.trajectorySamples.map { it.walkedMeters to it.point }}, " +
                "last=${unwalkableGap.lastMatch}, baseline=${unwalkableGap.arrivalBaselineRemainingMeters}",
        )
        requireNotNull(h9Rematch)
        assertEquals("location-gap", h9Rematch.fields["reason"])
        assertEquals(0.0, unwalkableGap.advanceCreditedMeters)
        assertTrue(kotlin.math.abs((unwalkableGap.arrivalBaselineRemainingMeters ?: 0.0) - 600.0) <= 2.0)

        val belowGapThreshold = runGap(29.0)
        assertTrue(belowGapThreshold.systemEvents.none { it.kind == "route.rematched" })
        assertFalse(belowGapThreshold.rematchPending)
    }

    @Test
    fun h1SamePathTurnaroundSwitchesAt150mAndArrivesAtOriginalStart() {
        val route = lineRoute(5_000.0)
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false)
        var state = GuideState.starting(route, config)
        var timestamp = 0L
        var turnaroundVoices = 0

        fun walk(progress: Double) {
            val result = Engine.guide(state, frame(timestamp, pointAt(progress)), config)
            state = result.nextState
            if ((result.guidance as? Guidance.Status)?.message == "되돌아가는 방향으로 안내합니다") turnaroundVoices += 1
            timestamp += 10_000L
        }

        for (step in 0..300) walk(step * 10.0)
        assertTrue(state.startupResolved)
        assertEquals(ProgressDirection.FORWARD, state.direction)
        val arrivalBeforeSwitch = state.arrivalBaselineRemainingMeters

        for (step in 299 downTo 285) walk(step * 10.0)
        val switch = state.systemEvents.single { it.kind == "direction.switched" }
        assertEquals("turnaround", switch.fields["condition"])
        assertEquals(1, state.systemEvents.count { it.kind == "arrival.baseline-reset" } - 1)
        assertEquals(ProgressDirection.FORWARD, state.direction)
        assertTrue(kotlin.math.abs((state.arrivalBaselineRemainingMeters ?: 0.0) - 2_850.0) <= 15.0)
        assertTrue((arrivalBeforeSwitch ?: 0.0) > 1_900.0)
        assertEquals(1, turnaroundVoices)

        val voiceCount = state.systemEvents.count { it.kind == "direction.switched" }
        for (step in 284 downTo 0) walk(step * 10.0)
        assertTrue(state.arrived, "walking from the switch point to the original start should arrive")
        assertEquals(voiceCount, state.systemEvents.count { it.kind == "direction.switched" })
    }

    @Test
    fun h4ReverseBelow150mIsSilentAndMatchesTheHighThresholdRun() {
        val route = lineRoute(1_500.0)
        val ordinary = replayTurnaround(route, 150.0, 100.0)
        val transitionOff = replayTurnaround(route, 100_000.0, 100.0)

        assertEquals(transitionOff, ordinary)
        assertTrue(ordinary.none { it.first is Guidance.Status })
        assertTrue(ordinary.all { it.second.direction != ProgressDirection.REVERSE })
    }

    @Test
    fun h2LollipopReverseBranchSwitchesWithoutTurnaroundVoice() {
        val route = lollipopRoute()
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false)
        var state = GuideState.starting(route, config)
        var timestamp = 0L
        val outputs = mutableListOf<Guidance?>()
        fun walk(point: GeoPoint) {
            val result = Engine.guide(state, frame(timestamp, point), config)
            state = result.nextState
            outputs += result.guidance
            timestamp += 10_000L
        }

        for (north in 0..200 step 10) walk(localPoint(0.0, north.toDouble()))
        val before = route.totalLengthMeters - (state.lastMatch?.projectedMeters ?: 0.0)
        for (north in 210..240 step 10) walk(localPoint(0.0, north.toDouble()))

        val switched = state.systemEvents.single { it.kind == "direction.switched" }
        assertEquals("branch", switched.fields["condition"])
        assertEquals(1, state.systemEvents.count { it.kind == "route.rematched" })
        assertEquals(2, state.systemEvents.count { it.kind == "arrival.baseline-reset" })
        assertTrue(
            kotlin.math.abs((state.arrivalBaselineRemainingMeters ?: 0.0) - before) <= 30.0,
            "before=$before after=${state.arrivalBaselineRemainingMeters} route=${route.totalLengthMeters} switch=${switched.fields}",
        )
        assertFalse(outputs.filterIsInstance<Guidance.Status>().any { it.message == "되돌아가는 방향으로 안내합니다" })
    }

    @Test
    fun h5CircularRouteReturnsAfterSustainedOffRouteDwell() {
        val route = circularRoute(500.0)
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false)
        val sampleEnd = 800.0
        val samples = (0..4).map { index ->
            val progress = sampleEnd - 40.0 + index * 10.0
            StartTrajectorySample(circlePoint(progress, route.totalLengthMeters, 500.0), -5_000L + index * 1_000L, 960.0 + index * 10.0, 1.0)
        }
        val stale = project(route, 100.0)
        val seeded = GuideState.initial(route).copy(
            hasEnteredRoute = true,
            lastMatch = stale.copy(direction = ProgressDirection.FORWARD),
            firstMatchProgressMeters = 100.0,
            lastTimestamp = -1_000L,
            sessionStartTimestamp = -10_000L,
            direction = ProgressDirection.FORWARD,
            offRoute = true,
            offRouteSince = -60_000L,
            trajectorySamples = samples,
            trajectoryWalkedMeters = samples.last().walkedMeters,
            trajectoryMetersConfig = config.startTrajectoryMeters,
            trajectoryFrameCount = samples.size,
            trajectoryStartTimestampMillis = samples.first().timestampMillis,
            lastTrajectoryTimestampMillis = -1_000L,
            lastTrajectoryPoint = circlePoint(sampleEnd - 10.0, route.totalLengthMeters, 500.0),
            lastTrajectorySpeedMps = 0.0,
            lastTrajectoryAccuracyMeters = 1.0,
            arrivalBaselineRemainingMeters = route.totalLengthMeters - 100.0,
            advanceHighWaterMeters = 100.0,
        )
        val result = Engine.guide(seeded, frame(0L, circlePoint(sampleEnd, route.totalLengthMeters, 500.0), speed = 0f), config)

        assertFalse(result.nextState.offRoute)
        assertEquals("off-route-dwell", result.nextState.systemEvents.single { it.kind == "route.rematched" }.fields["reason"])

        val lollipop = lollipopRoute()
        val lollipopSamples = (0..4).map { index ->
            StartTrajectorySample(
                localPoint(100.0 - index * 10.0, 400.0),
                -5_000L + index * 1_000L,
                960.0 + index * 10.0,
                1.0,
            )
        }
        val lollipopSeed = GuideState.initial(lollipop).copy(
            hasEnteredRoute = true,
            lastMatch = project(lollipop, 100.0).copy(direction = ProgressDirection.FORWARD),
            firstMatchProgressMeters = 100.0,
            lastTimestamp = -1_000L,
            sessionStartTimestamp = -10_000L,
            direction = ProgressDirection.FORWARD,
            offRoute = true,
            offRouteSince = -60_000L,
            trajectorySamples = lollipopSamples,
            trajectoryWalkedMeters = 1_000.0,
            trajectoryFrameCount = lollipopSamples.size,
            trajectoryStartTimestampMillis = lollipopSamples.first().timestampMillis,
            lastTrajectoryTimestampMillis = -1_000L,
            lastTrajectoryPoint = localPoint(60.0, 400.0),
            lastTrajectorySpeedMps = 0.0,
            lastTrajectoryAccuracyMeters = 1.0,
            arrivalBaselineRemainingMeters = lollipop.totalLengthMeters - 100.0,
            advanceHighWaterMeters = 100.0,
        )
        val lollipopResult = Engine.guide(lollipopSeed, frame(0L, localPoint(50.0, 400.0), speed = 0f), config)
        assertFalse(lollipopResult.nextState.offRoute)
        assertEquals(
            "off-route-dwell",
            lollipopResult.nextState.systemEvents.single { it.kind == "route.rematched" }.fields["reason"],
        )
    }

    @Test
    fun h6GapRematchNearEndpointResetsArrivalAdvanceWithoutArriving() {
        val route = lineRoute(1_000.0)
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false)
        val seeded = rematchSeed(route, config, oldProgress = 500.0, sampleEnd = 960.0)
        val result = Engine.guide(seeded, frame(30_000L, pointAt(970.0), speed = 0f), config)

        assertFalse(result.nextState.arrived)
        assertEquals(0.0, result.nextState.advanceCreditedMeters)
        assertTrue(kotlin.math.abs((result.nextState.arrivalBaselineRemainingMeters ?: 0.0) - 30.0) <= 2.0)
        assertEquals(1, result.nextState.systemEvents.count { it.kind == "route.rematched" })
        assertEquals(1, result.nextState.systemEvents.count { it.kind == "arrival.baseline-reset" })
    }

    @Test
    fun h7RematchRecordsSkippedEventsAndWaypointsExactlyOnce() {
        val route = lineRoute(1_000.0, waypointMeters = 600.0)
        val config = GuideConfig(
            sunsetEnabled = false,
            sunriseEnabled = false,
            milestoneIntervalMeters = 100.0,
            remainingAnnounceMeters = listOf(300.0, 100.0),
        )
        val seeded = rematchSeed(route, config, oldProgress = 500.0, sampleEnd = 940.0)
        val result = Engine.guide(seeded, frame(30_000L, pointAt(950.0), speed = 0f), config)
        val state = result.nextState

        assertEquals(1, state.systemEvents.count { it.kind == "route.rematched" })
        assertEquals(1, state.systemEvents.count { it.kind == "arrival.baseline-reset" })
        assertTrue(state.discardedEvents.any { it.event == "E1" && it.reason == "jump" })
        assertTrue(state.discardedEvents.any { it.event == "E3" && it.reason == "jump" })
        assertEquals(1, state.discardedWaypoints.count { it.reason == "jump" })
        assertEquals(0.0, state.advanceCreditedMeters)
    }

    private fun replayTurnaround(route: RouteModel, threshold: Double, reverseMeters: Double): List<Pair<Guidance?, GuideState>> {
        val config = GuideConfig(
            sunsetEnabled = false,
            sunriseEnabled = false,
            directionSwitchDistanceMeters = threshold,
        )
        var state = GuideState.starting(route, config)
        var timestamp = 0L
        val outputs = mutableListOf<Pair<Guidance?, GuideState>>()
        fun walk(progress: Double) {
            val result = Engine.guide(state, frame(timestamp, pointAt(progress)), config)
            state = result.nextState
            outputs += result.guidance to state
            timestamp += 10_000L
        }
        for (step in 0..100) walk(step * 10.0)
        for (step in 1..(reverseMeters / 10.0).toInt()) walk(1_000.0 - step * 10.0)
        return outputs
    }

    private fun rematchSeed(route: RouteModel, config: GuideConfig, oldProgress: Double, sampleEnd: Double): GuideState {
        val samples = (0..4).map { index ->
            val progress = sampleEnd - 40.0 + index * 10.0
            StartTrajectorySample(pointAt(progress), 25_000L + index * 1_000L, 960.0 + index * 10.0, 1.0)
        }
        val old = project(route, oldProgress)
        return GuideState.initial(route).copy(
            hasEnteredRoute = true,
            lastMatch = old.copy(direction = ProgressDirection.FORWARD),
            firstMatchProgressMeters = oldProgress,
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
            trajectorySamples = samples,
            trajectoryWalkedMeters = samples.last().walkedMeters,
            trajectoryMetersConfig = config.startTrajectoryMeters,
            trajectoryFrameCount = samples.size,
            trajectoryStartTimestampMillis = samples.first().timestampMillis,
            lastTrajectoryTimestampMillis = 29_000L,
            lastTrajectoryPoint = pointAt(sampleEnd),
            lastTrajectorySpeedMps = 0.0,
            lastTrajectoryAccuracyMeters = 1.0,
            arrivalBaselineRemainingMeters = route.totalLengthMeters - oldProgress,
            advanceHighWaterMeters = oldProgress,
            advanceCreditedMeters = 0.0,
        )
    }

    private fun lineRoute(lengthMeters: Double, waypointMeters: Double? = null): RouteModel {
        val routePoints = (0..(lengthMeters / 50.0).toInt()).joinToString("") { index ->
            "<trkpt lat=\"0.0\" lon=\"${longitude(index * 50.0)}\"/>"
        }
        val waypoint = waypointMeters?.let { "<wpt lat=\"0.0\" lon=\"${longitude(it)}\"><name>Midpoint</name></wpt>" }.orEmpty()
        return RouteModel.fromGpx("<gpx>$waypoint<trk><trkseg>$routePoints</trkseg></trk></gpx>")
    }

    private fun lollipopRoute(): RouteModel {
        val points = listOf(
            localPoint(0.0, 0.0),
            localPoint(0.0, 200.0),
            localPoint(200.0, 200.0),
            localPoint(200.0, 400.0),
            localPoint(0.0, 400.0),
            localPoint(0.0, 200.0),
            localPoint(0.0, 0.0),
        )
        val track = points.joinToString("") { point ->
            "<trkpt lat=\"${point.lat}\" lon=\"${point.lon}\"/>"
        }
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>")
    }

    private fun circularRoute(radiusMeters: Double): RouteModel {
        val points = (0..360).map { step ->
            val angle = 2.0 * Math.PI * step / 360.0
            localPoint(radiusMeters * cos(angle), radiusMeters * sin(angle))
        }
        val track = points.joinToString("") { point ->
            "<trkpt lat=\"${point.lat}\" lon=\"${point.lon}\"/>"
        }
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>")
    }

    private fun pointAt(progressMeters: Double): GeoPoint = GeoPoint(0.0, longitude(progressMeters))

    private fun localPoint(eastMeters: Double, northMeters: Double): GeoPoint = GeoPoint(
        Math.toDegrees(northMeters / 6_371_008.8),
        Math.toDegrees(eastMeters / 6_371_008.8),
    )

    private fun circlePoint(progressMeters: Double, routeLength: Double, radiusMeters: Double): GeoPoint {
        val angle = 2.0 * Math.PI * (progressMeters / routeLength)
        return localPoint(radiusMeters * cos(angle), radiusMeters * sin(angle))
    }

    private fun longitude(meters: Double): Double = Math.toDegrees(meters / 6_371_008.8)

    private fun frame(timestamp: Long, point: GeoPoint, speed: Float = 1f): SensorFrame =
        SensorFrame(timestamp, point.lat, point.lon, 1f, speed, null)

    private fun project(route: RouteModel, progressMeters: Double): MatchResult {
        val segment = route.cumulativeMeters.indexOfLast { it <= progressMeters }.coerceIn(0, route.points.lastIndex - 1)
        return MatchResult(0.0, progressMeters, segment, route.points[segment], ProgressDirection.FORWARD)
    }
}
