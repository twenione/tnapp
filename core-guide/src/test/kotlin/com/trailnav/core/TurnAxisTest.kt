package com.trailnav.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TurnAxisTest {
    private data class SyntheticRoute(
        val enuPoints: List<EnuPoint>,
        val cumulativeMeters: List<Double>,
        val expectedCornerMeters: List<Double>,
        val gpx: String,
    )

    private fun syntheticRoute(): SyntheticRoute {
        val points = mutableListOf<EnuPoint>()
        fun wave(distance: Int): Double = 1.5 * sin(2.0 * PI * distance / 40.0)

        for (north in 0..1_500) {
            points += EnuPoint(wave(north), north.toDouble())
        }
        for (west in 1..1_500) {
            points += EnuPoint(-west.toDouble(), 1_500.0 + wave(west))
        }
        for (north in 1..1_500) {
            points += EnuPoint(-1_500.0 + wave(north), 1_500.0 + north)
        }

        val cumulative = MutableList(points.size) { 0.0 }
        for (index in 1 until points.size) {
            val eastDelta = points[index].east - points[index - 1].east
            val northDelta = points[index].north - points[index - 1].north
            cumulative[index] = cumulative[index - 1] + sqrt(eastDelta * eastDelta + northDelta * northDelta)
        }

        val originLatitude = 10.0
        val originLongitude = 20.0
        val latitudeDegreesPerMeter = 180.0 / (PI * 6_371_008.8)
        val longitudeDegreesPerMeter = latitudeDegreesPerMeter / cos(Math.toRadians(originLatitude))
        val gpx = buildString {
            append("<gpx version=\"1.1\" creator=\"TASK-044 synthetic fixture\"><trk><trkseg>")
            points.forEach { point ->
                val latitude = originLatitude + point.north * latitudeDegreesPerMeter
                val longitude = originLongitude + point.east * longitudeDegreesPerMeter
                append("<trkpt lat=\"").append(latitude).append("\" lon=\"").append(longitude)
                    .append("\"><ele>100</ele></trkpt>")
            }
            append("</trkseg></trk></gpx>")
        }
        return SyntheticRoute(
            enuPoints = points,
            cumulativeMeters = cumulative,
            expectedCornerMeters = listOf(cumulative[1_500], cumulative[3_000]),
            gpx = gpx,
        )
    }

    private fun pointAtDistance(path: SyntheticRoute, distanceMeters: Double): EnuPoint {
        val distance = distanceMeters.coerceIn(0.0, path.cumulativeMeters.last())
        val upper = path.cumulativeMeters.binarySearch(distance).let { result ->
            if (result >= 0) result else -result - 1
        }.coerceIn(1, path.cumulativeMeters.lastIndex)
        val lower = upper - 1
        val span = path.cumulativeMeters[upper] - path.cumulativeMeters[lower]
        val fraction = if (span == 0.0) 0.0 else (distance - path.cumulativeMeters[lower]) / span
        return EnuPoint(
            east = path.enuPoints[lower].east + (path.enuPoints[upper].east - path.enuPoints[lower].east) * fraction,
            north = path.enuPoints[lower].north + (path.enuPoints[upper].north - path.enuPoints[lower].north) * fraction,
        )
    }

    private fun frameAt(path: SyntheticRoute, distanceMeters: Double, timestamp: Long): SensorFrame {
        val point = pointAtDistance(path, distanceMeters)
        val originLatitude = 10.0
        val originLongitude = 20.0
        val latitude = originLatitude + point.north / 6_371_008.8 * 180.0 / PI
        val longitude = originLongitude + point.east / (6_371_008.8 * cos(Math.toRadians(originLatitude))) * 180.0 / PI
        return SensorFrame(timestamp, latitude, longitude, 3f, 1.2f, null)
    }

    private fun guideConfig() = GuideConfig(emaAlpha = 1.0, sunsetEnabled = false)

    @Test
    fun turnAnnouncementsLandAtDesignDistanceFromTheCorner() {
        val path = syntheticRoute()
        val route = RouteModel.fromGpx(path.gpx)
        assertTrue(
            route.totalLengthMeters >= route.simplifiedCumulativeMeters.last() * 1.01,
            "the one-metre lateral waves must make the raw route at least 1% longer than its simplified geometry",
        )
        assertEquals(2, route.turns.size, "only the two constructed corners should be turns")
        assertEquals(listOf(Side.LEFT, Side.RIGHT), route.turns.map { it.side })

        val aheadRemaining = mutableMapOf<Int, Double>()
        val nowRemaining = mutableMapOf<Int, Double>()
        var state = GuideState.initial(route)
        var distance = 0.0
        var frameIndex = 0
        while (distance <= path.cumulativeMeters.last()) {
            val result = guide(state, frameAt(path, distance, frameIndex * 1_000L), guideConfig())
            val turnIndex = result.reason.details["turnIndex"]?.toIntOrNull()
            when (result.guidance) {
                is Guidance.TurnAhead -> if (turnIndex != null) {
                    aheadRemaining.putIfAbsent(turnIndex, path.expectedCornerMeters[turnIndex] - distance)
                }
                is Guidance.TurnNow -> if (turnIndex != null) {
                    nowRemaining.putIfAbsent(turnIndex, path.expectedCornerMeters[turnIndex] - distance)
                }
                else -> Unit
            }
            state = result.nextState
            distance += 1.2
            frameIndex += 1
        }

        assertEquals(setOf(0, 1), aheadRemaining.keys)
        assertEquals(setOf(0, 1), nowRemaining.keys)
        for (index in path.expectedCornerMeters.indices) {
            assertTrue(aheadRemaining.getValue(index) in 50.0..70.0, "corner $index TurnAhead must be 50–70 m away")
            assertTrue(nowRemaining.getValue(index) in 5.0..25.0, "corner $index TurnNow must be 5–25 m away")
        }
    }

    @Test
    fun nextTurnDistanceUsesTheRawRouteAxis() {
        val path = syntheticRoute()
        val route = RouteModel.fromGpx(path.gpx)
        val distance = 1_000.0
        val state = guide(
            GuideState.initial(route),
            frameAt(path, distance, 0L),
            guideConfig(),
        ).nextState
        val nextTurn = assertNotNull(routeStatus(state)?.nextTurn)
        assertEquals(path.expectedCornerMeters.first() - distance, nextTurn.distanceMeters, 3.0)
    }

    @Test
    fun turnPositionsDoNotDriftWithDistance() {
        val path = syntheticRoute()
        val route = RouteModel.fromGpx(path.gpx)
        assertEquals(2, route.turns.size)
        route.turns.zip(path.expectedCornerMeters).forEachIndexed { index, (turn, expected) ->
            assertTrue(kotlin.math.abs(turn.s - expected) <= 2.0, "corner $index turn position must use the raw route axis")
        }
    }
}