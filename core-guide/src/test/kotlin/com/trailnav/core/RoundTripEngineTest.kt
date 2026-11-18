package com.trailnav.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D-161 round-trip behavior through the production one-way-GPX startup path. */
class RoundTripEngineTest {
    private data class FrameResult(
        val index: Int,
        val walkedMeters: Double,
        val result: GuideResult,
    )

    private data class Trace(
        val originalRoute: RouteModel,
        val referenceRoute: RouteModel,
        val state: GuideState,
        val frames: List<FrameResult>,
    )

    @Test
    fun roundTripTargetingRespectsDisabledLoopAndEndpointGap() {
        val enabled = GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false)
        val oneWay = route(outwardPoints())
        val ordinary = runPrefix(oneWay, GuideConfig(sunsetEnabled = false, sunriseEnabled = false), 50.0)
        assertNull(ordinary.state.route.roundTripTurnaroundMeters)
        assertEquals(oneWay.totalLengthMeters, ordinary.state.route.totalLengthMeters, 0.01)
        assertFalse(ordinary.state.systemEvents.any { it.kind.startsWith("route.round_trip") })

        val loopRoute = route(listOf(
            EnuPoint(0.0, 0.0), EnuPoint(300.0, 0.0), EnuPoint(500.0, 200.0), EnuPoint(40.0, 30.0),
        ))
        val loop = runPrefix(loopRoute, enabled, 50.0)
        assertNull(loop.state.route.roundTripTurnaroundMeters)
        assertEquals(1, loop.state.systemEvents.count { it.kind == "route.round_trip.ignored" })
        assertEquals("loop", loop.state.systemEvents.single { it.kind == "route.round_trip.ignored" }.fields["reason"])
        assertTrue(loop.state.systemEvents.single { it.kind == "route.round_trip.ignored" }.fields.getValue("gap_m").toDouble() <= 100.0)

