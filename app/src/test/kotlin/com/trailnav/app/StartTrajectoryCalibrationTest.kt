package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private enum class StartTruthClass {
    OVERLAP_COMPLETE,
    BOUNDARY_NEAR,
    BOUNDARY_EXCEEDED,
    OVERLAP_NONE,
}

private data class CalibrationTrial(
    val startProgressMeters: Double,
    val generatedDirection: ProgressDirection,
    val locations: List<TrailLocation>,
)

private data class ExpectedStart(
    val classification: StartTruthClass,
    val outsideBarMeters: Double,
    val direction: ProgressDirection,
    val progressMeters: Double,
    val candidateCount: Int,
)

private enum class OracleMutation { ALWAYS_PAIR, GENERATED_DIRECTION_FIRST, START_POSITION_ONLY }

private data class CalibrationErrors(
    val counts: Map<StartTruthClass, Int>,
    val errors: Map<StartTruthClass, Int>,
    val directionErrors: Int,
    val positionErrors: Int,
    val unresolved: Int,
    val examples: List<String>,
) {
    val generalErrors: Int
        get() = listOf(
            StartTruthClass.OVERLAP_COMPLETE,
            StartTruthClass.BOUNDARY_EXCEEDED,
            StartTruthClass.OVERLAP_NONE,
        ).sumOf { errors.getValue(it) }
    val boundaryNearErrors: Int get() = errors.getValue(StartTruthClass.BOUNDARY_NEAR)
}

private data class CalibrationKey(val route: String, val sigma: Double)

class StartTrajectoryCalibrationTest {
    @Test
    fun handSolvedOverlapTruthExamplesAndOracleMutationsAreDistinguished() {
        val route = StartProbeRoutes.lollipop(0.0)
        fun trial(start: Double, direction: ProgressDirection) = CalibrationTrial(start, direction, emptyList())
        val examples = listOf(
            trial(100.0, ProgressDirection.FORWARD) to ExpectedStart(StartTruthClass.OVERLAP_COMPLETE, 0.0, ProgressDirection.FORWARD, 100.0, 2),
            trial(1_100.0, ProgressDirection.FORWARD) to ExpectedStart(StartTruthClass.OVERLAP_COMPLETE, 0.0, ProgressDirection.FORWARD, 1_100.0, 2),
            trial(1_100.0, ProgressDirection.REVERSE) to ExpectedStart(StartTruthClass.OVERLAP_COMPLETE, 0.0, ProgressDirection.FORWARD, 100.0, 2),
            trial(185.0, ProgressDirection.FORWARD) to ExpectedStart(StartTruthClass.BOUNDARY_NEAR, 15.0, ProgressDirection.FORWARD, 185.0, 1),
            trial(995.0, ProgressDirection.FORWARD) to ExpectedStart(StartTruthClass.BOUNDARY_NEAR, 5.0, ProgressDirection.FORWARD, 995.0, 1),
            trial(170.0, ProgressDirection.FORWARD) to ExpectedStart(StartTruthClass.BOUNDARY_EXCEEDED, 20.0, ProgressDirection.FORWARD, 170.0, 1),
        )
        examples.forEach { (input, expected) ->
            assertEquals(expected, classify(route, input, if (input.startProgressMeters == 170.0) 50.0 else 30.0))
        }

        val pairRemoved = OracleMutation.ALWAYS_PAIR
        assertNotEquals(examples[3].second, classifyWithMutation(route, examples[3].first, 30.0, pairRemoved))
        assertNotEquals(examples[4].second, classifyWithMutation(route, examples[4].first, 30.0, pairRemoved))
        assertNotEquals(
            examples[2].second,
            classifyWithMutation(route, examples[2].first, 30.0, OracleMutation.GENERATED_DIRECTION_FIRST),
        )
        assertNotEquals(
            examples[3].second,
            classifyWithMutation(route, examples[3].first, 30.0, OracleMutation.START_POSITION_ONLY),
        )
    }

