package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.RouteModel
import com.trailnav.core.guide
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionLoggerTest {
    @Test
    fun loggerAppendsAdoptedStreamsWithMonotonicSequence() {
        val directory = createTempDir(prefix = "tnapp-session-")
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
        )
        val sessionId = UUID.randomUUID().toString()
        JsonlSessionLogger(
            directory = directory,
            sessionId = sessionId,
            codeHash = "sha256:test",
            configHash = "sha256:config",
            eventSettings = EventSettings.defaults().toWireMap(),
            routeHash = "sha256:route",
            appVersion = "test",
            routeElevationUsed = true,
            routeElevationReason = "ok",
            routeWaypointCount = 2,
        ).use { logger ->
            val location = TrailLocation(1_000L, 10.0001, 20.0, 5f, 1f, null, "fake")
            val result = guide(GuideState.initial(route), location.toSensorFrame(), GuideConfig())
            logger.appendEnvelope(location, "loc", "location")
            val locationSeq = logger.appendLocation(location)
            logger.appendGuide(location, result, null, locationSeq)
            logger.appendGuide(location, result, "경로 위입니다", locationSeq, trigger = "on-demand")
            logger.appendSystem("service.started", mapOf("battery_pct" to "90"))
            logger.appendSystem(
                "events.config",
                EventSettings.defaults().toWireMap().mapValues { (_, enabled) -> enabled.toString() },
            )
            logger.appendError("test", "synthetic")
        }
        val manifest = File(directory, "manifest.json").readText()
        assertTrue(manifest.contains("\"upload_default\":false"))
        assertTrue(manifest.contains("\"elevation_used\":true"))
        assertTrue(manifest.contains("\"elevation_reason\":\"ok\""))
        assertTrue(manifest.contains("\"waypoint_count\":2"))
        assertTrue(manifest.contains("\"events\":{\"E1\":false,\"E2\":false,\"E3\":true,\"E4\":true,\"E5\":false,\"E6\":true,\"E7\":true,\"E8\":true}"))
        val events = File(directory, "events.ndjson").readLines().filter { it.isNotBlank() }
        assertEquals(7, events.size)
        assertTrue(events[0].contains("\"stream\":\"envelope\""))
        assertTrue(events[0].contains("\"event_stream\":\"loc\""))
        assertTrue(events[1].contains("\"stream\":\"loc\""))
        assertTrue(events[2].contains("\"stream\":\"guide\""))
        assertTrue(events[2].contains("\"src_seq\":1"))
        assertTrue(events[2].contains("\"rule\":\"matching.on-route\""))
        assertTrue(events[3].contains("\"stream\":\"guide\""))
        assertTrue(events[3].contains("\"trigger\":\"on-demand\""))
        assertTrue(events[4].contains("\"stream\":\"sys\""))
        assertTrue(events[5].contains("\"kind\":\"events.config\""))
        assertTrue(events[5].contains("\"E1\":\"false\""))
        assertTrue(events[5].contains("\"E8\":\"true\""))
        assertTrue(events[6].contains("\"stream\":\"err\""))
        val seqs = events.map { Regex("\"seq\":(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), seqs)
        directory.deleteRecursively()
    }

    @Test
    fun eventSelectionChangesSessionConfigHash() {
        val defaults = EventSettings.defaults()
        val allOff = GuideEvent.values().fold(defaults) { settings, event -> settings.withEnabled(event, false) }
        val enabledMilestone = allOff.withEnabled(GuideEvent.MILESTONE, true)
        val firstHash = JsonlSessionLogger.sha256(allOff.toGuideConfig().toString())
        val secondHash = JsonlSessionLogger.sha256(enabledMilestone.toGuideConfig().toString())

        assertNotEquals(firstHash, secondHash)
    }

    @Test
    fun guideSessionUsesCompiledCoreEngine() {
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
        )
        val session = GuideSession(route)
        val decision = session.accept(TrailLocation(0L, 10.0001, 20.0, 5f, 1f, null, "fake"))
        assertTrue(decision.result.reason.rule.isNotBlank())
    }
}
