package com.trailnav.core

import kotlin.math.cos
import kotlin.test.Test

/**
 * Contract tests intentionally small enough to run in the D-033 mutation
 * checkout.  They fail when dwell is bypassed or a caller config is ignored.
 */
class EngineContractTest {
    private val route = RouteModel.fromGpx(
        """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.002" lon="20.0"/></trkseg></trk></gpx>"""
    )

    @Test
    fun enterDwellIsEnforcedByCompiledEngine() {
        val config = GuideConfig(offRouteEnterDwellSeconds = 20.0)
        val metersPerDegreeLon = 6_371_008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east30 = 30.0 / metersPerDegreeLon
        val frame = { timestamp: Long -> SensorFrame(timestamp, 10.0005, 20.0 + east30, 5f, 1f, null) }
        var state = GuideState.initial(route)
        state = guide(state, frame(0L), config).nextState
        state = guide(state, frame(10_000L), config).nextState
        check(!state.offRoute) { "off-route entered before the configured dwell elapsed" }
        state = guide(state, frame(21_000L), config).nextState
        check(state.offRoute) { "off-route did not enter after the configured dwell elapsed" }
    }

    @Test
    fun callerConfigControlsEnterDistance() {
        val metersPerDegreeLon = 6_371_008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
        val east30 = 30.0 / metersPerDegreeLon
        val config = GuideConfig(offRouteEnterDistMeters = 100.0, offRouteEnterDwellSeconds = 0.0)
        val result = guide(
            GuideState.initial(route),
            SensorFrame(0L, 10.0005, 20.0 + east30, 5f, 1f, null),
            config
        )
        check(!result.nextState.offRoute) { "engine ignored caller-supplied enter distance" }
    }
}
