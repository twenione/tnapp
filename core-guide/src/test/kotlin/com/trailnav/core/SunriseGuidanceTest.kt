package com.trailnav.core

import java.time.Instant
import kotlin.test.Test
import kotlin.math.roundToLong

class SunriseGuidanceTest {
    private val route = RouteModel.fromGpx("""<gpx><trk><trkseg>
        <trkpt lat="37.5665" lon="126.9780"/><trkpt lat="37.5865" lon="126.9780"/>
    </trkseg></trk></gpx>""")

    private val config = GuideConfig(
        sunriseEnabled = true,
        sunriseAnnounceMinutes = listOf(30, 10),
        eventMinIntervalSeconds = 0.0,
        sunsetEnabled = false,
    )

    @Test
    fun thresholdCrossingEmitsOnceAndConsumesThreshold() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val first = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise - 31.0 * 60.0) * 1000.0).toLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(first.guidance == null)
        val crossing = guide(
            first.nextState,
            SensorFrame(((sunrise - 29.0 * 60.0) * 1000.0).toLong(), 37.5670, 126.9780, 5f, 1f, null),
            config,
        )
        check(crossing.guidance is Guidance.Sunrise)
        check(30 in crossing.nextState.consumedSunriseThresholds)
        val repeat = guide(
            crossing.nextState,
            SensorFrame(((sunrise - 28.0 * 60.0) * 1000.0).toLong(), 37.5675, 126.9780, 5f, 1f, null),
            config,
        )
        check(repeat.guidance !is Guidance.Sunrise)
    }

    @Test
    fun disabledSunriseStillConsumesCrossedThreshold() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val disabled = config.copy(sunriseEnabled = false)
        val result = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise - 5.0 * 60.0) * 1000.0).toLong(), 37.5665, 126.9780, 5f, 1f, null),
            disabled,
        )
        check(result.guidance !is Guidance.Sunrise)
        check(result.nextState.consumedSunriseThresholds.containsAll(listOf(30, 10)))
    }

    @Test
    fun sunriseAfterStartAndAtZeroDoNotAnnounce() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val after = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise + 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(after.guidance !is Guidance.Sunrise)
        val zero = guide(
            GuideState.initial(route),
            SensorFrame((sunrise * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(zero.guidance !is Guidance.Sunrise)

        val justBefore = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise - 0.1 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(justBefore.guidance !is Guidance.Sunrise)
    }

    @Test
    fun sunriseCrossingAtZeroDoesNotAnnounce() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val first = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise - 11.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        val zero = guide(
            first.nextState,
            SensorFrame(((sunrise + 0.5 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(zero.guidance !is Guidance.Sunrise)
    }

    @Test
    fun sunriseLocalDayResetsConsumptionAndOffRouteOnlyConsumes() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val firstSunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val nextSunrise = sunriseEpochSeconds(((firstSunrise + 86_400.0) * 1000.0).roundToLong(), 37.5665, 126.9780)!!
        val oldLocalDay = Instant.ofEpochMilli(seed)
            .atZone(java.time.ZoneOffset.UTC)
            .plusSeconds((126.9780 * 240.0).toLong())
            .toLocalDate()
            .toEpochDay()
        val resetState = GuideState.initial(route).copy(
            sunriseLocalDay = oldLocalDay,
            sunriseEvaluated = true,
            consumedSunriseThresholds = setOf(30, 10),
            lastTimestamp = ((nextSunrise - 31.0 * 60.0) * 1000.0).roundToLong(),
            sessionStartTimestamp = ((nextSunrise - 31.0 * 60.0) * 1000.0).roundToLong(),
        )
        val reset = guide(
            resetState,
            SensorFrame(((nextSunrise - 29.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(reset.guidance is Guidance.Sunrise)
        check(reset.nextState.sunriseLocalDay != oldLocalDay)

        val offRouteState = GuideState.initial(route).copy(offRoute = true)
        val offRoute = guide(
            offRouteState,
            SensorFrame(((firstSunrise - 5.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(offRoute.guidance !is Guidance.Sunrise)
        check(offRoute.nextState.consumedSunriseThresholds.containsAll(listOf(30, 10)))
    }

    @Test
    fun sunriseEpochUsesTheLocalCalendarDay() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val instant = Instant.ofEpochSecond(sunrise.toLong())
        check(instant.isAfter(Instant.parse("2026-09-20T20:30:00Z")))
        check(instant.isBefore(Instant.parse("2026-09-20T22:00:00Z")))
    }

    @Test
    fun sunriseMinIntervalDelaysButConsumesTheNextThreshold() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val intervalConfig = config.copy(eventMinIntervalSeconds = 1800.0)
        val first = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise - 31.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            intervalConfig,
        )
        val thirty = guide(
            first.nextState,
            SensorFrame(((sunrise - 29.0 * 60.0) * 1000.0).roundToLong(), 37.5670, 126.9780, 5f, 1f, null),
            intervalConfig,
        )
        check(thirty.guidance is Guidance.Sunrise)
        val ten = guide(
            thirty.nextState,
            SensorFrame(((sunrise - 9.0 * 60.0) * 1000.0).roundToLong(), 37.5675, 126.9780, 5f, 1f, null),
            intervalConfig,
        )
        check(ten.guidance !is Guidance.Sunrise)
        check(10 in ten.nextState.consumedSunriseThresholds)
    }
}
