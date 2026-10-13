package com.trailnav.app

import com.trailnav.core.GeoPoint
import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import com.trailnav.core.RouteTargetEstimate
import com.trailnav.core.TargetLabelKind
import com.trailnav.core.TargetKind
import com.trailnav.core.guide
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteRibbonTest {
    @Test
    fun snapshotCarriesDistanceDirectionOffRouteAccuracyAndRemainingDistance() {
        val route = route()
        val config = GuideConfig(offRouteEnterDwellSeconds = 0.0)
        val first = location(eastMeters = 0.0, accuracy = 7f, latitude = 10.00020)
        val current = location(eastMeters = 32.0, accuracy = 7f, latitude = 10.00035)
        val firstResult = guide(GuideState.initial(route), first.toSensorFrame(), config)
        val result = guide(
            firstResult.nextState,
            current.toSensorFrame(),
            config,
        )

        val ribbon = RouteRibbonCalculator.calculate(current, result, route, config)!!
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
    fun calculatorCarriesAnOptionalRouteTargetWithoutChangingExistingCalls() {
        val route = route()
        val config = GuideConfig()
        val location = location(eastMeters = 5.0, accuracy = 4f)
        val result = guide(GuideState.initial(route), location.toSensorFrame(), config)
        val target = RouteTargetEstimate(
            kind = TargetKind.NEXT_SUMMIT,
            source = "test",
            remainingMeters = 850.0,
            remainingSeconds = 600.0,
            correction = 1.0,
            correctionActive = false,
            slopeSource = "flat",
            labelKind = TargetLabelKind.NAMED_WAYPOINT,
            name = "바라산",
        )

        val withTarget = RouteRibbonCalculator.calculate(location, result, route, config, target)!!
        assertEquals(TargetKind.NEXT_SUMMIT, withTarget.targetKind)
        assertEquals(850.0, withTarget.targetRemainingMeters)
        assertEquals(TargetLabelKind.NAMED_WAYPOINT, withTarget.targetLabelKind)
        assertEquals("바라산", withTarget.targetName)

        val withoutTarget = RouteRibbonCalculator.calculate(location, result, route, config)!!
        assertNull(withoutTarget.targetKind)
        assertNull(withoutTarget.targetRemainingMeters)
    }

    @Test
    fun ribbonDistanceLabelUsesTargetSubjectAndFallsBackToDestinationDistance() {
        val state = RouteRibbonState(
            perpendicularDistanceMeters = 0.0,
            signedOffsetMeters = 0.0,
            direction = ProgressDirection.FORWARD,
            offRoute = false,
            enterBandMeters = 25.0,
            exitBandMeters = 15.0,
            accuracyRadiusMeters = 5.0,
            remainingDistanceMeters = 2_000.0,
        )

        assertEquals(
            "다음 정상까지 850m",
            ribbonDistanceLabel(state.copy(targetKind = TargetKind.NEXT_SUMMIT, targetRemainingMeters = 850.0)),
        )
        assertEquals(
            "도착지까지 1234m",
            ribbonDistanceLabel(state.copy(targetKind = TargetKind.DESTINATION, targetRemainingMeters = 1_234.4)),
        )
        assertEquals("도착지까지 2000m", ribbonDistanceLabel(state))
        assertEquals(
            "바라산까지 850m",
            ribbonDistanceLabel(state.copy(
                targetKind = TargetKind.NEXT_SUMMIT,
                targetRemainingMeters = 850.0,
                targetLabelKind = TargetLabelKind.NAMED_WAYPOINT,
                targetName = "바라산",
            )),
        )
        assertEquals(
            "다음 경유지까지 840m",
            ribbonDistanceLabel(state.copy(
                targetKind = TargetKind.NEXT_SUMMIT,
                targetRemainingMeters = 840.0,
                targetLabelKind = TargetLabelKind.UNNAMED_WAYPOINT,
            )),
        )
        assertEquals(
            "다음 정상까지 830m",
            ribbonDistanceLabel(state.copy(
                targetKind = TargetKind.NEXT_SUMMIT,
                targetRemainingMeters = 830.0,
                targetLabelKind = TargetLabelKind.ELEVATION_PEAK,
            )),
        )
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

        assertEquals(RibbonSide.LEFT, ribbon.side)
        assertTrue(!ribbon.offRoute)
        assertTrue(ribbon.accuracyRadiusMeters > ribbon.perpendicularDistanceMeters)
    }

    @Test
    fun signedOffsetAndPointScreenDirectionUseTheSameSideText() {
        val rightOfCenter = RouteRibbonState(
            perpendicularDistanceMeters = 10.0,
            signedOffsetMeters = 12.0,
            direction = ProgressDirection.FORWARD,
            offRoute = false,
            enterBandMeters = 25.0,
            exitBandMeters = 15.0,
            accuracyRadiusMeters = 5.0,
            remainingDistanceMeters = 100.0,
        )
        val leftOfCenter = rightOfCenter.copy(signedOffsetMeters = -12.0)

        // RouteRibbonView draws pointX = centerX + signedOffsetMeters * scale.
        assertEquals(RibbonSide.RIGHT, rightOfCenter.side)
        assertEquals(RibbonSide.LEFT, leftOfCenter.side)
    }

    @Test
    fun ribbonExposesNextTurnFromCompiledRouteStatus() {
        val route = RouteModel.fromGpx("""
            <gpx><trk><trkseg>
              <trkpt lat="10.000000" lon="20.000000"/>
              <trkpt lat="10.000000" lon="20.001000"/>
              <trkpt lat="10.001000" lon="20.001000"/>
            </trkseg></trk></gpx>
        """.trimIndent())
        val config = GuideConfig(offRouteEnterDwellSeconds = 0.0)
        val location = TrailLocation(0L, 10.000000, 20.0001, 5f, 1f, 90f, "test")
        val result = guide(GuideState.initial(route), location.toSensorFrame(), config)
        val ribbon = RouteRibbonCalculator.calculate(location, result, route, config)
        assertEquals(route.turns.firstOrNull()?.side?.name, ribbon?.nextTurn?.side?.name)
    }

    private fun route(): RouteModel = RouteModel.fromGpx(
        """<gpx><trk><trkseg>
            <trkpt lat="10.000000" lon="20.000000"/>
            <trkpt lat="10.000500" lon="20.000000"/>
            <trkpt lat="10.001000" lon="20.000000"/>
        </trkseg></trk></gpx>""".trimIndent(),
    )

    private fun location(eastMeters: Double, accuracy: Float, latitude: Double = 10.00035): TrailLocation {
        val lat = latitude
        val lon = 20.0 + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(lat))))
        return TrailLocation(40_000L, lat, lon, accuracy, 1f, 0f, "test")
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