    @Test
    fun fixedTrajectoryConfigIsMeasuredAgainstB4GeneratorTruth() {
        val routes = listOf(
            StartProbeRoutes.loop(),
            StartProbeRoutes.lollipop(0.0),
            StartProbeRoutes.lollipop(2.0),
            StartProbeRoutes.lollipop(5.0),
            StartProbeRoutes.lollipop(10.0),
            StartProbeRoutes.oneWay(),
        )
        val candidate = FIXED_CANDIDATE_METERS
        val tolerance = FIXED_MATCH_TOLERANCE_METERS
        val sigmas = listOf(3.0, 5.0, 10.0, 15.0)
        val trialsPerCell = System.getenv("B4_TRIALS")?.toInt() ?: 200
        val startSeed = System.getenv("B4_START")?.toLong() ?: 89_202_611L
        val noiseSeed = System.getenv("B4_NOISE")?.toLong() ?: 189_202_611L
        require(trialsPerCell > 0)
        val baseConfig = GuideConfig()
        val errors = linkedMapOf<CalibrationKey, CalibrationErrors>()
        val uniformity = linkedMapOf<Pair<String, Double>, Pair<Double, Double>>()
        val boundaryRates = linkedMapOf<Pair<String, Double>, Pair<Int, Double>>()
        val uniformityFailureCells = mutableListOf<String>()
        val biasedControlFailureCells = mutableListOf<String>()
        val routeModels = routes.associate { it.name to RouteModel.fromGpx(it.xml, baseConfig) }
        var referenceGeneralErrors = 0
        var referenceGeneralTrials = 0
        var referenceOverlapCompleteErrors = 0
        var referenceOverlapCompleteTrials = 0
        var maximumNearCellErrors = 0

        routes.forEachIndexed { routeIndex, route ->
            sigmas.forEachIndexed { sigmaIndex, sigma ->
                val sampleSeed = startSeed + routeIndex * 10_007L + sigmaIndex * 1_009L
                val range = allowedStartRange(route)
                val positions = uniformStartPositions(range, sampleSeed, trialsPerCell)
                val chiSquare = chiSquareUniformity(positions.map { toUnit(it, range) })
                val biasedPositions = biasedStartPositions(range, sampleSeed, trialsPerCell)
                val biasedChiSquare = chiSquareUniformity(biasedPositions.map { toUnit(it, range) })
                if (chiSquare >= CHI_SQUARE_CRITICAL) {
                    uniformityFailureCells += "${route.name}/sigma=$sigma/chiSquare=$chiSquare"
                }
                if (biasedChiSquare < CHI_SQUARE_CRITICAL) {
                    biasedControlFailureCells += "${route.name}/sigma=$sigma/chiSquare=$biasedChiSquare"
                }

                val trials = makeTrials(route, sigma, positions, routeIndex, sigmaIndex, noiseSeed)
                val boundaryNearCount = trials.count {
                    classify(route, it, candidate).classification == StartTruthClass.BOUNDARY_NEAR
                }
                val expectedRate = expectedBoundaryNearRate(route)
                boundaryRates[route.name to sigma] = boundaryNearCount to expectedRate
                assertTrue(
                    boundaryNearCount.toDouble() / trialsPerCell <= expectedRate * MAX_BOUNDARY_RATE_MULTIPLIER,
                    "B-12 boundary-near sample exceeded twice its hand-calculated rate: route=${route.name} sigma=$sigma observed=$boundaryNearCount/$trialsPerCell expected=$expectedRate",
                )
                uniformity[route.name to sigma] = chiSquare to biasedChiSquare
                val measured = runCell(route, routeModels.getValue(route.name), trials, candidate, tolerance, baseConfig)
                errors[CalibrationKey(route.name, sigma)] = measured
                if (sigma == 3.0 || sigma == 5.0) {
                    referenceGeneralErrors += measured.generalErrors
                    referenceGeneralTrials += trialsPerCell
                    referenceOverlapCompleteErrors += measured.errors.getValue(StartTruthClass.OVERLAP_COMPLETE)
                    referenceOverlapCompleteTrials += measured.counts.getValue(StartTruthClass.OVERLAP_COMPLETE)
                    maximumNearCellErrors = maxOf(maximumNearCellErrors, measured.boundaryNearErrors)
                    assertTrue(
                        measured.boundaryNearErrors <= ceil(0.03 * trialsPerCell).toInt(),
                        "B-4 boundary-near errors exceeded 3%: route=${route.name} sigma=$sigma observed=${measured.boundaryNearErrors}/$trialsPerCell",
                    )
                }
            }
        }

        println("START-TRAJECTORY-UNIFORMITY bins=$BIN_COUNT df=9 alpha=0.001 critical=$CHI_SQUARE_CRITICAL")
        println("START-TRAJECTORY-B12 uniformityFailureCells=${uniformityFailureCells.size} cells=${uniformityFailureCells.joinToString(";")}")
        println("START-TRAJECTORY-B12 biasedControlFailureCells=${biasedControlFailureCells.size} cells=${biasedControlFailureCells.joinToString(";")}")
        assertTrue(
            uniformityFailureCells.size < 3,
            "D-200 B-12 stopped after ${uniformityFailureCells.size} uniformity-failing cells: ${uniformityFailureCells.joinToString(";")}",
        )
        assertTrue(
            biasedControlFailureCells.isEmpty(),
            "D-200 B-12 biased control was not rejected in: ${biasedControlFailureCells.joinToString(";")}",
        )
        uniformity.forEach { (cell, scores) ->
            println("START-TRAJECTORY-UNIFORM route=${cell.first} sigma=${cell.second} chiSquare=${scores.first} biasedChiSquare=${scores.second}")
        }
        println("START-TRAJECTORY-TABLE columns=candidate,tolerance,route,sigma,trials,overlap-complete-errors,boundary-near-errors,boundary-exceeded-errors,overlap-none-errors,general-errors,near-count,direction-errors,position-errors,unresolved")
        routes.forEach { route ->
            sigmas.forEach { sigma ->
                val measured = errors.getValue(CalibrationKey(route.name, sigma))
                println(
                    "START-TRAJECTORY candidate=$candidate tolerance=$tolerance route=${route.name} sigma=$sigma trials=$trialsPerCell " +
                        "overlapCompleteErrors=${measured.errors.getValue(StartTruthClass.OVERLAP_COMPLETE)}/${measured.counts.getValue(StartTruthClass.OVERLAP_COMPLETE)} " +
                        "boundaryNearErrors=${measured.boundaryNearErrors}/${measured.counts.getValue(StartTruthClass.BOUNDARY_NEAR)} " +
                        "boundaryExceededErrors=${measured.errors.getValue(StartTruthClass.BOUNDARY_EXCEEDED)}/${measured.counts.getValue(StartTruthClass.BOUNDARY_EXCEEDED)} " +
                        "overlapNoneErrors=${measured.errors.getValue(StartTruthClass.OVERLAP_NONE)}/${measured.counts.getValue(StartTruthClass.OVERLAP_NONE)} " +
                        "generalErrors=${measured.generalErrors} nearCount=${measured.counts.getValue(StartTruthClass.BOUNDARY_NEAR)} " +
                        "directionErrors=${measured.directionErrors} positionErrors=${measured.positionErrors} unresolved=${measured.unresolved}",
                )
                measured.examples.forEach { println("START-TRAJECTORY-EXAMPLE route=${route.name} sigma=$sigma $it") }
            }
        }
        boundaryRates.forEach { (cell, pair) ->
            println(
                "START-TRAJECTORY-BOUNDARY-RATE route=${cell.first} sigma=${cell.second} candidate=$candidate " +
                    "observed=${pair.first}/$trialsPerCell expectedRate=${pair.second} maxRate=${pair.second * MAX_BOUNDARY_RATE_MULTIPLIER}",
            )
        }
        println("START-TRAJECTORY-B4-SUMMARY referenceGeneralErrors=$referenceGeneralErrors/$referenceGeneralTrials overlapCompleteErrors=$referenceOverlapCompleteErrors/$referenceOverlapCompleteTrials nearCellMax=$maximumNearCellErrors fixedCandidate=$candidate fixedTolerance=$tolerance trials=$trialsPerCell")
        if (trialsPerCell == 200) {
            assertTrue(referenceGeneralErrors <= 4, "B-4 reference general errors exceeded 4 across 12 cells: $referenceGeneralErrors/$referenceGeneralTrials")
        }
        assertEquals(30.0, baseConfig.startTrajectoryMeters, "GuideConfig startTrajectoryMeters must remain fixed at 30 m")
        assertEquals(1.0, baseConfig.startTrajectoryMatchToleranceMeters, "GuideConfig match tolerance must remain fixed at 1 m")
    }

