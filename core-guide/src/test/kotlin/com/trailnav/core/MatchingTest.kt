package com.trailnav.core

import kotlin.test.Test

/** Matching checks include the bounded search used for out-and-back routes. */
class MatchingTest {
    @Test
    fun boundedSearchPreservesDirection() {
    val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.001"/><trkpt lat="10.001" lon="20.001"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
    val route = RouteModel.fromGpx(xml)
    val config = GuideConfig()
    val first = RouteMatcher.nearest(route, SensorFrame(0, 10.0, 20.0005, 5f, 1f, null), null, config)
    check(first != null)
    val returnFrame = SensorFrame(1_000, 10.001, 20.0005, 5f, 1f, null)
    val bounded = RouteMatcher.nearest(route, returnFrame, route.totalLengthMeters * 0.65, config)
    check(bounded != null && bounded.projectedMeters > route.totalLengthMeters * 0.5)
    val boundedMatch = requireNotNull(bounded)
    val state = GuideState.initial(route).copy(lastMatch = first)
    val directed = RouteMatcher.withDirection(state, boundedMatch, returnFrame, config)
    check(directed.direction == ProgressDirection.FORWARD)
    }
}
