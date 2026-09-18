package com.trailnav.app

import com.trailnav.core.GeoPoint
import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import com.trailnav.core.guide
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteRibbonTest {
    @Test
    fun snapshotCarriesDistanceDirectionOffRouteAccuracyAndRemainingDistance() {
        val route = route()
        val config = GuideConfig(offRouteEnterDwellSeconds = 0.0)
        val result = guide(
            GuideState.initial(route),
            location(eastMeters = 32.0, accuracy = 7f).toSensorFrame(),
            config,
        )

        val ribbon = RouteRibbonCalculator.calculate(location(32.0, 7f), result, route, config)!!
        assertEquals(ProgressDirection.FORWARD, ribbon.direction)
        assertTrue(ribbon.offRoute)
        assertEquals(32.0, ribbon.perpendicularDistanceMeters, 1.0)
        assertEquals(32.0, kotlin.math.abs(ribbon.signedOffsetMeters), 1.0)
        assertEquals(7.0, ribbon.accuracyRadiusMeters, 0.001)
        assertEquals(config.offRouteEnterDistMeters, ribbon.enterBandMeters, 0.001)
        assertEquals(config.offRouteExitDistMeters, ribbon.exitBandMeters, 0.001)
        assertTrue(ribbon.remainingDistanceMeters in 40.0..80.0)
    }

    @Test
    fun changingEnterAndExitConfigChangesRibbonBandWidths() {
        val route = route()
        val location = location(eastMeters = 8.0, accuracy = 4f)
        val baseConfig = GuideConfig(offRouteEnterDistMeters = 25.0, offRouteExitDistMeters = 15.0)
        val changedConfig = GuideConfig(offRouteEnterDistMeters = 40.0, offRouteExitDistMeters = 10.0)
        val baseResult = guide(GuideState.initial(route), location.toSensorFrame(), baseConfig)
        val changedResult = guide(GuideState.initial(route), location.toSensorFrame(), changedConfig)

        val base = RouteRibbonCalculator.calculate(location, baseResult, route, baseConfig)!!
        val changed = RouteRibbonCalculator.calculate(location, changedResult, route, changedConfig)!!
        assertEquals(25.0, base.enterBandMeters, 0.001)
        assertEquals(15.0, base.exitBandMeters, 0.001)
        assertEquals(40.0, changed.enterBandMeters, 0.001)
        assertEquals(10.0, changed.exitBandMeters, 0.001)
        assertEquals(base.perpendicularDistanceMeters, changed.perpendicularDistanceMeters, 0.5)
    }

    @Test
    fun inBandPointIsRepresentedAsCenterSideWithAccuracyRadius() {
        val route = route()
        val config = GuideConfig()
        val location = location(eastMeters = 5.0, accuracy = 18f)
        val result = guide(GuideState.initial(route), location.toSensorFrame(), config)
        val ribbon = RouteRibbonCalculator.calculate(location, result, route, config)!!

        assertEquals(RibbonSide.RIGHT, ribbon.side)
        assertTrue(!ribbon.offRoute)
        assertTrue(ribbon.accuracyRadiusMeters > ribbon.perpendicularDistanceMeters)
    }

    private fun route(): RouteModel = RouteModel.fromGpx(
        """<gpx><trk><trkseg>
            <trkpt lat="10.000000" lon="20.000000"/>
            <trkpt lat="10.000500" lon="20.000000"/>
            <trkpt lat="10.001000" lon="20.000000"/>
        </trkseg></trk></gpx>""".trimIndent(),
    )

    private fun location(eastMeters: Double, accuracy: Float): TrailLocation {
        val lat = 10.00035
        val lon = 20.0 + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(lat))))
        return TrailLocation(40_000L, lat, lon, accuracy, 1f, 0f, "test")
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
