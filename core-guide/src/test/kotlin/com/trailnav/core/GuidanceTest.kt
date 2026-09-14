package com.trailnav.core

import kotlin.test.Test

/** Off-route hysteresis, accuracy filtering, arrival, and re-announcement checks. */
class GuidanceTest {
    @Test
    fun hysteresisAccuracyArrivalAndReannouncement() {
    val xml = """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.002"/></trkseg></trk></gpx>"""
    val route = RouteModel.fromGpx(xml)
    val config = GuideConfig(offRouteEnterDwellSeconds = 2.0, offRouteExitDwellSeconds = 2.0)
    var state = GuideState.initial(route)
    val filtered = guide(state, SensorFrame(0, 10.0, 20.0, 51f, 1f, null), config)
    check(filtered.guidance == null && filtered.nextState == state)
    state = guide(state, SensorFrame(0, 10.0, 20.0, 5f, 1f, null), config).nextState
    val far1 = guide(state, SensorFrame(1, 10.0005, 20.0, 5f, 1f, null), config)
    check(!far1.nextState.offRoute)
    val far2 = guide(far1.nextState, SensorFrame(3, 10.0005, 20.0, 5f, 1f, null), config)
    check(far2.nextState.offRoute && far2.guidance is Guidance.OffRoute)
    val near1 = guide(far2.nextState, SensorFrame(4, 10.0, 20.0005, 5f, 1f, null), config)
    check(near1.nextState.offRoute)
    val near2 = guide(near1.nextState, SensorFrame(6, 10.0, 20.0005, 5f, 1f, null), config)
    check(!near2.nextState.offRoute)
    val middle = guide(GuideState.initial(route), SensorFrame(0, 10.0, 20.001, 5f, 1f, null), config)
    check(middle.guidance == null)
    val end = guide(middle.nextState, SensorFrame(1, 10.0, 20.002, 5f, 1f, null), config)
    check(end.guidance == Guidance.Arrived && end.nextState.arrived)
    }
}
