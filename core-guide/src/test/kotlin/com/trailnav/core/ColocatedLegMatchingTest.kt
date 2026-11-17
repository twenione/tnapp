package com.trailnav.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

class ColocatedLegMatchingTest {
    private data class Measurement(
        val over30Frames: Int,
        val longestOver30Run: Int,
        val maxErrorMeters: Double,
        val rematches: Int,
        val offRouteFrames: Int,
        val arrivalWalkedMeters: Double?,
    )

    @Test
    fun roundTripCoLocatedLegsKeepForwardProgressAcrossNoiseSeeds() {
        val route = roundTripRoute()
        kotlin.test.assertTrue(kotlin.math.abs(route.totalLengthMeters - 4_203.5) < 1.0)
        for (sigma in listOf(0.0, 3.0, 5.0)) {
            val samples = (1..5).map { seed -> runRoundTrip(sigma, seed) }
            samples.forEachIndexed { index, result ->
                val seed = index + 1
                kotlin.test.assertTrue(
                    result.longestOver30Run <= 20,
                    "sigma=$sigma seed=$seed longest >30m error run=${result.longestOver30Run} " +
                        "frames=${result.over30Frames} max=${result.maxErrorMeters} rematches=${result.rematches}",
                )
                kotlin.test.assertEquals(0, result.offRouteFrames, "sigma=$sigma seed=$seed off-route frames")
                val arrival = kotlin.test.assertNotNull(result.arrivalWalkedMeters, "sigma=$sigma seed=$seed no arrival")
                kotlin.test.assertTrue(
                    arrival >= route.totalLengthMeters - 60.0,
                    "sigma=$sigma seed=$seed arrived at walked=$arrival before total-60=${route.totalLengthMeters - 60.0}",
                )
            }
            println(
                "TASK118_ROUND_TRIP sigma=$sigma " +
                    "over30=${samples.minOf { it.over30Frames }}..${samples.maxOf { it.over30Frames }} " +
                    "longest=${samples.minOf { it.longestOver30Run }}..${samples.maxOf { it.longestOver30Run }} " +
                    "maxError=${samples.minOf { it.maxErrorMeters }}..${samples.maxOf { it.maxErrorMeters }} " +
                    "rematches=${samples.minOf { it.rematches }}..${samples.maxOf { it.rematches }} " +
                    "offRoute=${samples.sumOf { it.offRouteFrames }} " +
                    "arrival=${samples.mapNotNull { it.arrivalWalkedMeters }.minOrNull()}..${samples.mapNotNull { it.arrivalWalkedMeters }.maxOrNull()}",
            )
        }
    }

    @Test
    fun midRouteReturnArrivesAtTheStartAfterOneRematch() {
        val route = oneWayRoute()
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false)
        val turnaroundMeters = route.totalLengthMeters * 0.6
        val outboundSteps = kotlin.math.ceil(turnaroundMeters / 1.2).toInt()
        var state = GuideState.starting(route, config)
        var timestampMillis = 0L
        var walkedMeters = 0.0
        var arrivalWalkedMeters: Double? = null
        var arrivalReturnProgress: Double? = null
        var arrivalBeforeTurnaround = false

        fun step(progressMeters: Double, returning: Boolean) {
            val point = pointAt(route, progressMeters.coerceIn(0.0, route.totalLengthMeters))
            val geo = localToGeo(point.east, point.north)
            val result = Engine.guide(
                state,
                SensorFrame(timestampMillis, geo.lat, geo.lon, 5f, 1.2f, null),
                config,
            )
            state = result.nextState
            if (result.guidance == Guidance.Arrived && arrivalWalkedMeters == null) {
                arrivalWalkedMeters = walkedMeters
                arrivalReturnProgress = progressMeters
                if (!returning) arrivalBeforeTurnaround = true
            }
            timestampMillis += 1_000L
            walkedMeters += 1.2
        }

        for (index in 0..outboundSteps) {
            step(minOf(index * 1.2, turnaroundMeters), returning = false)
        }
        for (index in 1..outboundSteps) {
            if (arrivalWalkedMeters != null) break
            step((turnaroundMeters - index * 1.2).coerceAtLeast(0.0), returning = true)
        }

