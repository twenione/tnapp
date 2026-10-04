package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.RouteModel
import com.trailnav.core.TargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteEtaSessionTest {
    @Test
    fun routeStatusIncludesTargetForTheCurrentSessionMatch() {
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg>
                <trkpt lat="10.0" lon="20.0"/>
                <trkpt lat="10.0" lon="20.001"/>
            </trkseg></trk></gpx>""".trimIndent()
        )
        val session = GuideSession(route, GuideConfig(minimumSessionSecondsBeforeArrival = 0.0))

        session.accept(TrailLocation(0L, 10.0, 20.0, 5f, 1f, null, "test"))
        session.accept(TrailLocation(30_000L, 10.0, 20.0005, 5f, 1f, null, "test"))

        val status = assertNotNull(session.routeStatus())
        val target = assertNotNull(status.target)
        assertEquals(TargetKind.DESTINATION, target.kind)
        assertEquals("flat", target.slopeSource)
        assertTrue(target.remainingSeconds!! > 0.0)
    }
}