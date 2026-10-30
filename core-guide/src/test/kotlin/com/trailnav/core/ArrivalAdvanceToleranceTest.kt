package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ArrivalAdvanceToleranceTest {
    private data class StartNoiseSummary(
        val falseArrivals: Int,
        val stoppedFalseArrivals: Int,
        val walkingFalseArrivals: Int,
    )

    private data class SessionRun(
        val route: RouteModel,
        val frames: List<SensorFrame>,
        val results: List<GuideResult>,
    ) {
        val finalState: GuideState get() = results.last().nextState
        val arrivalFrameIndices: List<Int>
            get() = results.indices.filter { results[it].guidance == Guidance.Arrived }
    }

    // D-178 upper bound: 5 seeds x N=1000 (177001, 177901..177904) pooled 88/5000 = 1.76 %;
    // p + 2.33 * sqrt(p * (1 - p) / 1000) = 2.73 %, rounded up to 0.5 %p = 3 % (30/1000).
    // Counterexample mutations must exceed twice the bound (60/1000): tolerance 20 m 139, no advance requirement 621.
    @Test
    fun circularStartWithSigma15AndOneHzWalkingStaysWithinThreePercentAtN1000() {
        val summary = runCircularStartNoise(trials = 1_000, sigma = 15.0, seed = 177_001)
        val percent = percent(summary.falseArrivals, 1_000)
        println(
            "TASK082_S1B sigma=15 n=1000 seed=177001 false_arrivals=${summary.falseArrivals}/1000 " +
                "(${percent}%) stopped=${summary.stoppedFalseArrivals}/1000 walking=${summary.walkingFalseArrivals}/1000",
        )

        assertTrue(
            summary.falseArrivals <= 30,
            "S1b false arrivals ${summary.falseArrivals}/1000 (${percent}%), upper bound 3 % (D-178); " +
                "stopped=${summary.stoppedFalseArrivals}/1000, walking=${summary.walkingFalseArrivals}/1000",
        )
    }

    @Test
    fun circularStartWithSigma5And10HasNoFalseArrivalAtN1000() {
        listOf(5.0 to 177_005, 10.0 to 177_010).forEach { (sigma, seed) ->
            val summary = runCircularStartNoise(trials = 1_000, sigma = sigma, seed = seed)
            println(
                "TASK082_S1B sigma=$sigma n=1000 seed=$seed false_arrivals=${summary.falseArrivals}/1000 " +
                    "stopped=${summary.stoppedFalseArrivals}/1000 walking=${summary.walkingFalseArrivals}/1000",
            )
            assertEquals(
                0,
                summary.falseArrivals,
                "S1b sigma=$sigma false arrivals ${summary.falseArrivals}/1000; expected zero",
            )
        }
    }

    @Test
    fun circularStartWithSigma15AndTolerance20ExceedsTwiceTheBound() {
        val summary = runCircularStartNoise(
            trials = 1_000,
            sigma = 15.0,
            seed = 177_001,
            config = GuideConfig(arrivalJumpToleranceMeters = 20.0),
        )
        println(
            "TASK082_COUNTEREXAMPLE tolerance=20 sigma=15 n=1000 seed=177001 " +
                "false_arrivals=${summary.falseArrivals}/1000 (${percent(summary.falseArrivals, 1_000)}%)",
        )
        assertTrue(
            summary.falseArrivals >= 60,
            "20 m counterexample must reach twice the 3 % bound (60/1000), measured ${summary.falseArrivals}/1000",
        )
    }

    @Test
    fun circularStartWithArrivalAdvanceRequirementAtMinimumExceedsTwiceTheBound() {
        val summary = runCircularStartNoise(
            trials = 1_000,
            sigma = 15.0,
            seed = 177_001,
            config = GuideConfig(arrivalAdvanceFraction = Double.MIN_VALUE, arrivalMinAdvanceMeters = 0.0),
        )
        println(
            "TASK082_COUNTEREXAMPLE advance_requirement=min sigma=15 n=1000 seed=177001 " +
                "false_arrivals=${summary.falseArrivals}/1000 (${percent(summary.falseArrivals, 1_000)}%)",
        )
        assertTrue(
            summary.falseArrivals >= 60,
            "minimum arrival advance counterexample must reach twice the 3 % bound (60/1000), measured ${summary.falseArrivals}/1000",
        )
    }

    private fun runCircularStartNoise(
        trials: Int,
        sigma: Double,
        seed: Int,
        config: GuideConfig = GuideConfig(),
    ): StartNoiseSummary {
        val route = circleRoute()
        val random = Random(seed)
        var falseArrivals = 0
        var stoppedFalseArrivals = 0
        var walkingFalseArrivals = 0

        repeat(trials) {
            val tail = pointAt(route, route.totalLengthMeters - 5.0)
            var state = GuideState.initial(route)
            var result = guide(
                state,
                withOffset(tail, normalApprox(random, sigma), normalApprox(random, sigma), 0L, 0f),
                config,
            )
            state = result.nextState
            var firstArrivalFrame = if (result.guidance == Guidance.Arrived) 0 else null

            for (frameIndex in 1..29) {
                result = guide(
                    state,
                    withOffset(
                        tail,
                        normalApprox(random, sigma),
                        normalApprox(random, sigma),
                        frameIndex * 1_000L,
                        0f,
                    ),
                    config,
                )
                state = result.nextState
                if (firstArrivalFrame == null && result.guidance == Guidance.Arrived) firstArrivalFrame = frameIndex
            }

            for (frameIndex in 30..700) {
                val distance = (frameIndex - 30) * 1.4
                result = guide(
                    state,
                    withOffset(
                        pointAt(route, distance),
                        normalApprox(random, sigma),
                        normalApprox(random, sigma),
                        frameIndex * 1_000L,
                        1.4f,
                    ),
                    config,
                )
                state = result.nextState
                if (firstArrivalFrame == null && result.guidance == Guidance.Arrived) firstArrivalFrame = frameIndex
            }

            when {
                firstArrivalFrame == null -> Unit
                firstArrivalFrame!! < 30 -> {
                    falseArrivals++
                    stoppedFalseArrivals++
                }
                else -> {
                    falseArrivals++
                    walkingFalseArrivals++
                }
            }
        }

        return StartNoiseSummary(falseArrivals, stoppedFalseArrivals, walkingFalseArrivals)
    }

    @Test
    fun fullCircularOneHzWalkWithSigma5_10_15ArrivesInEveryTrialAtN1000() {
        listOf(5.0 to 177_105, 10.0 to 177_110, 15.0 to 177_115).forEach { (sigma, seed) ->
            val arrivals = mutableListOf<Int>()
            val delays = mutableListOf<Int>()
            val route = circleRoute()
            val random = Random(seed)
            val lastFrame = ceil(route.totalLengthMeters / 1.4).toInt()
            repeat(1_000) { trial ->
                var state = GuideState.initial(route)
                var firstNewArrival: Int? = null
                var firstOldCandidate: Int? = null
                for (frameIndex in 0..lastFrame) {
                    val point = pointAt(route, minOf(frameIndex * 1.4, route.totalLengthMeters))
                    val frame = if (frameIndex == 0) {
                        withOffset(point, 0.0, 0.0, 0L, 1.4f)
                    } else {
                        withOffset(
                            point,
                            normalApprox(random, sigma),
                            normalApprox(random, sigma),
                            frameIndex * 1_000L,
                            1.4f,
                        )
                    }
                    val result = guide(state, frame)
                    state = result.nextState
                    if (firstOldCandidate == null && oldArrivalCandidate(route, result, frame.timestamp)) {
                        firstOldCandidate = frameIndex
                    }
                    if (firstNewArrival == null && result.guidance == Guidance.Arrived) {
                        firstNewArrival = frameIndex
                    }
                    if (firstNewArrival != null && firstOldCandidate != null) break
                }
                assertNotNull(firstNewArrival, "S2b sigma=$sigma did not arrive in trial $trial")
                assertNotNull(firstOldCandidate, "S2b sigma=$sigma legacy arrival candidate missing in trial $trial")
                arrivals += trial
                delays += maxOf(0, firstNewArrival!! - firstOldCandidate!!)
            }
            assertEquals(1_000, arrivals.size, "S2b sigma=$sigma arrivals")
            println(
                "TASK082_S2B sigma=$sigma n=1000 seed=$seed arrivals=${arrivals.size}/1000 " +
                    "max_delay_frames=${delays.maxOrNull()}",
            )
        }
    }

    private fun percent(count: Int, trials: Int): String =
        String.format(Locale.ROOT, "%.1f", count * 100.0 / trials)

    @Test
    fun storedComboBArrivesOn243rdLocationOnlyWithFortyMeterTolerance() {
        val directory = sessionDirectory("11_combo_b")
        val defaultRun = runSession(directory, GuideConfig())
        assertEquals(260, defaultRun.frames.size)
        assertEquals(listOf(242), defaultRun.arrivalFrameIndices, "expected arrival on one-based loc frame 243")

        val strictRun = runSession(directory, GuideConfig(arrivalJumpToleranceMeters = 20.0))
        assertTrue(strictRun.arrivalFrameIndices.isEmpty(), "20 m tolerance unexpectedly arrived")
    }

    @Test
    fun storedComboEDiscontinuitiesAreNotCreditedAsArrivalAdvance() {
        val run = runSession(sessionDirectory("14_combo_e"), GuideConfig())
        assertTrue(run.arrivalFrameIndices.isEmpty(), "an impossible progress teleport must not cause arrival")
        val baseline = assertNotNull(run.finalState.arrivalBaselineRemainingMeters)
        val required = maxOf(
            GuideConfig().arrivalAdvanceFraction * baseline,
            minOf(GuideConfig().arrivalMinAdvanceMeters, GuideConfig().arrivalAdvanceFraction * run.route.totalLengthMeters),
        )
        assertTrue(run.finalState.advanceCreditedMeters < required)
        assertEquals(1, run.finalState.arrivalBlockedRecords.size)
        assertTrue(maximumLocationStepMeters(run.frames) >= 100.0, "fixture must contain a progress discontinuity")
    }

    @Test
    fun continuousReverseOverlapArrivesAndRejectsTeleportAndImpossibleWalkingSpeed() {
        val continuous = runSession(sessionDirectory("18_combo_e_continuous"), GuideConfig())
        val teleport = runSession(sessionDirectory("14_combo_e"), GuideConfig())
        val impossibleWalk = runSession(
            sessionDirectory("18_combo_e_continuous"),
            GuideConfig(arrivalWalkMaxSpeedMps = 0.5, arrivalJumpToleranceMeters = 0.0),
        )

        assertTrue(continuous.arrivalFrameIndices.isNotEmpty(), "continuous reverse overlap should arrive")
        assertTrue(maximumLocationStepMeters(continuous.frames) <= 3.5)
        assertTrue(
            continuous.results.indices.any { index ->
                index in 100..178 && continuous.results[index].nextState.direction == ProgressDirection.REVERSE
            },
            "reverse walking segment was not observed by the engine",
        )
        assertTrue(teleport.arrivalFrameIndices.isEmpty(), "14_combo_e must remain blocked")
        assertTrue(impossibleWalk.arrivalFrameIndices.isEmpty(), "0.5 m/s with zero jump tolerance must block this walk")
    }

    private fun sessionDirectory(name: String): Path =
        E5EventCountSupport.repositoryRoot().resolve("testdata/sessions/synth").resolve(name)

    private fun runSession(directory: Path, config: GuideConfig): SessionRun {
        val route = RouteModel.fromGpx(Files.readString(directory.resolve("route.gpx")))
        val frames = Files.readAllLines(directory.resolve("events.ndjson"))
            .filter { it.contains("\"stream\":\"loc\"") || it.contains("\"stream\": \"loc\"") }
            .map { line ->
                SensorFrame(
                    timestamp = (number(line, "t") * 1_000.0).toLong(),
                    lat = number(line, "lat"),
                    lon = number(line, "lon"),
                    accuracy = number(line, "accuracy").toFloat(),
                    speed = number(line, "speed_mps").toFloat(),
                    gpsBearing = numberOrNull(line, "bearing_deg")?.toFloat(),
                )
            }
        require(frames.isNotEmpty()) { "no location frames in " + directory.fileName }
        var state = GuideState.initial(route)
        val results = frames.map { frame ->
            guide(state, frame, config).also { state = it.nextState }
        }
        return SessionRun(route, frames, results)
    }

    private fun number(line: String, key: String): Double =
        requireNotNull(numberOrNull(line, key)) { "missing numeric field " + key }

    private fun numberOrNull(line: String, key: String): Double? =
        Regex("\"" + key + "\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)")
            .find(line)?.groupValues?.get(1)?.toDouble()

    private fun maximumLocationStepMeters(frames: List<SensorFrame>): Double =
        frames.zipWithNext().maxOfOrNull { (a, b) -> distanceMeters(a, b) } ?: 0.0

    private fun distanceMeters(a: SensorFrame, b: SensorFrame): Double {
        val latitude = Math.toRadians(a.lat)
        val dLatitude = Math.toRadians(b.lat - a.lat)
        val dLongitude = Math.toRadians(b.lon - a.lon)
        val east = dLongitude * EARTH_RADIUS_METERS * cos(latitude)
        val north = dLatitude * EARTH_RADIUS_METERS
        return hypot(east, north)
    }

    private fun oldArrivalCandidate(route: RouteModel, result: GuideResult, timestamp: Long): Boolean {
        val match = result.nextState.lastMatch ?: return false
        val start = result.nextState.sessionStartTimestamp ?: timestamp
        val end = route.points.last()
        val projectedEndDistance = hypot(match.projectedPoint.east - end.east, match.projectedPoint.north - end.north)
        val config = GuideConfig()
        return (timestamp - start) / 1_000.0 >= config.minimumSessionSecondsBeforeArrival &&
            projectedEndDistance <= config.arriveRadiusMeters &&
            match.projectedMeters > route.totalLengthMeters * config.arriveProgressFraction
    }

    private fun circleRoute(): RouteModel {
        val radius = 1_200.0 / (2.0 * PI)
        val metersPerLatitudeDegree = EARTH_RADIUS_METERS * PI / 180.0
        val metersPerLongitudeDegree = EARTH_RADIUS_METERS * cos(Math.toRadians(10.0)) * PI / 180.0
        val points = (0..96).map { index ->
            val angle = 2.0 * PI * index / 96.0
            val lat = 10.0 + radius * sin(angle) / metersPerLatitudeDegree
            val lon = 20.0 + radius * (cos(angle) - 1.0) / metersPerLongitudeDegree
            GeoPoint(lat, lon)
        }
        val track = points.joinToString("") { point ->
            "<trkpt lat=\"" + point.lat + "\" lon=\"" + point.lon + "\"/>"
        }
        return RouteModel.fromGpx("<gpx><trk><trkseg>" + track + "</trkseg></trk></gpx>")
    }

    private fun pointAt(route: RouteModel, meters: Double): GeoPoint {
        val distance = meters.coerceIn(0.0, route.totalLengthMeters)
        val segment = (0 until route.cumulativeMeters.lastIndex).firstOrNull { index ->
            route.cumulativeMeters[index + 1] >= distance
        } ?: route.cumulativeMeters.lastIndex - 1
        val start = route.sourcePoints[segment]
        val end = route.sourcePoints[segment + 1]
        val length = route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment]
        val ratio = if (length <= 0.0) 0.0 else (distance - route.cumulativeMeters[segment]) / length
        return GeoPoint(start.lat + (end.lat - start.lat) * ratio, start.lon + (end.lon - start.lon) * ratio)
    }

    private fun withOffset(point: GeoPoint, east: Double, north: Double, timestamp: Long, speed: Float): SensorFrame {
        val metersPerLatitudeDegree = EARTH_RADIUS_METERS * PI / 180.0
        val metersPerLongitudeDegree = EARTH_RADIUS_METERS * cos(Math.toRadians(point.lat)) * PI / 180.0
        return SensorFrame(
            timestamp = timestamp,
            lat = point.lat + north / metersPerLatitudeDegree,
            lon = point.lon + east / metersPerLongitudeDegree,
            accuracy = 5f,
            speed = speed,
            gpsBearing = null,
        )
    }

    private fun normalApprox(random: Random, sigma: Double): Double =
        (0 until 12).sumOf { random.nextDouble() - 0.5 } * sigma

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