        kotlin.test.assertFalse(arrivalBeforeTurnaround, "the outward leg must not arrive at the start")
        val arrival = kotlin.test.assertNotNull(arrivalWalkedMeters, "the return to the start should arrive")
        kotlin.test.assertTrue(arrivalReturnProgress!! <= config.arriveRadiusMeters)
        kotlin.test.assertEquals(1, state.systemEvents.count { it.kind == "route.rematched" })
        println(
            "TASK118_MID_ROUTE_RETURN walked=${arrival}m target=${2.0 * turnaroundMeters}m " +
                "returnProgress=${arrivalReturnProgress}m rematches=${state.systemEvents.count { it.kind == "route.rematched" }}",
        )
    }

    private fun runRoundTrip(sigma: Double, seed: Int): Measurement {
        val route = roundTripRoute()
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false)
        val random = Gaussian(seed.toLong())
        var state = GuideState.starting(route, config)
        var over30Frames = 0
        var longestOver30Run = 0
        var currentRun = 0
        var maxErrorMeters = 0.0
        var offRouteFrames = 0
        var arrivalWalkedMeters: Double? = null
        val frameCount = kotlin.math.ceil(route.totalLengthMeters / 1.2).toInt()

        for (index in 0..frameCount) {
            val walkedMeters = minOf(index * 1.2, route.totalLengthMeters)
            val point = pointAt(route, walkedMeters)
            val noisyPoint = localToGeo(
                point.east + sigma * random.next(),
                point.north + sigma * random.next(),
            )
            val result = Engine.guide(state, SensorFrame(index * 1_000L, noisyPoint.lat, noisyPoint.lon, 5f, 1.2f, null), config)
            state = result.nextState
            if (state.startupResolved) {
                val error = kotlin.math.abs((state.lastMatch?.projectedMeters ?: 0.0) - walkedMeters)
                maxErrorMeters = max(maxErrorMeters, error)
                if (error > 30.0) {
                    over30Frames++
                    currentRun++
                    longestOver30Run = max(longestOver30Run, currentRun)
                } else {
                    currentRun = 0
                }
            }
            if (state.offRoute) offRouteFrames++
            if (result.guidance == Guidance.Arrived && arrivalWalkedMeters == null) arrivalWalkedMeters = walkedMeters
        }

        return Measurement(
            over30Frames = over30Frames,
            longestOver30Run = longestOver30Run,
            maxErrorMeters = maxErrorMeters,
            rematches = state.systemEvents.count { it.kind == "route.rematched" },
            offRouteFrames = offRouteFrames,
            arrivalWalkedMeters = arrivalWalkedMeters,
        )
    }

    private fun roundTripRoute(): RouteModel {
        val outward = outwardPoints()
        return routeFrom(outward + outward.dropLast(1).asReversed())
    }

    private fun oneWayRoute(): RouteModel = routeFrom(outwardPoints())

    private fun outwardPoints(): List<EnuPoint> = listOf(
            EnuPoint(0.0, 0.0),
            EnuPoint(400.0, 0.0),
            EnuPoint(800.0, 120.0),
            EnuPoint(1_200.0, 120.0),
            EnuPoint(1_500.0, 500.0),
            EnuPoint(1_500.0, 900.0),
        )

    private fun routeFrom(points: List<EnuPoint>): RouteModel {
        val track = points.joinToString("") { point ->
            val geo = localToGeo(point.east, point.north)
            "<trkpt lat=\"${geo.lat}\" lon=\"${geo.lon}\"/>"
        }
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>")
    }

    private fun pointAt(route: RouteModel, progressMeters: Double): EnuPoint {
        val index = route.cumulativeMeters.indexOfLast { it <= progressMeters }
            .coerceIn(0, route.points.lastIndex - 1)
        val startS = route.cumulativeMeters[index]
        val endS = route.cumulativeMeters[index + 1]
        val fraction = ((progressMeters - startS) / (endS - startS)).coerceIn(0.0, 1.0)
        val start = route.points[index]
        val end = route.points[index + 1]
        return EnuPoint(
            start.east + (end.east - start.east) * fraction,
            start.north + (end.north - start.north) * fraction,
        )
    }

    private fun localToGeo(eastMeters: Double, northMeters: Double): GeoPoint = GeoPoint(
        ORIGIN_LAT + Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
        ORIGIN_LON + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(ORIGIN_LAT)))),
    )

    private class Gaussian(seed: Long) {
        private val random = Random(seed.toInt())

        fun next(): Double {
            var first: Double
            do {
                first = random.nextDouble()
            } while (first <= 0.0)
            val second = random.nextDouble()
            val radius = sqrt(-2.0 * kotlin.math.ln(first))
            val angle = 2.0 * PI * second
            return radius * cos(angle)
        }
    }

    private companion object {
        const val ORIGIN_LAT = 37.0
        const val ORIGIN_LON = 127.0
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
