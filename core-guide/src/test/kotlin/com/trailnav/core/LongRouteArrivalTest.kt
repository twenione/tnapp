package com.trailnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongRouteArrivalTest {
    @Test
    fun longSyntheticRouteArrivesOnceAtTheInputCalculatedFrame() {
        val config = GuideConfig()
        val route = RouteModel.fromGpx(
            """
                <gpx><trk><trkseg>
                  <trkpt lat="37.00000000" lon="127.00000000"/>
                  <trkpt lat="37.00000000" lon="127.00168721"/>
                  <trkpt lat="37.00134747" lon="127.00168721"/>
                </trkseg></trk></gpx>
            """.trimIndent(),
            config,
        )
        var state = GuideState.starting(route, config)
        val arrivals = mutableListOf<Int>()
        val frames = listOf(
            0 to (0.0 to 0.0),
            15 to (15.0 to 0.0),
            30 to (30.0 to 0.0),
            95 to (95.0 to 0.0),
            140 to (140.0 to 0.0),
            290 to (150.0 to 140.0),
        )

        frames.forEach { (second, point) ->
            val result = Engine.guide(state, frame(point.first, point.second, second), config)
            state = result.nextState
            if (result.guidance == Guidance.Arrived) arrivals += second
            if (second < 290) assertTrue(result.guidance != Guidance.Arrived, "unexpected arrival at $second s")
        }

        assertTrue(state.startupResolved, "30m input path should resolve before the destination approach")
        assertEquals(listOf(290), arrivals, "input first exceeds the 95% progress gate inside the destination radius at 290s")
    }

    private fun frame(east: Double, north: Double, second: Int) = SensorFrame(
        timestamp = second * 1_000L,
        lat = 37.0 + north / 111_320.0,
        lon = 127.0 + east / (111_320.0 * kotlin.math.cos(Math.toRadians(37.0))),
        accuracy = 5f,
        speed = 1f,
        gpsBearing = null,
    )
}
