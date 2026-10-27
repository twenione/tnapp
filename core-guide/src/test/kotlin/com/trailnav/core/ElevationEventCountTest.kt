package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Canonical E5 event-count replay over the seven local routes, when supplied by the operator. */
class ElevationEventCountTest {
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

        val goldens = E5EventCountSupport.localGoldens().associateBy { it.alias to it.ordinal }
        val ordinals = mutableMapOf<String, Int>()
        println(
            "E5_SETTINGS step_m=5.0 speed_mps=1.0 frame_ms_per_m=1000 " +
                "config=${E5EventCountSupport.canonicalConfig}",
        )

        for (source in sources) {
            val alias = source.fileName.toString().take(12)
            val ordinal = ordinals.merge(alias, 1, Int::plus) ?: error("could not assign ordinal")
            val golden = goldens[alias to ordinal] ?: error("$alias#$ordinal: no E5 golden entry")
            val actualSha = E5EventCountSupport.sha256Prefix(source)
            check(actualSha == golden.sha256Prefix) {
                "$alias#$ordinal: GPX SHA-256 prefix expected ${golden.sha256Prefix}, actual $actualSha"
            }

            val measured = E5EventCountSupport.measure(source, alias)
            val actualCount = measured.eventPositionsMeters.size
            println(
                "E5_CANONICAL alias=$alias points=${measured.route.points.size} " +
                    "length_m=${"%.1f".format(java.util.Locale.ROOT, measured.route.totalLengthMeters)} " +
                    "events=$actualCount positions_m=${E5EventCountSupport.formatPositions(measured.eventPositionsMeters)}",
            )
            assertTrue(
                abs(actualCount - golden.expectedCount) <= 1,
                "$alias#$ordinal: expected ${golden.expectedCount} ±1 E5 events, actual $actualCount " +
                    "at ${E5EventCountSupport.formatPositions(measured.eventPositionsMeters)}",
            )
            E5EventCountSupport.assertExpectedPositionsRetained(
                "$alias#$ordinal",
                golden.expectedPositionsMeters,
                measured.eventPositionsMeters,
            )

            if (alias == kuksaAlias) {
                val nearbyProfile = (480..520 step 5).joinToString(",") { distance ->
                    "$distance:${"%.2f".format(java.util.Locale.ROOT,
                        E5EventCountSupport.elevationAt(measured.route, distance.toDouble()))}"
                }
                println("E5_PROFILE_480_520 alias=$alias distance_elevation_m=$nearbyProfile")
            }
        }
        check(goldens.keys == ordinals.flatMap { (alias, count) -> (1..count).map { alias to it } }.toSet()) {
            "local E5 golden route set does not match the seven supplied GPX routes"
        }
    }
}