        val active = runPrefix(oneWay, enabled, 50.0)
        assertNotNull(active.state.route.roundTripTurnaroundMeters)
        assertTrue(active.state.route.totalLengthMeters > oneWay.totalLengthMeters * 1.9)
        assertEquals(1, active.state.systemEvents.count { it.kind == "route.round_trip" })
        val built = active.state.systemEvents.single { it.kind == "route.round_trip" }
        assertTrue(built.fields.getValue("turnaround_m").toDouble() in (oneWay.totalLengthMeters - 1.0)..(oneWay.totalLengthMeters + 1.0))
        assertTrue(built.fields.getValue("total_m").toDouble() > oneWay.totalLengthMeters * 1.9)
        assertEquals(oneWay.sourceTrackPoints.size.toString(), built.fields["source_point_count"])
    }

    @Test
    fun endToEndRoundTripAnnouncesTurnaroundAndArrivesAtStartAcrossNoiseSeeds() {
        for (sigma in listOf(0.0, 3.0, 5.0)) {
            for (seed in 1..5) {
                val trace = walkJoinedPath(sigma, seed)
                val route = trace.state.route
                val turnaround = assertNotNull(route.roundTripTurnaroundMeters, "sigma=$sigma seed=$seed was not built")
                val startEvents = trace.frames.filter { it.result.guidance == Guidance.RoundTripStart }
                assertEquals(1, startEvents.size, "sigma=$sigma seed=$seed start announcement count")
                assertEquals(0, startEvents.single().index, "sigma=$sigma seed=$seed start announcement frame")
                val ahead = trace.frames.filter { it.result.guidance is Guidance.TurnaroundAhead }
                val now = trace.frames.filter { it.result.guidance == Guidance.Turnaround }
                assertEquals(1, ahead.size, "sigma=$sigma seed=$seed turnaround ahead count")
                assertEquals(1, now.size, "sigma=$sigma seed=$seed turnaround now count")
                val aheadDistance = ahead.single().result.reason.details.getValue("remainingMeters").toDouble()
                val nowDistance = now.single().result.reason.details.getValue("remainingMeters").toDouble()
                assertTrue(aheadDistance in 15.0..60.0, "sigma=$sigma seed=$seed ahead distance=$aheadDistance")
                assertTrue(nowDistance in 0.0..15.0, "sigma=$sigma seed=$seed now distance=$nowDistance")
                if (sigma == 5.0 && seed == 1) {
                    val beforeThirtyMeters = trace.frames.count {
                        it.walkedMeters in (turnaround - 30.0)..turnaround &&
                            (it.result.guidance is Guidance.TurnaroundAhead || it.result.guidance == Guidance.Turnaround)
                    }
                    val afterThirtyMeters = trace.frames.count {
                        it.walkedMeters in turnaround..(turnaround + 30.0) &&
                            (it.result.guidance is Guidance.TurnaroundAhead || it.result.guidance == Guidance.Turnaround)
                    }
                    println("TASK119_SIGMA5 seed=1 round_trip_start_frame=${startEvents.single().index} turnaround_events_within_30m_before=$beforeThirtyMeters after=$afterThirtyMeters")
                }
                assertTrue(trace.frames.none {
                    val guidance = it.result.guidance
                    it.result.nextState.route.turns.getOrNull((it.result.reason.details["turnIndex"] ?: "-1").toIntOrNull() ?: -1)?.isTurnaround == true &&
                        (guidance is Guidance.TurnAhead || guidance is Guidance.TurnNow)
                }, "sigma=$sigma seed=$seed emitted a left/right turn at the turnaround")
                val ordinaryTurnGuidance = trace.frames.mapNotNull { it.result.guidance }
                    .filter { it is Guidance.TurnAhead || it is Guidance.TurnNow }
                assertTrue(ordinaryTurnGuidance.isNotEmpty(), "sigma=$sigma seed=$seed did not exercise ordinary turns")
                val outboundSide = ordinaryTurnGuidance.filterIsInstance<Guidance.TurnAhead>().firstOrNull()?.side
                assertNotNull(outboundSide, "sigma=$sigma seed=$seed outbound turn missing")
                val returnTurns = ordinaryTurnGuidance.filterIsInstance<Guidance.TurnAhead>().map { it.side }
                assertTrue(outboundSide in returnTurns && returnTurns.any { it != outboundSide }, "sigma=$sigma seed=$seed did not exercise mirrored left/right turns: $returnTurns")
                assertEquals(0, trace.frames.count { it.result.guidance is Guidance.OffRoute }, "sigma=$sigma seed=$seed off-route frames")
                assertEquals(0, trace.state.systemEvents.count { it.kind == "direction.switched" }, "sigma=$sigma seed=$seed direction switch diagnostics")
                val arrivals = trace.frames.filter { it.result.guidance == Guidance.ArrivedAtStart }
                assertEquals(1, arrivals.size, "sigma=$sigma seed=$seed start arrival count")
                assertTrue(arrivals.single().walkedMeters >= route.totalLengthMeters - 60.0, "sigma=$sigma seed=$seed early arrival walked=${arrivals.single().walkedMeters} total=${route.totalLengthMeters}")
                assertTrue(trace.frames.none { it.result.guidance == Guidance.Arrived }, "sigma=$sigma seed=$seed ordinary destination arrival")
                assertEquals(1, trace.state.systemEvents.count { it.kind == "arrival.baseline-reset" && it.fields["reason"] == "round-trip-turnaround" })
            }
        }
    }

    @Test
    fun reverseDepartureReorientsTheOneWayGpxBeforeBuildingTheReturnLeg() {
        val config = GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false)
        val imported = route(outwardPoints())
        val orientedOneWay = imported.reversed(config)
        val reference = orientedOneWay.roundTrip(config)
        val trace = walkProgressPath(imported, reference, regularProgress(0.0, reference.totalLengthMeters), config)

        val orientationEvents = trace.state.systemEvents.filter { it.kind == "route.orientation" }
        assertTrue(trace.state.routeReversed, "orientation=$orientationEvents decided=${trace.state.decidedDirection}")
        val orientation = trace.state.systemEvents.single { it.kind == "route.orientation" }
        assertEquals("true", orientation.fields["reversed"])
        assertEquals(orientedOneWay.totalLengthMeters, trace.state.route.roundTripTurnaroundMeters!!, 1.0)
        assertEquals(1, trace.frames.count { it.result.guidance == Guidance.ArrivedAtStart })
        assertTrue(trace.frames.none { it.result.guidance == Guidance.Arrived })
        assertEquals(0, trace.state.systemEvents.count { it.kind == "direction.switched" })
    }

    @Test
    fun midRouteReturnRematchesOnceWithoutAnnouncingOrArrivingAtTurnaround() {
        val config = GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false)
        val imported = route(outwardPoints())
        val reference = imported.roundTrip(config)
        val length = reference.roundTripTurnaroundMeters!!
        val progress = regularProgress(0.0, length * 0.6) + regularProgress(length * 0.6, 0.0).drop(1)
        val trace = walkProgressPath(imported, reference, progress, config)

        assertEquals(1, trace.state.systemEvents.count { it.kind == "route.rematched" })
        assertEquals(1, trace.state.systemEvents.count { it.kind == "arrival.baseline-reset" && it.fields["reason"] == "trajectory-rematch" })
        assertEquals(0, trace.frames.count { it.result.guidance is Guidance.TurnaroundAhead || it.result.guidance == Guidance.Turnaround })
        assertEquals(0, trace.frames.count { it.result.guidance == Guidance.Arrived })
        assertEquals(1, trace.frames.count { it.result.guidance == Guidance.ArrivedAtStart })
        assertEquals(0, trace.state.systemEvents.count { it.kind == "direction.switched" })
    }

    @Test
    fun doubleTurnAroundRearmsMarkerAndKeepsDirectionSwitchSilent() {
        val config = GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false)
        val imported = route(outwardPoints())
        val reference = imported.roundTrip(config)
        val length = reference.roundTripTurnaroundMeters!!
        val progress = regularProgress(0.0, length * 0.6) +
            regularProgress(length * 0.6, length * 0.2).drop(1) +
            regularProgress(length * 0.2, length).drop(1) +
            regularProgress(length, reference.totalLengthMeters).drop(1)
        val trace = walkProgressPath(imported, reference, progress, config)

        val rematches = trace.state.systemEvents.filter { it.kind == "route.rematched" }
        assertTrue(rematches.isNotEmpty(), "expected a route rematch during the two turn-around scenario")
        assertEquals(0, trace.state.systemEvents.count { it.kind == "direction.switched" })
        assertEquals(1, trace.frames.count { it.result.guidance is Guidance.TurnaroundAhead })
        assertEquals(1, trace.frames.count { it.result.guidance == Guidance.Turnaround })
        assertEquals(1, trace.frames.count { it.result.guidance == Guidance.ArrivedAtStart })
        assertEquals(0, trace.frames.count { it.result.guidance == Guidance.Arrived })
    }

    @Test
    fun milestonesContinueAcrossTurnaroundAndRemainingThresholdsRestartByLeg() {
        val config = GuideConfig(
            roundTripEnabled = true,
            milestoneEnabled = true,
            remainingEnabled = true,
            sunsetEnabled = false,
            sunriseEnabled = false,
        )
        val trace = walkJoinedPath(0.0, 1, config)
        val milestones = trace.frames.mapNotNull { it.result.guidance as? Guidance.Milestone }
        assertEquals(listOf(1_000.0, 2_000.0, 3_000.0, 4_000.0), milestones.map { it.distanceMeters })
        milestones.forEach { milestone ->
            val actual = trace.frames.single { it.result.guidance == milestone }.result.nextState.milestoneWalkedMeters
            assertTrue(kotlin.math.abs(actual - milestone.distanceMeters) <= 2.4, "milestone=${milestone.distanceMeters} walked=$actual")
        }

        val remaining = trace.frames.mapNotNull { frame ->
            (frame.result.guidance as? Guidance.Remaining)?.let { frame to it }
        }
        assertEquals(6, remaining.size, "remaining targets: ${remaining.map { it.second }}")
        val outbound = remaining.filterNot { it.second.returnLeg }
        val returning = remaining.filter { it.second.returnLeg }
        assertEquals(listOf(2_000.0, 1_000.0, 500.0), outbound.map { it.second.thresholdMeters })
        assertEquals(listOf(2_000.0, 1_000.0, 500.0), returning.map { it.second.thresholdMeters })
        val turnaround = trace.state.route.roundTripTurnaroundMeters!!
        val expectedOutboundS = listOf(turnaround - 2_000.0, turnaround - 1_000.0, turnaround - 500.0)
        val expectedReturnS = listOf(trace.state.route.totalLengthMeters - 2_000.0, trace.state.route.totalLengthMeters - 1_000.0, trace.state.route.totalLengthMeters - 500.0)
        outbound.zip(expectedOutboundS).forEach { (item, expected) ->
            assertTrue(kotlin.math.abs(item.first.result.nextState.lastMatch!!.projectedMeters - expected) <= 2.4)
        }
        returning.zip(expectedReturnS).forEach { (item, expected) ->
            assertTrue(kotlin.math.abs(item.first.result.nextState.lastMatch!!.projectedMeters - expected) <= 2.4)
        }
        assertTrue(returning.all { it.second.returnLeg })
    }

    @Test
    fun waypointsAreDuplicatedAndAStartPassedOutboundIsStillAnnouncedOnReturn() {
        val config = GuideConfig(roundTripEnabled = true, waypointEnabled = true, sunsetEnabled = false, sunriseEnabled = false)
        val waypointPoint = localToGeo(1_000.0, 120.0)
        val imported = route(outwardPoints(), listOf(waypointPoint to "Ridge"))
        val reference = imported.roundTrip(config)
        val fullTrace = walkProgressPath(imported, reference, regularProgress(0.0, reference.totalLengthMeters), config)
        val waypoints = fullTrace.state.route.waypoints.filter { it.name == "Ridge" }
        assertEquals(2, waypoints.size)
        val spoken = fullTrace.frames.mapNotNull { frame -> frame.result.guidance as? Guidance.Waypoint }
        assertEquals(2, spoken.count { it.name == "Ridge" })
        assertEquals(waypoints.map { it.s }.sorted(), spoken.map { fullTrace.state.route.waypoints[it.index].s }.sorted())

        val passedStart = 1_100.0
        val laterStartProgress = regularProgress(passedStart, reference.totalLengthMeters)
        val laterTrace = walkProgressPath(imported, reference, laterStartProgress, config)
        assertTrue(laterTrace.state.discardedWaypoints.any { it.reason == "start" && it.waypointS < passedStart })
        val returnWaypoint = laterTrace.frames.mapNotNull { frame -> frame.result.guidance as? Guidance.Waypoint }
        assertEquals(1, returnWaypoint.count { it.name == "Ridge" })
        assertTrue(laterTrace.state.roundTripOnReturnLeg)
    }

    @Test
    fun turnaroundWinsSameFrameAndRecordsTheOrdinaryTurnNowTarget() {
        val config = GuideConfig(roundTripEnabled = true, turnNowDistanceMeters = 2.0, sunsetEnabled = false, sunriseEnabled = false)
        val points = listOf(
            EnuPoint(0.0, 0.0), EnuPoint(400.0, 0.0), EnuPoint(800.0, 120.0),
            EnuPoint(1_200.0, 120.0), EnuPoint(1_500.0, 500.0), EnuPoint(1_500.0, 830.0),
            EnuPoint(1_541.0122, 858.9878), EnuPoint(1_500.0, 900.0),
        )
        val imported = route(points)
        val reference = imported.roundTrip(config)
        val markerIndex = reference.turns.indexOfFirst { it.isTurnaround }
        assertTrue(markerIndex >= 0)
        val marker = reference.turns[markerIndex]
        val ordinary = reference.turns.withIndex().firstOrNull { !it.value.isTurnaround && kotlin.math.abs(it.value.s - (marker.s - 60.0)) < 3.0 }
        assertNotNull(ordinary, "ordinary turn about 60 m before turnaround not found: ${reference.turns}")
        val firstProgress = (marker.s - 59.8) % WALK_METERS_PER_SECOND
        val trace = walkProgressPath(imported, reference, regularProgress(firstProgress, marker.s - 55.0), config)
        val priorityFrame = trace.frames.firstOrNull { frame ->
            frame.result.guidance is Guidance.TurnaroundAhead &&
                frame.result.nextState.discardedEvents.any { it.event == "TURN_NOW" && it.reason == "priority-turnaround" }
        }
        assertNotNull(priorityFrame, "turnaround did not win over the co-located ordinary turn target")
    }

    @Test
    fun roundTripStartHasFirstFramePriorityAndDefersApproachUntilNextFrame() {
        val route = route(outwardPoints())
        val config = GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false)
        val state = GuideState.starting(route, config)
        val geo = localToGeo(0.0, 120.0)
        val first = Engine.guide(state, SensorFrame(0L, geo.lat, geo.lon, 5f, 1.2f, null), config)
        assertEquals(Guidance.RoundTripStart, first.guidance)
        assertTrue(first.nextState.roundTripStartAnnounced)
        val second = Engine.guide(first.nextState, SensorFrame(1_000L, geo.lat, geo.lon, 5f, 1.2f, null), config)
        assertTrue(second.guidance is Guidance.Approach || second.guidance is Guidance.OffRoute)
        assertFalse(second.guidance == Guidance.RoundTripStart)
    }

    @Test
    fun inaccurateFirstFrameDoesNotConsumeRoundTripStartAnnouncement() {
        val route = route(outwardPoints())
        val config = GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false)
        val state = GuideState.starting(route, config)
        val accurateGeo = localToGeo(0.0, 0.0)
        val rejected = Engine.guide(state, SensorFrame(0L, accurateGeo.lat, accurateGeo.lon, 80f, 1.2f, null), config)
        assertFalse(rejected.nextState.roundTripStartAnnounced)
        val accepted = Engine.guide(rejected.nextState, SensorFrame(1_000L, accurateGeo.lat, accurateGeo.lon, 5f, 1.2f, null), config)
        assertEquals(Guidance.RoundTripStart, accepted.guidance)
    }

    @Test
    fun initialStateDoesNotBuildRoundTripWithoutStartupDirectionResolution() {
        val route = route(outwardPoints())
        val state = GuideState.initial(route)
        val result = Engine.guide(
            state,
            frameAt(route, 0.0, 0L),
            GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false),
        )
        assertNull(result.nextState.route.roundTripTurnaroundMeters)
    }

    private fun runPrefix(route: RouteModel, config: GuideConfig, distanceMeters: Double): Trace {
        var state = GuideState.starting(route, config)
        val reference = route
        val frames = mutableListOf<FrameResult>()
        val count = ceil(distanceMeters / WALK_METERS_PER_SECOND).toInt()
        for (index in 0..count) {
            val walked = minOf(index * WALK_METERS_PER_SECOND, distanceMeters)
            val result = Engine.guide(state, frameAt(reference, walked, index * 1_000L), config)
            state = result.nextState
            frames += FrameResult(index, walked, result)
        }
        return Trace(route, reference, state, frames)
    }

    private fun walkJoinedPath(
        sigma: Double,
        seed: Int,
        config: GuideConfig = GuideConfig(roundTripEnabled = true, sunsetEnabled = false, sunriseEnabled = false),
    ): Trace {
        val original = route(outwardPoints())
        val reference = original.roundTrip(config)
        var state = GuideState.starting(original, config)
        val random = Gaussian(seed)
        val frames = mutableListOf<FrameResult>()
        val frameCount = ceil(reference.totalLengthMeters / WALK_METERS_PER_SECOND).toInt()
        for (index in 0..frameCount) {
            val walked = minOf(index * WALK_METERS_PER_SECOND, reference.totalLengthMeters)
            val point = pointAt(reference, walked)
            val geo = routeToGeo(reference, EnuPoint(point.east + sigma * random.next(), point.north + sigma * random.next()))
            val result = Engine.guide(state, SensorFrame(index * 1_000L, geo.lat, geo.lon, 5f, 1.2f, null), config)
            state = result.nextState
            frames += FrameResult(index, walked, result)
        }
        return Trace(original, reference, state, frames)
    }

    private fun walkProgressPath(
        original: RouteModel,
        reference: RouteModel,
        progress: List<Double>,
        config: GuideConfig,
        sigma: Double = 0.0,
        seed: Int = 1,
    ): Trace {
        var state = GuideState.starting(original, config)
        val random = Gaussian(seed)
        val frames = mutableListOf<FrameResult>()
        var walked = 0.0
        var previous: Double? = null
        progress.forEachIndexed { index, progressMeters ->
            if (previous != null) walked += kotlin.math.abs(progressMeters - previous!!)
            val point = pointAt(reference, progressMeters.coerceIn(0.0, reference.totalLengthMeters))
            val geo = routeToGeo(reference, EnuPoint(point.east + sigma * random.next(), point.north + sigma * random.next()))
            val result = Engine.guide(state, SensorFrame(index * 1_000L, geo.lat, geo.lon, 5f, 1.2f, null), config)
            state = result.nextState
            frames += FrameResult(index, walked, result)
            previous = progressMeters
        }
        return Trace(original, reference, state, frames)
    }

    private fun regularProgress(startMeters: Double, endMeters: Double): List<Double> {
        val distance = kotlin.math.abs(endMeters - startMeters)
        if (distance == 0.0) return listOf(startMeters)
        val direction = if (endMeters > startMeters) 1.0 else -1.0
        val count = ceil(distance / WALK_METERS_PER_SECOND).toInt()
        return buildList {
            add(startMeters)
            for (index in 1..count) add(startMeters + direction * minOf(index * WALK_METERS_PER_SECOND, distance))
        }
    }

    private fun route(points: List<EnuPoint>, waypoints: List<Pair<GeoPoint, String>> = emptyList()): RouteModel {
        val track = points.joinToString("") { point ->
            val geo = localToGeo(point.east, point.north)
            "<trkpt lat=\"${geo.lat}\" lon=\"${geo.lon}\"/>"
        }
        val waypointXml = waypoints.joinToString("") { (point, name) ->
            "<wpt lat=\"${point.lat}\" lon=\"${point.lon}\"><name>$name</name></wpt>"
        }
        return RouteModel.fromGpx("<gpx>$waypointXml<trk><trkseg>$track</trkseg></trk></gpx>")
    }

    private fun frameAt(route: RouteModel, progressMeters: Double, timestampMillis: Long): SensorFrame {
        val point = pointAt(route, progressMeters.coerceIn(0.0, route.totalLengthMeters))
        val geo = routeToGeo(route, point)
        return SensorFrame(timestampMillis, geo.lat, geo.lon, 5f, 1.2f, null)
    }

    private fun routeToGeo(route: RouteModel, point: EnuPoint): GeoPoint {
        val origin = route.sourcePoints.first()
        return GeoPoint(
            origin.lat + Math.toDegrees(point.north / EARTH_RADIUS_METERS),
            origin.lon + Math.toDegrees(point.east / (EARTH_RADIUS_METERS * cos(Math.toRadians(origin.lat)))),
        )
    }

    private fun pointAt(route: RouteModel, progressMeters: Double): EnuPoint {
        val index = route.cumulativeMeters.indexOfLast { it <= progressMeters }.coerceIn(0, route.points.lastIndex - 1)
        val startS = route.cumulativeMeters[index]
        val endS = route.cumulativeMeters[index + 1]
        val fraction = ((progressMeters - startS) / (endS - startS)).coerceIn(0.0, 1.0)
        val start = route.points[index]
        val end = route.points[index + 1]
        return EnuPoint(start.east + (end.east - start.east) * fraction, start.north + (end.north - start.north) * fraction)
    }

    private fun localToGeo(eastMeters: Double, northMeters: Double): GeoPoint = GeoPoint(
        ORIGIN_LAT + Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
        ORIGIN_LON + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(ORIGIN_LAT)))),
    )

    private fun outwardPoints(): List<EnuPoint> = listOf(
        EnuPoint(0.0, 0.0), EnuPoint(400.0, 0.0), EnuPoint(800.0, 120.0),
        EnuPoint(1_200.0, 120.0), EnuPoint(1_500.0, 500.0), EnuPoint(1_500.0, 900.0),
    )

    private class Gaussian(seed: Int) {
        private val random = Random(seed)
        fun next(): Double {
            var first: Double
            do { first = random.nextDouble() } while (first <= 0.0)
            val second = random.nextDouble()
            return sqrt(-2.0 * kotlin.math.ln(first)) * kotlin.math.cos(2.0 * PI * second)
        }
    }

    private companion object {
        const val WALK_METERS_PER_SECOND = 1.2
        const val ORIGIN_LAT = 37.0
        const val ORIGIN_LON = 127.0
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
