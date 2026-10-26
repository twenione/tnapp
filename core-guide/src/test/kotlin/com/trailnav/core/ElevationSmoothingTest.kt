package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test

class ElevationSmoothingTest {
    @Test
    fun smoothingIsInvariantToPointSpacing() {
        val config = GuideConfig(minimumPointSpacingMeters = 0.0)
        val unevenDistances = listOf(
            0.0, 0.6, 80.6, 81.2, 160.6, 161.2, 200.0, 200.6, 201.2, 350.0, 450.0, 450.6, 451.2, 540.0, 600.0,
        )
        val unevenElevations = unevenDistances.map { syntheticTerrain(it / 600.0) }
        assertSpacingInvariant(
            "synthetic",
            profileXml(unevenDistances, unevenElevations),
            profileXml(densify(distanceSamples(unevenDistances, unevenElevations), unevenDistances, 5.0)),
            config,
        )

        val root = repositoryRoot()
        for (fixture in listOf("eta_control_uneven_1", "eta_gap_steep_1")) {
            val fixtureRoute = RouteModel.fromGpx(
                Files.readString(root.resolve("testdata/routes/$fixture/route.gpx")),
                config,
            )
            check(fixtureRoute.sourcePoints.size >= 2) { "$fixture: expected a real-route geometry fixture" }
            val fixtureElevations = fixtureRoute.cumulativeMeters.map { distance ->
                syntheticTerrain(distance / fixtureRoute.totalLengthMeters)
            }
            val sparseSamples = fixtureRoute.sourcePoints.zip(fixtureElevations).map { (point, elevation) ->
                ProfilePoint(point, elevation)
            }
            val denseSamples = densify(sparseSamples, fixtureRoute.cumulativeMeters, 5.0)
            assertSpacingInvariant(
                fixture,
                profileXml(sparseSamples),
                profileXml(denseSamples),
                config,
            )
        }
    }

    @Test
    fun peakOnSparseRouteIsNotLost() {
        val route = RouteModel.fromGpx(
            profileXml(
                distances = listOf(0.0, 102.0, 204.0),
                elevations = listOf(100.0, 150.0, 100.0),
            ),
        )

        check(route.peaks.size == 1) {
            "expected the 50 m summit at 102 m to survive distance smoothing; " +
                "use=${route.elevationUse}, peaks=${route.peaks}, profile=${route.smoothedElevationMeters}"
        }
        check(route.elevationUse == ElevationUse(true, "ok"))
        check(route.peaks.single().climbMeters >= GuideConfig().peakProminenceMeters)
        val gridPosition = route.peaks.single().s / GuideConfig().elevationGridMeters
        check(abs(gridPosition - gridPosition.roundToInt()) <= 1e-6) {
            "peak must be extracted from the distance grid, got s=${route.peaks.single().s}"
        }
    }

    @Test
    fun spikeJudgmentDoesNotDependOnSampling() {
        val distances = listOf(0.0, 1495.0, 1500.0, 1505.0, 3000.0)
        val elevations = listOf(100.0, 100.0, 120.0, 100.0, 100.0)
        val sparse = RouteModel.fromGpx(profileXml(distances, elevations))
        val dense = RouteModel.fromGpx(
            profileXml(densify(distanceSamples(distances, elevations), distances, 5.0)),
        )

        check(sparse.elevationUse == ElevationUse(true, "ok"))
        check(dense.elevationUse == sparse.elevationUse)
        check(sparse.elevationRepair.spikePoints == 1)
        check(dense.elevationRepair.spikePoints == sparse.elevationRepair.spikePoints)
        check(sparse.elevationRepair.interpolatedPoints == 1)
        check(dense.elevationRepair.interpolatedPoints == sparse.elevationRepair.interpolatedPoints)
        check(abs(sparse.elevationRepair.atMeters.single() - dense.elevationRepair.atMeters.single()) <= 1e-6)
    }

    @Test
    fun gridSpacingControlsSampleCount() {
        val xml = profileXml(listOf(0.0, 101.0), listOf(100.0, 120.0))
        val fine = RouteModel.fromGpx(xml, GuideConfig(elevationGridMeters = 5.0, elevationSpikeThresholdMeters = 1_000.0))
        val coarse = RouteModel.fromGpx(xml, GuideConfig(elevationGridMeters = 10.0, elevationSpikeThresholdMeters = 1_000.0))

        check(fine.points.size == coarse.points.size)
        check(fine.elevationRepair.totalPoints > coarse.elevationRepair.totalPoints)
        check(fine.elevationRepair.totalPoints != coarse.elevationRepair.totalPoints)
        check(runCatching { GuideConfig(elevationGridMeters = 0.0) }.isFailure)
        check(runCatching { GuideConfig(elevationGridMeters = -1.0) }.isFailure)
    }

