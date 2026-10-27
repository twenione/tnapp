package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs

/** Shared D-137 canonical replay procedure for local and checked-in GPX routes. */
internal object E5EventCountSupport {
    data class Measurement(
        val route: RouteModel,
        val eventPositionsMeters: List<Double>,
    )

    data class LocalGolden(
        val alias: String,
        val ordinal: Int,
        val sha256Prefix: String,
        val expectedCount: Int,
        val expectedPositionsMeters: List<Double>,
    )

    data class FixtureGolden(
        val path: String,
        val expectedCount: Int,
        val expectedPositionsMeters: List<Double>,
    )

    val canonicalConfig = GuideConfig(
        milestoneEnabled = false,
        elapsedEnabled = false,
        remainingEnabled = false,
        slopeEnabled = false,
        elevationEnabled = true,
        waypointEnabled = false,
        sunsetEnabled = false,
        sunriseEnabled = false,
        elevationBoundaryMeters = 100.0,
        elevationHysteresisMeters = 10.0,
        eventMinIntervalSeconds = 0.0,
        minimumSessionSecondsBeforeArrival = 1_000_000.0,
    )

    fun measure(source: Path, alias: String = source.fileName.toString().take(12)): Measurement {
        val route = RouteModel.fromGpx(Files.readString(source))
        check(!route.isEmpty) { "$alias: parsed route is empty" }
        check(route.elevationUse.used) { "$alias: elevation profile is not usable (${route.elevationUse.reason})" }

        val eventPositions = mutableListOf<Double>()
        var state = GuideState.initial(route)
        var previousMeters = 0.0
        var frameIndex = 0
        while (previousMeters < route.totalLengthMeters) {
            val positionMeters = minOf(frameIndex * STEP_METERS, route.totalLengthMeters)
            val point = pointAt(route, positionMeters)
            val result = guide(
                state,
                SensorFrame(
                    timestamp = (positionMeters * 1_000.0).toLong(),
                    lat = point.lat,
                    lon = point.lon,
                    accuracy = 5f,
                    speed = 1f,
                    gpsBearing = null,
                ),
                canonicalConfig,
            )
            if (result.guidance is Guidance.Elevation) eventPositions += positionMeters
            state = result.nextState
            previousMeters = positionMeters
            frameIndex += 1
            if (frameIndex > route.totalLengthMeters / STEP_METERS + 2.0) {
                error("$alias: E5 replay did not advance through the route")
            }
        }
        return Measurement(route, eventPositions)
    }

    fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("testdata/routes"))) return candidate
            candidate = candidate.parent
        }
        error("Could not find repository testdata/routes from the test working directory")
    }

    fun fixtureRoutes(): List<Path> {
        val routesDir = repositoryRoot().resolve("testdata/routes")
        return Files.list(routesDir).use { stream ->
            stream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("eta_") }
                .map { it.resolve("route.gpx") }
                .filter { Files.isRegularFile(it) }
                .sorted()
                .toList()
        }
    }

    fun formatPositions(positions: List<Double>): String =
        positions.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) }

    fun sha256Prefix(source: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(source))
        .take(8)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }

    fun localGoldens(): List<LocalGolden> {
        val text = goldenText()
        val rows = Regex(
            """\{\s*"alias"\s*:\s*"([^"]+)"\s*,\s*"ordinal"\s*:\s*(\d+)\s*,\s*"gpx_sha256_prefix"\s*:\s*"([0-9a-f]{16})"\s*,\s*"expected_count"\s*:\s*(\d+)\s*,\s*"expected_positions_m"\s*:\s*\[([0-9.,\s]*)]\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(text).map { match ->
            LocalGolden(
                alias = match.groupValues[1],
                ordinal = match.groupValues[2].toInt(),
                sha256Prefix = match.groupValues[3],
                expectedCount = match.groupValues[4].toInt(),
                expectedPositionsMeters = parsePositions(match.groupValues[5]),
            )
        }.toList()
        check(text.contains("\"schema_version\": \"1.0\"")) { "unsupported E5 golden schema" }
        check(rows.size == 7) { "expected seven local E5 goldens, found ${rows.size}" }
        check(rows.map { it.alias to it.ordinal }.toSet().size == rows.size) { "duplicate local E5 golden key" }
        return rows
    }

    fun fixtureGoldens(): List<FixtureGolden> {
        val rows = Regex(
            """\{\s*"path"\s*:\s*"(testdata/routes/eta_[^\"]+/route\.gpx)"\s*,\s*"expected_count"\s*:\s*(\d+)\s*,\s*"expected_positions_m"\s*:\s*\[([0-9.,\s]*)]\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(goldenText()).map { match ->
            FixtureGolden(
                path = match.groupValues[1],
                expectedCount = match.groupValues[2].toInt(),
                expectedPositionsMeters = parsePositions(match.groupValues[3]),
            )
        }.toList()
        check(rows.size == 6) { "expected six fixture E5 goldens, found ${rows.size}" }
        check(rows.map { it.path }.toSet().size == rows.size) { "duplicate fixture E5 golden path" }
        return rows
    }

    fun assertExpectedPositionsRetained(label: String, expected: List<Double>, actual: List<Double>) {
        val tolerance = Regex(""""position_match_tolerance_m"\s*:\s*([0-9.]+)""")
            .find(goldenText())?.groupValues?.get(1)?.toDouble()
            ?: error("E5 golden position tolerance is missing")
        expected.forEachIndexed { index, position ->
            val nearest = actual.minByOrNull { abs(it - position) }
            val deviation = nearest?.let { abs(it - position) }
            check(deviation != null && deviation <= tolerance) {
                "$label: golden E5 position #${index + 1} at ${formatPositions(listOf(position))}m " +
                    "has no actual event within ${tolerance}m; nearest=${nearest?.let { formatPositions(listOf(it)) } ?: "none"}"
            }
        }
    }

    private fun goldenText(): String {
        val override = System.getenv("TRAILNAV_E5_GOLDEN")
        val path = if (override.isNullOrBlank()) {
            repositoryRoot().resolve("testdata/routes/e5_event_count_golden.json")
        } else {
            Path.of(override).toAbsolutePath().normalize()
        }
        check(Files.isRegularFile(path)) { "E5 golden file is missing" }
        return Files.readString(path)
    }

    private fun parsePositions(serialized: String): List<Double> = serialized.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::toDouble)

    fun pointAt(route: RouteModel, distanceMeters: Double): GeoPoint {
        val cumulative = route.cumulativeMeters
        val points = route.sourcePoints
        if (distanceMeters <= cumulative.first()) return points.first()
        if (distanceMeters >= cumulative.last()) return points.last()
        val upper = cumulative.indexOfFirst { it >= distanceMeters }.coerceAtLeast(1)
        val lower = upper - 1
        val span = cumulative[upper] - cumulative[lower]
        val fraction = if (span <= 0.0) 0.0 else (distanceMeters - cumulative[lower]) / span
        return GeoPoint(
            lat = points[lower].lat + (points[upper].lat - points[lower].lat) * fraction,
            lon = points[lower].lon + (points[upper].lon - points[lower].lon) * fraction,
        )
    }

    fun elevationAt(route: RouteModel, distanceMeters: Double): Double {
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

    private const val STEP_METERS = 5.0
}
