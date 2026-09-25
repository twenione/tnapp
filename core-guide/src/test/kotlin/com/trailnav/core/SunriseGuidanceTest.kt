package com.trailnav.core

import java.time.Instant
import kotlin.test.Test

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
}