    @Test
    fun medianWindowControlsSpikeRemoval() {
        val distances = listOf(0.0, 490.0, 495.0, 500.0, 505.0, 510.0, 515.0, 1_000.0)
        val elevations = listOf(100.0, 100.0, 100.0, 120.0, 120.0, 100.0, 100.0, 100.0)
        val narrow = RouteModel.fromGpx(
            profileXml(distances, elevations),
            GuideConfig(elevationMedianHalfWindowMeters = 5.0),
        )
        val wide = RouteModel.fromGpx(
            profileXml(distances, elevations),
            GuideConfig(elevationMedianHalfWindowMeters = 10.0),
        )

        check(narrow.elevationRepair.spikePoints == 0) { "5 m median window marked ${narrow.elevationRepair.spikePoints} spikes" }
        check(wide.elevationRepair.spikePoints == 2) { "10 m median window marked ${wide.elevationRepair.spikePoints} spikes" }
        check(wide.elevationRepair.interpolatedPoints == 2)
        check(runCatching { GuideConfig(elevationMedianHalfWindowMeters = 0.0) }.isFailure)
        check(runCatching { GuideConfig(elevationMedianHalfWindowMeters = -1.0) }.isFailure)
    }

    @Test
    fun meanWindowControlsSmoothing() {
        val distances = (0..8).map { it * 5.0 }
        val elevations = listOf(100.0, 100.0, 100.0, 140.0, 100.0, 100.0, 100.0, 100.0, 100.0)
        val narrow = RouteModel.fromGpx(
            profileXml(distances, elevations),
            GuideConfig(
                elevationMedianHalfWindowMeters = 1.0,
                elevationMeanHalfWindowMeters = 5.0,
                elevationSpikeThresholdMeters = 1_000.0,
            ),
        )
        val wide = RouteModel.fromGpx(
            profileXml(distances, elevations),
            GuideConfig(
                elevationMedianHalfWindowMeters = 1.0,
                elevationMeanHalfWindowMeters = 10.0,
                elevationSpikeThresholdMeters = 1_000.0,
            ),
        )

        check(narrow.smoothedElevationMeters[3] != wide.smoothedElevationMeters[3])
        check(narrow.smoothedElevationMeters[3] > wide.smoothedElevationMeters[3])
        check(runCatching { GuideConfig(elevationMeanHalfWindowMeters = 0.0) }.isFailure)
        check(runCatching { GuideConfig(elevationMeanHalfWindowMeters = -1.0) }.isFailure)
    }

    @Test
    fun endpointsAreNotSmoothed() {
        val distances = (0..8).map { it * 5.0 }
        val elevations = distances.map { 100.0 + it * 2.0 }
        val route = RouteModel.fromGpx(
            profileXml(distances, elevations),
            GuideConfig(elevationSpikeThresholdMeters = 1_000.0),
        )

        check(route.smoothedElevationMeters.first() == elevations.first())
        check(route.smoothedElevationMeters.last() == elevations.last())
    }

    @Test
    fun fewerThanFiveGridSamplesKeepsRawElevation() {
        val distances = listOf(0.0, 4.0, 8.0, 14.0)
        val elevations = listOf(100.0, 140.0, 100.0, 160.0)
        val route = RouteModel.fromGpx(profileXml(distances, elevations))

        check(route.elevationRepair.totalPoints == 4)
        check(route.elevationRepair.spikePoints == 0)
        check(route.elevationRepair.interpolatedPoints == 0)
        check(route.elevationUse == ElevationUse(true, "ok"))
        check(route.smoothedElevationMeters == elevations)
    }

    @Test
    fun smoothedProfileHasOnePointPerRoutePoint() {
        val route = RouteModel.fromGpx(
            profileXml(
                listOf(0.0, 22.0, 47.0, 90.0),
                listOf(100.0, 130.0, 120.0, 150.0),
            ),
            GuideConfig(elevationSpikeThresholdMeters = 1_000.0),
        )

        check(route.points.size == 4)
        check(route.smoothedElevationMeters.size == route.points.size)
        check(route.smoothedElevationMeters.size != route.elevationRepair.totalPoints)
    }

