package com.trailnav.app

import com.trailnav.core.GeoPoint
import com.trailnav.core.Guidance
import com.trailnav.core.RouteModel
import java.io.File
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoundTripGuidanceSessionTest {
    @Test
    fun oneWayGpxProducesOutboundAndReturnRemainingPhrasesThroughGuideSession() {
        val eventSettings = GuideEvent.values().fold(EventSettings.defaults()) { settings, event ->
            settings.withEnabled(event, event == GuideEvent.REMAINING)
        }
        val config = guideConfigFor(eventSettings, roundTripEnabled = true)
        val route = RouteModel.fromGpx(oneWayGpx())
        val joinedRoute = route.roundTrip(config)
        val turnaround = assertNotNull(joinedRoute.roundTripTurnaroundMeters)
        assertTrue(joinedRoute.totalLengthMeters > turnaround)
        val session = GuideSession(route, config, startupEnabled = true)
        val gpx = oneWayGpx()
        val sessionDirectory = File("build/task120-round-trip-session").apply {
            deleteRecursively()
            mkdirs()
        }
        File(sessionDirectory, "route.gpx").writeText(gpx)
        val logger = JsonlSessionLogger(
            directory = sessionDirectory,
            codeHash = "sha256:task120-test",
            configHash = "sha256:${JsonlSessionLogger.sha256(config.toString())}",
            eventSettings = eventSettings.toWireMap(),
            routeHash = "sha256:${JsonlSessionLogger.sha256(gpx)}",
            appVersion = "test",
            roundTripEnabled = config.roundTripEnabled,
        )
        val outboundSpeech = mutableListOf<String>()
        val returnSpeech = mutableListOf<String>()
        var returnEtaCount = 0
        var returnEtaNullCount = 0
        val maxProgress = joinedRoute.totalLengthMeters
        val frameCount = ceil(maxProgress / WALK_STEP_METERS).toInt()

        logger.use { sink ->
            for (index in 0..frameCount) {
                val progress = minOf(index * WALK_STEP_METERS, maxProgress)
                val location = locationAt(joinedRoute, progress, index * FRAME_INTERVAL_MILLIS)
                val decision = session.accept(location)
                val remaining = decision.result.guidance as? Guidance.Remaining
                val estimate = if (remaining?.returnLeg == true) session.startEstimate()?.remainingSeconds else null
                val spoken = decision.result.guidance.toSpeech(decision.result.nextState, estimate)
                sink.appendEnvelope(location, "loc", "location")
                val sourceSeq = sink.appendLocation(location)
                sink.appendGuide(location, decision.result, spoken, sourceSeq)
                sink.appendEngineSystemEvents(decision.newlyEmittedSystemEvents)
                sink.appendDiscardedEvents(
                    decision.newlyDiscardedSlopePeaks,
                    decision.newlyDiscardedWaypoints,
                    decision.newlyDiscardedEvents,
                )
                sink.appendArrivalBlockedEvents(decision.newlyBlockedArrivals)
                if (remaining?.returnLeg == true) {
                    if (estimate == null) returnEtaNullCount++
                    returnEtaCount++
                    returnSpeech += requireNotNull(spoken)
                } else if (remaining != null && decision.result.nextState.route.roundTripTurnaroundMeters != null) {
                    outboundSpeech += requireNotNull(spoken)
                }
                if (outboundSpeech.isNotEmpty() && returnSpeech.isNotEmpty()) break
            }
            sink.appendEngineSystemEvents(session.finish())
        }

        assertTrue(outboundSpeech.isNotEmpty(), "The one-way GPX never produced an outbound E3 event")
        assertTrue(outboundSpeech.all { it.startsWith("반환점까지 ") }, outboundSpeech.toString())
        assertTrue(returnSpeech.isNotEmpty(), "The one-way GPX never produced a return E3 event")
        assertTrue(returnSpeech.all { it.startsWith("출발지까지는 ") }, returnSpeech.toString())
        println("TASK120_RETURN_E3_ETA eta_null=$returnEtaNullCount/$returnEtaCount")
    }

    private fun oneWayGpx(): String {
        val points = listOf(
            0.0 to 0.0,
            400.0 to 0.0,
            800.0 to 120.0,
            1_200.0 to 120.0,
            1_500.0 to 500.0,
            1_500.0 to 900.0,
        ).joinToString("") { (east, north) ->
            val geo = localToGeo(east, north)
            "<trkpt lat=\"${geo.lat}\" lon=\"${geo.lon}\"/>"
        }
        return "<gpx><trk><trkseg>$points</trkseg></trk></gpx>"
    }

    private fun locationAt(route: RouteModel, progressMeters: Double, timestampMillis: Long): TrailLocation {
        val progress = progressMeters.coerceIn(0.0, route.totalLengthMeters)
        val index = route.cumulativeMeters.indexOfLast { it <= progress }.coerceIn(0, route.points.lastIndex - 1)
        val startS = route.cumulativeMeters[index]
        val endS = route.cumulativeMeters[index + 1]
        val fraction = if (endS <= startS) 0.0 else (progress - startS) / (endS - startS)
        val start = route.points[index]
        val end = route.points[index + 1]
        val east = start.east + (end.east - start.east) * fraction
        val north = start.north + (end.north - start.north) * fraction
        val origin = route.sourcePoints.first()
        val latitude = origin.lat + Math.toDegrees(north / EARTH_RADIUS_METERS)
        val longitude = origin.lon + Math.toDegrees(east / (EARTH_RADIUS_METERS * cos(Math.toRadians(origin.lat))))
        return TrailLocation(timestampMillis, latitude, longitude, 5f, WALK_METERS_PER_SECOND.toFloat(), null, "test")
    }

    private fun localToGeo(east: Double, north: Double): GeoPoint = GeoPoint(
        ORIGIN_LAT + Math.toDegrees(north / EARTH_RADIUS_METERS),
        ORIGIN_LON + Math.toDegrees(east / (EARTH_RADIUS_METERS * cos(Math.toRadians(ORIGIN_LAT)))),
    )

    private companion object {
        const val WALK_METERS_PER_SECOND = 1.2
        const val WALK_STEP_METERS = 1.2
        const val FRAME_INTERVAL_MILLIS = 1_000L
        const val ORIGIN_LAT = 37.0
        const val ORIGIN_LON = 127.0
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
