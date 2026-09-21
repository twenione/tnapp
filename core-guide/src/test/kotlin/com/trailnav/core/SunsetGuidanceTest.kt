package com.trailnav.core

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test

/** Independent public-coordinate sunset reference rows from TASK-027 §8.2. */
class SunsetGuidanceTest {
    private data class Row(val name: String, val lat: Double, val lon: Double, val sunsetUtcMillis: Long?)

    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()

    private val rows = listOf(
        Row("Seoul-2026-09-21", 37.5665, 126.9780, epoch("2026-09-21T09:31:43Z")),
        Row("Seoul-2026-03-20", 37.5665, 126.9780, epoch("2026-03-20T09:43:15Z")),
        Row("Seoul-2024-09-21", 37.5665, 126.9780, epoch("2024-09-21T09:30:41Z")),
        Row("Seoul-2028-09-21", 37.5665, 126.9780, epoch("2028-09-21T09:30:39Z")),
        Row("Busan-2026-12-21", 35.1796, 129.0756, epoch("2026-12-21T08:15:02Z")),
        Row("Jeju-2026-06-21", 33.4996, 126.5312, epoch("2026-06-21T10:46:42Z")),
        Row("Denver-2026-09-21", 39.7392, -104.9903, epoch("2026-09-22T00:58:50Z")),
        Row("Sydney-2026-12-21", -33.8688, 151.2093, epoch("2026-12-21T09:05:14Z")),
        Row("Auckland-2026-06-21", -36.8485, 174.7633, epoch("2026-06-21T05:11:23Z")),
        Row("Tromso-2026-06-21", 69.6492, 18.9553, null),
    )

    @Test
    fun referenceRowsEmitAtSixtyAndThirtyMinuteCrossings() {
        rows.forEach { row ->
            val route = RouteModel.fromGpx("<gpx><trk><trkseg><trkpt lat=\"${row.lat}\" lon=\"${row.lon}\"/><trkpt lat=\"${row.lat + 0.02}\" lon=\"${row.lon}\"/></trkseg></trk></gpx>")
            var state = GuideState.initial(route)
            val base = row.sunsetUtcMillis ?: epoch("2026-06-21T10:46:40Z")
            val frames = listOf(-62L, -58L, -32L, -28L).map { minutes ->
                SensorFrame(base + minutes * 60_000L, row.lat, row.lon, 5f, 0f, null)
            }
            val results = frames.map { frame ->
                val result = guide(state, frame)
                state = result.nextState
                result
            }
            if (row.sunsetUtcMillis == null) {
                check(results.all { it.guidance !is Guidance.Sunset }) { row.name }
            } else {
                check(results[0].guidance !is Guidance.Sunset) { row.name }
                check(results[1].guidance is Guidance.Sunset) { row.name }
                check(results[3].guidance is Guidance.Sunset) { row.name }
                val firstMinutes = results[1].reason.details["minutesRemaining"]!!.toInt()
                val secondMinutes = results[3].reason.details["minutesRemaining"]!!.toInt()
                check(abs(firstMinutes - 58) <= 2) { "${row.name} 60-minute crossing=$firstMinutes" }
                check(abs(secondMinutes - 28) <= 2) { "${row.name} 30-minute crossing=$secondMinutes" }
            }
        }
    }
}
