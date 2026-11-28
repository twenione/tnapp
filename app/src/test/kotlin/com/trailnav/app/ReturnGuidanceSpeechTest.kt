package com.trailnav.app

import com.trailnav.core.Engine
import com.trailnav.core.GeoPoint
import com.trailnav.core.GuideConfig
import com.trailnav.core.Guidance
import com.trailnav.core.RouteModel
import java.io.File
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReturnGuidanceSpeechTest {
    @Test
    fun realGuideSessionOutputSpeaksTheHintOnceAndMatchesReturnGuideDetailsWithAndWithoutAnchor() {
        val config = GuideConfig(
            sunsetEnabled = false,
            sunriseEnabled = false,
            returnGuideTriggerMeters = 200.0,
            offRouteEnterDwellSeconds = 20.0,
            offRouteExitDwellSeconds = 10.0,
            reannounceIntervalSeconds = 60.0,
            positionGapRematchSeconds = 120.0,
            taggingOffRouteDwellSeconds = 600.0,
        )
        val route = lineRoute()
        val session = GuideSession(route, config)
        val hintSpeech = mutableListOf<String>()
        var timestamp = 0L

        for (east in 0..600 step 20) {
            recordGuidance(session.accept(location(timestamp, east.toDouble(), 0.0)), hintSpeech)
            timestamp += FRAME_MILLIS
        }
        var activationFrames = 0
        while (!session.snapshot().returnButtonActive && activationFrames < 40) {
            recordGuidance(session.accept(location(timestamp, 600.0, 250.0)), hintSpeech)
            timestamp += FRAME_MILLIS
            activationFrames++
        }
        assertTrue(session.snapshot().returnButtonActive, "the real route session never activated the return button")
        assertEquals(1, hintSpeech.size, hintSpeech.toString())
        assertTrue(hintSpeech.single().contains(GuidancePhrases.returnHintSentence()), hintSpeech.single())

        val withAnchor = assertNotNull(Engine.returnGuidance(session.snapshot(), config))
        val withAnchorSpeech = assertMatchesResult(withAnchor)
        assertTrue((withAnchor.guidance as Guidance.ReturnGuide).backtrackMeters != null)
        assertTrue(withAnchorSpeech.contains("왔던 길로 되돌아가세요"))
        writeReturnRequestSession(withAnchor.reason.details)

        var truncationFrames = 0
        while (session.snapshot().returnTrajectoryUnavailableReason != "truncated" && truncationFrames < 500) {
            val north = 250.0 + (truncationFrames + 1) * 5.0
            recordGuidance(session.accept(location(timestamp, 600.0, north)), hintSpeech)
            timestamp += FRAME_MILLIS
            truncationFrames++
        }
        assertEquals("truncated", session.snapshot().returnTrajectoryUnavailableReason)
        val withoutAnchor = assertNotNull(Engine.returnGuidance(session.snapshot(), config))
        val withoutAnchorSpeech = assertMatchesResult(withoutAnchor)
        val returnGuide = assertIs<Guidance.ReturnGuide>(withoutAnchor.guidance)
        assertEquals(null, returnGuide.backtrackMeters)
        assertEquals("false", withoutAnchor.reason.details["A_present"])
        assertEquals("none", withoutAnchor.reason.details["N"])
        assertFalseContainsBacktrack(withoutAnchorSpeech)
        assertEquals(1, hintSpeech.size, "the hint repeated during one off-route segment")
    }

    private fun assertMatchesResult(result: com.trailnav.core.GuideResult): String {
        val guidance = assertIs<Guidance.ReturnGuide>(result.guidance)
        val speech = assertNotNull(result.guidance.toSpeech())
        assertEquals(guidance.backtrackMeters?.toString() ?: "none", result.reason.details["N"])
        assertEquals((guidance.backtrackMeters != null).toString(), result.reason.details["A_present"])
        assertEquals(guidance.straightMeters.toString(), result.reason.details["M"])
        assertEquals(guidance.compass.name, result.reason.details["compass"])
        assertEquals(
            GuidancePhrases.returnGuide(guidance.backtrackMeters, guidance.straightMeters, guidance.compass),
            speech,
        )
        return speech
    }

    private fun recordGuidance(decision: SessionDecision, hintSpeech: MutableList<String>) {
        val speech = decision.result.guidance.toSpeech()
        if (speech?.contains(GuidancePhrases.returnHintSentence()) == true) hintSpeech += speech
    }

    private fun assertFalseContainsBacktrack(speech: String) {
        assertFalse(speech.contains("왔던 길"), speech)
    }

    private fun lineRoute(): RouteModel {
        return RouteModel.fromGpx(lineGpx())
    }

    private fun lineGpx(): String {
        val points = (0..5).joinToString("") { index ->
            val point = localToGeo(index * 1_000.0, 0.0)
            "<trkpt lat=\"${point.lat}\" lon=\"${point.lon}\"/>"
        }
        return "<gpx><trk><trkseg>$points</trkseg></trk></gpx>"
    }

    private fun writeReturnRequestSession(details: Map<String, String>) {
        val directory = File("build/task137-return-request-validation").apply {
            deleteRecursively()
            mkdirs()
        }
        val gpx = lineGpx()
        File(directory, "route.gpx").writeText(gpx)
        val config = GuideConfig(sunsetEnabled = false, sunriseEnabled = false)
        val route = RouteModel.fromGpx(gpx, config)
        val location = location(1_000L, 100.0, 0.0)
        val decision = GuideSession(route, config, startupEnabled = true).accept(location)
        JsonlSessionLogger(
            directory = directory,
            sessionId = "13700000-0000-4000-8000-000000000137",
            codeHash = "sha256:task137-test",
            configHash = "sha256:${JsonlSessionLogger.sha256(config.toString())}",
            eventSettings = EventSettings.defaults().toWireMap(),
            routeHash = "sha256:${JsonlSessionLogger.sha256(gpx)}",
            appVersion = "task137-test",
        ).use { logger ->
            logger.appendEnvelope(location, "loc", "location")
            val sourceSeq = logger.appendLocation(location)
            logger.appendGuide(location, decision.result, decision.result.guidance.toSpeech(), sourceSeq)
            logger.appendEngineSystemEvents(decision.newlyEmittedSystemEvents)
            logger.appendSystem("return.requested", details)
        }
    }

    private fun location(timestamp: Long, eastMeters: Double, northMeters: Double): TrailLocation {
        val point = localToGeo(eastMeters, northMeters)
        return TrailLocation(timestamp, point.lat, point.lon, 5f, 1f, null, "test")
    }

    private fun localToGeo(eastMeters: Double, northMeters: Double): GeoPoint = GeoPoint(
        ORIGIN_LAT + Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
        ORIGIN_LON + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(ORIGIN_LAT)))),
    )

    private companion object {
        const val FRAME_MILLIS = 1_000L
        const val ORIGIN_LAT = 37.0
        const val ORIGIN_LON = 127.0
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
