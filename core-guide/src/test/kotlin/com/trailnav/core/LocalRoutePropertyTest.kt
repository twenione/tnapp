package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Optional local-only property sweep; CI skips it unless a route directory is supplied. */
class LocalRoutePropertyTest {
    @Test
    fun localGpxRoutesRespectEtaBoundsAndSpeedFloor() {
        val configured = System.getenv("TRAILNAV_LOCAL_ROUTES")
        assumeTrue(!configured.isNullOrBlank(), "TRAILNAV_LOCAL_ROUTES is not set")
        val root = Path.of(requireNotNull(configured)).toAbsolutePath().normalize()
        assertTrue(Files.isDirectory(root), "TRAILNAV_LOCAL_ROUTES does not name a directory")
        val routes = Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".gpx", ignoreCase = true) }
                .sorted()
                .toList()
        }
        assertTrue(routes.isNotEmpty(), "TRAILNAV_LOCAL_ROUTES contains no GPX files")

        for (source in routes) {
            val alias = source.fileName.toString().take(12)
            val route = try {
                RouteModel.fromGpx(Files.readString(source))
            } catch (_: Exception) {
                throw AssertionError("$alias: GPX parsing failed")
            }
            assertTrue(!route.isEmpty, "$alias: parsed route is empty")
            val tracker = RouteEtaTracker(route)
            var unavailableCount = 0
            var maximumAvailableEta = 0.0
            var position = 0.0
            while (position <= route.totalLengthMeters) {
                val target = assertNotNull(
                    tracker.estimate(position, ProgressDirection.FORWARD, arrived = false),
                    "$alias: estimate missing at ${position.toInt()}m",
                )
                val seconds = target.remainingSeconds
                if (seconds == null) {
                    unavailableCount += 1
                    assertNotNull(target.etaUnavailableReason, "$alias: unavailable ETA has no reason")
                } else {
                    assertTrue(seconds.isFinite(), "$alias: ETA is non-finite at ${position.toInt()}m")
                    assertTrue(seconds <= 86_400.0 + 1e-6, "$alias: ETA exceeds one day at ${position.toInt()}m")
                    if (target.remainingMeters > 0.0 && seconds > 0.0) {
                        assertTrue(
                            target.remainingMeters / seconds + 1e-9 >= 0.15,
                            "$alias: ETA speed floor violated at ${position.toInt()}m",
                        )
                    }
                    maximumAvailableEta = maxOf(maximumAvailableEta, seconds)
                }
                position += 5.0
            }
            println(
                "LOCAL_ROUTE alias=$alias points=${route.points.size} length_m=${"%.1f".format(route.totalLengthMeters)} " +
                    "eta_unavailable_samples=$unavailableCount max_eta_s=${"%.1f".format(maximumAvailableEta)}",
            )
        }
    }
}
