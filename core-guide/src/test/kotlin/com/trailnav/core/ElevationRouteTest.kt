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

    @Test
    fun slopeProfileUsesLookahead() {
        val xml = "<gpx><trk><trkseg>" +
            listOf(0, 7, 15, 23, 30, 38, 46).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val short = RouteModel.fromGpx(xml, GuideConfig(slopeLookaheadMeters = 120.0))
        val long = RouteModel.fromGpx(xml, GuideConfig(slopeLookaheadMeters = 400.0))
        check(long.slopeSegments.firstOrNull()?.endS ?: 0.0 > (short.slopeSegments.firstOrNull()?.endS ?: 0.0))
    }

    @Test
    fun slopeProfileUsesThreshold() {
        val xml = "<gpx><trk><trkseg>" +
            listOf(0, 7, 15, 23, 30).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val defaultRoute = RouteModel.fromGpx(xml)
        val highThreshold = RouteModel.fromGpx(xml, GuideConfig(slopeMinDeltaMeters = 100.0))
        check(defaultRoute.slopeSegments.isNotEmpty())
        check(highThreshold.slopeSegments.isEmpty())
    }

    @Test
    fun slopeProfileUsesHysteresis() {
        val xml = "<gpx><trk><trkseg>" +
            listOf(0, 5, 5, 5, 24, 24).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val hysteresis = RouteModel.fromGpx(xml, GuideConfig(slopeHysteresisMeters = 30.0, slopeLookaheadMeters = 400.0, slopeMinDeltaMeters = 25.0))
        val noHysteresis = RouteModel.fromGpx(xml, GuideConfig(slopeHysteresisMeters = 0.0, slopeLookaheadMeters = 400.0, slopeMinDeltaMeters = 1.0))
        check(hysteresis.slopeSegments.isEmpty())
        check(noHysteresis.slopeSegments.isNotEmpty())
    }

    @Test
    fun waypointNearRouteFilter() {
        val xml = "<gpx><wpt lat=\"10.002\" lon=\"20.001\"><name>far</name></wpt>" +
            "<trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk></gpx>"
        val excluded = RouteModel.fromGpx(xml)
        val included = RouteModel.fromGpx(xml, GuideConfig(waypointNearRouteMeters = 200.0))
        check(excluded.waypoints.isEmpty())
        check(included.waypoints.size == 1)
    }

    @Test
    fun waypointNameIsSanitized() {
        val xml = "<gpx><wpt lat=\"10.00045\" lon=\"20.0\"><name>  Summit\t View  </name></wpt>" +
            "<trk><trkseg>${pointXml(0)}${pointXml(1)}</trkseg></trk></gpx>"
        val route = RouteModel.fromGpx(xml, GuideConfig(waypointNameMaxLength = 6))
        check(route.waypoints.single().name == "Summit")
    }

    @Test
    fun spikeIsUnstable() {
        val xml = "<gpx><trk><trkseg>" +
            listOf(0, 0, 15, 0, 0, 0).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val defaultRoute = RouteModel.fromGpx(xml)
        val tolerant = RouteModel.fromGpx(xml, GuideConfig(elevationSpikeThresholdMeters = 20.0))
        check(defaultRoute.elevationReason == "unstable")
        check(tolerant.elevationReason == "ok")
    }
}
