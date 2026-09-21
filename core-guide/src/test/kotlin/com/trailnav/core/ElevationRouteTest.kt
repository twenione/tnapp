package com.trailnav.core

import kotlin.math.cos
import kotlin.test.Test

/** Deterministic GPX preprocessing tests for the Phase 3 B route model. */
class ElevationRouteTest {
    private fun pointXml(index: Int, elevation: String? = null): String {
        val lat = 10.0 + index * 0.00045
        return if (elevation == null) {
            "<trkpt lat=\"$lat\" lon=\"20.0\"/>"
        } else {
            "<trkpt lat=\"$lat\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
        }
    }

    @Test
    fun missingAndPartialElevationUseTheExplicitFallbackReasons() {
        val absent = RouteModel.fromGpx("<gpx><trk><trkseg>${pointXml(0)}${pointXml(1)}</trkseg></trk></gpx>")
        check(!absent.elevationUsed && absent.elevationReason == "absent")

        val partial = RouteModel.fromGpx("<gpx><trk><trkseg>${pointXml(0, "1")}${pointXml(1)}</trkseg></trk></gpx>")
        check(!partial.elevationUsed && partial.elevationReason == "partial")
        check(partial.slopeSegments.isEmpty())
    }

    @Test
    fun smoothProfileProducesAnAscentSegmentWithoutChangingRouteGeometry() {
        val xml = "<gpx><trk><trkseg>" +
            listOf(0, 7, 15, 23, 30).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val route = RouteModel.fromGpx(xml)
        check(route.elevationUse == ElevationUse(true, "ok"))
        check(route.points.size == 5)
        check(route.slopeSegments.any { it.kind == SlopeKind.ASCENT && it.deltaMeters >= 20.0 })
    }

    @Test
    fun noiseAndSingleSpikeDoNotCreateSlopeSegments() {
        val noise = "<gpx><trk><trkseg>" +
            listOf(0, 3, -3, 2, -2, 1).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val flat = RouteModel.fromGpx(noise)
        check(flat.elevationUse.reason == "ok")
        check(flat.slopeSegments.isEmpty())

        val spike = "<gpx><trk><trkseg>" +
            listOf(0, 0, 15, 0, 0, 0).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val unstable = RouteModel.fromGpx(spike)
        check(unstable.elevationReason == "unstable")
        check(unstable.slopeSegments.isEmpty())
    }

    @Test
    fun waypointIsSanitizedProjectedAndNeverBecomesRouteGeometry() {
        val rawName = "  Summit\t   View  "
        val xml = "<gpx><wpt lat=\"10.00045\" lon=\"20.0\"><name>$rawName</name></wpt>" +
            "<wpt lat=\"10.003\" lon=\"20.0\"><name>far away</name></wpt>" +
            "<trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk></gpx>"
        val route = RouteModel.fromGpx(xml, GuideConfig(waypointNameMaxLength = 20))
        check(route.points.size == 3)
        check(route.cumulativeMeters.size == 3)
        check(route.waypoints.size == 1)
        check(route.waypoints.single().name == "Summit View")
        check(route.waypoints.single().s > 0.0)
    }

    @Test
    fun waypointAtRouteEndAndDuplicateNamesRemainStable() {
        val xml = """
            <gpx><trk><trkseg>${pointXml(0)}${pointXml(1)}</trkseg></trk>
              <wpt lat="10.00045" lon="20.0"><name>End</name></wpt>
              <wpt lat="10.00045" lon="20.0"><name>End</name></wpt>
              <wpt lat="10.00045" lon="20.0006"><name>no-name</name></wpt>
            </gpx>
        """.trimIndent()
        val route = RouteModel.fromGpx(xml)
        check(route.waypoints.size == 2)
        check(route.waypoints.all { it.s >= 0.0 && it.s <= route.totalLengthMeters })
    }

    @Test
    fun elevationAlignmentFollowsTheOneMetrePointFilter() {
        val xml = "<gpx><trk><trkseg>" +
            "<trkpt lat=\"10.0\" lon=\"20.0\"><ele>100</ele></trkpt>" +
            "<trkpt lat=\"10.0000001\" lon=\"20.0\"><ele>999</ele></trkpt>" +
            "<trkpt lat=\"10.001\" lon=\"20.0\"><ele>110</ele></trkpt>" +
            "</trkseg></trk></gpx>"
        val route = RouteModel.fromGpx(xml)
        check(route.points.size == 2)
        check(route.elevationMeters == listOf(100.0, 110.0))
    }
}
