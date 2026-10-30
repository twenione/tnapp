package com.trailnav.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArrivalAdvanceTest {
    @Test
    fun circularFalseArrivalIsBlockedAcrossDeterministicAndNoisyStarts() {
        val route = circleRoute()
        val random = Random(155_001)
        var arrivals = 0
        var candidateTrials = 0
        var blockedRecords = 0
        repeat(202) { trial ->
            val sigma = when {
                trial < 2 -> 0.0
                trial < 102 -> 3.0
                else -> 5.0
            }
            val offset = when (trial) {
                0 -> 5.0
                1 -> 20.0
                else -> 0.0
            }
            val base = pointAt(route, if (offset > 0.0) route.totalLengthMeters - offset else route.totalLengthMeters)
            val first = withOffset(
                base,
                normalApprox(random, sigma),
                normalApprox(random, sigma),
                0L,
                speed = 0f,
            )
            var state = GuideState.initial(route)
            var result = guide(state, first)
            state = result.nextState
            var hasOldCandidate = oldArrivalCandidate(route, result, first.timestamp)

            var trialArrived = result.guidance == Guidance.Arrived
            repeat(60) { second ->
                result = guide(state, first.copy(timestamp = (second + 1L) * 1_000L))
                state = result.nextState
                if (result.guidance == Guidance.Arrived) trialArrived = true
                hasOldCandidate = hasOldCandidate || oldArrivalCandidate(route, result, (second + 1L) * 1_000L)
            }
            if (hasOldCandidate) candidateTrials++
            if (state.arrivalBlockedRecords.size == 1) blockedRecords++

            repeat(30) { step ->
                val frame = frameAt(route, (step + 1) * 10.0, 61_000L + step * 5_000L, speed = 2f)
                result = guide(state, frame)
                state = result.nextState
                if (result.guidance == Guidance.Arrived) trialArrived = true
            }
            if (trialArrived) arrivals++
        }
        assertEquals(0, arrivals, "circular start must not falsely finish: $arrivals/202")
        assertTrue(candidateTrials >= 2, "the deterministic 5 m and 20 m tail matches must be arrival candidates")
        assertEquals(candidateTrials, blockedRecords)
    }

    @Test
    fun fullCircularWalkArrivesWithinFiveFramesOfLegacyRule() {
        val route = circleRoute()
        for ((sigma, count, seed) in listOf(Triple(3.0, 50, 155_003), Triple(5.0, 50, 155_005))) {
            val random = Random(seed)
            repeat(count) { trial ->
                var state = GuideState.initial(route)
                var arrivalFrame: Int? = null
                var oldArrivalFrame: Int? = null
                for (frameIndex in 0..120) {
                    val distance = frameIndex * 10.0
                    val frame = if (frameIndex == 0) {
                        frameAt(route, 0.0, 0L, speed = 2f)
                    } else {
                        val point = pointAt(route, distance)
                        withOffset(
                            point,
                            normalApprox(random, sigma),
                            normalApprox(random, sigma),
                            frameIndex * 5_000L,
                            speed = 2f,
                        )
                    }
                    val result = guide(state, frame)
                    state = result.nextState
                    if (oldArrivalFrame == null && oldArrivalCandidate(route, result, frame.timestamp)) oldArrivalFrame = frameIndex
                    if (result.guidance == Guidance.Arrived) {
                        arrivalFrame = frameIndex
                        break
                    }
                }
                assertNotNull(arrivalFrame, "sigma=$sigma trial=$trial did not arrive")
                assertNotNull(oldArrivalFrame, "sigma=$sigma trial=$trial never met the legacy candidate")
                assertTrue(arrivalFrame <= oldArrivalFrame + 5, "sigma=$sigma trial=$trial delayed $arrivalFrame vs $oldArrivalFrame")
            }
        }
    }

    @Test
    fun midRouteOneWayStartArrivesOnLegacyCandidateFrame() {
        val route = lineRoute(2_000.0)
        val config = GuideConfig()
        var state = GuideState.initial(route)
        var result = guide(state, frameAt(route, 1_200.0, 0L))
        state = result.nextState
        var oldFrame: Int? = null
        var newFrame: Int? = null
        for (step in 1..100) {
            val frame = frameAt(route, 1_200.0 + step * 10.0, step * 5_000L)
            result = guide(state, frame, config)
            state = result.nextState
            if (oldFrame == null && oldArrivalCandidate(route, result, frame.timestamp)) oldFrame = step
            if (result.guidance == Guidance.Arrived) {
                newFrame = step
                break
            }
        }
        assertNotNull(newFrame)
        assertEquals(oldFrame, newFrame)
    }

    @Test
    fun oneWayWalkFromStartArrivesOnLegacyCandidateFrame() {
        val route = lineRoute(2_000.0)
        var state = GuideState.initial(route)
        var result = guide(state, frameAt(route, 0.0, 0L))
        state = result.nextState
        var oldFrame: Int? = null
        var newFrame: Int? = null
        for (step in 1..200) {
            val frame = frameAt(route, step * 10.0, step * 5_000L)
            result = guide(state, frame)
            state = result.nextState
            if (oldFrame == null && oldArrivalCandidate(route, result, frame.timestamp)) oldFrame = step
            if (result.guidance == Guidance.Arrived) {
                newFrame = step
                break
            }
        }
        assertNotNull(newFrame)
        assertEquals(oldFrame, newFrame)
    }

    @Test
    fun oneFrameGpsJumpDoesNotEarnArrivalAdvance() {
        val route = lineRoute(2_000.0)
        val config = GuideConfig(searchWindowMeters = 2_200.0)
        var state = GuideState.initial(route)
        state = guide(state, frameAt(route, 100.0, 0L), config).nextState
        var arrived = false
        for (step in 1..30) {
            val distance = if (step == 1) route.totalLengthMeters - 5.0 else route.totalLengthMeters - 5.0
            val result = guide(state, frameAt(route, distance, step * 1_000L, speed = 0f), config)
            state = result.nextState
            if (result.guidance == Guidance.Arrived) arrived = true
        }
        assertFalse(arrived)
        assertEquals(0.0, state.advanceCreditedMeters)
    }

    @Test
    fun stationaryNoiseNearEndDoesNotAccumulatePositiveJitterAndWalkingCanArrive() {
        val route = lineRoute(2_000.0)
        val random = Random(155_006)
        val config = GuideConfig(minimumSessionSecondsBeforeArrival = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, frameAt(route, 1_940.0, 0L, speed = 0f), config).nextState
        for (second in 1..300) {
            val frame = frameAt(
                route,
                1_940.0,
                second * 1_000L,
                speed = 0f,
                eastError = random.nextDouble(-4.0, 4.0),
            )
            val result = guide(state, frame, config)
            state = result.nextState
            assertFalse(result.guidance == Guidance.Arrived, "stationary noise arrived at second=$second")
        }
        assertTrue(state.advanceCreditedMeters < 50.0, "bounded jitter credited ${state.advanceCreditedMeters} m")

        val firstCandidate = guide(state, frameAt(route, 1_980.0, 310_000L, speed = 2f), config)
        assertTrue(oldArrivalCandidate(route, firstCandidate, 310_000L))
        assertFalse(firstCandidate.guidance == Guidance.Arrived, "35 m of actual advance must not meet the 50 m floor")
        val arrival = guide(firstCandidate.nextState, frameAt(route, 2_000.0, 315_000L, speed = 2f), config)
        assertEquals(Guidance.Arrived, arrival.guidance)
    }

    @Test
    fun startAtEndDoesNotArriveFromTenMinutesOfNoise() {
        val route = lineRoute(2_000.0)
        val random = Random(155_007)
        var state = GuideState.initial(route)
        state = guide(state, frameAt(route, 2_000.0, 0L, speed = 0f)).nextState
        repeat(600) { minuteFrame ->
            val frame = frameAt(
                route,
                2_000.0,
                (minuteFrame + 1L) * 1_000L,
                speed = 0f,
                eastError = random.nextDouble(-2.0, 2.0),
            )
            val result = guide(state, frame)
            state = result.nextState
            assertFalse(result.guidance == Guidance.Arrived, "end-start noise arrived at frame=$minuteFrame")
        }
        // Known consequence: starting within the 50 m absolute floor of the end cannot earn arrival voice.
        assertEquals(1, state.arrivalBlockedRecords.size)
        assertTrue(state.advanceCreditedMeters < 50.0)
    }

    @Test
    fun shortOneWayRouteArrivesWithCappedAbsoluteFloor() {
        val route = lineRoute(80.0)
        var state = GuideState.initial(route)
        state = guide(state, frameAt(route, 0.0, 0L)).nextState
        var arrived = false
        for (step in 1..16) {
            val result = guide(state, frameAt(route, step * 5.0, step * 2_500L))
            state = result.nextState
            if (result.guidance == Guidance.Arrived) {
                arrived = true
                break
            }
        }
        assertTrue(arrived)
    }

    @Test
    fun largeBackwardRematchLowersHighWaterWithoutChangingCredit() {
        val route = lineRoute(2_000.0)
        val config = GuideConfig(searchWindowMeters = 2_200.0, minimumSessionSecondsBeforeArrival = 1_000_000.0)
        var state = GuideState.initial(route)
        state = guide(state, frameAt(route, 0.0, 0L), config).nextState
        for (step in 1..50) state = guide(state, frameAt(route, step * 10.0, step * 1_000L), config).nextState
        val priorCredit = state.advanceCreditedMeters
        val backward = guide(state, frameAt(route, 100.0, 51_000L), config)
        assertEquals(100.0, backward.nextState.advanceHighWaterMeters!!, 1.0)
        assertEquals(priorCredit, backward.nextState.advanceCreditedMeters, 1e-6)
        val forward = guide(backward.nextState, frameAt(route, 110.0, 52_000L), config)
        assertEquals(110.0, forward.nextState.advanceHighWaterMeters!!, 1.0)
        assertTrue(forward.nextState.advanceCreditedMeters >= priorCredit + 9.0)
    }

    @Test
    fun blockedArrivalRecordIsWrittenOnceAndArrivalCanLaterProceed() {
        val route = lineRoute(2_000.0)
        val config = GuideConfig(minimumSessionSecondsBeforeArrival = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, frameAt(route, 1_940.0, 0L, speed = 0f), config).nextState
        var candidate = guide(state, frameAt(route, 1_980.0, 10_000L), config)
        state = candidate.nextState
        assertFalse(candidate.guidance == Guidance.Arrived)
        assertEquals(1, state.arrivalBlockedRecords.size)
        repeat(60) { index ->
            candidate = guide(state, frameAt(route, 1_980.0, 11_000L + index * 1_000L, speed = 0f), config)
            state = candidate.nextState
            assertEquals(1, state.arrivalBlockedRecords.size)
            assertFalse(candidate.guidance == Guidance.Arrived)
        }
        val arrival = guide(state, frameAt(route, 2_000.0, 72_000L), config)
        assertEquals(Guidance.Arrived, arrival.guidance)
        assertEquals(1, arrival.nextState.arrivalBlockedRecords.size)
    }

    @Test
    fun arrivalAdvanceFractionChangesOutcome() {
        val route = lineRoute(2_000.0)
        val distances = listOf(1_880.0, 1_942.0, 1_980.0)
        val times = listOf(0L, 11_000L, 12_000L)
        val low = runTimedFrames(route, distances, times, GuideConfig(arrivalAdvanceFraction = 0.5, arrivalJumpToleranceMeters = 20.0, minimumSessionSecondsBeforeArrival = 0.0))
        val high = runTimedFrames(route, distances, times, GuideConfig(arrivalAdvanceFraction = 0.8, arrivalJumpToleranceMeters = 20.0, minimumSessionSecondsBeforeArrival = 0.0))
        assertEquals(Guidance.Arrived, low.last().guidance, "low-fraction state=${low.last().nextState}")
        assertFalse(high.last().guidance == Guidance.Arrived)
    }

    @Test
    fun arrivalMinAdvanceMetersChangesOutcome() {
        val route = lineRoute(2_000.0)
        val low = runFrames(route, listOf(1_960.0, 2_000.0), GuideConfig(arrivalMinAdvanceMeters = 20.0, minimumSessionSecondsBeforeArrival = 0.0), 10_000L)
        val high = runFrames(route, listOf(1_960.0, 2_000.0), GuideConfig(arrivalMinAdvanceMeters = 50.0, minimumSessionSecondsBeforeArrival = 0.0), 10_000L)
        assertEquals(Guidance.Arrived, low.last().guidance)
        assertFalse(high.last().guidance == Guidance.Arrived)
    }

    @Test
    fun arrivalWalkMaxSpeedChangesOutcome() {
        val route = lineRoute(2_000.0)
        val slow = runFrames(route, listOf(1_850.0, 1_940.0, 1_980.0), GuideConfig(arrivalWalkMaxSpeedMps = 4.0, minimumSessionSecondsBeforeArrival = 0.0), 10_000L)
        val fast = runFrames(route, listOf(1_850.0, 1_940.0, 1_980.0), GuideConfig(arrivalWalkMaxSpeedMps = 40.0, minimumSessionSecondsBeforeArrival = 0.0), 10_000L)
        assertFalse(slow.last().guidance == Guidance.Arrived)
        assertEquals(Guidance.Arrived, fast.last().guidance)
    }

    @Test
    fun arrivalJumpToleranceChangesOutcome() {
        val route = lineRoute(2_000.0)
        val distances = listOf(1_850.0, 1_900.0, 1_940.0, 1_980.0)
        val times = listOf(0L, 10_000L, 11_000L, 12_000L)
        val strict = runTimedFrames(route, distances, times, GuideConfig(arrivalJumpToleranceMeters = 20.0, minimumSessionSecondsBeforeArrival = 0.0))
        val tolerant = runTimedFrames(route, distances, times, GuideConfig(arrivalJumpToleranceMeters = 40.0, minimumSessionSecondsBeforeArrival = 0.0))
        assertFalse(strict.last().guidance == Guidance.Arrived)
        assertEquals(Guidance.Arrived, tolerant.last().guidance)
    }

    @Test
    fun approachFrameDoesNotSeedTheArrivalBaselineBeforeRouteEntry() {
        val route = lineRoute(2_000.0)
        val config = GuideConfig(minimumSessionSecondsBeforeArrival = 0.0)
        var state = GuideState.initial(route)
        var result = guide(state, frameAt(route, 1_800.0, 0L, northError = 20.0), config)
        state = result.nextState
        assertTrue(state.arrivalBaselineRemainingMeters == null)
        for (step in 1..18) {
            result = guide(state, frameAt(route, 1_800.0 + step * 10.0, step * 2_000L, northError = 20.0), config)
            state = result.nextState
        }
        assertFalse(state.arrived, "approach-only frames must not earn a route-entry baseline")
        assertEquals(null, state.arrivalBaselineRemainingMeters)
        assertEquals(0.0, state.advanceCreditedMeters)
    }

    @Test
    fun invalidArrivalParametersAreRejected() {
        assertFails { GuideConfig(arrivalWalkMaxSpeedMps = 0.0) }
        assertFails { GuideConfig(arrivalJumpToleranceMeters = -0.1) }
        assertFails { GuideConfig(arrivalAdvanceFraction = 0.0) }
        assertFails { GuideConfig(arrivalAdvanceFraction = 1.01) }
        assertFails { GuideConfig(arrivalMinAdvanceMeters = -0.1) }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed, "expected invalid arrival config to throw IllegalArgumentException")
    }

    private fun runFrames(route: RouteModel, distances: List<Double>, config: GuideConfig, intervalMillis: Long): List<GuideResult> {
        var state = GuideState.initial(route)
        return distances.mapIndexed { index, distance ->
            val result = guide(state, frameAt(route, distance, index * intervalMillis, speed = if (index == 0) 0f else 4f), config)
            state = result.nextState
            result
        }
    }

    private fun runTimedFrames(
        route: RouteModel,
        distances: List<Double>,
        timestamps: List<Long>,
        config: GuideConfig,
    ): List<GuideResult> {
        require(distances.size == timestamps.size)
        var state = GuideState.initial(route)
        return distances.indices.map { index ->
            val result = guide(state, frameAt(route, distances[index], timestamps[index]), config)
            state = result.nextState
            result
        }
    }

    private fun oldArrivalCandidate(route: RouteModel, result: GuideResult, timestamp: Long): Boolean {
        val match = result.nextState.lastMatch ?: return false
        val start = result.nextState.sessionStartTimestamp ?: timestamp
        val projectedEndDistance = kotlin.math.sqrt(
            (match.projectedPoint.east - route.points.last().east) * (match.projectedPoint.east - route.points.last().east) +
                (match.projectedPoint.north - route.points.last().north) * (match.projectedPoint.north - route.points.last().north),
        )
        return (timestamp - start) / 1_000.0 >= GuideConfig().minimumSessionSecondsBeforeArrival &&
            projectedEndDistance <= GuideConfig().arriveRadiusMeters &&
            match.projectedMeters > route.totalLengthMeters * GuideConfig().arriveProgressFraction
    }

    private fun lineRoute(lengthMeters: Double): RouteModel {
        val metersPerLongitudeDegree = EARTH_RADIUS_METERS * cos(Math.toRadians(10.0)) * PI / 180.0
        val endLongitude = 20.0 + lengthMeters / metersPerLongitudeDegree
        return RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.0\" lon=\"$endLongitude\"/></trkseg></trk></gpx>",
        )
    }

    private fun circleRoute(): RouteModel {
        val radius = 1_200.0 / (2.0 * PI)
        val metersPerLatitudeDegree = EARTH_RADIUS_METERS * PI / 180.0
        val metersPerLongitudeDegree = EARTH_RADIUS_METERS * cos(Math.toRadians(10.0)) * PI / 180.0
        val points = (0..96).map { index ->
            val angle = 2.0 * PI * index / 96.0
            val lat = 10.0 + radius * sin(angle) / metersPerLatitudeDegree
            val lon = 20.0 + radius * (cos(angle) - 1.0) / metersPerLongitudeDegree
            GeoPoint(lat, lon)
        }
        return route(points)
    }

    private fun route(points: List<GeoPoint>): RouteModel {
        val track = points.joinToString("") { point -> "<trkpt lat=\"${point.lat}\" lon=\"${point.lon}\"/>" }
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>")
    }

    private fun frameAt(
        route: RouteModel,
        meters: Double,
        timestamp: Long,
        speed: Float = 2f,
        eastError: Double = 0.0,
        northError: Double = 0.0,
    ): SensorFrame = withOffset(pointAt(route, meters), eastError, northError, timestamp, speed)

    private fun pointAt(route: RouteModel, meters: Double): GeoPoint {
        val distance = meters.coerceIn(0.0, route.totalLengthMeters)
        val segment = (0 until route.cumulativeMeters.lastIndex).firstOrNull { index ->
            route.cumulativeMeters[index + 1] >= distance
        } ?: route.cumulativeMeters.lastIndex - 1
        val start = route.sourcePoints[segment]
        val end = route.sourcePoints[segment + 1]
        val length = route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment]
        val ratio = if (length <= 0.0) 0.0 else (distance - route.cumulativeMeters[segment]) / length
        return GeoPoint(
            start.lat + (end.lat - start.lat) * ratio,
            start.lon + (end.lon - start.lon) * ratio,
        )
    }

    private fun withOffset(
        point: GeoPoint,
        eastMeters: Double,
        northMeters: Double,
        timestamp: Long,
        speed: Float,
    ): SensorFrame {
        val metersPerLatitudeDegree = EARTH_RADIUS_METERS * PI / 180.0
        val metersPerLongitudeDegree = EARTH_RADIUS_METERS * cos(Math.toRadians(point.lat)) * PI / 180.0
        return SensorFrame(
            timestamp = timestamp,
            lat = point.lat + northMeters / metersPerLatitudeDegree,
            lon = point.lon + eastMeters / metersPerLongitudeDegree,
            accuracy = 5f,
            speed = speed,
            gpsBearing = null,
        )
    }

    private fun normalApprox(random: Random, sigma: Double): Double =
        (0 until 12).sumOf { random.nextDouble() - 0.5 } * sigma

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
