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

    private fun profileXml(elevations: List<Int>, waypointIndices: List<Int> = emptyList()): String {
        val waypoints = waypointIndices.joinToString("") { index ->
            "<wpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"><name>W$index</name></wpt>"
        }
        val track = elevations.mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("")
        return "<gpx>$waypoints<trk><trkseg>$track</trkseg></trk></gpx>"
    }

    private fun singlePeakProfile(): List<Int> =
        listOf(100, 100, 100, 130, 160, 200, 230, 250, 250, 250, 250, 250, 220, 200, 180, 180, 180)

    @Test
    fun missingAndPartialElevationUseTheExplicitFallbackReasons() {
        val absent = RouteModel.fromGpx("<gpx><trk><trkseg>${pointXml(0)}${pointXml(1)}</trkseg></trk></gpx>")
        check(!absent.elevationUsed && absent.elevationReason == "absent")

        val partial = RouteModel.fromGpx("<gpx><trk><trkseg>${pointXml(0, "1")}${pointXml(1)}</trkseg></trk></gpx>")
        check(!partial.elevationUsed && partial.elevationReason == "partial")
        check(partial.peaks.isEmpty())
    }

    @Test
    fun confirmedPeakUsesTheSmoothedProfileWithoutChangingRouteGeometry() {
        val route = RouteModel.fromGpx(profileXml(singlePeakProfile()))
        check(route.elevationUse == ElevationUse(true, "ok"))
        check(route.points.size == singlePeakProfile().size)
        check(route.peaks.size == 1)
        val peak = route.peaks.single()
        check(peak.s == route.cumulativeMeters[8])
        check(peak.elevationMeters == 250.0)
        check(peak.climbMeters == 150.0)
    }

    @Test
    fun flatNoiseAndSingleSpikeDoNotCreatePeaks() {
        val noise = "<gpx><trk><trkseg>" +
            listOf(0, 3, -3, 2, -2, 1).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val flat = RouteModel.fromGpx(noise)
        check(flat.elevationUse.reason == "ok")
        check(flat.peaks.isEmpty())

        val spike = "<gpx><trk><trkseg>" +
            listOf(0, 0, 15, 0, 0, 0).mapIndexed { index, elevation -> pointXml(index, elevation.toString()) }.joinToString("") +
            "</trkseg></trk></gpx>"
        val unstable = RouteModel.fromGpx(spike)
        check(unstable.elevationReason == "unstable")
        check(unstable.peaks.isEmpty())
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
    fun naverExtensionWaypointsAreProjectedAndFarPointsAreExcluded() {
        val xml = """
            <gpx xmlns:nmap="https://map.naver.com/gpx/1">
              <extensions><nmap:walkCourse><nmap:waypoints>
                <nmap:waypoint lat="10.00045" lon="20.0"><nmap:name>  출발지   </nmap:name></nmap:waypoint>
                <nmap:waypoint lat="10.003" lon="20.0"><nmap:name>먼 지점</nmap:name></nmap:waypoint>
              </nmap:waypoints></nmap:walkCourse></extensions>
              <trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk>
            </gpx>
        """.trimIndent()
        val route = RouteModel.fromGpx(xml)

        check(route.waypoints.size == 1)
        check(route.waypoints.single().name == "출발지")
        check(route.waypoints.single().s > 0.0)
    }

    @Test
    fun standardAndNaverWaypointsAreBothAccepted() {
        val xml = """
            <gpx xmlns:nmap="https://map.naver.com/gpx/1">
              <wpt lat="10.00045" lon="20.0"><name>표준 지점</name></wpt>
              <extensions><nmap:walkCourse><nmap:waypoints>
                <nmap:waypoint lat="10.0009" lon="20.0"><nmap:name>네이버 지점</nmap:name></nmap:waypoint>
              </nmap:waypoints></nmap:walkCourse></extensions>
              <trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk>
            </gpx>
        """.trimIndent()
        val route = RouteModel.fromGpx(xml)

        check(route.waypoints.map { it.name } == listOf("표준 지점", "네이버 지점"))
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
    fun peakRequiresConfiguredProminence() {
        val elevations = listOf(0, 0, 0) +
            (10..250 step 10).toList() +
            listOf(250, 250, 250, 240, 240, 240, 240, 250) +
            (260..400 step 10).toList() +
            listOf(400, 400) +
            (390 downTo 100 step 10).toList()
        val default = RouteModel.fromGpx(profileXml(elevations))
        val sensitive = RouteModel.fromGpx(profileXml(elevations), GuideConfig(peakProminenceMeters = 5.0))
        check(default.elevationUse.used)
        check(default.peaks.size == 1) { "default peaks=" + default.peaks + " smoothed=" + default.smoothedElevationMeters }
        check(default.peaks.single().elevationMeters == 400.0)
        check(sensitive.peaks.size == 2) { "sensitive peaks=" + sensitive.peaks + " smoothed=" + sensitive.smoothedElevationMeters }
        check(sensitive.peaks.first().elevationMeters == 250.0)
    }

    @Test
    fun peakClimbMetersIsRiseSincePrecedingValley() {
        val peak = RouteModel.fromGpx(profileXml(singlePeakProfile())).peaks.single()
        check(peak.elevationMeters == 250.0)
        check(peak.climbMeters == 150.0)
    }

    @Test
    fun peakWithinDedupeDistanceOfAWaypointIsOmitted() {
        val elevations = singlePeakProfile()
        val base = RouteModel.fromGpx(profileXml(elevations))
        val peakIndex = base.cumulativeMeters.indexOf(base.peaks.single().s)
        check(peakIndex >= 4)
        val within100Meters = RouteModel.fromGpx(profileXml(elevations, listOf(peakIndex - 2)))
        val beyond150Meters = RouteModel.fromGpx(profileXml(elevations, listOf(peakIndex - 4)))
        check(within100Meters.waypoints.single().s.let { kotlin.math.abs(base.peaks.single().s - it) } < 150.0)
        check(within100Meters.peaks.isEmpty())
        check(beyond150Meters.waypoints.single().s.let { kotlin.math.abs(base.peaks.single().s - it) } > 150.0)
        check(beyond150Meters.peaks.single().elevationMeters == 250.0)
    }

    @Test
    fun flatOrMonotonicProfilesProduceNoPeaks() {
        val flat = RouteModel.fromGpx(profileXml(List(8) { 100 }))
        val ascending = RouteModel.fromGpx(profileXml(listOf(0, 10, 20, 30, 40, 50, 60, 70)))
        val descending = RouteModel.fromGpx(profileXml(listOf(70, 60, 50, 40, 30, 20, 10, 0)))
        check(flat.peaks.isEmpty())
        check(ascending.peaks.isEmpty())
        check(descending.peaks.isEmpty())
    }

    @Test
    fun unstableOrAbsentElevationProducesNoPeaks() {
        val absent = RouteModel.fromGpx("<gpx><trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk></gpx>")
        val partial = RouteModel.fromGpx("<gpx><trk><trkseg>${pointXml(0, "100")}${pointXml(1)}${pointXml(2, "110")}</trkseg></trk></gpx>")
        val unstable = RouteModel.fromGpx(profileXml(listOf(0, 0, 15, 0, 0, 0)))
        check(!absent.elevationUse.used && absent.peaks.isEmpty())
        check(!partial.elevationUse.used && partial.peaks.isEmpty())
        check(!unstable.elevationUse.used && unstable.peaks.isEmpty())
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
        check(defaultRoute.peaks.isEmpty())
        check(tolerant.elevationReason == "ok")
    }
}