    private fun uniformStartPositions(range: Pair<Double, Double>, seed: Long, trials: Int): List<Double> {
        val random = Random(seed)
        return List(trials) { range.first + random.nextDouble() * (range.second - range.first) }
    }

    private fun biasedStartPositions(range: Pair<Double, Double>, seed: Long, trials: Int): List<Double> =
        List(trials) { trial -> range.first + Random(seed + trial).nextDouble() * (range.second - range.first) }

    private fun allowedStartRange(route: StartRoute): Pair<Double, Double> {
        if (route.wrapsAsWalkingLoop()) return 0.0 to route.lengthMeters
        val margin = MAX_CANDIDATE_METERS + OPEN_ROUTE_EXTRA_MARGIN_METERS
        return margin to route.lengthMeters - margin
    }

    private fun toUnit(value: Double, range: Pair<Double, Double>): Double =
        ((value - range.first) / (range.second - range.first)).coerceIn(0.0, Math.nextDown(1.0))

    private fun chiSquareUniformity(unitValues: List<Double>): Double {
        val counts = IntArray(BIN_COUNT)
        unitValues.forEach { value -> counts[min(BIN_COUNT - 1, (value * BIN_COUNT).toInt())]++ }
        val expected = unitValues.size.toDouble() / BIN_COUNT
        return counts.sumOf { count -> (count - expected) * (count - expected) / expected }
    }