    @Test
    fun spikeAtEdgeOrAboveRatioIsUnstable() {
        val distances = (0..100).map { it * 5.0 } + 503.0
        fun profile(spikeDistances: Set<Double>): String = profileXml(
            distances,
            distances.map { if (it in spikeDistances) 120.0 else 100.0 },
        )

        val edge = RouteModel.fromGpx(profile(setOf(0.0)))
        check(edge.elevationUse == ElevationUse(false, "unstable"))
        check(edge.elevationRepair.spikePoints == 1)
        check(edge.elevationRepair.interpolatedPoints == 0)

        val atRatioBoundary = RouteModel.fromGpx(
            profile(setOf(100.0, 200.0)),
            GuideConfig(elevationSpikeMaxRatio = 2.0 / 102.0),
        )
        check(atRatioBoundary.elevationRepair.totalPoints == 102)
        check(atRatioBoundary.elevationRepair.spikePoints == 2)
        check(atRatioBoundary.elevationUse == ElevationUse(true, "ok"))
        check(atRatioBoundary.elevationRepair.interpolatedPoints == 2)

        val aboveRatioBoundary = RouteModel.fromGpx(
            profile(setOf(100.0, 200.0, 300.0)),
            GuideConfig(elevationSpikeMaxRatio = Math.nextDown(3.0 / 102.0)),
        )
        check(aboveRatioBoundary.elevationRepair.totalPoints == 102)
        check(aboveRatioBoundary.elevationRepair.spikePoints == 3)
        check(aboveRatioBoundary.elevationUse == ElevationUse(false, "unstable"))
        check(aboveRatioBoundary.elevationRepair.interpolatedPoints == 0)
    }

    @Test
    fun ratioDenominatorIsGridSamplesNotPoints() {
        val distances = listOf(0.0, 195.0, 200.0, 205.0, 495.0, 500.0, 505.0, 795.0, 800.0, 805.0, 1_000.0)
        val elevations = listOf(100.0, 100.0, 120.0, 100.0, 100.0, 120.0, 100.0, 100.0, 120.0, 100.0, 100.0)
        val fine = RouteModel.fromGpx(
            profileXml(distances, elevations),
            GuideConfig(elevationGridMeters = 5.0),
        )
        val coarse = RouteModel.fromGpx(
            profileXml(distances, elevations),
            GuideConfig(elevationGridMeters = 10.0),
        )

        check(fine.points.size == coarse.points.size)
        check(fine.elevationRepair.spikePoints == 3)
        check(coarse.elevationRepair.spikePoints == 3)
        check(fine.elevationRepair.totalPoints == 201)
        check(coarse.elevationRepair.totalPoints == 101)
        check(fine.elevationUse == ElevationUse(true, "ok"))
        check(coarse.elevationUse == ElevationUse(false, "unstable"))
    }

    private fun assertSpacingInvariant(label: String, sparseXml: String, denseXml: String, config: GuideConfig) {
        val sparse = RouteModel.fromGpx(sparseXml, config)
        val dense = RouteModel.fromGpx(denseXml, config)
        check(sparse.elevationUse == ElevationUse(true, "ok")) {
            "$label sparse: ${sparse.elevationUse}, repair=${sparse.elevationRepair}"
        }
        check(dense.elevationUse == sparse.elevationUse) { "$label dense: ${dense.elevationUse}" }
        check(sparse.peaks.size == dense.peaks.size) {
            "$label peak counts differ: sparse=${sparse.peaks}, dense=${dense.peaks}"
        }
        check(sparse.peaks.zip(dense.peaks).all { (first, second) -> abs(first.s - second.s) <= 25.0 }) {
            "$label peak positions differ by more than 25 m: sparse=${sparse.peaks}, dense=${dense.peaks}"
        }
        check(sparse.elevationRepair.spikePoints == dense.elevationRepair.spikePoints) {
            "$label spike counts differ: sparse=${sparse.elevationRepair}, dense=${dense.elevationRepair}"
        }
        val sparseEvents = elevationEvents(sparse).size
        val denseEvents = elevationEvents(dense).size
        check(sparseEvents > 0) { "$label did not exercise an E5 threshold crossing" }
        check(sparseEvents == denseEvents) { "$label E5 event counts differ: sparse=$sparseEvents, dense=$denseEvents" }
    }

    private data class ElevationEvent(val s: Double, val boundaryMeters: Double, val direction: String)

