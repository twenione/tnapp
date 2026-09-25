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

    @Test
    fun boundaryCrossingEmitsAscendingAndDescendingEvents() {
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0000, 20.0, 5f, 1f, null), config).nextState
        val up = guide(state, SensorFrame(1_000L, 10.0030, 20.0, 5f, 1f, null), config)
        check(up.guidance is Guidance.Elevation)
        check(up.reason.details["direction"] == "up")

        val down = guide(up.nextState, SensorFrame(2_000L, 10.0001, 20.0, 5f, 1f, null), config)
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
    fun disabledElevationStillAdvancesBand() {
        val disabled = config.copy(elevationEnabled = false)
        var state = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0000, 20.0, 5f, 1f, null),
            disabled,
        ).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0030, 20.0, 5f, 1f, null), disabled)
        check(result.guidance !is Guidance.Elevation)
        check(result.nextState.elevationBand != null)
    }
}