    private fun makeTrials(
        route: StartRoute,
        sigma: Double,
        startPositions: List<Double>,
        routeIndex: Int,
        sigmaIndex: Int,
        noiseSeed: Long,
    ): List<CalibrationTrial> {
        val noise = Random(noiseSeed + routeIndex * 10_007L + sigmaIndex * 1_009L)
        val maxSeconds = ceil(MAX_CANDIDATE_METERS / WALK_SPEED_MPS).toInt() + DECISION_GRACE_SECONDS
        return startPositions.mapIndexed { trial, start ->
            val direction = if (trial % 2 == 0) ProgressDirection.FORWARD else ProgressDirection.REVERSE
            val directionSign = if (direction == ProgressDirection.FORWARD) 1.0 else -1.0
            val locations = (0..maxSeconds).map { second ->
                val point = route.pointAt(start + directionSign * WALK_SPEED_MPS * second)
                noisyLocation(point, second, sigma, noise)
            }
            CalibrationTrial(start, direction, locations)
        }
    }

    private fun classify(route: StartRoute, trial: CalibrationTrial, candidate: Double): ExpectedStart {
        if (!isLollipop(route)) {
            return ExpectedStart(
                StartTruthClass.OVERLAP_NONE,
                0.0,
                trial.generatedDirection,
                wrapProgress(route, trial.startProgressMeters),
                1,
            )
        }
        val sign = if (trial.generatedDirection == ProgressDirection.FORWARD) 1.0 else -1.0
        val insideMeters = distanceInsideBars(route, trial.startProgressMeters, sign * candidate)
        val outsideMeters = (candidate - insideMeters).coerceAtLeast(0.0)
        val e = round(outsideMeters * 10.0) / 10.0
        val truthClass = when {
            insideMeters == 0.0 -> StartTruthClass.OVERLAP_NONE
            e == 0.0 -> StartTruthClass.OVERLAP_COMPLETE
            e <= BOUNDARY_NEAR_METERS -> StartTruthClass.BOUNDARY_NEAR
            else -> StartTruthClass.BOUNDARY_EXCEEDED
        }
        val generated = trial.generatedDirection to wrapProgress(route, trial.startProgressMeters)
        val candidates = if (truthClass == StartTruthClass.OVERLAP_COMPLETE) {
            listOf(
                generated,
                opposite(trial.generatedDirection) to wrapProgress(route, route.lengthMeters - trial.startProgressMeters),
            )
        } else {
            listOf(generated)
        }
        val expected = candidates.minWith(
            compareBy<Pair<ProgressDirection, Double>> { if (it.first == ProgressDirection.FORWARD) 0 else 1 }
                .thenBy { it.second },
        )
        return ExpectedStart(truthClass, e, expected.first, expected.second, candidates.size)
    }

