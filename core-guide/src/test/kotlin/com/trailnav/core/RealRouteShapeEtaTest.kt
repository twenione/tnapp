package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises ETA behavior against anonymized shape excerpts from owner-provided GPX routes. */
class RealRouteShapeEtaTest {
    private data class Fixture(
        val id: String,
        val expectedLengthMeters: Double,
        val defect: Boolean,
        val correctionSensitive: Boolean = false,
    )
    private data class TargetKey(val kind: TargetKind, val source: String, val roundedTargetMeters: Long)

    private val fixtures = listOf(
        Fixture("eta_gap_steep_1", 297.998023, defect = true),
        Fixture("eta_floor_steep_2", 235.538045, defect = true),
        Fixture("eta_floor_steep_3", 894.877076, defect = true),
        Fixture("eta_cliff_steep_2", 891.314692, defect = true, correctionSensitive = true),
        Fixture("eta_control_uneven_1", 698.859503, defect = false),
        Fixture("eta_control_uneven_2", 700.278026, defect = false),
    )

    private fun repositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.parent
        }
        return requireNotNull(current) { "repository root was not found from the test working directory" }
    }

    private fun route(id: String): RouteModel {
        val routeFile = repositoryRoot().resolve("testdata/routes").resolve(id).resolve("route.gpx")
        assertTrue(Files.isRegularFile(routeFile), "missing route fixture $id")
        return RouteModel.fromGpx(Files.readString(routeFile))
    }

    private fun positions(totalMeters: Double): List<Double> {
        val samples = generateSequence(0.0) { it + 5.0 }.takeWhile { it < totalMeters }.toMutableList()
        if (samples.lastOrNull() != totalMeters) samples += totalMeters
        return samples
    }

    private fun maximumToblerSpeedMetersPerSecond(): Double =
        (0..20_000).maxOf { sample -> toblerSpeedMetersPerSecond(-1.0 + sample * 1e-4) }

    @Test
    fun realRouteEtaPropertiesHoldAtFiveMeterSamples() {
        val failures = mutableListOf<String>()
        val config = GuideConfig()
        val maximumSpeed = maximumToblerSpeedMetersPerSecond()
        for (fixture in fixtures) {
            runCatching {
                val route = route(fixture.id)
                assertTrue(
                    kotlin.math.abs(route.totalLengthMeters - fixture.expectedLengthMeters) <= 0.005,
                    "${fixture.id}: Python clip distance differs from RouteModel.fromGpx",
                )
                assertTrue(route.elevationUse.used, "${fixture.id}: fixture elevation profile was rejected")
                val tracker = RouteEtaTracker(route, config)
                val previousByTarget = mutableMapOf<TargetKey, Double>()
                var maximumEtaSeconds = 0.0
                for (position in positions(route.totalLengthMeters)) {
                    val target = assertNotNull(
                        tracker.estimate(position, ProgressDirection.FORWARD, arrived = false),
                        "${fixture.id}: estimate missing at ${position.toInt()}m",
                    )
                    val seconds = assertNotNull(
                        target.remainingSeconds,
                        "${fixture.id}: ETA unavailable at ${position.toInt()}m; target=${target.source}",
                    )
                    assertTrue(seconds.isFinite(), "${fixture.id}: ETA is non-finite at ${position.toInt()}m: $seconds")
                    assertTrue(seconds <= 86_400.0 + 1e-6, "${fixture.id}: ETA exceeds one day at ${position.toInt()}m: $seconds")
                    maximumEtaSeconds = maxOf(maximumEtaSeconds, seconds)
                    val minimumSeconds = target.remainingMeters / maximumSpeed
                    val maximumSeconds = target.remainingMeters / config.etaMinSegmentSpeedMps
                    assertTrue(
                        seconds + 1e-6 >= minimumSeconds && seconds <= maximumSeconds + 1e-6,
                        "${fixture.id}: ETA outside physical range at ${position.toInt()}m: " +
                            "R=${target.remainingMeters}, ETA=$seconds, range=[$minimumSeconds,$maximumSeconds] " +
                            "(vMax=$maximumSpeed, vMin=${config.etaMinSegmentSpeedMps})",
                    )
                    val key = TargetKey(target.kind, target.source, (position + target.remainingMeters).roundToLong())
                    val previous = previousByTarget[key]
                    if (previous != null) {
                        assertTrue(
                            seconds <= previous + 1e-6,
                            "${fixture.id}: ETA increased for $key at ${position.toInt()}m: $previous -> $seconds",
                        )
                    }
                    previousByTarget[key] = seconds
                }
                val startTarget = assertNotNull(tracker.estimate(0.0, ProgressDirection.FORWARD, arrived = false))
                val startRemainingMeters = startTarget.remainingMeters
                println(
                    "ROUTE_ETA fixture=${fixture.id} start_eta_s=${startTarget.remainingSeconds} " +
                        "max_eta_s=$maximumEtaSeconds physical_range_s=" +
                        "[${startRemainingMeters / maximumSpeed},${startRemainingMeters / config.etaMinSegmentSpeedMps}] " +
                        "v_max_mps=$maximumSpeed v_min_mps=${config.etaMinSegmentSpeedMps}",
                )
            }.onFailure { failure -> failures += failure.message ?: failure::class.simpleName.orEmpty() }
        }

        val floorProbeRoutes = steepWindowRoutes(route("eta_floor_steep_3"), config.etaMinSegmentSpeedMps)
        assertTrue(floorProbeRoutes.isNotEmpty(), "floor counterexample has no segment below etaMinSegmentSpeedMps")
        for ((probeIndex, probeRoute) in floorProbeRoutes.withIndex()) {
            val tracker = RouteEtaTracker(probeRoute, config)
            for (position in positions(probeRoute.totalLengthMeters)) {
                val target = assertNotNull(
                    tracker.estimate(position, ProgressDirection.FORWARD, arrived = false),
                    "floor probe $probeIndex: estimate missing at ${position.toInt()}m",
                )
                val seconds = assertNotNull(
                    target.remainingSeconds,
                    "floor probe $probeIndex: ETA unavailable at ${position.toInt()}m",
                )
                val minimumSeconds = target.remainingMeters / maximumSpeed
                val maximumSeconds = target.remainingMeters / config.etaMinSegmentSpeedMps
                assertTrue(
                    seconds + 1e-6 >= minimumSeconds && seconds <= maximumSeconds + 1e-6,
                    "floor probe $probeIndex: ETA outside physical range at ${position.toInt()}m: " +
                        "R=${target.remainingMeters}, ETA=$seconds, range=[$minimumSeconds,$maximumSeconds] " +
                        "(vMax=$maximumSpeed, vMin=${config.etaMinSegmentSpeedMps})",
                )
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun etaStartGoldenMatches() {
        val goldenPath = repositoryRoot().resolve("testdata/routes/eta_start_eta_golden.json")
        val content = Files.readString(goldenPath)
        assertTrue(content.contains("\"schema_version\": 1"), "ETA golden schema_version must be 1")
        val startEtaSection = content.substringAfter("\"start_eta_seconds\"").substringAfter("{").substringBefore("}")
        val entryPattern = Regex("\"([^\"]+)\"\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)")
        val entries = entryPattern.findAll(startEtaSection).map { match ->
            match.groupValues[1] to match.groupValues[2].toDouble()
        }.toList()
        val goldens = entries.toMap()
        val expectedIds = fixtures.map { it.id }.toSet()
        assertTrue(entries.size == 6 && goldens.keys == expectedIds, "ETA golden fixture keys differ: ${goldens.keys}")

        val config = GuideConfig()
        val failures = mutableListOf<String>()
        for (fixture in fixtures) {
            runCatching {
                val target = assertNotNull(
                    RouteEtaTracker(route(fixture.id), config).estimate(0.0, ProgressDirection.FORWARD, arrived = false),
                    "${fixture.id}: start estimate missing",
                )
                val actual = assertNotNull(target.remainingSeconds, "${fixture.id}: start ETA unavailable")
                val expected = requireNotNull(goldens[fixture.id])
                val changePercent = (actual - expected) / expected * 100.0
                assertTrue(
                    kotlin.math.abs(changePercent) <= 1e-7,
                    "${fixture.id}: golden=$expected actual=$actual change=${changePercent}%",
                )
            }.onFailure { failure -> failures += failure.message ?: failure::class.simpleName.orEmpty() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun defectRoutesDoNotPinObservedCorrectionAtMinimum() {
        val failures = mutableListOf<String>()
        for (fixture in fixtures.filter { it.correctionSensitive }) {
            runCatching {
                val route = route(fixture.id)
                val tracker = RouteEtaTracker(route, GuideConfig(etaMinWindowMeters = 0.0, etaWindowSeconds = 3_600.0))
                var timestampMillis = 0L
                var positionMeters = 0.0
                tracker.observe(timestampMillis, positionMeters, ProgressDirection.FORWARD, onRoute = true)
                while (positionMeters + 5.0 <= route.totalLengthMeters) {
                    positionMeters += 5.0
                    timestampMillis += 3_500L
                    tracker.observe(timestampMillis, positionMeters, ProgressDirection.FORWARD, onRoute = true)
                }
                val target = assertNotNull(tracker.estimate(positionMeters, ProgressDirection.FORWARD, arrived = false))
                assertTrue(target.correctionActive, "${fixture.id}: correction was not active")
                assertTrue(
                    target.correction > 0.5 + 1e-6,
                    "${fixture.id}: correction pinned at minimum 0.5 after 5m/3.5s observations (value=${target.correction})",
                )
            }.onFailure { failure -> failures += failure.message ?: failure::class.simpleName.orEmpty() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun steepWindowsTakenFromRealRouteKeepTheSegmentSpeedFloor() {
        val config = GuideConfig()
        val sourceRoute = route("eta_floor_steep_3")
        val isolatedRoutes = steepWindowRoutes(sourceRoute, config.etaMinSegmentSpeedMps)
        assertTrue(isolatedRoutes.isNotEmpty(), "selected real-route fixture contains no 50 m window below the speed floor")
        for (isolatedRoute in isolatedRoutes) {
            val target = assertNotNull(
                RouteEtaTracker(isolatedRoute, config).estimate(0.0, ProgressDirection.FORWARD, arrived = false),
                "isolated steep segment estimate missing",
            )
            val seconds = assertNotNull(target.remainingSeconds, "isolated steep segment ETA unavailable")
            val speed = target.remainingMeters / seconds
            assertTrue(
                speed + 1e-9 >= config.etaMinSegmentSpeedMps,
                "real route slope window violated the ${config.etaMinSegmentSpeedMps} m/s floor: $speed m/s",
            )
        }
    }

    @Test
    fun clampedRealRouteObservationsDoNotPinCorrectionAtMinimum() {
        val config = GuideConfig(etaMinWindowMeters = 0.0, etaWindowSeconds = 3_600.0)
        val isolatedRoutes = steepWindowRoutes(route("eta_floor_steep_3"), config.etaMinSegmentSpeedMps)
        assertTrue(isolatedRoutes.isNotEmpty(), "selected real-route fixture contains no clamped 50 m window")
        for (isolatedRoute in isolatedRoutes) {
            val tracker = RouteEtaTracker(isolatedRoute, config)
            var timestampMillis = 0L
            var positionMeters = 0.0
            tracker.observe(timestampMillis, positionMeters, ProgressDirection.FORWARD, onRoute = true)
            while (positionMeters + 5.0 <= isolatedRoute.totalLengthMeters) {
                positionMeters += 5.0
                timestampMillis += 3_500L
                tracker.observe(timestampMillis, positionMeters, ProgressDirection.FORWARD, onRoute = true)
            }
            val target = assertNotNull(tracker.estimate(positionMeters, ProgressDirection.FORWARD, arrived = false))
            assertTrue(
                target.correction > 0.5 + 1e-6,
                "clamped route observations pinned correction at 0.5 (value=${target.correction})",
            )
        }
    }

    private fun steepWindowRoutes(
        sourceRoute: RouteModel,
        minimumSegmentSpeedMetersPerSecond: Double,
    ): List<RouteModel> {
        if (!sourceRoute.elevationUse.used) return emptyList()
        val windowMeters = 50.0
        return (0 until sourceRoute.cumulativeMeters.lastIndex).mapNotNull { index ->
            val midpoint = (sourceRoute.cumulativeMeters[index] + sourceRoute.cumulativeMeters[index + 1]) / 2.0
            val start = (midpoint - windowMeters / 2.0).coerceAtLeast(0.0)
            val end = (midpoint + windowMeters / 2.0).coerceAtMost(sourceRoute.totalLengthMeters)
            val span = end - start
            if (span <= 0.0) return@mapNotNull null
            val startElevation = elevationAt(sourceRoute, start)
            val endElevation = elevationAt(sourceRoute, end)
            val slope = (endElevation - startElevation) / span
            if (toblerSpeedMetersPerSecond(slope) >= minimumSegmentSpeedMetersPerSecond) return@mapNotNull null

            val isolatedPoints = listOf(EnuPoint(0.0, 0.0), EnuPoint(span, 0.0))
            RouteModel(
                sourcePoints = listOf(sourceRoute.sourcePoints.first(), sourceRoute.sourcePoints.last()),
                points = isolatedPoints,
                cumulativeMeters = listOf(0.0, span),
                simplifiedPoints = isolatedPoints,
                simplifiedCumulativeMeters = listOf(0.0, span),
                turns = emptyList(),
                spatialIndex = SpatialIndex.build(isolatedPoints, 100.0),
                warnings = emptyList(),
                elevationMeters = listOf(startElevation, endElevation),
                smoothedElevationMeters = listOf(startElevation, endElevation),
                elevationUse = sourceRoute.elevationUse,
            )
        }
    }

    private fun elevationAt(route: RouteModel, distanceMeters: Double): Double {
        val cumulative = route.cumulativeMeters
        val elevations = route.smoothedElevationMeters
        if (distanceMeters <= cumulative.first()) return elevations.first()
        if (distanceMeters >= cumulative.last()) return elevations.last()
        val upper = cumulative.indexOfFirst { it >= distanceMeters }.coerceAtLeast(1)
        val lower = upper - 1
        val span = cumulative[upper] - cumulative[lower]
        val fraction = if (span <= 0.0) 0.0 else (distanceMeters - cumulative[lower]) / span
        return elevations[lower] + (elevations[upper] - elevations[lower]) * fraction
    }

    @Test
    fun normalControlsKeepCorrectionInsideRangeAndAboveMinimum() {
        for (fixture in fixtures.filterNot { it.defect }) {
            val route = route(fixture.id)
            val tracker = RouteEtaTracker(route, GuideConfig(etaMinWindowMeters = 0.0, etaWindowSeconds = 3_600.0))
            var timestampMillis = 0L
            var positionMeters = 0.0
            tracker.observe(timestampMillis, positionMeters, ProgressDirection.FORWARD, onRoute = true)
            while (positionMeters + 5.0 <= route.totalLengthMeters) {
                positionMeters += 5.0
                timestampMillis += 3_500L
                tracker.observe(timestampMillis, positionMeters, ProgressDirection.FORWARD, onRoute = true)
            }
            val target = assertNotNull(tracker.estimate(positionMeters, ProgressDirection.FORWARD, arrived = false))
            assertTrue(target.correctionActive, "${fixture.id}: correction was not active")
            assertTrue(target.correction in 0.5..2.0, "${fixture.id}: correction outside configured range: ${target.correction}")
            assertTrue(target.correction > 0.5 + 1e-6, "${fixture.id}: normal-route correction pinned at floor")
        }
    }
}
