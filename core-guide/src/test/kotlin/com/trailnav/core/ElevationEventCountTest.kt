package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Canonical E5 event-count replay over the seven local routes, when supplied by the operator. */
class ElevationEventCountTest {
    private data class EventAt(val routeMeters: Double)
    private val kuksaAlias = "청계_매봉_국사봉_바라"

    @Test
    fun localRouteE5EventsUseActualEngineFrames() {
        val configured = System.getenv("TRAILNAV_LOCAL_ROUTES")
        assumeTrue(!configured.isNullOrBlank(), "TRAILNAV_LOCAL_ROUTES is not set")
        val root = Path.of(requireNotNull(configured)).toAbsolutePath().normalize()
        check(Files.isDirectory(root)) { "TRAILNAV_LOCAL_ROUTES does not name a directory" }
        val sources = Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".gpx", ignoreCase = true) }
                .sorted()
                .toList()
        }
        check(sources.size == 7) { "expected seven local GPX files, found ${sources.size}" }

        val config = GuideConfig(
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
        println(
            "E5_SETTINGS step_m=5.0 speed_mps=1.0 frame_ms_per_m=1000 config=$config",
        )

        for (source in sources) {
            val alias = source.fileName.toString().take(12)
            val route = RouteModel.fromGpx(Files.readString(source))
            check(!route.isEmpty) { "$alias: parsed route is empty" }
            check(route.elevationUse.used) { "$alias: elevation profile is not usable (${route.elevationUse.reason})" }
            val events = mutableListOf<EventAt>()
            var state = GuideState.initial(route)
            var previousMeters = 0.0
            var frameIndex = 0
            while (previousMeters < route.totalLengthMeters) {
                val positionMeters = minOf(frameIndex * 5.0, route.totalLengthMeters)
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
                    config,
                )
                val guidance = result.guidance
                if (guidance is Guidance.Elevation) {
                    events += EventAt(positionMeters)
                }
                state = result.nextState
                previousMeters = positionMeters
                frameIndex += 1
                if (frameIndex > route.totalLengthMeters / 5.0 + 2.0) {
                    error("$alias: E5 replay did not advance through the route")
                }
            }
            println(
                "E5_CANONICAL alias=$alias points=${route.points.size} length_m=${"%.1f".format(route.totalLengthMeters)} " +
                    "events=${events.size} positions_m=${events.joinToString(",") { "%.1f".format(it.routeMeters) }}",
            )
            if (alias == kuksaAlias) {
                val nearbyProfile = (480..520 step 5).joinToString(",") { distance ->
                    "$distance:${"%.2f".format(elevationAt(route, distance.toDouble()))}"
                }
                println("E5_PROFILE_480_520 alias=$alias distance_elevation_m=$nearbyProfile")
            }
        }
    }

    private fun pointAt(route: RouteModel, distanceMeters: Double): GeoPoint {
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
}