    private fun classifyWithMutation(
        route: StartRoute,
        trial: CalibrationTrial,
        candidate: Double,
        mutation: OracleMutation,
    ): ExpectedStart {
        val correct = classify(route, trial, candidate)
        return when (mutation) {
            OracleMutation.ALWAYS_PAIR -> if (isLollipop(route)) correct.copy(candidateCount = 2) else correct
            OracleMutation.GENERATED_DIRECTION_FIRST -> if (
                correct.classification == StartTruthClass.OVERLAP_COMPLETE && trial.generatedDirection == ProgressDirection.REVERSE
            ) {
                correct.copy(direction = trial.generatedDirection, progressMeters = wrapProgress(route, trial.startProgressMeters))
            } else correct
            OracleMutation.START_POSITION_ONLY -> {
                val startInsideBar = trial.startProgressMeters in 0.0..BAR_LENGTH_METERS ||
                    trial.startProgressMeters in (route.lengthMeters - BAR_LENGTH_METERS)..route.lengthMeters
                if (isLollipop(route) && startInsideBar && correct.classification != StartTruthClass.OVERLAP_COMPLETE) {
                    correct.copy(classification = StartTruthClass.OVERLAP_COMPLETE, candidateCount = 2)
                } else correct
            }
        }
    }

    private fun distanceInsideBars(route: StartRoute, start: Double, delta: Double): Double {
        val end = start + delta
        val low = min(start, end)
        val high = maxOf(start, end)
        val length = route.lengthMeters
        if (length <= 0.0) return 0.0
        var inside = 0.0
        val firstCycle = floor(low / length).toInt() - 1
        val lastCycle = floor(high / length).toInt() + 1
        for (cycle in firstCycle..lastCycle) {
            val base = cycle * length
            inside += intervalIntersection(low, high, base, base + BAR_LENGTH_METERS)
            inside += intervalIntersection(low, high, base + length - BAR_LENGTH_METERS, base + length)
        }
        return inside.coerceIn(0.0, abs(delta))
    }

    private fun intervalIntersection(low: Double, high: Double, start: Double, end: Double): Double =
        (min(high, end) - maxOf(low, start)).coerceAtLeast(0.0)

    private fun expectedBoundaryNearRate(route: StartRoute): Double {
        if (!isLollipop(route)) return 0.0
        val allowed = allowedStartRange(route)
        return (4.0 * BOUNDARY_NEAR_METERS) / (allowed.second - allowed.first)
    }

    private fun isLollipop(route: StartRoute): Boolean = route.name.startsWith("B-lollipop-")

