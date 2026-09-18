package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.RouteModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteOrientationTest {
    private val xml = """
        <gpx><trk><trkseg>
          <trkpt lat="10.000000" lon="20.000000"/>
          <trkpt lat="10.000000" lon="20.001000"/>
          <trkpt lat="10.000000" lon="20.002000"/>
        </trkseg></trk></gpx>
    """.trimIndent()

    @Test
    fun forwardStartKeepsOriginalOrderAndArrivesAtOriginalLastPoint() {
        val result = RouteOrientation.orient(xml, location(10.0, 20.0))
        assertFalse(result.reversed)
        assertEquals("first-endpoint-closer", result.reason)
        val route = RouteModel.fromGpx(result.gpxXml)
        assertEquals(20.0, route.sourcePoints.first().lon, 0.000001)

        val arrival = guideAtEndpoints(route, startLon = 20.0, destinationLon = 20.002)
        assertTrue(arrival.result.guidance is Guidance.Arrived)
        assertTrue(arrival.result.nextState.arrived)
    }

    @Test
    fun reverseStartFlipsOrderAndArrivesAtOriginalFirstPoint() {
        val result = RouteOrientation.orient(xml, location(10.0, 20.002))
        assertTrue(result.reversed)
        assertEquals("last-endpoint-closer", result.reason)
        val route = RouteModel.fromGpx(result.gpxXml)
        assertEquals(20.002, route.sourcePoints.first().lon, 0.000001)
        assertEquals(20.0, route.sourcePoints.last().lon, 0.000001)

        val arrival = guideAtEndpoints(route, startLon = 20.002, destinationLon = 20.0)
        assertTrue(arrival.result.guidance is Guidance.Arrived)
        assertTrue(arrival.result.nextState.arrived)
    }

    @Test
    fun ambiguousDistanceKeepsOriginalOrder() {
        val result = RouteOrientation.orient(xml, location(10.0, 20.001))
        assertFalse(result.reversed)
        assertEquals("endpoint-distance-ambiguous", result.reason)
        assertEquals(20.0, RouteModel.fromGpx(result.gpxXml).sourcePoints.first().lon, 0.000001)
    }

    @Test
    fun missingLastLocationKeepsOriginalOrderWithoutWaiting() {
        val result = RouteOrientation.orient(xml, null)
        assertFalse(result.reversed)
        assertEquals("last-location-unavailable", result.reason)
        assertEquals(20.0, RouteModel.fromGpx(result.gpxXml).sourcePoints.first().lon, 0.000001)
    }

    private fun guideAtEndpoints(route: RouteModel, startLon: Double, destinationLon: Double): SessionDecision {
        val session = GuideSession(route)
        session.accept(TrailLocation(0L, 10.0, startLon, 5f, 1f, null, "test"))
        return session.accept(TrailLocation(1_000L, 10.0, destinationLon, 5f, 1f, null, "test"))
    }

    private fun location(latitude: Double, longitude: Double) =
        TrailLocation(0L, latitude, longitude, 5f, 0f, null, "test")
}
