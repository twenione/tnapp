package com.trailnav.core

import java.time.Instant
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaitingStateTest {
    private val radius = 6_371_008.8
    private val latitude = 37.5665
    private val longitude = 126.9780
    private val route = straightRoute(20_000.0)

    @Test
    fun farStartSpeaksOnceThenStaysSilentWhileApproaching() {
        val config = GuideConfig(sunsetEnabled = false)
        var state = GuideState.initial(route)
        val results = mutableListOf<GuideResult>()
        val first = guide(state, frame(eastMeters = 5_000.0, northMeters = 10_000.0, timestamp = 1_000L), config)
        state = first.nextState
        results += first
        assertIs<Guidance.WaitingStart>(first.guidance)
        assertEquals(1, state.systemEvents.count { it.kind == "waiting.enter" })
        assertTrue(kotlin.math.abs(state.systemEvents.single { it.kind == "waiting.enter" }.fields.getValue("distance_m").toDouble() - 5_000.0) < 0.01)

        for (index in 1..10) {
            val result = guide(
                state,
                frame(
                    eastMeters = 5_000.0 - index * 400.0,
                    northMeters = 10_000.0,
                    timestamp = 1_000L + index * 60_000L,
                ),
                config,
            )
            state = result.nextState
            results += result
            assertNull(result.guidance, "waiting frame=$index guidance=${result.guidance}")
        }

        assertTrue(state.waitingForRoute)
        assertTrue(state.waitingDecisionEvaluated)
        assertEquals(1, state.systemEvents.count { it.kind == "waiting.enter" })
        assertEquals(0, results.drop(1).count { it.guidance is Guidance.Approach || it.guidance is Guidance.OffRoute })
    }

    @Test
    fun entryAndExitBoundariesAreInclusiveAndStrictAndExitFrameApproaches() {
        val config = GuideConfig(sunsetEnabled = false)
        val under = guide(
            GuideState.initial(route),
            frame(eastMeters = 1_999.9, northMeters = 10_000.0, timestamp = 0L),
            config,
        )
        assertIs<Guidance.Approach>(under.guidance)
        assertFalse(under.nextState.waitingForRoute)

        val atEntry = guide(
            GuideState.initial(route),
            frame(eastMeters = 2_000.0, northMeters = 10_000.0, timestamp = 0L),
            config,
        )
        assertIs<Guidance.WaitingStart>(atEntry.guidance)

        val atExit = guide(
            atEntry.nextState,
            frame(eastMeters = 500.0, northMeters = 10_000.0, timestamp = 60_000L),
            config,
        )
        assertNull(atExit.guidance)
        assertTrue(atExit.nextState.waitingForRoute)
        assertEquals(0, atExit.nextState.systemEvents.count { it.kind == "waiting.exit" })

        val insideExit = guide(
            atExit.nextState,
            frame(eastMeters = 499.9, northMeters = 10_000.0, timestamp = 120_000L),
            config,
        )
        assertIs<Guidance.Approach>(insideExit.guidance)
        assertFalse(insideExit.nextState.waitingForRoute)
        assertEquals(1, insideExit.nextState.systemEvents.count { it.kind == "waiting.exit" })
        assertEquals("120.0", insideExit.nextState.systemEvents.single { it.kind == "waiting.exit" }.fields["waited_seconds"])
    }

    @Test
    fun waitingDoesNotReenterAfterExit() {
        val config = GuideConfig(sunsetEnabled = false)
        val entry = guide(
            GuideState.initial(route),
            frame(eastMeters = 3_000.0, northMeters = 10_000.0, timestamp = 0L),
            config,
        )
        val exit = guide(
            entry.nextState,
            frame(eastMeters = 499.9, northMeters = 10_000.0, timestamp = 60_000L),
            config,
        )
        val movedAway = guide(
            exit.nextState,
            frame(eastMeters = 800.0, northMeters = 10_000.0, timestamp = 120_000L),
            config,
        )
        assertIs<Guidance.Approach>(movedAway.guidance)
        assertFalse(movedAway.nextState.waitingForRoute)
        assertEquals(1, movedAway.nextState.systemEvents.count { it.kind == "waiting.enter" })
    }

    @Test
    fun elapsedClockStartsAtWaitingExit() {
        val config = GuideConfig(elapsedEnabled = true, sunsetEnabled = false)
        var state = GuideState.initial(route)
        val duringWaiting = mutableListOf<GuideResult>()
        val entry = guide(state, frame(3_000.0, 10_000.0, 0L), config)
        state = entry.nextState
        for (seconds in listOf(3_600L, 7_200L)) {
            val result = guide(state, frame(3_000.0, 10_000.0, seconds * 1_000L), config)
            state = result.nextState
            duringWaiting += result
        }
        val exit = guide(state, frame(499.9, 10_000.0, 10_800_000L), config)
        state = exit.nextState
        duringWaiting += exit
        assertEquals(10_800_000L, state.sessionStartTimestamp)
        assertTrue(duringWaiting.none { it.guidance is Guidance.Elapsed })

        val approach = guide(state, frame(14.0, 10_000.0, 10_801_000L), config)
        state = approach.nextState
        val beforeBoundary = guide(state, frame(14.0, 10_100.0, 14_399_000L), config)
        state = beforeBoundary.nextState
        assertTrue(beforeBoundary.guidance !is Guidance.Elapsed)
        val atBoundary = guide(state, frame(14.0, 10_101.2, 14_400_000L), config)
        assertEquals(Guidance.Elapsed(1), atBoundary.guidance)
    }

    @Test
    fun sunsetAndSunriseStaySilentDuringWaitingAndSunsetStartsOnceAfterExit() {
        val sunsetConfig = GuideConfig(eventMinIntervalSeconds = 0.0)
        val sunset = Instant.parse("2026-09-21T09:31:43Z").toEpochMilli() / 1_000.0
        var sunsetState = GuideState.initial(route)
        val sunsetEntry = guide(sunsetState, frame(2_500.0, 5_000.0, (sunset * 1_000.0).toLong() - 65 * 60_000L), sunsetConfig)
        sunsetState = sunsetEntry.nextState
        assertIs<Guidance.WaitingStart>(sunsetEntry.guidance)
        val rejectedSunsetFrame = guide(
            sunsetState,
            frame(2_500.0, 5_000.0, (sunset * 1_000.0).toLong() - 58 * 60_000L, accuracy = 80f),
            sunsetConfig,
        )
        sunsetState = rejectedSunsetFrame.nextState
        assertNull(rejectedSunsetFrame.guidance)
        assertFalse(sunsetState.sunsetEvaluated)
        val sunsetWaitingResults = listOf(-58L, -28L).map { minutes ->
            val result = guide(
                sunsetState,
                frame(2_500.0, 5_000.0, (sunset * 1_000.0).toLong() + minutes * 60_000L),
                sunsetConfig,
            )
            sunsetState = result.nextState
            result
        }
        assertTrue(sunsetWaitingResults.none { it.guidance is Guidance.Sunset })
        val afterWaitSunset = guide(
            sunsetState,
            frame(5.0, 5_000.0, (sunset * 1_000.0).toLong() - 25 * 60_000L),
            sunsetConfig,
        )
        assertIs<Guidance.Sunset>(afterWaitSunset.guidance)
        assertEquals("true", afterWaitSunset.reason.details["startAnnouncement"])
        assertTrue(afterWaitSunset.nextState.consumedSunsetThresholds.containsAll(setOf(60, 30)))
        val repeatedSunset = guide(
            afterWaitSunset.nextState,
            frame(5.0, 5_000.0, (sunset * 1_000.0).toLong() - 24 * 60_000L),
            sunsetConfig,
        )
        assertTrue(repeatedSunset.guidance !is Guidance.Sunset)

        val sunriseConfig = GuideConfig(sunsetEnabled = false, sunriseEnabled = true, eventMinIntervalSeconds = 0.0)
        val sunriseSeed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(sunriseSeed, latitude, longitude)!!
        var sunriseState = GuideState.initial(route)
        val sunriseEntry = guide(sunriseState, frame(2_500.0, 5_000.0, (sunrise * 1_000.0).toLong() - 35 * 60_000L), sunriseConfig)
        sunriseState = sunriseEntry.nextState
        val rejectedSunriseFrame = guide(
            sunriseState,
            frame(2_500.0, 5_000.0, (sunrise * 1_000.0).toLong() - 29 * 60_000L, accuracy = 80f),
            sunriseConfig,
        )
        sunriseState = rejectedSunriseFrame.nextState
        assertNull(rejectedSunriseFrame.guidance)
        assertFalse(sunriseState.sunriseEvaluated)
        val sunriseWaitingResults = listOf(-29L, -9L).map { minutes ->
            val result = guide(
                sunriseState,
                frame(2_500.0, 5_000.0, (sunrise * 1_000.0).toLong() + minutes * 60_000L),
                sunriseConfig,
            )
            sunriseState = result.nextState
            result
        }
        assertTrue(sunriseWaitingResults.none { it.guidance is Guidance.Sunrise })
        val afterWaitSunrise = guide(
            sunriseState,
            frame(5.0, 5_000.0, (sunrise * 1_000.0).toLong() - 5 * 60_000L),
            sunriseConfig,
        )
        assertIs<Guidance.Sunrise>(afterWaitSunrise.guidance)
        assertFalse(afterWaitSunrise.nextState.waitingForRoute)
    }

    @Test
    fun waitingWithNoRouteMatchDoesNotEvaluateSolarEvents() {
        val emptyRoute = RouteModel.empty()
        val config = GuideConfig(sunriseEnabled = true)
        val waiting = GuideState.initial(emptyRoute).copy(
            waitingDecisionEvaluated = true,
            waitingForRoute = true,
            waitingStartedAtMillis = 1L,
        )
        val result = guide(waiting, SensorFrame(60_001L, latitude, longitude, 5f, 0f, null), config)
        assertNull(result.guidance)
        assertFalse(result.nextState.sunsetEvaluated)
        assertFalse(result.nextState.sunriseEvaluated)
    }

    @Test
    fun roundTripStartWaitsUntilAfterWaitingAndStartupResolution() {
        val shortRoute = straightRoute(2_000.0)
        val config = GuideConfig(roundTripEnabled = true, sunsetEnabled = false)
        var state = GuideState.starting(shortRoute, config)
        val waitingResults = mutableListOf<GuideResult>()
        val entry = guide(state, frame(5_000.0, 1_000.0, 0L), config)
        state = entry.nextState
        waitingResults += entry
        for (index in 1..3) {
            val result = guide(state, frame(4_000.0 - index * 500.0, 1_000.0, index * 60_000L), config)
            state = result.nextState
            waitingResults += result
        }
        assertTrue(waitingResults.none { it.guidance == Guidance.RoundTripStart })
        val exit = guide(state, frame(499.9, 1_000.0, 240_000L), config)
        assertEquals(Guidance.RoundTripStart, exit.guidance)
        state = exit.nextState
        val later = mutableListOf<GuideResult>()
        for (index in 0..60) {
            val result = guide(
                state,
                frame(5.0, 1_000.0 + index * 1.2, 241_000L + index * 1_000L),
                config,
            )
            state = result.nextState
            later += result
        }
        assertEquals(
            1,
            1 + later.count { it.guidance == Guidance.RoundTripStart },
            "startupResolved=${state.startupResolved} route=${state.route.roundTripTurnaroundMeters} direction=${state.direction} events=${state.systemEvents}",
        )
        assertTrue(state.roundTripStartAnnounced)
    }

    private fun straightRoute(lengthMeters: Double): RouteModel {
        val endLatitude = latitude + lengthMeters / radius * 180.0 / PI
        return RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"$latitude\" lon=\"$longitude\"/><trkpt lat=\"$endLatitude\" lon=\"$longitude\"/></trkseg></trk></gpx>",
        )
    }

    private fun frame(eastMeters: Double, northMeters: Double, timestamp: Long, accuracy: Float = 5f): SensorFrame = SensorFrame(
        timestamp = timestamp,
        lat = latitude + northMeters / radius * 180.0 / PI,
        lon = longitude + eastMeters / (radius * kotlin.math.cos(Math.toRadians(latitude))) * 180.0 / PI,
        accuracy = accuracy,
        speed = 1.2f,
        gpsBearing = 0f,
    )
}
