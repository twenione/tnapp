package com.trailnav.core

import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test

/** Continuous synthetic route sessions for the E7/E8 threshold contracts. */
class SolarEventSessionTest {
    private data class Trace(val results: List<GuideResult>, val finalState: GuideState)

    private data class SolarEvent(
        val event: String,
        val threshold: String,
        val minutesRemaining: Int?,
        val startAnnouncement: Boolean,
        val timestamp: Long,
        val offRoute: Boolean,
    )

    @Test
    fun s1SunsetSessionEmitsSixtyThirtyAndAfterSunsetOnce() {
        val sunset = epoch("2026-09-21T09:31:43Z")
        val trace = runSession(
            config = GuideConfig(eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 7_000.0,
            startMillis = sunset - 75 * MINUTE,
            endMillis = sunset + 10 * MINUTE,
            stepSeconds = 60,
        )
        val events = solarEvents(trace, "E7")
        check(events.count { it.threshold.containsThreshold(60) } == 1) { "E7 events=$events" }
        check(events.count { it.threshold.containsThreshold(30) } == 1) { "E7 events=$events" }
        check(events.count { it.threshold == "after-sunset" } == 1) { "E7 events=$events" }
        check(abs((events.single { it.threshold.containsThreshold(60) }.minutesRemaining ?: -999) - 60) <= 2)
        check(abs((events.single { it.threshold.containsThreshold(30) }.minutesRemaining ?: -999) - 30) <= 2)
        check(events.single { it.threshold == "after-sunset" }.minutesRemaining == 0)
        check(thresholdMismatches(events, "E7", listOf(60, 30), sunset).isEmpty())
    }

    @Test
    fun s2SunriseSessionEmitsThirtyAndTenAndStaysSilentAfterSunrise() {
        val sunrise = epoch("2026-09-20T21:18:44Z")
        val trace = runSession(
            config = GuideConfig(sunsetEnabled = false, sunriseEnabled = true, eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 7_000.0,
            startMillis = sunrise - 45 * MINUTE,
            endMillis = sunrise + 5 * MINUTE,
            stepSeconds = 60,
        )
        val events = solarEvents(trace, "E8")
        check(events.count { it.threshold.containsThreshold(30) } == 1) { "E8 events=$events" }
        check(events.count { it.threshold.containsThreshold(10) } == 1) { "E8 events=$events" }
        check(trace.results.filter { it.nextState.lastTimestamp!! > sunrise }.none { it.guidance is Guidance.Sunrise })
        check(abs((events.single { it.threshold.containsThreshold(30) }.minutesRemaining ?: -999) - 30) <= 2)
        check(abs((events.single { it.threshold.containsThreshold(10) }.minutesRemaining ?: -999) - 10) <= 2)
        check(thresholdMismatches(events, "E8", listOf(30, 10), sunrise).isEmpty())

        val longitudeShifted = runSession(
            config = GuideConfig(sunsetEnabled = false, sunriseEnabled = true, eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 7_000.0,
            startMillis = sunrise - 45 * MINUTE,
            endMillis = sunrise + 5 * MINUTE,
            stepSeconds = 60,
            longitude = BASE_LONGITUDE + 15.0,
        )
        check(thresholdMismatches(solarEvents(longitudeShifted, "E8"), "E8", listOf(30, 10), sunrise).isNotEmpty())
    }

    @Test
    fun s3StartingInsideAThresholdAnnouncesItOnceForBothEvents() {
        val sunset = epoch("2026-09-21T09:31:43Z")
        val sunsetTrace = runSession(
            config = GuideConfig(eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 7_000.0,
            startMillis = sunset - 40 * MINUTE,
            endMillis = sunset - 20 * MINUTE,
            stepSeconds = 60,
        )
        val sunsetEvents = solarEvents(sunsetTrace, "E7")
        val startE7 = sunsetEvents.single { it.threshold.containsThreshold(60) }
        check(startE7.startAnnouncement)
        check(sunsetEvents.count { it.threshold.containsThreshold(60) } == 1)
        check(sunsetEvents.count { it.threshold.containsThreshold(30) } == 1)

        val sunrise = epoch("2026-09-20T21:18:44Z")
        val sunriseTrace = runSession(
            config = GuideConfig(sunsetEnabled = false, sunriseEnabled = true, eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 7_000.0,
            startMillis = sunrise - 25 * MINUTE,
            endMillis = sunrise - 5 * MINUTE,
            stepSeconds = 60,
        )
        val sunriseEvents = solarEvents(sunriseTrace, "E8")
        check(sunriseEvents.single { it.threshold.containsThreshold(30) }.startAnnouncement)
        check(sunriseEvents.count { it.threshold.containsThreshold(30) } == 1)
        check(sunriseEvents.count { it.threshold.containsThreshold(10) } == 1)
    }

    @Test
    fun s4AfterEventStartsFollowTheExistingE7AndE8Rules() {
        val sunset = epoch("2026-09-21T09:31:43Z")
        val afterSunset = runSession(
            config = GuideConfig(eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 2_000.0,
            startMillis = sunset + 5 * MINUTE,
            endMillis = sunset + 10 * MINUTE,
            stepSeconds = 60,
        )
        val e7 = solarEvents(afterSunset, "E7")
        check(e7.count { it.threshold == "after-sunset" } == 1)
        check(e7.single().minutesRemaining == 0)

        val sunrise = epoch("2026-09-20T21:18:44Z")
        val afterSunrise = runSession(
            config = GuideConfig(sunsetEnabled = false, sunriseEnabled = true, eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 2_000.0,
            startMillis = sunrise + 5 * MINUTE,
            endMillis = sunrise + 10 * MINUTE,
            stepSeconds = 60,
        )
        check(solarEvents(afterSunrise, "E8").isEmpty())
        check(afterSunrise.finalState.consumedSunriseThresholds.containsAll(setOf(30, 10)))
    }

    @Test
    fun s5OffRouteSessionSpeaksPendingE7WithFireTimeValueAndConsumesSilentE8() {
        val sunrise = epoch("2026-09-20T21:18:44Z")
        val sunset = epoch("2026-09-21T09:31:43Z")
        val trace = runSession(
            config = GuideConfig(
                sunsetEnabled = true,
                sunriseEnabled = true,
                eventMinIntervalSeconds = 30.0,
            ),
            routeLengthMeters = 50_000.0,
            startMillis = sunrise - 35 * MINUTE,
            endMillis = sunset + 5 * MINUTE,
            stepSeconds = 5,
            offRouteAfterStart = true,
        )
        val e8 = solarEvents(trace, "E8")
        check(e8.isEmpty()) { "E8 spoke while off-route: $e8" }
        check(trace.finalState.consumedSunriseThresholds.containsAll(setOf(30, 10)))
        val e8Crossing = trace.results.minBy { abs(it.nextState.lastTimestamp!! - (sunrise - 29 * MINUTE)) }
        check(e8Crossing.nextState.offRoute)
        check(e8Crossing.guidance !is Guidance.Sunrise)

        val e7Sixty = solarEvents(trace, "E7").filter { it.threshold.containsThreshold(60) }
        check(e7Sixty.size == 1) { "E7 60-minute events=$e7Sixty" }
        val event = e7Sixty.single()
        check(event.offRoute) { "E7 should speak while off-route: $event" }
        val expectedAtFire = ((sunset - event.timestamp) / MINUTE).toInt()
        check(event.minutesRemaining == expectedAtFire) { "fire time minutes=${event.minutesRemaining}, expected=$expectedAtFire" }
        check(offRouteE7Mismatches(announced = true, expectedToSpeak = true).isEmpty())
        check(offRouteE7Mismatches(announced = true, expectedToSpeak = false).isNotEmpty())
    }

    @Test
    fun s6MinIntervalDelayRetainsE7AndUsesTheFireTimeValue() {
        val sunset = epoch("2026-09-21T09:31:43Z")
        val start = sunset - 61 * MINUTE
        val route = route(2_000.0, BASE_LONGITUDE)
        val trace = runSession(
            config = GuideConfig(eventMinIntervalSeconds = 90.0),
            route = route,
            startMillis = start,
            endMillis = start + 120_000L,
            stepSeconds = 5,
            initialState = GuideState.initial(route).copy(lastPeriodicEventAt = start),
        )
        val events = solarEvents(trace, "E7").filter { it.threshold.containsThreshold(60) }
        check(events.size == 1) { "E7 60-minute events=$events" }
        val delayed = events.single()
        check(delayed.startAnnouncement.not())
        check(delayed.minutesRemaining == ((sunset - delayed.timestamp) / MINUTE).toInt())
        val result = trace.results.single { it.nextState.lastTimestamp == delayed.timestamp }
        check(result.reason.details["delayed"] == "true")
        check(result.reason.details["delayReason"] == "event-min-interval")
    }

    @Test
    fun s8TwelveHourShiftAndDisabledE7AreNegativeControls() {
        val sunset = epoch("2026-09-21T09:31:43Z")
        val shifted = runSession(
            config = GuideConfig(eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 7_000.0,
            startMillis = sunset - 75 * MINUTE + 12 * 60 * MINUTE,
            endMillis = sunset + 10 * MINUTE + 12 * 60 * MINUTE,
            stepSeconds = 60,
        )
        check(solarEvents(shifted, "E7").isEmpty())

        val disabled = runSession(
            config = GuideConfig(sunsetEnabled = false, eventMinIntervalSeconds = 0.0),
            routeLengthMeters = 7_000.0,
            startMillis = sunset - 75 * MINUTE,
            endMillis = sunset + 10 * MINUTE,
            stepSeconds = 60,
        )
        check(solarEvents(disabled, "E7").isEmpty())
        check(disabled.finalState.consumedSunsetThresholds.containsAll(setOf(60, 30, 0)))
    }

    private fun runSession(
        config: GuideConfig,
        routeLengthMeters: Double,
        startMillis: Long,
        endMillis: Long,
        stepSeconds: Long,
        longitude: Double = BASE_LONGITUDE,
        offRouteAfterStart: Boolean = false,
        initialState: GuideState? = null,
        route: RouteModel = route(routeLengthMeters, longitude),
    ): Trace = runSession(
        config = config,
        route = route,
        startMillis = startMillis,
        endMillis = endMillis,
        stepSeconds = stepSeconds,
        offRouteAfterStart = offRouteAfterStart,
        initialState = initialState,
    )

    private fun runSession(
        config: GuideConfig,
        route: RouteModel,
        startMillis: Long,
        endMillis: Long,
        stepSeconds: Long,
        offRouteAfterStart: Boolean = false,
        initialState: GuideState? = null,
    ): Trace {
        var state = initialState ?: GuideState.initial(route)
        val results = mutableListOf<GuideResult>()
        val origin = route.sourcePoints.firstOrNull() ?: GeoPoint(BASE_LATITUDE, BASE_LONGITUDE)
        val stepMillis = stepSeconds * 1_000L
        var timestamp = startMillis
        while (timestamp <= endMillis) {
            val progressMeters = (timestamp - startMillis) / 1_000.0
            val offsetMeters = if (offRouteAfterStart && timestamp > startMillis) 35.0 else 0.0
            val latitude = origin.lat + Math.toDegrees(progressMeters / EARTH_RADIUS_METERS)
            val longitude = origin.lon + Math.toDegrees(
                offsetMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(origin.lat))),
            )
            val result = guide(
                state,
                SensorFrame(timestamp, latitude, longitude, 5f, 1f, 0f),
                config,
            )
            state = result.nextState
            results += result
            timestamp += stepMillis
        }
        return Trace(results, state)
    }

    private fun route(lengthMeters: Double, longitude: Double): RouteModel {
        val endLatitude = BASE_LATITUDE + Math.toDegrees(lengthMeters / EARTH_RADIUS_METERS)
        return RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"$BASE_LATITUDE\" lon=\"$longitude\"/>" +
                "<trkpt lat=\"$endLatitude\" lon=\"$longitude\"/></trkseg></trk></gpx>"
        )
    }

    private fun solarEvents(trace: Trace, event: String): List<SolarEvent> = trace.results.mapNotNull { result ->
        val details = result.reason.details
        if (details["event"] != event) return@mapNotNull null
        SolarEvent(
            event = event,
            threshold = details["thresholdMinutes"].orEmpty(),
            minutesRemaining = details["minutesRemaining"]?.toIntOrNull(),
            startAnnouncement = details["startAnnouncement"] == "true",
            timestamp = result.nextState.lastTimestamp ?: 0L,
            offRoute = result.nextState.offRoute,
        )
    }

    private fun thresholdMismatches(
        actual: List<SolarEvent>,
        event: String,
        thresholds: List<Int>,
        referenceMillis: Long,
    ): List<String> = thresholds.flatMap { threshold ->
        val matches = actual.filter { it.event == event && it.threshold.containsThreshold(threshold) }
        when {
            matches.size != 1 -> listOf("$event threshold $threshold count=${matches.size}")
            abs((matches.single().timestamp - (referenceMillis - threshold * MINUTE)) / MINUTE.toDouble()) > 2.0 ->
                listOf("$event threshold $threshold fired at ${matches.single().timestamp}")
            else -> emptyList()
        }
    }

    private fun offRouteE7Mismatches(announced: Boolean, expectedToSpeak: Boolean): List<String> =
        if (announced == expectedToSpeak) emptyList() else listOf("off-route E7 announced=$announced expected=$expectedToSpeak")

    private fun String.containsThreshold(threshold: Int): Boolean =
        split(',').any { it == threshold.toString() }

    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()

    private companion object {
        const val BASE_LATITUDE = 37.5665
        const val BASE_LONGITUDE = 126.9780
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val MINUTE = 60_000L
    }
}
