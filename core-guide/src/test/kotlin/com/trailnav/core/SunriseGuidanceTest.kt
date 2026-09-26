package com.trailnav.core

import java.time.Instant
import kotlin.test.Test
import kotlin.math.roundToLong

class SunriseGuidanceTest {
    private data class SunriseReference(
        val name: String,
        val localDate: String,
        val latitude: Double,
        val longitude: Double,
        val expectedUtc: String?,
    )

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
    fun toggleOffSilentButConsumes() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val crossingFrame = SensorFrame(((sunrise - 5.0 * 60.0) * 1000.0).toLong(), 37.5665, 126.9780, 5f, 1f, null)
        val enabled = guide(GuideState.initial(route), crossingFrame, config)
        check(enabled.guidance is Guidance.Sunrise)
        val disabled = config.copy(sunriseEnabled = false)
        val result = guide(
            GuideState.initial(route),
            crossingFrame,
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

    }

    @Test
    fun afterSunriseConsumesThresholdsWithoutAnnouncing() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val beforeTimestamp = ((sunrise - 1.0 * 60.0) * 1000.0).roundToLong()
        val localDay = Instant.ofEpochMilli(beforeTimestamp)
            .atZone(java.time.ZoneOffset.UTC)
            .plusSeconds((126.9780 * 240.0).toLong())
            .toLocalDate()
            .toEpochDay()
        val before = GuideState.initial(route).copy(
            sunriseEvaluated = true,
            sunriseLocalDay = localDay,
            lastTimestamp = beforeTimestamp,
            sessionStartTimestamp = beforeTimestamp,
        )
        val after = guide(
            before,
            SensorFrame(((sunrise + 0.5 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 1f, null),
            config,
        )
        check(after.guidance !is Guidance.Sunrise)
        check(after.nextState.consumedSunriseThresholds.containsAll(listOf(30, 10)))
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

    private fun referenceRows() = listOf(
        SunriseReference("Seoul-2026-09-21", "2026-09-21", 37.5665, 126.9780, "2026-09-20T21:18:44Z"),
        SunriseReference("Seoul-2026-03-20", "2026-03-20", 37.5665, 126.9780, "2026-03-19T21:36:28Z"),
        SunriseReference("Seoul-2024-09-21", "2024-09-21", 37.5665, 126.9780, "2024-09-20T21:19:09Z"),
        SunriseReference("Seoul-2028-09-21", "2028-09-21", 37.5665, 126.9780, "2028-09-20T21:19:11Z"),
        SunriseReference("Busan-2026-12-21", "2026-12-21", 35.1796, 129.0756, "2026-12-20T22:28:06Z"),
        SunriseReference("Jeju-2026-06-21", "2026-06-21", 33.4996, 126.5312, "2026-06-20T20:24:25Z"),
        SunriseReference("Denver-2026-09-21", "2026-09-21", 39.7392, -104.9903, "2026-09-21T12:46:53Z"),
        SunriseReference("Sydney-2026-12-21", "2026-12-21", -33.8688, 151.2093, "2026-12-20T18:40:45Z"),
        SunriseReference("Auckland-2026-06-21", "2026-06-21", -36.8485, 174.7633, "2026-06-20T19:33:47Z"),
        SunriseReference("Tromso-2026-06-21", "2026-06-21", 69.6492, 18.9553, null),
    )

    private fun referenceSeed(row: SunriseReference): Long {
        val midnightUtc = java.time.LocalDate.parse(row.localDate)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        return midnightUtc - (row.longitude * 240_000.0).toLong() + 12L * 60L * 60L * 1_000L
    }

    @Test
    fun sunriseEpochSecondsMatchesReferenceRows() {
        referenceRows().forEach { row ->
            val actual = sunriseEpochSeconds(referenceSeed(row), row.latitude, row.longitude)
            val expected = row.expectedUtc?.let { Instant.parse(it).toEpochMilli() / 1000.0 }
            if (expected == null) {
                check(actual == null) { "${row.name}: expected polar-day null but got $actual" }
            } else {
                check(actual != null && kotlin.math.abs(actual - expected) <= 120.0) {
                    "${row.name}: expected $expected, got $actual seconds"
                }
            }
        }
    }

    @Test
    fun referenceTableEmitsAtThirtyAndTenMinuteCrossings() {
        referenceRows().forEach { row ->
            val reference = row.expectedUtc?.let { Instant.parse(it).toEpochMilli() / 1000.0 }
            val rowRoute = RouteModel.fromGpx(
                "<gpx><trk><trkseg><trkpt lat=\"${row.latitude}\" lon=\"${row.longitude}\"/>" +
                    "<trkpt lat=\"${row.latitude + 0.02}\" lon=\"${row.longitude}\"/></trkseg></trk></gpx>"
            )
            var state = GuideState.initial(rowRoute)
            listOf(-35, -28, -12, -8).forEach { offset ->
                val timestamp = if (reference == null) {
                    Instant.parse("2026-06-20T12:00:00Z").toEpochMilli() + offset * 60_000L
                } else {
                    ((reference + offset * 60.0) * 1000.0).roundToLong()
                }
                val result = guide(
                    state,
                    SensorFrame(timestamp, row.latitude, row.longitude, 5f, 0f, null),
                    config,
                )
                val shouldSpeak = reference != null && offset in setOf(-28, -8)
                check((result.guidance is Guidance.Sunrise) == shouldSpeak) {
                    "${row.name} at $offset minutes: unexpected guidance ${result.guidance}"
                }
                if (result.guidance is Guidance.Sunrise) {
                    val expectedMinutes = -offset
                    val actualMinutes = result.reason.details["minutesRemaining"]!!.toInt()
                    check(kotlin.math.abs(actualMinutes - expectedMinutes) <= 2) {
                        "${row.name} at $offset minutes: remaining=$actualMinutes"
                    }
                    val actualSunrise = result.reason.details["sunriseEpochSeconds"]!!.toDouble()
                    check(kotlin.math.abs(actualSunrise - reference!!) <= 120.0) {
                        "${row.name}: reason sunrise differs from the independent reference"
                    }
                }
                state = result.nextState
            }
        }
    }

    private fun runAtOffsets(startOffsetMinutes: Double, laterOffsets: List<Double>): List<GuideResult> {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        var state = GuideState.initial(route)
        return (listOf(startOffsetMinutes) + laterOffsets).map { offset ->
            val result = guide(
                state,
                SensorFrame(((sunrise + offset * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
                config,
            )
            state = result.nextState
            result
        }
    }

    @Test
    fun startRules() {
        val fortyBefore = runAtOffsets(-40.0, listOf(-35.0, -28.0, -12.0, -8.0, -2.0, 2.0, 10.0))
        check(fortyBefore.mapIndexedNotNull { index, result -> if (result.guidance is Guidance.Sunrise) index else null } == listOf(2, 4))
        check((fortyBefore[2].guidance as Guidance.Sunrise).minutesRemaining in 26..30)
        check((fortyBefore[4].guidance as Guidance.Sunrise).minutesRemaining in 6..10)

        val twentyFiveBefore = runAtOffsets(-25.0, listOf(-8.0))
        check((twentyFiveBefore[0].guidance as Guidance.Sunrise).minutesRemaining in 24..26)
        check((twentyFiveBefore[1].guidance as Guidance.Sunrise).minutesRemaining in 7..9)

        val fiveBefore = runAtOffsets(-5.0, listOf(-2.0, 2.0, 10.0))
        check(fiveBefore.count { it.guidance is Guidance.Sunrise } == 1)
        check((fiveBefore[0].guidance as Guidance.Sunrise).minutesRemaining in 4..6)

        val after = runAtOffsets(5.0, listOf(10.0))
        check(after.none { it.guidance is Guidance.Sunrise })

        val twentySecondsBefore = runAtOffsets(-20.0 / 60.0, emptyList()).single()
        check((twentySecondsBefore.guidance as Guidance.Sunrise).minutesRemaining == 1)
    }

    @Test
    fun announceMinutesConfigChangesCrossings() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        fun observed(configuredThresholds: List<Int>): List<Pair<Int, Int>> {
            val configured = config.copy(sunriseAnnounceMinutes = configuredThresholds)
            var state = GuideState.initial(route)
            val observed = mutableListOf<Pair<Int, Int>>()
            listOf(-50, -40, -25, -7, -3).forEach { offset ->
                val result = guide(
                    state,
                    SensorFrame(((sunrise + offset * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
                    configured,
                )
                if (result.guidance is Guidance.Sunrise) {
                    observed += offset to result.guidance.minutesRemaining
                }
                state = result.nextState
            }
            return observed
        }
        val standard = observed(listOf(30, 10))
        val changed = observed(listOf(45, 5))
        check(standard == listOf(-25 to 25, -7 to 7)) { "default thresholds produced $standard" }
        check(changed == listOf(-40 to 40, -3 to 3)) { "changed thresholds produced $changed" }
    }

    @Test
    fun sunriseFiresInReverseAfterDwell() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val reverseConfig = config.copy(reverseWarningDwellSeconds = 60.0)
        var state = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise - 33.0 * 60.0) * 1000.0).roundToLong(), 37.5685, 126.9780, 5f, 1f, null),
            reverseConfig,
        ).nextState
        state = guide(
            state,
            SensorFrame(((sunrise - 31.0 * 60.0) * 1000.0).roundToLong(), 37.5675, 126.9780, 5f, 1f, null),
            reverseConfig,
        ).nextState
        val result = guide(
            state,
            SensorFrame(((sunrise - 29.0 * 60.0) * 1000.0).roundToLong(), 37.5670, 126.9780, 5f, 1f, null),
            reverseConfig,
        )
        check(result.nextState.direction == ProgressDirection.REVERSE)
        check(result.guidance is Guidance.Sunrise)
    }

    @Test
    fun roundsRemainingMinutes() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        var state = guide(
            GuideState.initial(route),
            SensorFrame(((sunrise - 31.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
            config,
        ).nextState
        val crossed = guide(
            state,
            SensorFrame(((sunrise - 29.98 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
            config,
        )
        check((crossed.guidance as Guidance.Sunrise).minutesRemaining == 30)
        val startedAt = runAtOffsets(-25.3, emptyList()).single()
        check((startedAt.guidance as Guidance.Sunrise).minutesRemaining == 25)
    }

    @Test
    fun overnightStartResetsThresholdsAtLocalMidnight() {
        val sunrise = Instant.parse("2026-09-20T21:18:44Z").toEpochMilli() / 1000.0
        val timestamps = listOf(
            Instant.parse("2026-09-20T13:32:00Z").toEpochMilli(),
            Instant.parse("2026-09-20T16:00:00Z").toEpochMilli(),
            ((sunrise - 35.0 * 60.0) * 1000.0).roundToLong(),
            ((sunrise - 28.0 * 60.0) * 1000.0).roundToLong(),
            ((sunrise - 12.0 * 60.0) * 1000.0).roundToLong(),
            ((sunrise - 8.0 * 60.0) * 1000.0).roundToLong(),
        )
        var state = GuideState.initial(route)
        val results = timestamps.map { timestamp ->
            val result = guide(state, SensorFrame(timestamp, 37.5665, 126.9780, 5f, 0f, null), config)
            state = result.nextState
            result
        }
        check(results.take(3).none { it.guidance is Guidance.Sunrise })
        check(results[3].guidance is Guidance.Sunrise)
        check(results[4].guidance !is Guidance.Sunrise)
        check(results[5].guidance is Guidance.Sunrise)
    }

    @Test
    fun offRouteSunriseThresholdIsConsumedWithoutLateAnnouncement() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        var state = GuideState.initial(route).copy(offRoute = true)
        val offRouteConfig = config.copy(offRouteExitDwellSeconds = 600.0)
        val first = guide(state, SensorFrame(((sunrise - 31.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null), offRouteConfig)
        state = first.nextState
        val crossedOffRoute = guide(state, SensorFrame(((sunrise - 29.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null), offRouteConfig)
        check(crossedOffRoute.guidance !is Guidance.Sunrise)
        check(30 in crossedOffRoute.nextState.consumedSunriseThresholds)
        val recovered = guide(
            crossedOffRoute.nextState.copy(offRoute = false),
            SensorFrame(((sunrise - 28.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
            offRouteConfig,
        )
        check(recovered.guidance !is Guidance.Sunrise)
    }

    @Test
    fun closedMinimumIntervalConsumesSunriseThreshold() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        val oneThreshold = config.copy(sunriseAnnounceMinutes = listOf(30), eventMinIntervalSeconds = 600.0)
        val firstTime = ((sunrise - 31.0 * 60.0) * 1000.0).roundToLong()
        val first = guide(GuideState.initial(route), SensorFrame(firstTime, 37.5665, 126.9780, 5f, 0f, null), oneThreshold)
        val closed = guide(
            first.nextState.copy(lastPeriodicEventAt = firstTime - 300_000L),
            SensorFrame(((sunrise - 29.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
            oneThreshold,
        )
        check(closed.guidance !is Guidance.Sunrise)
        check(30 in closed.nextState.consumedSunriseThresholds)
        val opened = guide(
            closed.nextState,
            SensorFrame(((sunrise - 15.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
            oneThreshold,
        )
        check(opened.guidance !is Guidance.Sunrise)
    }

    @Test
    fun rejectedAccuracyFrameDefersCrossingToNextGoodFrame() {
        val seed = Instant.parse("2026-09-20T19:00:00Z").toEpochMilli()
        val sunrise = sunriseEpochSeconds(seed, 37.5665, 126.9780)!!
        var state = GuideState.initial(route)
        val before = guide(
            state,
            SensorFrame(((sunrise - 31.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
            config,
        )
        state = before.nextState
        val rejected = guide(
            state,
            SensorFrame(((sunrise - 29.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 80f, 0f, null),
            config,
        )
        check(rejected.reason.rule == "input.accuracy-filter")
        check(30 !in rejected.nextState.consumedSunriseThresholds)
        val accepted = guide(
            rejected.nextState,
            SensorFrame(((sunrise - 27.0 * 60.0) * 1000.0).roundToLong(), 37.5665, 126.9780, 5f, 0f, null),
            config,
        )
        check(accepted.guidance is Guidance.Sunrise)
        check((accepted.guidance as Guidance.Sunrise).minutesRemaining in 26..28)
        check(accepted.nextState.consumedSunriseThresholds.contains(30))
    }
}
