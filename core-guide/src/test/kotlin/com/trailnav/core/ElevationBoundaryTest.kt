package com.trailnav.core

import kotlin.test.Test

class ElevationBoundaryTest {
    private val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
        <trkpt lat="10.0000" lon="20.0000"><ele>50</ele></trkpt>
        <trkpt lat="10.0005" lon="20.0000"><ele>60</ele></trkpt>
        <trkpt lat="10.0010" lon="20.0000"><ele>70</ele></trkpt>
        <trkpt lat="10.0015" lon="20.0000"><ele>80</ele></trkpt>
        <trkpt lat="10.0020" lon="20.0000"><ele>90</ele></trkpt>
        <trkpt lat="10.0025" lon="20.0000"><ele>100</ele></trkpt>
        <trkpt lat="10.0030" lon="20.0000"><ele>110</ele></trkpt>
        <trkpt lat="10.0035" lon="20.0000"><ele>119</ele></trkpt>
        <trkpt lat="10.0040" lon="20.0000"><ele>128</ele></trkpt>
    </trkseg></trk></gpx>""")

    private val config = GuideConfig(
        elevationEnabled = true,
        elevationBoundaryMeters = 100.0,
        elevationHysteresisMeters = 10.0,
        eventMinIntervalSeconds = 0.0,
        sunsetEnabled = false,
    )

    private val unstableRoute = RouteModel.fromGpx("""<gpx><trk><trkseg>
        <trkpt lat="10.0000" lon="20.0000"><ele>50</ele></trkpt>
        <trkpt lat="10.0005" lon="20.0000"><ele>60</ele></trkpt>
        <trkpt lat="10.0010" lon="20.0000"><ele>70</ele></trkpt>
        <trkpt lat="10.0015" lon="20.0000"><ele>80</ele></trkpt>
        <trkpt lat="10.0020" lon="20.0000"><ele>90</ele></trkpt>
        <trkpt lat="10.0025" lon="20.0000"><ele>100</ele></trkpt>
        <trkpt lat="10.0030" lon="20.0000"><ele>110</ele></trkpt>
        <trkpt lat="10.0035" lon="20.0000"><ele>120</ele></trkpt>
        <trkpt lat="10.0040" lon="20.0000"><ele>200</ele></trkpt>
    </trkseg></trk></gpx>""")

    private fun linearElevationRoute(elevations: List<Int>): RouteModel = RouteModel.fromGpx(
        "<gpx><trk><trkseg>" + elevations.mapIndexed { index, elevation ->
            "<trkpt lat=\"${10.0 + index * 0.0003}\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
        }.joinToString("") + "</trkseg></trk></gpx>"
    )

    @Test
    fun boundaryCrossingEmitsAscendingAndDescendingEvents() {
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0000, 20.0, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(500L, 10.0015, 20.0, 5f, 1f, null), config).nextState
        val up = guide(state, SensorFrame(1_000L, 10.0035, 20.0, 5f, 1f, null), config)
        check(up.guidance is Guidance.Elevation)
        check(up.reason.details["direction"] == "up")

        val down = guide(up.nextState, SensorFrame(2_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(down.guidance is Guidance.Elevation)
        check(down.reason.details["direction"] == "down")
    }

    @Test
    fun hysteresisPreventsBoundaryChatter() {
        val primed = GuideState.initial(route).copy(elevationBand = 1)
        val result = guide(primed, SensorFrame(1_000L, 10.002375, 20.0, 5f, 1f, null), config)
        check(result.guidance == null)
        check(result.nextState.elevationBand == 1)
    }

    @Test
    fun hysteresisRequiresTheConfiguredOvershoot() {
        val primed = GuideState.initial(route).copy(elevationBand = 0)
        val result = guide(primed, SensorFrame(1_000L, 10.00275, 20.0, 5f, 1f, null), config)
        check(result.guidance !is Guidance.Elevation)
        check(result.nextState.elevationBand == 0)
    }

    @Test
    fun initialElevationAtBoundaryUsesTheContainingBand() {
        val result = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.00225, 20.0, 5f, 1f, null),
            config,
        )
        check(result.guidance == null)
        check(result.nextState.elevationBand == 0)
    }

    @Test
    fun unstableElevationProfileDoesNotProduceBoundaryEvents() {
        val unstableConfig = config.copy(minimumSessionSecondsBeforeArrival = 100_000.0)
        var state = guide(
            GuideState.initial(unstableRoute),
            SensorFrame(0L, 10.0000, 20.0, 5f, 1f, null),
            unstableConfig,
        ).nextState
        state = guide(state, SensorFrame(500L, 10.0015, 20.0, 5f, 1f, null), unstableConfig).nextState
        state = guide(state, SensorFrame(1_000L, 10.0030, 20.0, 5f, 1f, null), unstableConfig).nextState
        val result = guide(state, SensorFrame(1_500L, 10.0040, 20.0, 5f, 1f, null), unstableConfig)
        check(result.guidance !is Guidance.Elevation)
    }

    @Test
    fun offRouteElevationCrossingIsConsumedWithoutAnnouncement() {
        val offRouteConfig = config.copy(minimumSessionSecondsBeforeArrival = 100_000.0)
        val offRoute = GuideState.initial(route).copy(offRoute = true, elevationBand = 0)
        val result = guide(offRoute, SensorFrame(1_000L, 10.0040, 20.0, 5f, 1f, null), offRouteConfig)
        check(result.guidance !is Guidance.Elevation)
        check(result.nextState.offRoute)
    }

    @Test
    fun toggleOffSilentButAdvancesBand() {
        val disabled = config.copy(elevationEnabled = false)
        var state = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0000, 20.0, 5f, 1f, null),
            disabled,
        ).nextState
        state = guide(state, SensorFrame(500L, 10.0015, 20.0, 5f, 1f, null), disabled).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0035, 20.0, 5f, 1f, null), disabled)
        check(result.guidance !is Guidance.Elevation)
        check(result.nextState.elevationBand != null)
    }

    @Test
    fun boundaryConfigChangesCrossings() {
        val testRoute = linearElevationRoute((0..180 step 10).toList())
        fun boundaries(boundary: Double): List<Double> {
            val configured = config.copy(elevationBoundaryMeters = boundary, elevationHysteresisMeters = 0.0)
            var state = GuideState.initial(testRoute)
            val emitted = mutableListOf<Double>()
            (0..18).forEach { index ->
                val result = guide(
                    state,
                    SensorFrame(index * 1_000L, 10.0 + index * 0.0003, 20.0, 5f, 1f, null),
                    configured,
                )
                if (result.guidance is Guidance.Elevation) {
                    emitted += result.reason.details["boundaryMeters"]!!.toDouble()
                }
                state = result.nextState
            }
            return emitted
        }
        val hundred = boundaries(100.0)
        val fifty = boundaries(50.0)
        check(hundred == listOf(100.0)) { "100 m boundary emitted $hundred" }
        check(fifty == listOf(50.0, 100.0, 150.0)) { "50 m boundary emitted $fifty" }
    }

    @Test
    fun hysteresisConfigChangesChatter() {
        val testRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg>" + (0..200 step 10).mapIndexed { index, elevation ->
                "<trkpt lat=\"${10.0 + index * 0.0005}\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
            }.joinToString("") + "</trkseg></trk></gpx>"
        )
        fun chatter(hysteresis: Double): Int {
            val configured = config.copy(
                elevationBoundaryMeters = 100.0,
                elevationHysteresisMeters = hysteresis,
                eventMinIntervalSeconds = 0.0,
            )
            val start = guide(
                GuideState.initial(testRoute),
                SensorFrame(0L, 10.00498, 20.0, 5f, 1f, null),
                configured,
            )
            var state = start.nextState
            var emitted = 0
            repeat(60) { index ->
                val lat = if (index % 2 == 0) 10.00502 else 10.00498
                val result = guide(state, SensorFrame((index + 1) * 1_000L, lat, 20.0, 5f, 1f, null), configured)
                if (result.guidance is Guidance.Elevation) emitted++
                state = result.nextState
            }
            return emitted
        }
        check(chatter(10.0) == 0) { "10 m hysteresis should absorb the ±0.4 m chatter" }
        check(chatter(30.0) == 0) { "30 m hysteresis should absorb the ±0.4 m chatter" }
        val zeroHysteresisEvents = chatter(0.0)
        check(zeroHysteresisEvents == 60) {
            "zero hysteresis should expose all 60 alternating crossings, got $zeroHysteresisEvents; " +
                "routeUse=${testRoute.elevationUse}, profile=${testRoute.smoothedElevationMeters}"
        }
    }
}
