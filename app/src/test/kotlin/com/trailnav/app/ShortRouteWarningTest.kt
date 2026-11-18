package com.trailnav.app

import com.trailnav.core.GeoPoint
import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.Guidance
import com.trailnav.core.RouteModel
import com.trailnav.core.RouteQuality
import com.trailnav.core.SensorFrame
import com.trailnav.core.guide
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortRouteWarningTest {
    @Test
    fun measuredArrivalLengthsSetTheShortRouteWarningThreshold() {
        val lengths = (50..150 step 10).map(Int::toDouble)
        val results = lengths.map { length -> length to arrivesAtLength(length) }
        val thresholdIndex = results.indices.firstOrNull { index -> results.drop(index).all { it.second } }
            ?: error("arrival did not persist through all tested longer routes: $results")
        val rawThreshold = results[thresholdIndex].first
        val measuredThreshold = ceil(rawThreshold / 10.0) * 10.0
        val table = results.joinToString(" ") { (length, arrived) -> "$length=${if (arrived) "Arrived" else "no"}" }
        println("D-205 arrival length table: $table; rawL=$rawThreshold; roundedL=$measuredThreshold")
        assertTrue(measuredThreshold in 80.0..150.0, "measured L outside D-205 investigation range: $table")
        assertEquals(RouteImportWarning.SHORT_ROUTE_WARNING_METERS, measuredThreshold)
    }

    @Test
    fun shortRouteWarningIsStrictAndCombinesWithGapWarningOnce() {
        val threshold = RouteImportWarning.SHORT_ROUTE_WARNING_METERS
        assertEquals(RouteImportWarning.SHORT_ROUTE_WARNING_MESSAGE, RouteImportWarning.shortRoute(threshold - 0.1))
        assertNull(RouteImportWarning.shortRoute(threshold))

        val shortOnly = RouteImportWarning.from(RouteQuality.EMPTY, threshold - 0.1)
        assertNotNull(shortOnly)
        assertEquals("경로 안내", shortOnly.title)
        assertEquals(RouteImportWarning.SHORT_ROUTE_WARNING_MESSAGE, shortOnly.message)
        assertNull(RouteImportWarning.from(RouteQuality.EMPTY, threshold))

        val both = RouteImportWarning.from(RouteQuality.EMPTY.copy(gapOver100Count = 2), threshold - 0.1)
        assertNotNull(both)
        assertEquals("경로 안내", both.title)
        assertEquals(2, both.message.split(". ").size)
        assertTrue(both.message.contains("100m 넘게 떨어진 구간이 2곳"))
        assertTrue(both.message.endsWith(RouteImportWarning.SHORT_ROUTE_WARNING_MESSAGE))
        assertEquals("확인", both.button)
    }

    private fun arrivesAtLength(lengthMeters: Double): Boolean {
        val config = GuideConfig()
        val route = straightRoute(lengthMeters, config)
        var state = GuideState.starting(route, config)
        var arrived = false
        var index = 0
        var progress = 0.0
        while (progress < lengthMeters) {
            val point = pointAtDistance(route.sourcePoints.first(), progress)
            val result = guide(
                state,
                SensorFrame(index * 1_000L, point.lat, point.lon, 5f, 1.2f, 0f),
                config,
            )
            state = result.nextState
            arrived = arrived || result.guidance == Guidance.Arrived || result.guidance == Guidance.ArrivedAtStart
            index += 1
            progress = (index * 1.2).coerceAtMost(lengthMeters)
        }
        return arrived
    }

    private fun straightRoute(lengthMeters: Double, config: GuideConfig): RouteModel {
        val origin = GeoPoint(10.0, 20.0)
        val end = pointAtDistance(origin, lengthMeters)
        val gpx = String.format(
            Locale.US,
            "<gpx><trk><trkseg><trkpt lat=\"%.12f\" lon=\"%.12f\"/><trkpt lat=\"%.12f\" lon=\"%.12f\"/></trkseg></trk></gpx>",
            origin.lat,
            origin.lon,
            end.lat,
            end.lon,
        )
        return RouteModel.fromGpx(gpx, config)
    }

    private fun pointAtDistance(origin: GeoPoint, northMeters: Double): GeoPoint {
        val radius = 6_371_008.8
        return GeoPoint(origin.lat + northMeters / radius * 180.0 / PI, origin.lon)
    }
}