    private fun runCell(
        route: StartRoute,
        routeModel: RouteModel,
        trials: List<CalibrationTrial>,
        candidate: Double,
        tolerance: Double,
        baseConfig: GuideConfig,
    ): CalibrationErrors {
        val counts = StartTruthClass.entries.associateWith { 0 }.toMutableMap()
        val errors = StartTruthClass.entries.associateWith { 0 }.toMutableMap()
        var directionErrors = 0
        var positionErrors = 0
        var unresolved = 0
        val examples = mutableListOf<String>()
        val config = baseConfig.copy(
            startTrajectoryMeters = candidate,
            startTrajectoryMatchToleranceMeters = tolerance,
        )
        val allowedSeconds = ceil(candidate / WALK_SPEED_MPS).toInt() + DECISION_GRACE_SECONDS

        trials.forEachIndexed { trialIndex, trial ->
            val expected = classify(route, trial, candidate)
            counts[expected.classification] = counts.getValue(expected.classification) + 1
            val session = GuideSession(routeModel, config, startupEnabled = true)
            var resolution: SessionDecision? = null
            val lastSecond = min(allowedSeconds, trial.locations.lastIndex)
            for (second in 0..lastSecond) {
                val decision = session.accept(trial.locations[second])
                if (decision.startupJustResolved) {
                    resolution = decision
                    break
                }
            }
            val state = resolution?.result?.nextState
            val actualDirection = state?.decidedDirection
            val actualProgress = state?.firstMatchProgressMeters?.let { oriented ->
                if (state.routeReversed) wrapProgress(route, route.lengthMeters - oriented) else wrapProgress(route, oriented)
            }
            val directionWrong = actualDirection != expected.direction
            val positionError = actualProgress?.let { routeDistance(route, it, expected.progressMeters) } ?: Double.POSITIVE_INFINITY
            val positionWrong = positionError > baseConfig.offRouteEnterDistMeters
            if (resolution == null) unresolved++
            if (directionWrong) directionErrors++
            if (positionWrong) positionErrors++
            if (resolution == null || directionWrong || positionWrong) {
                errors[expected.classification] = errors.getValue(expected.classification) + 1
                if (examples.size < MAX_EXAMPLES_PER_CELL) {
                    examples +=
                        "trial=$trialIndex class=${expected.classification} e=${expected.outsideBarMeters} " +
                            "generated=${trial.generatedDirection}/${trial.startProgressMeters} " +
                            "truth=${expected.direction}/${expected.progressMeters} actual=$actualDirection/$actualProgress " +
                            "positionError=$positionError unresolved=${resolution == null}"
                }
            }
        }
        return CalibrationErrors(counts, errors, directionErrors, positionErrors, unresolved, examples)
    }

    private fun routeDistance(route: StartRoute, left: Double, right: Double): Double {
        val difference = abs(left - right)
        return if (route.closed) min(difference, route.lengthMeters - difference) else difference
    }

    private fun wrapProgress(route: StartRoute, progress: Double): Double =
        if (route.closed) ((progress % route.lengthMeters) + route.lengthMeters) % route.lengthMeters else progress

    private fun opposite(direction: ProgressDirection): ProgressDirection =
        if (direction == ProgressDirection.FORWARD) ProgressDirection.REVERSE else ProgressDirection.FORWARD

    private fun noisyLocation(point: StartPoint, second: Int, sigma: Double, random: Random): TrailLocation {
        val east = point.east + if (sigma == 0.0) 0.0 else random.nextGaussian() * sigma
        val north = point.north + if (sigma == 0.0) 0.0 else random.nextGaussian() * sigma
        return TrailLocation(
            timestampMillis = second * 1_000L,
            latitude = StartProbeRoutes.latitude + north / StartProbeRoutes.METERS_PER_DEGREE,
            longitude = StartProbeRoutes.longitude + east / StartProbeRoutes.metersPerDegreeLongitude,
            accuracyMeters = 5f,
            speedMps = WALK_SPEED_MPS.toFloat(),
            bearingDegrees = null,
            provider = "synthetic-start-trajectory-calibration",
        )
    }

    private companion object {
        const val BIN_COUNT = 10
        const val CHI_SQUARE_CRITICAL = 27.88
        const val BAR_LENGTH_METERS = 200.0
        const val BOUNDARY_NEAR_METERS = 15.0
        const val MAX_BOUNDARY_RATE_MULTIPLIER = 2.0
        const val WALK_SPEED_MPS = 1.2
        const val MAX_CANDIDATE_METERS = 50.0
        const val FIXED_CANDIDATE_METERS = 30.0
        const val FIXED_MATCH_TOLERANCE_METERS = 1.0
        const val OPEN_ROUTE_EXTRA_MARGIN_METERS = 30.0
        const val DECISION_GRACE_SECONDS = 5
        const val MAX_EXAMPLES_PER_CELL = 3
    }
}
