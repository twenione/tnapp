package com.trailnav.core

import kotlin.test.Test

/** Matching checks include the bounded search used for out-and-back routes. */
class MatchingTest {
    @Test
    fun boundedSearchFindsReturnBranchWithoutInferringDirection() {
        val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.001"/><trkpt lat="10.001" lon="20.001"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
        val route = RouteModel.fromGpx(xml)
        val config = GuideConfig()
        val returnFrame = SensorFrame(1_000, 10.001, 20.0005, 5f, 1f, null)
        val bounded = RouteMatcher.nearest(route, returnFrame, route.totalLengthMeters * 0.65, config)
        check(bounded != null && bounded.projectedMeters > route.totalLengthMeters * 0.5)
        check(bounded.direction == ProgressDirection.UNKNOWN)
    }
}