    private fun elevationEvents(route: RouteModel): List<ElevationEvent> {
        val config = GuideConfig(
            elevationEnabled = true,
            eventMinIntervalSeconds = 0.0,
            minimumSessionSecondsBeforeArrival = 1_000_000_000.0,
            sunsetEnabled = false,
            sunriseEnabled = false,
        )
        val stepMeters = 10.0
        val lastStep = ceil(route.totalLengthMeters / stepMeters).toInt()
        var state = GuideState.initial(route)
        val events = mutableListOf<ElevationEvent>()
        for (step in 0..lastStep) {
            val distance = (step * stepMeters).coerceAtMost(route.totalLengthMeters)
            val point = pointAt(route, distance)
            val result = guide(
                state,
                SensorFrame(
                    timestamp = step * 10_000L,
                    lat = point.lat,
                    lon = point.lon,
                    accuracy = 3f,
                    speed = 1f,
                    gpsBearing = null,
                ),
                config,
            )
            if (result.guidance is Guidance.Elevation) {
                events += ElevationEvent(
                    s = distance,
                    boundaryMeters = result.reason.details.getValue("boundaryMeters").toDouble(),
                    direction = result.reason.details.getValue("direction"),
                )
            }
            state = result.nextState
        }
        return events
    }

    private fun pointAt(route: RouteModel, distanceMeters: Double): GeoPoint {
        if (route.sourcePoints.size == 1) return route.sourcePoints.single()
        val distance = distanceMeters.coerceIn(0.0, route.totalLengthMeters)
        val index = route.cumulativeMeters.binarySearch(distance).let { found ->
            if (found >= 0) found.coerceAtMost(route.sourcePoints.lastIndex - 1)
            else (-found - 2).coerceIn(0, route.sourcePoints.lastIndex - 1)
        }
        val span = route.cumulativeMeters[index + 1] - route.cumulativeMeters[index]
        val ratio = if (span <= 0.0) 0.0 else (distance - route.cumulativeMeters[index]) / span
        val first = route.sourcePoints[index]
        val second = route.sourcePoints[index + 1]
        return GeoPoint(
            lat = first.lat + (second.lat - first.lat) * ratio,
            lon = first.lon + (second.lon - first.lon) * ratio,
        )
    }

    private data class ProfilePoint(val point: GeoPoint, val elevation: Double)

    private fun distanceSamples(distances: List<Double>, elevations: List<Double>): List<ProfilePoint> {
        require(distances.size == elevations.size)
        return distances.zip(elevations).map { (distance, elevation) ->
            ProfilePoint(GeoPoint(0.0, Math.toDegrees(distance / EARTH_RADIUS_METERS)), elevation)
        }
    }

    private fun densify(
        samples: List<ProfilePoint>,
        cumulativeMeters: List<Double>,
        maximumSpacingMeters: Double,
    ): List<ProfilePoint> {
        require(samples.size == cumulativeMeters.size)
        if (samples.size < 2) return samples
        val dense = mutableListOf<ProfilePoint>()
        for (index in 0 until samples.lastIndex) {
            val first = samples[index]
            val second = samples[index + 1]
            val span = cumulativeMeters[index + 1] - cumulativeMeters[index]
            val intervals = ceil(span / maximumSpacingMeters).toInt().coerceAtLeast(1)
            for (part in 0 until intervals) {
                val ratio = part.toDouble() / intervals
                dense += ProfilePoint(
                    point = GeoPoint(
                        first.point.lat + (second.point.lat - first.point.lat) * ratio,
                        first.point.lon + (second.point.lon - first.point.lon) * ratio,
                    ),
                    elevation = first.elevation + (second.elevation - first.elevation) * ratio,
                )
            }
        }
        dense += samples.last()
        return dense
    }

    private fun profileXml(distances: List<Double>, elevations: List<Double>): String =
        profileXml(distanceSamples(distances, elevations))

    private fun profileXml(samples: List<ProfilePoint>): String {
        val points = samples.joinToString("") { sample ->
            "<trkpt lat=\"${sample.point.lat}\" lon=\"${sample.point.lon}\"><ele>${sample.elevation}</ele></trkpt>"
        }
        return "<gpx><trk><trkseg>$points</trkseg></trk></gpx>"
    }

    private fun syntheticTerrain(fraction: Double): Double {
        val knots = listOf(
            0.0 to 80.0,
            0.15 to 80.0,
            0.35 to 150.0,
            0.55 to 80.0,
            0.70 to 80.0,
            0.85 to 160.0,
            1.0 to 80.0,
        )
        val u = fraction.coerceIn(0.0, 1.0)
        val index = (0 until knots.lastIndex).first { u <= knots[it + 1].first }
        val (startX, startY) = knots[index]
        val (endX, endY) = knots[index + 1]
        val ratio = (u - startX) / (endX - startX)
        return startY + (endY - startY) * ratio
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.isDirectory(candidate.resolve("testdata/routes"))) return candidate
            candidate = candidate.parent ?: error("repository root with testdata/routes was not found")
        }
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
