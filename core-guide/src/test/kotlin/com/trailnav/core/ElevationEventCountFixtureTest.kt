package com.trailnav.core

import kotlin.test.Test

/** CI-visible E5 count assertions for all checked-in ETA route fixtures. */
class ElevationEventCountFixtureTest {
    @Test
    fun checkedInRouteFixturesUseTheCanonicalE5Procedure() {
        val root = E5EventCountSupport.repositoryRoot()
        val sources = E5EventCountSupport.fixtureRoutes()
        check(sources.size == 6) { "expected six eta_* route fixtures, found ${sources.size}" }
        val goldens = E5EventCountSupport.fixtureGoldens().associateBy { it.path }
        val actualPaths = sources.map { root.relativize(it).toString().replace('\\', '/') }.toSet()
        check(goldens.keys == actualPaths) { "fixture E5 golden set does not match the six checked-in route fixtures" }

        for (source in sources) {
            val path = root.relativize(source).toString().replace('\\', '/')
            val golden = goldens[path] ?: error("$path: no fixture E5 golden entry")
            val alias = source.parent.fileName.toString()
            val measured = E5EventCountSupport.measure(source, alias)
            val actualCount = measured.eventPositionsMeters.size
            println(
                "E5_FIXTURE alias=$alias points=${measured.route.points.size} " +
                    "length_m=${"%.1f".format(java.util.Locale.ROOT, measured.route.totalLengthMeters)} " +
                    "events=$actualCount positions_m=${E5EventCountSupport.formatPositions(measured.eventPositionsMeters)}",
            )
            E5EventCountSupport.assertMatchesGolden(
                path,
                golden.expectedCount,
                golden.expectedPositionsMeters,
                measured.eventPositionsMeters,
            )
        }
    }
}
