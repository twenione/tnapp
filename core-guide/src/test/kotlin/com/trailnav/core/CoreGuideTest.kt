package com.trailnav.core

/** Small dependency-free test entry point used by local and CI smoke checks. */
fun main() {
    parsesTrackAndRoutePoints()
    removesNearDuplicatesAndBuildsIndex()
    println("core-guide tests: OK")
}

private fun parsesTrackAndRoutePoints() {
    val track = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.001"/></trkseg></trk></gpx>"""
    val route = RouteModel.fromGpx(track)
    check(route.sourcePoints.size == 2)
    val rte = """<gpx><rte><rtept lat="10.0" lon="20.0"/><rtept lat="10.001" lon="20.0"/></rte></gpx>"""
    check(RouteModel.fromGpx(rte).sourcePoints.size == 2)
}

private fun removesNearDuplicatesAndBuildsIndex() {
    val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0000001" lon="20.0"/><trkpt lat="10.0" lon="20.001"/></trkseg></trk></gpx>"""
    val route = RouteModel.fromGpx(xml)
    check(route.points.size == 2)
    check(route.simplifiedPoints !== route.points)
    check(route.spatialIndex.candidateSegments(route.points.first(), 100.0).contains(0))
}
