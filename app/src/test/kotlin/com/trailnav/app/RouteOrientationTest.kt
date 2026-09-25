package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.GuideConfig
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun clearForwardMovementKeepsOriginalOrderAndResolvesWithGuideProgress() {
        val observer = RouteDirectionObserver(RouteModel.fromGpx(xml))
        assertEquals(
            RouteDirectionObservationOutcome.START_FOUND,
            observer.observe(location(20.001000), 0L).outcome,
        )
        val observation = observer.observe(location(20.001250), 1_000L)

        assertEquals(RouteDirectionObservationOutcome.FORWARD, observation.outcome)
        assertEquals(ProgressDirection.FORWARD, observation.direction)
        val orientation = RouteOrientation.orient(
            xml,
            observation.direction,
            "direction-observed",
            kotlin.math.abs(observation.netDisplacementMeters),
            observation.elapsedSeconds,
        )
        assertFalse(orientation.reversed)
        assertEquals("direction-observed", orientation.reason)
        assertEquals(20.0, RouteModel.fromGpx(orientation.gpxXml).sourcePoints.first().lon, 0.000001)
    }

    @Test
    fun clearReverseMovementReversesOrderAndGuideArrivesAtOriginalFirstPoint() {
        val observer = RouteDirectionObserver(RouteModel.fromGpx(xml))
        observer.observe(location(20.001000), 0L)
        val observation = observer.observe(location(20.000750), 1_000L)

        assertEquals(RouteDirectionObservationOutcome.REVERSE, observation.outcome)
        assertEquals(ProgressDirection.REVERSE, observation.direction)
        val orientation = RouteOrientation.orient(
            xml,
            observation.direction,
            "direction-observed",
            kotlin.math.abs(observation.netDisplacementMeters),
            observation.elapsedSeconds,
        )
        assertTrue(orientation.reversed)
        val route = RouteModel.fromGpx(orientation.gpxXml)
        assertEquals(20.002, route.sourcePoints.first().lon, 0.000001)
        assertEquals(20.0, route.sourcePoints.last().lon, 0.000001)
        val session = GuideSession(route, GuideConfig(minimumSessionSecondsBeforeArrival = 0.0))
        session.accept(location(20.002))
        val arrival = session.accept(location(20.0))
        assertTrue(arrival.result.guidance is Guidance.Arrived)
    }

    @Test
    fun noisyInPlaceMovementFallsBackWithoutReversingAfterThirtySeconds() {
        val observer = RouteDirectionObserver(RouteModel.fromGpx(xml))
        observer.observe(location(20.001000), 0L)
        observer.observe(location(20.001020), 10_000L)
        observer.observe(location(20.000980), 20_000L)
        val fallback = observer.observe(location(20.001010), 30_000L)

        assertEquals(RouteDirectionObservationOutcome.FALLBACK_NO_NET_DISPLACEMENT, fallback.outcome)
        assertNull(fallback.direction)
        val orientation = RouteOrientation.orient(
            xml,
            fallback.direction,
            "fallback-no-net-displacement",
            kotlin.math.abs(fallback.netDisplacementMeters),
            fallback.elapsedSeconds,
        )
        assertFalse(orientation.reversed)
        assertEquals("fallback-no-net-displacement", orientation.reason)
    }

    @Test
    fun timeoutWithoutARecentFixUsesOriginalOrder() {
        val coordinator = RouteStartupCoordinator(xml, startupAtMillis = 0L)
        val update = coordinator.timeout(30_000L)

        assertEquals(RoutePreparationStage.DIRECTION_CONFIRMED, update.stage)
        assertEquals(RouteDirectionObservationOutcome.FALLBACK_TIMEOUT, update.observation.outcome)
        assertEquals("fallback-timeout", update.orientation?.reason)
        assertEquals(30.0, update.orientation?.observationElapsedSeconds)
        assertFalse(update.orientation?.reversed ?: true)
        assertTrue(update.replayLocations.isEmpty())
    }

    @Test
    fun endpointFallbackReversesWhenBufferedStartIsNearOriginalEnd() {
        val coordinator = RouteStartupCoordinator(xml, startupAtMillis = 0L)
        val nearOriginalEnd = location(20.0019)

        coordinator.accept(nearOriginalEnd, 0L)
        val update = coordinator.timeout(30_000L)

        assertEquals(RouteDirectionObservationOutcome.FALLBACK_TIMEOUT, update.observation.outcome)
        assertTrue(update.orientation?.reversed == true)
        assertEquals("fallback-endpoint-distance", update.orientation?.reason)
        assertEquals(20.002, RouteModel.fromGpx(update.orientation!!.gpxXml).sourcePoints.first().lon, 0.000001)
    }

    @Test
    fun endpointFallbackKeepsOrderWhenBufferedStartIsNearOriginalStart() {
        val coordinator = RouteStartupCoordinator(xml, startupAtMillis = 0L)
        val nearOriginalStart = location(20.0001)

        coordinator.accept(nearOriginalStart, 0L)
        val update = coordinator.timeout(30_000L)

        assertFalse(update.orientation?.reversed ?: true)
        assertEquals("fallback-endpoint-distance", update.orientation?.reason)
        assertEquals(20.0, RouteModel.fromGpx(update.orientation!!.gpxXml).sourcePoints.first().lon, 0.000001)
    }

    @Test
    fun bufferedLocationsAreReturnedInOrderForReplayWithoutLoss() {
        val coordinator = RouteStartupCoordinator(xml)
        val first = location(20.001000)
        val second = location(20.001080)
        val third = location(20.001250)
        coordinator.accept(first, 0L)
        coordinator.accept(second, 500L)
        val resolved = coordinator.accept(third, 1_000L)

        assertEquals(listOf(first, second, third), resolved.replayLocations)
        val session = GuideSession(RouteModel.fromGpx(resolved.orientation!!.gpxXml))
        val decisions = resolved.replayLocations.map(session::accept)
        assertEquals(3, decisions.size)
        assertTrue(decisions.all { it.result.nextState.lastMatch != null })
    }

    @Test
    fun reversePreservesElevationSamplesAndWaypointNames() {
        val source = """
            <gpx version="1.1"><wpt lat="10.0000" lon="20.0005"><name>쉼터 &amp; 전망대</name></wpt>
              <trk><trkseg>
                <trkpt lat="10.000000" lon="20.000000"><ele>101.5</ele></trkpt>
                <trkpt lat="10.000000" lon="20.001000"><ele>108.0</ele></trkpt>
              </trkseg></trk></gpx>
        """.trimIndent()
        val reversed = RouteModel.fromGpx(RouteOrientation.reverseGpx(source))
        assertEquals(20.001, reversed.sourcePoints.first().lon, 0.000001)
        assertEquals(listOf(108.0, 101.5), reversed.elevationMeters)
        assertEquals(listOf("쉼터 & 전망대"), reversed.waypoints.map { it.name })
    }

    @Test
    fun reversePreservesNaverWaypointsAsStandardWaypoints() {
        val source = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:nmap="https://map.naver.com/gpx/1">
              <metadata><extensions><nmap:walkCourse><nmap:waypoints>
                <nmap:waypoint index="0" lat="10.0000" lon="20.0000"><nmap:type>start</nmap:type><nmap:name>출발지</nmap:name><nmap:desc>start</nmap:desc></nmap:waypoint>
                <nmap:waypoint index="1" lat="10.0000" lon="20.0010"><nmap:type>waypoint</nmap:type><nmap:name>전망대</nmap:name><nmap:desc>view</nmap:desc></nmap:waypoint>
                <nmap:waypoint index="2" lat="10.0000" lon="20.0020"><nmap:type>goal</nmap:type><nmap:name>도착지</nmap:name><nmap:desc>goal</nmap:desc></nmap:waypoint>
              </nmap:waypoints></nmap:walkCourse></extensions></metadata>
              <trk><trkseg>
                <trkpt lat="10.0000" lon="20.0000"><ele>100</ele></trkpt>
                <trkpt lat="10.0000" lon="20.0010"><ele>120</ele></trkpt>
                <trkpt lat="10.0000" lon="20.0020"><ele>140</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val reversed = RouteModel.fromGpx(RouteOrientation.reverseGpx(source))

        assertEquals(listOf("출발지", "전망대", "도착지"), reversed.waypoints.map { it.name })
        assertEquals(listOf(20.0, 20.001, 20.002), reversed.waypoints.map { it.longitude })
        assertEquals(3, reversed.waypoints.size)
        assertEquals(listOf(140.0, 120.0, 100.0), reversed.elevationMeters)
    }

    @Test
    fun preparationStageLabelsAreShortAndExplicit() {
        assertEquals("안내를 준비중", RoutePreparationStage.PREPARING.label)
        assertEquals("시작점 잡힘", RoutePreparationStage.START_FOUND.label)
        assertEquals("경로 방향 잡힘 (안내를 시작합니다)", RoutePreparationStage.DIRECTION_CONFIRMED.label)
    }

    private fun location(longitude: Double): TrailLocation {
        return TrailLocation(
            timestampMillis = 0L,
            latitude = 10.0,
            longitude = longitude,
            accuracyMeters = 5f,
            speedMps = 1f,
            bearingDegrees = null,
            provider = "test",
        )
    }
}
