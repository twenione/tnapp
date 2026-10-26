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
        check(peak.s in route.cumulativeMeters[7]..route.cumulativeMeters[8])
        check(kotlin.math.abs(peak.s / GuideConfig().elevationGridMeters -
            (peak.s / GuideConfig().elevationGridMeters).toInt()) < 1e-9)
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

        val spike = distanceProfileXml(
            List(7) { it * 5.0 },
            List(7) { if (it == 3) 120.0 else 100.0 },
        )
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
        val peakDistance = base.peaks.single().s
        check(peakDistance >= base.cumulativeMeters[4])
        val within100Meters = RouteModel.fromGpx(profileXml(elevations, listOf(7)))
        val beyond150Meters = RouteModel.fromGpx(profileXml(elevations, listOf(4)))
        check(within100Meters.waypoints.single().s.let { kotlin.math.abs(peakDistance - it) } < 150.0)
        check(within100Meters.peaks.isEmpty())
        check(beyond150Meters.waypoints.single().s.let { kotlin.math.abs(peakDistance - it) } > 150.0)
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
        val unstable = RouteModel.fromGpx(
            distanceProfileXml(List(7) { it * 5.0 }, List(7) { if (it == 3) 120.0 else 100.0 }),
        )
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
        val xml = distanceProfileXml(
            List(7) { it * 5.0 },
            List(7) { if (it == 3) 120.0 else 100.0 },
        )
        val defaultRoute = RouteModel.fromGpx(xml)
        val tolerant = RouteModel.fromGpx(xml, GuideConfig(elevationSpikeThresholdMeters = 20.0))
        check(defaultRoute.elevationReason == "unstable")
        check(defaultRoute.peaks.isEmpty())
        check(tolerant.elevationReason == "ok")
    }

    @Test
    fun twoSpikesInA200PointSlopedProfileAreInterpolatedLocally() {
        val elevations = List(200) { index -> 100.0 + index * 0.1 }.toMutableList()
        elevations[80] += 16.0
        elevations[81] += 16.0
        val route = RouteModel.fromGpx(distanceProfileXml(List(200) { it * 5.0 }, elevations))
        val expected = 100.0 + 80 * 0.1

        check(route.elevationUse == ElevationUse(true, "ok"))
        check(route.elevationRepair.spikePoints == 2)
        check(route.elevationRepair.interpolatedPoints == 2)
        check(route.elevationRepair.totalPoints == 200)
        check(route.elevationRepair.maxResidualMeters in 15.0..17.0)
        check(route.elevationRepair.atMeters == listOf(route.cumulativeMeters[80], route.cumulativeMeters[81]))
        check(kotlin.math.abs(route.smoothedElevationMeters[80] - expected) <= 1e-6)
        check(kotlin.math.abs(route.smoothedElevationMeters[81] - (expected + 0.1)) <= 1e-6)
        val target = RouteEtaTracker(route).estimate(0.0, ProgressDirection.FORWARD, arrived = false)
        check(target?.slopeSource == "tobler")
    }

    @Test
    fun distanceWeightedInterpolationUsesCumulativeMetersNotIndexMidpoint() {
        val distances = listOf(0.0, 190.0, 200.0, 205.0, 500.0, 1_000.0, 2_000.0)
        val elevations = distances.map { 100.0 + it * 0.01 }.toMutableList().also { it[2] += 30.0 }
        val route = RouteModel.fromGpx(
            distanceProfileXml(distances, elevations),
            GuideConfig(elevationSpikeMaxRatio = 0.15),
        )

        check(route.elevationUse == ElevationUse(true, "ok"))
        check(route.elevationRepair.spikePoints == 2)
        check(route.elevationRepair.interpolatedPoints == 2)
        check(route.elevationRepair.atMeters.zip(listOf(195.0, 200.0)).all { (actual, expected) ->
            kotlin.math.abs(actual - expected) <= 0.01
        })
        check(kotlin.math.abs(route.smoothedElevationMeters[2] - 102.0) < 0.1)
    }

    @Test
    fun spikeRatioBoundaryAndConfigControl() {
        fun routeWithSpikes(count: Int, config: GuideConfig = GuideConfig()): RouteModel {
            val elevations = MutableList(101) { 100.0 }
            listOf(20, 40, 60).take(count).forEach { index -> elevations[index] += 20.0 }
            return RouteModel.fromGpx(distanceProfileXml(List(101) { it * 5.0 }, elevations), config)
        }

        val atBoundary = routeWithSpikes(2)
        check(atBoundary.elevationUse == ElevationUse(true, "ok"))
        check(atBoundary.elevationRepair.spikePoints == 2)
        check(atBoundary.elevationRepair.interpolatedPoints == 2)

        val aboveBoundary = routeWithSpikes(3)
        check(aboveBoundary.elevationUse == ElevationUse(false, "unstable"))
        check(aboveBoundary.elevationRepair.spikePoints == 3)
        check(aboveBoundary.elevationRepair.interpolatedPoints == 0)

        val increasedCap = routeWithSpikes(3, GuideConfig(elevationSpikeMaxRatio = 0.05))
        check(increasedCap.elevationUse == ElevationUse(true, "ok"))
        check(increasedCap.elevationRepair.interpolatedPoints == 3)
        check(runCatching { GuideConfig(elevationSpikeMaxRatio = -0.01) }.isFailure)
        check(runCatching { GuideConfig(elevationSpikeMaxRatio = 1.01) }.isFailure)
    }

    @Test
    fun endpointSpikesAreNeverInterpolated() {
        val elevations = MutableList(200) { 100.0 }
        elevations[0] += 20.0
        elevations[199] -= 20.0
        val route = RouteModel.fromGpx(distanceProfileXml(List(200) { it * 5.0 }, elevations))

        check(route.elevationUse == ElevationUse(false, "unstable"))
        check(route.elevationRepair.spikePoints == 2)
        check(route.elevationRepair.interpolatedPoints == 0)
        check(route.elevationRepair.atMeters == listOf(route.cumulativeMeters.first(), route.cumulativeMeters.last()))
    }

    @Test
    fun postInterpolationResidualCanKeepTheProfileUnstable() {
        val distances = List(101) { it * 5.0 }
        val elevations = distances.indices.map { index ->
            100.0 - index + (if (index >= 2) -20.0 else 0.0) + (if (index == 6) -20.0 else 0.0)
        }
        val route = RouteModel.fromGpx(
            distanceProfileXml(distances, elevations),
            GuideConfig(minimumPointSpacingMeters = 0.0),
        )

        check(route.elevationUse == ElevationUse(false, "unstable"))
        check(route.elevationRepair.spikePoints == 2)
        check(route.elevationRepair.interpolatedPoints == 2)
        check(route.elevationRepair.atMeters.zip(listOf(5.0, 30.0)).all { (actual, expected) ->
            kotlin.math.abs(actual - expected) <= 0.01
        })
    }

    @Test
    fun noSpikeProfileKeepsTheOriginalSmoothedValues() {
        val elevations = List(40) { index -> 100.0 + index * 0.5 }
        val route = RouteModel.fromGpx(distanceProfileXml(List(40) { it * 5.0 }, elevations))

        check(route.elevationUse == ElevationUse(true, "ok"))
        val expected = legacySmoothedProfile(elevations)
        check(route.smoothedElevationMeters == expected)
        check(route.elevationRepair.spikePoints == 0)
        check(route.elevationRepair.interpolatedPoints == 0)
        check(route.elevationRepair.totalPoints == 40)
        check(route.elevationRepair.atMeters.isEmpty())
    }

    private fun distanceProfileXml(distances: List<Double>, elevations: List<Double>): String {
        require(distances.size == elevations.size)
        val points = distances.zip(elevations).joinToString("") { (distance, elevation) ->
            val longitude = Math.toDegrees(distance / 6_371_008.8)
            "<trkpt lat=\"0.0\" lon=\"$longitude\"><ele>$elevation</ele></trkpt>"
        }
        return "<gpx><trk><trkseg>$points</trkseg></trk></gpx>"
    }

    private fun legacySmoothedProfile(raw: List<Double>): List<Double> {
        val median = raw.indices.map { index ->
            val from = (index - 2).coerceAtLeast(0)
            val to = (index + 2).coerceAtMost(raw.lastIndex)
            raw.subList(from, to + 1).sorted()[(to - from) / 2]
        }
        return median.indices.map { index ->
            if (index == 0 || index == median.lastIndex) raw[index]
            else {
                val from = (index - 1).coerceAtLeast(0)
                val to = (index + 1).coerceAtMost(median.lastIndex)
                median.subList(from, to + 1).average()
            }
        }
    }
}
