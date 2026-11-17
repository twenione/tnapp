package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideResult
import com.trailnav.core.GuideState
import com.trailnav.core.ArrivalBlockedRecord
import com.trailnav.core.MatchResult
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters

internal data class StartPoint(val east: Double, val north: Double, val elevation: Double = 100.0)

internal data class StartRoute(
    val name: String,
    val corners: List<StartPoint>,
    val closed: Boolean,
    val waypointMeters: List<Double> = emptyList(),
    val pointsOverride: List<StartPoint>? = null,
    val xmlOverride: String? = null,
) {
    val points: List<StartPoint> = pointsOverride ?: densify(corners)
    val cumulative: List<Double> = cumulativeMeters(points)
    val lengthMeters: Double = cumulative.last()
    val xml: String = xmlOverride ?: routeGpx(points)

    fun pointAt(distanceMeters: Double): StartPoint {
        val s = if (closed) {
            ((distanceMeters % lengthMeters) + lengthMeters) % lengthMeters
        } else {
            distanceMeters
        }
        if (!closed && s < 0.0) return extrapolate(points[0], points[1], s)
        if (!closed && s > lengthMeters) return extrapolate(points[points.lastIndex], points[points.lastIndex - 1], s - lengthMeters)
        if (s <= 0.0) return points.first()
        if (s >= lengthMeters) return points.last()
        var index = 1
        while (cumulative[index] < s) index++
        val span = cumulative[index] - cumulative[index - 1]
        val fraction = (s - cumulative[index - 1]) / span
        return interpolate(points[index - 1], points[index], fraction)
    }

    fun tangentAt(distanceMeters: Double): StartPoint {
        val before = pointAt(distanceMeters - 1.0)
        val after = pointAt(distanceMeters + 1.0)
        val deltaEast = after.east - before.east
        val deltaNorth = after.north - before.north
        val length = hypot(deltaEast, deltaNorth).coerceAtLeast(1e-9)
        return StartPoint(deltaEast / length, deltaNorth / length)
    }

    fun wrapsAsWalkingLoop(): Boolean = closed && !hasRetracedOverlap()

    private fun hasRetracedOverlap(): Boolean {
        if (!closed || points.size < 3) return false
        val lastIndex = points.lastIndex
        for (leftIndex in points.indices) {
            for (rightIndex in (leftIndex + 2)..lastIndex) {
                if (leftIndex == 0 && rightIndex == lastIndex) continue
                val routeGap = cumulative[rightIndex] - cumulative[leftIndex]
                if (routeGap < RETRACED_POSITION_MIN_GAP_METERS) continue
                val left = points[leftIndex]
                val right = points[rightIndex]
                if (hypot(left.east - right.east, left.north - right.north) <= RETRACED_POSITION_TOLERANCE_METERS) {
                    return true
                }
            }
        }
        return false
    }

    private companion object {
        const val RETRACED_POSITION_MIN_GAP_METERS = 100.0
        const val RETRACED_POSITION_TOLERANCE_METERS = 0.25
    }
}

private data class StartScenario(
    val route: StartRoute,
    val positionName: String,
    val startMeters: Double,
    val ingressMeters: Double,
    val standSeconds: Int,
    val direction: Int,
    val sigmaMeters: Double,
)

internal fun directionLabelMatchesRouteDistance(
    route: StartRoute,
    startMeters: Double,
    endMeters: Double,
    label: ProgressDirection,
): Boolean {
    val start = nearestRouteDistance(route, route.pointAt(startMeters))
    val end = nearestRouteDistance(route, route.pointAt(endMeters))
    val delta = end - start
    val fromSign = when {
        delta > 0.001 -> ProgressDirection.FORWARD
        delta < -0.001 -> ProgressDirection.REVERSE
        else -> ProgressDirection.STATIONARY
    }
    return fromSign == label
}

internal fun nearestRouteDistance(route: StartRoute, point: StartPoint): Double {
    var bestSquaredDistance = Double.POSITIVE_INFINITY
    var bestDistance = 0.0
    for (index in 1 until route.points.size) {
        val start = route.points[index - 1]
        val end = route.points[index]
        val east = end.east - start.east
        val north = end.north - start.north
        val lengthSquared = east * east + north * north
        val fraction = if (lengthSquared == 0.0) 0.0 else (
            ((point.east - start.east) * east + (point.north - start.north) * north) / lengthSquared
        ).coerceIn(0.0, 1.0)
        val projectedEast = start.east + fraction * east
        val projectedNorth = start.north + fraction * north
        val deltaEast = point.east - projectedEast
        val deltaNorth = point.north - projectedNorth
        val squaredDistance = deltaEast * deltaEast + deltaNorth * deltaNorth
        if (squaredDistance < bestSquaredDistance) {
            bestSquaredDistance = squaredDistance
            val segmentLength = hypot(east, north)
            bestDistance = route.cumulative[index - 1] + fraction * segmentLength
        }
    }
    return bestDistance
}

internal data class StartCellEvidence(
    val label: String,
    val sigmaMeters: Double,
    val samples: Int,
    val arrivalRuns: Int,
    val onRouteOffRouteFrames: Int,
    val opposedDirectionFrames: Int,
    val directionDependentGuidanceBeforeResolution: Int,
    val unresolvedRuns: Int = 0,
    val undecidedEvents: Int = 0,
    val unresolvedWithoutExactlyOneTerminalEventRuns: Int = 0,
    val hiddenArrivalReasonRuns: Int = 0,
    val directionDependentEventCounts: Map<String, Int> = emptyMap(),
    val sampleErrors: List<String> = emptyList(),
    val allowedUnresolvedByEndpointRule: Boolean = false,
    val remainingToEndpointMeters: Double? = null,
    val unresolvedArrivalRuns: Int = 0,
    val unresolvedOnRouteOffRouteFrames: Int = 0,
    val unresolvedDirectionDependentGuidance: Int = 0,
    val falseArrivalRuns: Int = 0,
    val falseArrivalRunsMargin0: Int = 0,
    val arrivalTimes: List<StartArrivalObservation> = emptyList(),
)

internal data class StartArrivalObservation(
    val sample: Int,
    val seed: Long,
    val timeSeconds: Int,
    val projectedMeters: Double,
    val creditedMeters: Double,
    val actualNoiseMeters: Double,
    val falseArrivalMargin0: Boolean,
    val falseArrivalAtActualNoise: Boolean,
    val falseArrivalAt3Sigma: Boolean,
    val falseArrivalSeverityMeters: Double,
)

internal data class D210ComparisonEvidence(
    val label: String,
    val samples: Int,
    val arrivalRuns: Int,
    val falseArrivalRuns: Int,
    val falseArrivalObservations: List<StartArrivalObservation>,
)

internal data class D210ArrivalInput(
    // Every distance supplied to this gate is expressed in RouteMath/engine metres.
    val routeLengthMeters: Double,
    val startMeters: Double,
    val direction: Int,
    val startTrajectoryMeters: Double,
    val arrivalMinAdvanceMeters: Double,
    val arrivalAdvanceFraction: Double,
    val arriveRadiusMeters: Double,
    val arrivalTimeSeconds: Int,
    val standSeconds: Int,
    val ingressMeters: Double,
    val walkSpeedMps: Double,
    val actualNoiseMeters: Double,
    val sigmaMeters: Double,
    val unresolved: Boolean = false,
)

internal data class D210ArrivalEvidence(
    val remainingMeters: Double,
    val minimumRemainingMeters: Double,
    val generatedWalkingMeters: Double,
    val actualNoiseMeters: Double,
    val endpointReachable: Boolean,
    val walkingReachesArrivalCircle: Boolean,
    val falseArrival: Boolean,
)

internal fun d210FalseArrivalSeverityMeters(input: D210ArrivalInput, evidence: D210ArrivalEvidence): Double = max(
    (evidence.minimumRemainingMeters - evidence.remainingMeters).coerceAtLeast(0.0),
    (evidence.remainingMeters - input.arriveRadiusMeters - evidence.actualNoiseMeters - evidence.generatedWalkingMeters)
        .coerceAtLeast(0.0),
)

internal fun evaluateD210Arrival(input: D210ArrivalInput): D210ArrivalEvidence {
    require(input.direction == 1 || input.direction == -1)
    require(input.actualNoiseMeters >= 0.0)
    val remainingMeters = if (input.direction == 1) {
        input.routeLengthMeters - input.startMeters
    } else {
        input.startMeters
    }
    val minimumRemainingMeters = input.startTrajectoryMeters + minOf(
        input.arrivalMinAdvanceMeters,
        input.arrivalAdvanceFraction * input.routeLengthMeters,
    )
    val generatedWalkingMeters = max(
        0.0,
        (input.arrivalTimeSeconds - input.standSeconds) * input.walkSpeedMps - input.ingressMeters,
    )
    val endpointReachable = remainingMeters >= minimumRemainingMeters
    val walkingReachesArrivalCircle = generatedWalkingMeters >=
        remainingMeters - input.arriveRadiusMeters - input.actualNoiseMeters
    return D210ArrivalEvidence(
        remainingMeters = remainingMeters,
        minimumRemainingMeters = minimumRemainingMeters,
        generatedWalkingMeters = generatedWalkingMeters,
        actualNoiseMeters = input.actualNoiseMeters,
        endpointReachable = endpointReachable,
        walkingReachesArrivalCircle = walkingReachesArrivalCircle,
        falseArrival = input.unresolved || !(endpointReachable && walkingReachesArrivalCircle),
    )
}

internal data class ShortRouteRunEvidence(
    val arrivalTimeSeconds: Int?,
    val resolvedAtSeconds: Int?,
    val blockedArrivals: List<ArrivalBlockedRecord>,
)

internal fun measureShortRouteRuns(
    lengthMeters: Double,
    sigmaMeters: Double,
    startupEnabled: Boolean,
    sampleCount: Int,
    seedBase: Long,
): List<ShortRouteRunEvidence> {
    require(lengthMeters > 0.0)
    require(sigmaMeters >= 0.0)
    require(sampleCount >= 1)
    val route = StartRoute(
        name = "short-$lengthMeters",
        corners = listOf(StartPoint(0.0, 0.0), StartPoint(lengthMeters, 0.0)),
        closed = false,
    )
    val config = GuideConfig()
    val routeModel = RouteModel.fromGpx(route.xml, config)
    return (0 until sampleCount).map { sample ->
        val session = GuideSession(routeModel, config, startupEnabled = startupEnabled)
        var arrivalTimeSeconds: Int? = null
        var resolvedAtSeconds: Int? = null
        syntheticTrack(
            route = route,
            startMeters = 0.0,
            direction = 1,
            standSeconds = 0,
            sigmaMeters = sigmaMeters,
            seconds = 300,
            seed = seedBase + sample,
        ).forEachIndexed { second, location ->
            session.accept(location)
            val snapshot = session.snapshot()
            if (resolvedAtSeconds == null && snapshot.startupResolved) resolvedAtSeconds = second
            if (arrivalTimeSeconds == null && snapshot.arrived) arrivalTimeSeconds = second
        }
        ShortRouteRunEvidence(
            arrivalTimeSeconds = arrivalTimeSeconds,
            resolvedAtSeconds = resolvedAtSeconds,
            blockedArrivals = session.snapshot().arrivalBlockedRecords,
        )
    }
}

private data class StartRunEvidence(
    val arrived: Boolean,
    val onRouteOffRouteFrames: Int,
    val opposedDirectionFrames: Int,
    val directionDependentGuidanceBeforeResolution: Int,
    val unresolved: Boolean,
    val undecidedEvents: Int,
    val unresolvedWithoutExactlyOneTerminalEvent: Boolean,
    val hiddenArrivalReason: Boolean,
    val directionDependentEventCounts: Map<String, Int>,
    val unresolvedArrival: Boolean,
    val unresolvedOnRouteOffRouteFrames: Int,
    val unresolvedDirectionDependentGuidance: Int,
    val arrivalTimeSeconds: Int?,
    val arrivalProjectedMeters: Double?,
    val arrivalCreditedMeters: Double?,
    val arrivalNoiseMeters: Double?,
)

internal fun hasHiddenInsufficientAdvanceReason(
    state: GuideState,
    timestampMillis: Long,
    config: GuideConfig,
): Boolean {
    if (!state.startupResolved) return false
    if (state.arrivalBlockedRecords.none { it.reason == "direction-unknown" }) return false
    if (state.arrivalBlockedRecords.any { it.reason == "insufficient-advance" }) return false
    val match = state.lastMatch ?: return false
    val endpoint = state.route.points.lastOrNull() ?: return false
    val elapsed = state.sessionStartTimestamp?.let { (timestampMillis - it).coerceAtLeast(0L) / 1_000.0 } ?: 0.0
    val distanceToEnd = hypot(match.projectedPoint.east - endpoint.east, match.projectedPoint.north - endpoint.north)
    val candidate = elapsed >= config.minimumSessionSecondsBeforeArrival &&
        match.distanceMeters < config.offRouteEnterDistMeters &&
        distanceToEnd <= config.arriveRadiusMeters &&
        match.projectedMeters > state.route.totalLengthMeters * config.arriveProgressFraction
    val proportional = state.arrivalBaselineRemainingMeters?.let { config.arrivalAdvanceFraction * it } ?: 0.0
    val absoluteFloor = minOf(config.arrivalMinAdvanceMeters, config.arrivalAdvanceFraction * state.route.totalLengthMeters)
    val requiredAdvance = max(proportional, absoluteFloor)
    val hasAdvance = state.arrivalBaselineRemainingMeters != null && state.advanceCreditedMeters >= requiredAdvance
    return candidate && !hasAdvance
}

internal fun arrivalViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.falseArrivalRuns > 0 }
    .map { cell ->
        cell.label + ": falseArrivalRuns=" + cell.falseArrivalRuns +
            "/" + cell.samples + ", margin0=" + cell.falseArrivalRunsMargin0
    }

internal fun onRouteOffRouteViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.onRouteOffRouteFrames > 0 }
    .map { "${it.label}: off-route frames=${it.onRouteOffRouteFrames}" }

internal fun oppositeDirectionViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.opposedDirectionFrames > 0 }
    .map { "${it.label}: direction-opposed frames=${it.opposedDirectionFrames}" }

internal fun prematureDirectionGuidanceViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.directionDependentGuidanceBeforeResolution > 0 }
    .map { "${it.label}: premature direction-dependent guidance=${it.directionDependentGuidanceBeforeResolution}" }

internal fun unresolvedAllowedByD209(
    routeClosed: Boolean,
    routeLengthMeters: Double,
    startMeters: Double,
    direction: Int,
    startupTrajectoryMeters: Double,
    offRouteEnterDistMeters: Double,
): Boolean {
    if (routeClosed) return false
    require(direction == 1 || direction == -1)
    val remainingToEndpoint = if (direction == 1) routeLengthMeters - startMeters else startMeters
    return remainingToEndpoint < startupTrajectoryMeters + offRouteEnterDistMeters
}

internal fun unresolvedStartPhaseViolations(cells: List<StartCellEvidence>): List<String> {
    val observed = cells.filter { it.unresolvedRuns > 0 }.associateBy { it.label }
    val unexpected = observed.values.filterNot { it.allowedUnresolvedByEndpointRule }
    val malformed = cells.filter { it.unresolvedWithoutExactlyOneTerminalEventRuns > 0 }
        .map { "${it.label}: unresolved runs without exactly one terminal event=${it.unresolvedWithoutExactlyOneTerminalEventRuns}" }
    return buildList {
        if (unexpected.isNotEmpty()) {
            add("unresolved cells outside D-209 endpoint allowance: ${unexpected.map { "${it.label} runs=${it.unresolvedRuns} remaining=${it.remainingToEndpointMeters}" }.sorted()}")
        }
        addAll(malformed)
        val allowedWithArrival = observed.values.filter { it.allowedUnresolvedByEndpointRule && it.unresolvedArrivalRuns > 0 }
        if (allowedWithArrival.isNotEmpty()) {
            add("allowed unresolved cells with arrival runs: ${allowedWithArrival.map { "${it.label} runs=${it.unresolvedArrivalRuns}" }.sorted()}")
        }

        val allowedWithGuidance = observed.values.filter { it.allowedUnresolvedByEndpointRule && it.unresolvedDirectionDependentGuidance > 0 }
        if (allowedWithGuidance.isNotEmpty()) {
            add("allowed unresolved cells with direction-dependent guidance: ${allowedWithGuidance.map { "${it.label} events=${it.unresolvedDirectionDependentGuidance}" }.sorted()}")
        }
        val unresolvedRuns = cells.sumOf { it.unresolvedRuns }
        val terminalEvents = cells.sumOf { it.undecidedEvents }
        if (unresolvedRuns != terminalEvents) add("unresolved runs=$unresolvedRuns but direction.undecided-at-end events=$terminalEvents")
    }
}

internal fun missingExpectedFailureSourceIds(expected: Set<String>, sourceIds: Set<String>): Set<String> = expected - sourceIds

internal fun assertExpectedFailureSourceConsistency(expected: Set<String>, sourceIds: Set<String>) {
    val missing = missingExpectedFailureSourceIds(expected, sourceIds)
    assertTrue(missing.isEmpty(), "expected_failure ids absent from source: $missing")
}

internal fun assertExpectedFailureRuntimeConsistency(expected: Set<String>, observed: Set<String>) {
    val missing = expected - observed
    assertTrue(missing.isEmpty(), "expected_failure ids not observed at runtime: $missing")
}

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class StartPhaseGridTest {
    @Test
    fun shortRouteStartupEnabledAndDisabledMeasurement() {
        val defaultSamples = parseTask097ShortRouteSampleCount(System.getenv("TASK097_SHORT_ROUTE_N"))
        for ((lengthIndex, lengthMeters) in listOf(70.0, 80.0, 82.0, 84.0, 90.0, 100.0, 105.0, 110.0, 120.0).withIndex()) {
            for (startupEnabled in listOf(true, false)) {
                for ((sigmaIndex, sigmaMeters) in listOf(0.0, 3.0, 5.0).withIndex()) {
                    val samples = if (sigmaMeters == 0.0) defaultSamples else 1_000
                    val seedBase = 97_011_111L + lengthIndex * 1_000_000L + sigmaIndex * 100_000L
                    val runs = measureShortRouteRuns(
                        lengthMeters = lengthMeters,
                        sigmaMeters = sigmaMeters,
                        startupEnabled = startupEnabled,
                        sampleCount = samples,
                        seedBase = seedBase,
                    )
                    val arrivals = runs.count { it.arrivalTimeSeconds != null }
                    val blockedNoArrivalRuns = runs.filter { it.arrivalTimeSeconds == null }
                    val blockedReasonRuns = blockedNoArrivalRuns
                        .flatMap { run -> run.blockedArrivals.map { it.reason }.toSet() }
                        .groupingBy { it }
                        .eachCount()
                        .toSortedMap()
                    println(
                        "TASK097-SHORT-ROUTE length=$lengthMeters startupEnabled=$startupEnabled sigma=$sigmaMeters " +
                            "N=$samples arrivals=$arrivals ratio=$arrivals/$samples blockedNoArrivalRuns=" +
                            blockedNoArrivalRuns.size + " blockedReasonRuns=" + blockedReasonRuns,
                    )
                }
            }
        }
    }

    @Test
    fun sampleCountInputUsesDefaultAndRejectsInvalidValues() {
        assertEquals(1_000, parseStartPhaseGridSampleCount(null))
        assertEquals(250, parseStartPhaseGridSampleCount("250"))
        assertEquals(5, parseTask097ShortRouteSampleCount(null))
        assertEquals(250, parseTask097ShortRouteSampleCount("250"))
        for (invalid in listOf("", "abc", "0", "-1")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                parseStartPhaseGridSampleCount(invalid)
            }
            assertFailsWith<IllegalArgumentException>(invalid) {
                parseTask097ShortRouteSampleCount(invalid)
            }
        }
    }

    @Test
    fun shortRouteStartupArrivalAdvanceGateMatchesD210Reproduction() {
        data class Expected(
            val lengthMeters: Double,
            val arrivalTimeSeconds: Int?,
            val creditedMeters: Double?,
            val requiredMeters: Double?,
        )
        val expected = listOf(
            Expected(60.0, null, 27.6, 47.9),
            Expected(80.0, null, 46.7, 50.0),
            Expected(82.0, 67, null, null),
        )
        for (row in expected) {
            val run = measureShortRouteRuns(
                lengthMeters = row.lengthMeters,
                sigmaMeters = 0.0,
                startupEnabled = true,
                sampleCount = 1,
                seedBase = 97_011_111L,
            ).single()
            assertEquals(25, run.resolvedAtSeconds, "length=" + row.lengthMeters)
            assertEquals(row.arrivalTimeSeconds, run.arrivalTimeSeconds, "length=" + row.lengthMeters)
            if (row.arrivalTimeSeconds == null) {
                val blocked = run.blockedArrivals.firstOrNull { it.reason == "insufficient-advance" }
                assertTrue(blocked != null, "length=" + row.lengthMeters + " blocked=" + run.blockedArrivals)
                assertEquals(row.creditedMeters!!, blocked!!.creditedMeters, 0.2, "length=" + row.lengthMeters)
                assertEquals(row.requiredMeters!!, blocked.requiredMeters, 0.2, "length=" + row.lengthMeters)
            }
        }
    }

    @Test
    // After the loop-end start resolves, the directed projection wraps toward the GPX start and never reaches the missing-advance candidate gate.
    fun startPhaseArrivalAdvanceGuardRejectsNearLoopEndStartMutation() {
        assertFalse(
            StartPhaseGrid.deterministicLoopNearEndDidArrive(),
            "a deterministic start near the loop end must not satisfy arrival without credited advance",
        )
    }

    @Test
    fun startPhaseNoFalseArrivalWithinFiveMinutes() {
        assertLowSigmaFalseArrivalCountsDoNotExceedMainBaseline(reportWhenNotN20 = true)
    }

    @Test
    fun startPhaseD210LowSigmaFalseArrivalGate() {
        assertLowSigmaFalseArrivalCountsDoNotExceedMainBaseline(reportWhenNotN20 = false)
    }

    @Test
    fun startPhaseBaselineComparisonRejectsMissingLabelsAndRegressions() {
        val baseline = mapOf("cell-a" to 0, "cell-b" to 1)
        assertEquals(emptyList(), lowSigmaFalseArrivalBaselineViolations(baseline, baseline))
        assertTrue(
            lowSigmaFalseArrivalBaselineViolations(mapOf("cell-a" to 0), baseline)
                .any { it.contains("label mismatch") },
        )
        assertTrue(
            lowSigmaFalseArrivalBaselineViolations(mapOf("cell-a" to 1, "cell-b" to 1), baseline)
                .any { it.contains("cell-a") },
        )
    }

    @Test
    fun startPhaseHighSigmaFalseArrivalCountsAreMeasurementOnly() {
        StartPhaseGrid.cells.filter { it.sigmaMeters >= 10.0 }.forEach { cell ->
            println(
                "D210-MEASUREMENT " + cell.label +
                    " falseArrivalRuns=" + cell.falseArrivalRuns +
                    " falseArrivalRunsMargin0=" + cell.falseArrivalRunsMargin0,
            )
        }
        StartPhaseGrid.printD210ComparisonsAndWorstSigma15SamplesIfN1000()
    }

    @Test
    fun startPhaseOnRouteNeverLeavesRoute() {
        expectedFailureAware("startPhaseOnRouteNeverLeavesRoute") {
            val violations = onRouteOffRouteViolations(StartPhaseGrid.cells)
            assertTrue(violations.isEmpty(), violations.take(30).joinToString("\n"))
        }
    }

    @Test
    fun startPhaseDirectionNeverOpposesTravel() {
        expectedFailureAware("startPhaseDirectionNeverOpposesTravel") {
            val violations = oppositeDirectionViolations(StartPhaseGrid.cells)
            assertTrue(violations.isEmpty(), violations.take(30).joinToString("\n"))
        }
    }

    @Test
    fun startPhaseDirectionLabelsMatchIndependentRouteDistanceSign() {
        val route = StartProbeRoutes.oneWay()
        val forwardStart = 580.0
        val forwardEnd = 620.0
        val reverseStart = 620.0
        val reverseEnd = 580.0
        assertTrue(directionLabelMatchesRouteDistance(route, forwardStart, forwardEnd, ProgressDirection.FORWARD))
        assertTrue(directionLabelMatchesRouteDistance(route, reverseStart, reverseEnd, ProgressDirection.REVERSE))
        assertFalse(directionLabelMatchesRouteDistance(route, forwardStart, forwardEnd, ProgressDirection.REVERSE))
        assertFalse(directionLabelMatchesRouteDistance(route, reverseStart, reverseEnd, ProgressDirection.FORWARD))
    }

    @Test
    fun startPhaseDirectionDependentGuidanceWaitsForResolution() {
        val violations = prematureDirectionGuidanceViolations(StartPhaseGrid.cells)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun unresolvedStartPhaseRunsHaveOneTerminalEventAndExpectedCells() {
        val violations = unresolvedStartPhaseViolations(StartPhaseGrid.cells)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun unresolvedStartPhaseAllowanceUsesRouteEndpointAndWalkingDirection() {
        val length = 2_200.0
        fun allowed(start: Double, direction: Int, closed: Boolean = false) = unresolvedAllowedByD209(
            routeClosed = closed,
            routeLengthMeters = length,
            startMeters = start,
            direction = direction,
            startupTrajectoryMeters = 30.0,
            offRouteEnterDistMeters = 25.0,
        )

        assertFalse(allowed(0.0, 1))
        assertTrue(allowed(0.0, -1))
        assertFalse(allowed(5.0, 1))
        assertTrue(allowed(5.0, -1))
        assertFalse(allowed(20.0, 1))
        assertTrue(allowed(20.0, -1))
        assertTrue(allowed(length - 5.0, 1))
        assertFalse(allowed(length - 5.0, -1))
        assertTrue(allowed(length - 20.0, 1))
        assertFalse(allowed(length - 20.0, -1))
        assertFalse(allowed(length * 0.10, 1))
        assertFalse(allowed(length * 0.10, -1))
        assertFalse(allowed(length - 55.0, 1))
        assertFalse(allowed(length - 50.0, 1, closed = true))
    }

    @Test
    fun unresolvedStartupCandidateIsNotMisclassifiedAsHiddenInsufficientAdvance() {
        val route = RouteModel.fromGpx(StartProbeRoutes.oneWay().xml, GuideConfig())
        val endpoint = route.points.last()
        val state = GuideState.starting(route).copy(
            sessionStartTimestamp = 0L,
            lastMatch = MatchResult(
                distanceMeters = 0.0,
                projectedMeters = route.totalLengthMeters,
                segmentIndex = route.cumulativeMeters.lastIndex - 1,
                projectedPoint = endpoint,
            ),
            arrivalBlockedRecords = listOf(
                ArrivalBlockedRecord(0.0, 50.0, 20.0, route.totalLengthMeters, "direction-unknown"),
            ),
        )

        assertFalse(hasHiddenInsufficientAdvanceReason(state, 20_000L, GuideConfig()))
    }

    @Test
    fun startPhaseMilestoneUsesGuidanceStartReference() {
        expectedFailureAware("startPhaseMilestoneUsesGuidanceStartReference") {
            val violations = StartPhaseMilestoneProbe.violations()
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    @Test
    fun startPhaseEarlyEventsHaveJustifiedTargets() {
        val violations = StartPhaseEarlyEventProbe.violations()
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun startPhasePreDecisionOffRouteUsesWholeRouteMinimum() {
        val route = StartProbeRoutes.oneWay()
        val cases = listOf(
            Triple("vertex", route.lengthMeters * 0.50, 80.0 * kotlin.math.sqrt(0.5)),
            Triple("segment", 200.0, 80.0),
        )
        val planarResults = cases.map { (name, startMeters, handExpected) ->
            val entry = route.pointAt(startMeters)
            val tangent = route.tangentAt(startMeters)
            val point = StartPoint(
                east = entry.east - tangent.north * 80.0,
                north = entry.north + tangent.east * 80.0,
            )
            val planarMinimum = independentWholeRouteMinimum(route.points, point)
            val withinTolerance = abs(planarMinimum - handExpected) <= 1e-6
            println(
                "PREDECISION-HAND position=" + name +
                    " startMeters=" + startMeters +
                    " planarMinimum=" + planarMinimum +
                    " handExpected=" + handExpected +
                    " within1e-6=" + withinTolerance,
            )
            assertTrue(
                withinTolerance,
                "independent whole-route minimum mismatch at " + name +
                    ": calculated=" + planarMinimum + " hand=" + handExpected,
            )
            Triple(name, startMeters, planarMinimum)
        }
        val engineDistanceScale = StartProbeRoutes.engineDistanceScale
        val expectedDistances = planarResults.map { (name, startMeters, planarMinimum) ->
            val engineExpected = planarMinimum * engineDistanceScale
            println(
                "PREDECISION-EXPECTED position=" + name +
                    " planar=" + planarMinimum +
                    " engineScale=" + engineDistanceScale +
                    " expected=" + engineExpected,
            )
            Triple(name, startMeters, engineExpected)
        }
        val violations = expectedDistances.flatMap { (name, startMeters, expected) ->
            val caseViolations = StartPhasePreDecisionProbe.violations(startMeters, expected)
            println(
                "PREDECISION-RESULT position=" + name +
                    " startMeters=" + startMeters +
                    " expected=" + expected +
                    " violations=" + caseViolations,
            )
            caseViolations
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }


    @Test
    fun d210FalseArrivalMatchesHandCalculatedCasesAndActualNoise() {
        fun input(
            remainingMeters: Double,
            walkedMeters: Double,
            actualNoiseMeters: Double,
            routeLengthMeters: Double = 2_200.0,
        ) = D210ArrivalInput(
            routeLengthMeters = routeLengthMeters,
            startMeters = routeLengthMeters - remainingMeters,
            direction = 1,
            startTrajectoryMeters = 30.0,
            arrivalMinAdvanceMeters = 50.0,
            arrivalAdvanceFraction = 0.8,
            arriveRadiusMeters = 25.0,
            arrivalTimeSeconds = 1,
            standSeconds = 0,
            ingressMeters = 0.0,
            walkSpeedMps = walkedMeters,
            actualNoiseMeters = actualNoiseMeters,
            sigmaMeters = 0.0,
        )
        data class Expected(
            val row: Int,
            val remainingMeters: Double,
            val walkedMeters: Double,
            val actualNoiseMeters: Double,
            val thresholdMeters: Double?,
            val falseArrival: Boolean,
            val routeLengthMeters: Double = 2_200.0,
        )
        val engineScale = StartProbeRoutes.engineDistanceScale
        val engineRouteLength = 2_200.0 * engineScale
        val engineStartMeters = (2_200.0 - 220.0) * engineScale
        val engineRemainingMeters = engineRouteLength - engineStartMeters
        val rows9To12 = listOf(
            Expected(
                9,
                engineRemainingMeters,
                190.0 * engineScale,
                3.0 * engineScale,
                engineRemainingMeters - 25.0 - 3.0 * engineScale,
                true,
                routeLengthMeters = engineRouteLength,
            ),
            Expected(
                10,
                engineRemainingMeters,
                193.2 * engineScale,
                1.792622 * engineScale,
                engineRemainingMeters - 25.0 - 1.792622 * engineScale,
                false,
                routeLengthMeters = engineRouteLength,
            ),
            Expected(
                11,
                engineRemainingMeters,
                190.8 * engineScale,
                4.190294 * engineScale,
                engineRemainingMeters - 25.0 - 4.190294 * engineScale,
                false,
                routeLengthMeters = engineRouteLength,
            ),
            Expected(
                12,
                engineRemainingMeters,
                190.8 * engineScale,
                4.192951 * engineScale,
                engineRemainingMeters - 25.0 - 4.192951 * engineScale,
                false,
                routeLengthMeters = engineRouteLength,
            ),
        )
        val rows = listOf(
            Expected(1, 220.0, 220.0, 0.0, 195.0, false),
            Expected(2, 220.0, 190.0, 3.0, 192.0, true),
            Expected(3, 220.0, 150.0, 10.0, 185.0, true),
            Expected(4, 220.0, 150.0, 40.0, 155.0, true),
            Expected(5, 220.0, 150.0, 70.0, 125.0, false),
            Expected(6, 60.0, 500.0, 0.0, null, true),
            Expected(7, 80.0, 60.0, 0.0, 55.0, false, routeLengthMeters = 300.0),
            Expected(8, 79.9, 60.0, 0.0, null, true, routeLengthMeters = 300.0),
        ) + rows9To12
        for (row in rows) {
            val evidence = evaluateD210Arrival(
                input(row.remainingMeters, row.walkedMeters, row.actualNoiseMeters, row.routeLengthMeters),
            )
            assertEquals(row.remainingMeters, evidence.remainingMeters, "row=${row.row}")
            assertEquals(80.0, evidence.minimumRemainingMeters, "row=${row.row}")
            assertEquals(row.walkedMeters, evidence.generatedWalkingMeters, 1e-9, "row=${row.row}")
            assertEquals(row.actualNoiseMeters, evidence.actualNoiseMeters, "row=${row.row}")
            if (row.thresholdMeters == null) {
                assertFalse(evidence.endpointReachable, "row=${row.row}")
            } else {
                assertEquals(row.thresholdMeters, row.remainingMeters - 25.0 - row.actualNoiseMeters, "row=${row.row}")
                assertTrue(evidence.walkingReachesArrivalCircle == !row.falseArrival, "row=${row.row}")
            }
            assertEquals(row.falseArrival, evidence.falseArrival, "row=${row.row}")
        }

        val equality = evaluateD210Arrival(input(220.0, 195.0, 0.0))
        assertEquals(195.0, equality.remainingMeters - 25.0 - equality.actualNoiseMeters)
        assertEquals(195.0, equality.generatedWalkingMeters, 1e-9)
        assertFalse(equality.falseArrival, "A(t) equal to the threshold is legitimate")

        val reverse = input(220.0, 195.0, 0.0).copy(direction = -1, startMeters = 220.0)
        assertEquals(220.0, evaluateD210Arrival(reverse).remainingMeters)
    }

    @Test
    fun d210ActualNoiseGateIsDeterministicAndRetainsTwoKnownFalseSeeds() {
        val label = "C-oneway/start/on-route/stand-35/forward/sigma-15m"
        for (seed in listOf(91_299_438L, 91_299_636L)) {
            val first = StartPhaseGrid.d210ViolationForSeed(label, seed)
            val second = StartPhaseGrid.d210ViolationForSeed(label, seed)
            assertEquals(first.size, second.size, "seed=$seed violation count")
            assertEquals(first, second, "seed=$seed violation list")
            assertEquals(1, first.size, "known false arrival must remain detected, seed=$seed")
            println("D210-DETERMINISM seed=$seed result=${first.single()}")
        }
    }

    @Test
    fun startPhaseSummaryEvaluatorsRejectCounterexamples() {
        val clean = StartCellEvidence("clean", 0.0, 1, 0, 0, 0, 0)
        assertTrue(arrivalViolations(listOf(clean)).isEmpty())
        assertTrue(onRouteOffRouteViolations(listOf(clean)).isEmpty())
        assertTrue(oppositeDirectionViolations(listOf(clean)).isEmpty())
        assertTrue(prematureDirectionGuidanceViolations(listOf(clean)).isEmpty())

        assertTrue(arrivalViolations(listOf(clean.copy(falseArrivalRuns = 1))).isNotEmpty())
        assertTrue(onRouteOffRouteViolations(listOf(clean.copy(onRouteOffRouteFrames = 1))).isNotEmpty())
        assertTrue(oppositeDirectionViolations(listOf(clean.copy(opposedDirectionFrames = 1))).isNotEmpty())
        assertTrue(
            prematureDirectionGuidanceViolations(
                listOf(clean.copy(directionDependentGuidanceBeforeResolution = 1)),
            ).isNotEmpty(),
        )

        val allowedUnresolved = clean.copy(
            allowedUnresolvedByEndpointRule = true,
            unresolvedRuns = 1,
            undecidedEvents = 1,
        )
        assertTrue(unresolvedStartPhaseViolations(listOf(allowedUnresolved)).isEmpty())
        assertTrue(
            unresolvedStartPhaseViolations(
                listOf(allowedUnresolved.copy(unresolvedArrivalRuns = 1)),
            ).isNotEmpty(),
        )
        assertTrue(
            unresolvedStartPhaseViolations(
                listOf(allowedUnresolved.copy(unresolvedOnRouteOffRouteFrames = 1)),
            ).isEmpty(),
        )
        assertTrue(
            unresolvedStartPhaseViolations(
                listOf(allowedUnresolved.copy(unresolvedDirectionDependentGuidance = 1)),
            ).isNotEmpty(),
        )
        assertTrue(
            unresolvedStartPhaseViolations(
                listOf(clean.copy(unresolvedRuns = 1, undecidedEvents = 1)),
            ).isNotEmpty(),
        )
    }

    @Test
    fun zzzzExpectedFailureListMatchesSourceAndRuntimeCalls() {
        val expectations = expectedFailureEntries()
        val sourceRoot = repositoryRoot().resolve("app/src/test/kotlin")
        val callPattern = Regex("\\bexpectedFailureAware\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val sourceIds = FilesWalk.kotlinSources(sourceRoot)
            .flatMap { path ->
                val source = java.nio.file.Files.newBufferedReader(path).use { it.readText() }
                callPattern.findAll(source).map { it.groupValues[1] }.toList()
            }
            .toSet()
        assertExpectedFailureSourceConsistency(expectations.keys, sourceIds)
        assertExpectedFailureRuntimeConsistency(expectations.keys, observedExpectedFailureIds())
    }
}

internal fun parseTask097ShortRouteSampleCount(raw: String?): Int {
    val value = raw ?: "5"
    val parsed = value.toIntOrNull()
        ?: throw IllegalArgumentException("TASK-097 short-route sample count must be an integer, got '$value'")
    require(parsed >= 1) { "TASK-097 short-route sample count must be at least 1, got $parsed" }
    return parsed
}

internal fun parseStartPhaseGridSampleCount(raw: String?): Int {
    val value = raw ?: "1000"
    val parsed = value.toIntOrNull()
        ?: throw IllegalArgumentException("start-phase grid sample count must be an integer, got '$value'")
    require(parsed >= 1) { "start-phase grid sample count must be at least 1, got $parsed" }
    return parsed
}

internal object StartPhaseGrid {
    val sampleCount = parseStartPhaseGridSampleCount(
        System.getProperty("trailnav.startPhaseGrid.sampleCount"),
    )
    private const val SEED_BASE = 84_159_177L
    private const val SECONDS = 300
    private const val METERS_PER_DEGREE = 111_320.0
    private const val BASE_MILLIS = 0L
    private val latitudeOrigin = 37.0
    private val longitudeOrigin = 127.0
    private val metersPerDegreeLongitude = METERS_PER_DEGREE * cos(Math.toRadians(latitudeOrigin))
    private val config = GuideConfig().copy(
        milestoneEnabled = true,
        elapsedEnabled = true,
        remainingEnabled = true,
        slopeEnabled = true,
        elevationEnabled = true,
        waypointEnabled = true,
        sunsetEnabled = true,
        sunriseEnabled = true,
    )

    init {
        println("START-GRID-N n=$sampleCount")
        if (sampleCount < 1_000) {
            println("N 축소 — 0건이 1/1,000 수준을 보증하지 않음(D-183)")
        }
    }

    val cells: List<StartCellEvidence> by lazy(::run)

    fun printD210ComparisonsAndWorstSigma15SamplesIfN1000() {
        if (sampleCount != 1_000) return
        val falseArrivalCells = cells.filter { it.falseArrivalRuns > 0 }
        println("D210-COMPARISON-CELLS count=" + falseArrivalCells.size)
        val startupDisabled = compareFalseArrivalCellsWithStartupDisabled(falseArrivalCells)
        val currentCounts = falseArrivalCells.associate { it.label to it.falseArrivalRuns }
        startupDisabled.forEach { comparison ->
            println(
                "D210-COMPARISON " + comparison.label +
                    " N=" + comparison.samples +
                    " current096=" + currentCounts.getValue(comparison.label) +
                    " startupDisabled=" + comparison.falseArrivalRuns +
                    " disabledArrivals=" + comparison.arrivalRuns,
            )
        }
        val worstSamples = cells
            .filter { it.sigmaMeters == 15.0 }
            .flatMap { cell ->
                cell.arrivalTimes.filter { it.falseArrivalAtActualNoise }.map { cell.label to it }
            }
            .sortedByDescending { it.second.falseArrivalSeverityMeters }
            .take(3)
        worstSamples.forEachIndexed { index, (label, sample) ->
            println(
                "D210-WORST-SIGMA15 rank=" + (index + 1) +
                    " cell=" + label +
                    " sample=" + sample.sample +
                    " seed=" + sample.seed +
                    " time=" + sample.timeSeconds +
                    " projectionMeters=" + sample.projectedMeters +
                    " creditedAdvanceMeters=" + sample.creditedMeters +
                    " actualNoiseMeters=" + sample.actualNoiseMeters +
                    " falseMargin0=" + sample.falseArrivalMargin0 +
                    " shortfallMeters=" + sample.falseArrivalSeverityMeters,
            )
        }
    }

    fun deterministicLoopNearEndDidArrive(): Boolean {
        val scenario = StartScenario(
            route = loopRoute(),
            positionName = "end-side-5m",
            startMeters = loopRoute().lengthMeters - 5.0,
            ingressMeters = 0.0,
            standSeconds = 0,
            direction = 1,
            sigmaMeters = 0.0,
        )
        return runScenario(scenario, SEED_BASE).arrived
    }

    private val routes: List<StartRoute> by lazy {
        listOf(loopRoute(), lollipopRoute(0.0), lollipopRoute(2.0), lollipopRoute(5.0), lollipopRoute(10.0), oneWayRoute())
    }

    private fun loopRoute(): StartRoute = StartRoute(
        "A-loop",
        listOf(
            StartPoint(0.0, 0.0), StartPoint(300.0, 0.0), StartPoint(300.0, 300.0),
            StartPoint(0.0, 300.0), StartPoint(0.0, 0.0),
        ),
        closed = true,
    )

    private fun lollipopRoute(offsetMeters: Double): StartRoute = StartRoute(
        "B-lollipop-offset-${offsetMeters.toInt()}m",
        listOf(
            StartPoint(0.0, 0.0), StartPoint(0.0, 200.0), StartPoint(200.0, 200.0),
            StartPoint(200.0, 400.0), StartPoint(offsetMeters, 400.0), StartPoint(offsetMeters, 200.0),
            StartPoint(0.0, 200.0), StartPoint(0.0, 0.0),
        ),
        closed = true,
    )

    private fun oneWayRoute(): StartRoute = StartRoute(
        "C-oneway",
        listOf(
            StartPoint(0.0, 0.0), StartPoint(400.0, 0.0), StartPoint(400.0, 300.0),
            StartPoint(800.0, 300.0), StartPoint(800.0, 700.0), StartPoint(1_200.0, 700.0),
            StartPoint(1_200.0, 1_000.0),
        ),
        closed = false,
    )

    private fun scenarios(): List<StartScenario> = buildList {
        for (route in routes) {
            val positions = if (route.name == "C-oneway") {
                listOf(
                    "start" to 0.0,
                    "start-side-5m" to 5.0,
                    "start-side-20m" to 20.0,
                    "end-side-5m" to route.lengthMeters - 5.0,
                    "end-side-20m" to route.lengthMeters - 20.0,
                    "middle-10pct" to route.lengthMeters * 0.10,
                    "middle-25pct" to route.lengthMeters * 0.25,
                    "middle-50pct" to route.lengthMeters * 0.50,
                    "middle-75pct" to route.lengthMeters * 0.75,
                    "middle-90pct" to route.lengthMeters * 0.90,
                    "offroute-entry" to route.lengthMeters * 0.50,
                )
            } else {
                listOf(
                    "start" to 0.0,
                    "start-side-5m" to 5.0,
                    "start-side-20m" to 20.0,
                    "end-side-5m" to route.lengthMeters - 5.0,
                    "end-side-20m" to route.lengthMeters - 20.0,
                    "middle-side" to route.lengthMeters * 0.50,
                    "offroute-entry" to 0.0,
                )
            }
            for ((positionName, startMeters) in positions) {
                val ingress = if (positionName == "offroute-entry") 80.0 else 0.0
                for (stand in listOf(0, 35)) {
                    for (direction in listOf(1, -1)) {
                        for (sigma in listOf(0.0, 3.0, 5.0, 10.0, 15.0)) {
                            add(StartScenario(route, positionName, startMeters, ingress, stand, direction, sigma))
                        }
                    }
                }
            }
        }
    }

    fun compareFalseArrivalCellsWithStartupDisabled(referenceCells: List<StartCellEvidence>): List<D210ComparisonEvidence> {
        val allScenarios = scenarios()
        val scenariosByLabel = allScenarios.associateBy { it.label() }
        return referenceCells.map { reference ->
            val scenario = scenariosByLabel[reference.label] ?: error("unknown D-210 cell: " + reference.label)
            val cellIndex = allScenarios.indexOf(scenario)
            var arrivals = 0
            var falseArrivals = 0
            val falseObservations = mutableListOf<StartArrivalObservation>()
            for (sample in 0 until 1_000) {
                val seed = SEED_BASE + cellIndex * 10_000L + sample
                val run = runScenario(scenario, seed, startupEnabled = false)
                if (run.arrived) arrivals++
                run.arrivalTimeSeconds?.let { timeSeconds ->
                    val input = d210Input(scenario, run, timeSeconds)
                    val margin0 = evaluateD210Arrival(input.copy(actualNoiseMeters = 0.0))
                    val actualNoise = evaluateD210Arrival(input)
                    if (actualNoise.falseArrival) falseArrivals++
                    if (actualNoise.falseArrival) {
                        falseObservations += StartArrivalObservation(
                            sample = sample,
                            seed = seed,
                            timeSeconds = timeSeconds,
                            projectedMeters = run.arrivalProjectedMeters ?: error("arrived run has no projection"),
                            creditedMeters = run.arrivalCreditedMeters ?: error("arrived run has no credited advance"),
                            actualNoiseMeters = input.actualNoiseMeters,
                            falseArrivalMargin0 = margin0.falseArrival,
                            falseArrivalAtActualNoise = actualNoise.falseArrival,
                            falseArrivalAt3Sigma = evaluateD210Arrival(
                                input.copy(actualNoiseMeters = 3.0 * input.sigmaMeters),
                            ).falseArrival,
                            falseArrivalSeverityMeters = d210FalseArrivalSeverityMeters(input, actualNoise),
                        )
                    }
                }
            }
            D210ComparisonEvidence(
                label = reference.label,
                samples = 1_000,
                arrivalRuns = arrivals,
                falseArrivalRuns = falseArrivals,
                falseArrivalObservations = falseObservations,
            )
        }
    }

    internal fun d210ViolationForSeed(label: String, seed: Long): List<String> {
        val scenario = scenarios().single { it.label() == label }
        val run = runScenario(scenario, seed)
        val timeSeconds = run.arrivalTimeSeconds ?: return emptyList()
        val evidence = evaluateD210Arrival(d210Input(scenario, run, timeSeconds))
        return if (evidence.falseArrival) {
            listOf(label + " seed=" + seed + " e_t=" + evidence.actualNoiseMeters)
        } else {
            emptyList()
        }
    }

    private fun d210Input(scenario: StartScenario, run: StartRunEvidence, timeSeconds: Int): D210ArrivalInput {
        val engineScale = StartProbeRoutes.engineDistanceScale
        return D210ArrivalInput(
            routeLengthMeters = scenario.route.lengthMeters * engineScale,
            startMeters = scenario.startMeters * engineScale,
            direction = scenario.direction,
            startTrajectoryMeters = config.startTrajectoryMeters,
            arrivalMinAdvanceMeters = config.arrivalMinAdvanceMeters,
            arrivalAdvanceFraction = config.arrivalAdvanceFraction,
            arriveRadiusMeters = config.arriveRadiusMeters,
            arrivalTimeSeconds = timeSeconds,
            standSeconds = scenario.standSeconds,
            ingressMeters = scenario.ingressMeters * engineScale,
            walkSpeedMps = WALK_SPEED_MPS * engineScale,
            actualNoiseMeters = (run.arrivalNoiseMeters ?: error("arrived run has no measured noise")) * engineScale,
            sigmaMeters = scenario.sigmaMeters * engineScale,
            unresolved = run.unresolved,
        )
    }

    private fun run(): List<StartCellEvidence> {
        val cells = scenarios().mapIndexed { cellIndex, scenario ->
            val samplesForCell = sampleCount
            var arrivals = 0
            var falseArrivalRuns = 0
            var falseArrivalRunsMargin0 = 0
            val arrivalTimes = mutableListOf<StartArrivalObservation>()
            var offRouteFrames = 0
            var opposedFrames = 0
            var prematureFrames = 0
            var unresolvedRuns = 0
            var undecidedEvents = 0
            var unresolvedWithoutExactlyOneTerminalEventRuns = 0
            var hiddenArrivalReasonRuns = 0
            var unresolvedArrivalRuns = 0
            var unresolvedOnRouteOffRouteFrames = 0
            var unresolvedDirectionDependentGuidance = 0
            val eventCounts = (DIRECTION_DEPENDENT_EVENTS + "other").associateWith { 0 }.toMutableMap()
            val sampleErrors = mutableListOf<String>()
            for (sample in 0 until samplesForCell) {
                val seed = SEED_BASE + cellIndex * 10_000L + sample
                val run = try {
                    runScenario(scenario, seed)
                } catch (error: Exception) {
                    sampleErrors += "sample=$sample ${error.javaClass.simpleName}: ${error.message}"
                    continue
                }
                if (run.arrived) arrivals++
                run.arrivalTimeSeconds?.let { timeSeconds ->
                    val input = d210Input(scenario, run, timeSeconds)
                    val margin0 = evaluateD210Arrival(input.copy(actualNoiseMeters = 0.0))
                    val actualNoise = evaluateD210Arrival(input)
                    val priorThreeSigma = evaluateD210Arrival(
                        input.copy(actualNoiseMeters = 3.0 * input.sigmaMeters),
                    )
                    if (margin0.falseArrival) falseArrivalRunsMargin0++
                    if (actualNoise.falseArrival) falseArrivalRuns++
                    if (scenario.sigmaMeters <= 5.0 && priorThreeSigma.falseArrival) {
                        println(
                            "D210-097-LOW-SIGMA label=${scenario.label()} sample=$sample seed=$seed " +
                                "time=$timeSeconds actualNoiseMeters=${input.actualNoiseMeters} " +
                                "priorThreeSigmaFalse=true actualNoiseFalse=${actualNoise.falseArrival}",
                        )
                    }
                    arrivalTimes += StartArrivalObservation(
                        sample = sample,
                        seed = seed,
                        timeSeconds = timeSeconds,
                        projectedMeters = run.arrivalProjectedMeters ?: error("arrived run has no projection"),
                        creditedMeters = run.arrivalCreditedMeters ?: error("arrived run has no credited advance"),
                        actualNoiseMeters = input.actualNoiseMeters,
                        falseArrivalMargin0 = margin0.falseArrival,
                        falseArrivalAtActualNoise = actualNoise.falseArrival,
                        falseArrivalAt3Sigma = priorThreeSigma.falseArrival,
                        falseArrivalSeverityMeters = d210FalseArrivalSeverityMeters(input, actualNoise),
                    )
                }
                offRouteFrames += run.onRouteOffRouteFrames
                opposedFrames += run.opposedDirectionFrames
                prematureFrames += run.directionDependentGuidanceBeforeResolution
                if (run.unresolved) unresolvedRuns++
                undecidedEvents += run.undecidedEvents
                if (run.unresolvedWithoutExactlyOneTerminalEvent) unresolvedWithoutExactlyOneTerminalEventRuns++
                if (run.hiddenArrivalReason) hiddenArrivalReasonRuns++
                if (run.unresolvedArrival) unresolvedArrivalRuns++
                unresolvedOnRouteOffRouteFrames += run.unresolvedOnRouteOffRouteFrames
                unresolvedDirectionDependentGuidance += run.unresolvedDirectionDependentGuidance
                run.directionDependentEventCounts.forEach { (event, count) ->
                    val key = if (event in DIRECTION_DEPENDENT_EVENTS) event else "other"
                    eventCounts[key] = eventCounts.getValue(key) + count
                }
            }
            StartCellEvidence(
                label = scenario.label(),
                sigmaMeters = scenario.sigmaMeters,
                samples = samplesForCell,
                arrivalRuns = arrivals,
                onRouteOffRouteFrames = offRouteFrames,
                opposedDirectionFrames = opposedFrames,
                directionDependentGuidanceBeforeResolution = prematureFrames,
                unresolvedRuns = unresolvedRuns,
                undecidedEvents = undecidedEvents,
                unresolvedWithoutExactlyOneTerminalEventRuns = unresolvedWithoutExactlyOneTerminalEventRuns,
                hiddenArrivalReasonRuns = hiddenArrivalReasonRuns,
                directionDependentEventCounts = eventCounts,
                sampleErrors = sampleErrors,
                allowedUnresolvedByEndpointRule = unresolvedAllowedByD209(
                    routeClosed = scenario.route.closed,
                    routeLengthMeters = scenario.route.lengthMeters,
                    startMeters = scenario.startMeters,
                    direction = scenario.direction,
                    startupTrajectoryMeters = config.startTrajectoryMeters,
                    offRouteEnterDistMeters = config.offRouteEnterDistMeters,
                ),
                remainingToEndpointMeters = if (scenario.route.closed) null else if (scenario.direction == 1) {
                    scenario.route.lengthMeters - scenario.startMeters
                } else {
                    scenario.startMeters
                },
                unresolvedArrivalRuns = unresolvedArrivalRuns,
                unresolvedOnRouteOffRouteFrames = unresolvedOnRouteOffRouteFrames,
                unresolvedDirectionDependentGuidance = unresolvedDirectionDependentGuidance,
                falseArrivalRuns = falseArrivalRuns,
                falseArrivalRunsMargin0 = falseArrivalRunsMargin0,
                arrivalTimes = arrivalTimes,
            )
        }
        cells.forEach { cell ->
            val eventCounts = (DIRECTION_DEPENDENT_EVENTS + "other").joinToString(" ") { event ->
                val count = if (event == "other") {
                    cell.directionDependentEventCounts.filterKeys { it !in DIRECTION_DEPENDENT_EVENTS }.values.sum()
                } else {
                    cell.directionDependentEventCounts[event] ?: 0
                }
                "$event=$count"
            }
            println(
                "START-PHASE-CELL ${cell.label} N=${cell.samples} arrivalRuns=${cell.arrivalRuns} " +
                    "offRoute=${cell.onRouteOffRouteFrames} opposed=${cell.opposedDirectionFrames} " +
                    "premature=${cell.directionDependentGuidanceBeforeResolution} " +
                    "unresolved=${cell.unresolvedRuns} undecidedEvents=${cell.undecidedEvents} " +
                    "unresolvedWithoutOneTerminal=${cell.unresolvedWithoutExactlyOneTerminalEventRuns} " +
                "unresolvedAllowed=${cell.allowedUnresolvedByEndpointRule} " +
                "endRemaining=${cell.remainingToEndpointMeters} " +
                "unresolvedArrival=${cell.unresolvedArrivalRuns} " +
                "unresolvedOffRoute=${cell.unresolvedOnRouteOffRouteFrames} " +
                "unresolvedGuidance=${cell.unresolvedDirectionDependentGuidance} " +
                    "hiddenInsufficientAdvance=${cell.hiddenArrivalReasonRuns} " +
                    "events={$eventCounts} sampleErrors=${cell.sampleErrors.size}" +
                    if (cell.sampleErrors.isEmpty()) "" else " errors=${cell.sampleErrors.joinToString(" | ")}",
            )
        }
        cells.filter { it.arrivalTimes.isNotEmpty() }.forEach { cell ->
            println(
                "D210-ARRIVAL " + cell.label + " times=" +
                    cell.arrivalTimes.joinToString(",") { arrival ->
                        arrival.sample.toString() + ":" + arrival.seed + ":" + arrival.timeSeconds + ":" +
                            arrival.projectedMeters + ":" + arrival.creditedMeters + ":" +
                            arrival.actualNoiseMeters + ":" + arrival.falseArrivalMargin0 + ":" +
                            arrival.falseArrivalAtActualNoise + ":" + arrival.falseArrivalAt3Sigma + ":" +
                            arrival.falseArrivalSeverityMeters
                    },
            )
        }
        val arrivalCells = arrivalViolations(cells).size
        val offRouteCells = onRouteOffRouteViolations(cells).size
        val directionCells = oppositeDirectionViolations(cells).size
        val prematureCells = prematureDirectionGuidanceViolations(cells).size
        val arrivalRuns = cells.sumOf { it.arrivalRuns }
        val falseArrivalRuns = cells.sumOf { it.falseArrivalRuns }
        val falseArrivalRunsMargin0 = cells.sumOf { it.falseArrivalRunsMargin0 }
        val offRouteFrames = cells.sumOf { it.onRouteOffRouteFrames }
        val opposedFrames = cells.sumOf { it.opposedDirectionFrames }
        val prematureFrames = cells.sumOf { it.directionDependentGuidanceBeforeResolution }
        val unresolvedRuns = cells.sumOf { it.unresolvedRuns }
        val undecidedEvents = cells.sumOf { it.undecidedEvents }
        val unresolvedWithoutExactlyOneTerminalEventRuns = cells.sumOf { it.unresolvedWithoutExactlyOneTerminalEventRuns }
        val hiddenArrivalReasonRuns = cells.sumOf { it.hiddenArrivalReasonRuns }
        val sampleErrors = cells.sumOf { it.sampleErrors.size }
        val eventCounts = (DIRECTION_DEPENDENT_EVENTS + "other").joinToString(" ") { event ->
            val count = if (event == "other") {
                cells.sumOf { cell -> cell.directionDependentEventCounts.filterKeys { it !in DIRECTION_DEPENDENT_EVENTS }.values.sum() }
            } else {
                cells.sumOf { it.directionDependentEventCounts[event] ?: 0 }
            }
            "$event=$count"
        }
        println(
            "START-PHASE-GRID cells=${cells.size} samples=${cells.sumOf { it.samples }} " +
                "arrivalCells=$arrivalCells arrivalRuns=$arrivalRuns falseArrivalRuns=$falseArrivalRuns falseArrivalRunsMargin0=$falseArrivalRunsMargin0 " +
                "offRouteCells=$offRouteCells offRouteFrames=$offRouteFrames " +
                "directionCells=$directionCells opposedFrames=$opposedFrames " +
                "prematureCells=$prematureCells prematureFrames=$prematureFrames " +
                "unresolvedRuns=$unresolvedRuns undecidedEvents=$undecidedEvents " +
                "unresolvedWithoutOneTerminal=$unresolvedWithoutExactlyOneTerminalEventRuns " +
                "hiddenInsufficientAdvanceRuns=$hiddenArrivalReasonRuns " +
                "sampleErrors=$sampleErrors events={$eventCounts}",
        )
        return cells
    }

    private fun runScenario(
        scenario: StartScenario,
        seed: Long,
        startupEnabled: Boolean = true,
    ): StartRunEvidence {
        val route = scenario.route
        val session = GuideSession(RouteModel.fromGpx(route.xml, config), config, startupEnabled = startupEnabled)
        var resolvedAtSeconds: Int? = null
        var arrived = false
        var arrivalTimeSeconds: Int? = null
        var arrivalProjectedMeters: Double? = null
        var arrivalCreditedMeters: Double? = null
        var arrivalNoiseMeters: Double? = null
        var noiseMetersAtCurrentFrame = 0.0
        var offRouteFrames = 0
        var opposedFrames = 0
        var prematureGuidance = 0
        var hiddenArrivalReason = false
        val eventCounts = DIRECTION_DEPENDENT_EVENTS.associateWith { 0 }.toMutableMap()
        val random = Random(seed)

        fun truth(timeSeconds: Int): Pair<Double, Boolean> {
            val travel = max(0.0, (timeSeconds - scenario.standSeconds).toDouble()) * WALK_SPEED_MPS
            val ingressLeft = (scenario.ingressMeters - travel).coerceAtLeast(0.0)
            val alongMeters = (travel - scenario.ingressMeters).coerceAtLeast(0.0)
            val rawS = scenario.startMeters + scenario.direction * alongMeters
            val onRoute = scenario.ingressMeters == 0.0 || ingressLeft <= 0.0
            return rawS to (onRoute && (route.closed || rawS in 0.0..route.lengthMeters))
        }

        fun locationAt(timeSeconds: Int): TrailLocation {
            val travel = max(0.0, (timeSeconds - scenario.standSeconds).toDouble()) * WALK_SPEED_MPS
            val ingressLeft = (scenario.ingressMeters - travel).coerceAtLeast(0.0)
            val alongMeters = (travel - scenario.ingressMeters).coerceAtLeast(0.0)
            val rawS = scenario.startMeters + scenario.direction * alongMeters
            var point = route.pointAt(rawS)
            if (ingressLeft > 0.0) {
                val tangent = route.tangentAt(scenario.startMeters)
                point = StartPoint(
                    point.east - tangent.north * ingressLeft,
                    point.north + tangent.east * ingressLeft,
                )
            }
            val eastNoise = if (scenario.sigmaMeters > 0.0) random.nextGaussian() * scenario.sigmaMeters else 0.0
            val northNoise = if (scenario.sigmaMeters > 0.0) random.nextGaussian() * scenario.sigmaMeters else 0.0
            noiseMetersAtCurrentFrame = hypot(eastNoise, northNoise)
            val east = point.east + eastNoise
            val north = point.north + northNoise
            val moving = timeSeconds >= scenario.standSeconds
            return TrailLocation(
                timestampMillis = BASE_MILLIS + timeSeconds * 1_000L,
                latitude = latitudeOrigin + north / METERS_PER_DEGREE,
                longitude = longitudeOrigin + east / metersPerDegreeLongitude,
                accuracyMeters = 5f,
                speedMps = if (moving) WALK_SPEED_MPS.toFloat() else 0f,
                bearingDegrees = null,
                provider = "synthetic-start-phase",
            )
        }

        for (time in 0..SECONDS) {
            val location = locationAt(time)
            val previous = session.snapshot()
            val decision = session.accept(location)
            if (!hiddenArrivalReason && hasHiddenInsufficientAdvanceReason(decision.result.nextState, location.timestampMillis, config)) {
                hiddenArrivalReason = true
            }
            if (!previous.startupResolved && decision.result.nextState.startupResolved) {
                resolvedAtSeconds = time
            }
            if (!previous.startupResolved) {
                val observedEvents = findDirectionDependentGuidanceEvents(decision.result)
                if (observedEvents.any { it in DIRECTION_DEPENDENT_EVENTS }) prematureGuidance++
                observedEvents.forEach { event -> eventCounts[event] = eventCounts.getOrDefault(event, 0) + 1 }
            }
            if (decision.result.nextState.arrived && time <= SECONDS) {
                arrived = true
                if (arrivalTimeSeconds == null) {
                    arrivalTimeSeconds = time
                    arrivalProjectedMeters = decision.result.nextState.lastMatch?.projectedMeters
                    arrivalCreditedMeters = decision.result.nextState.advanceCreditedMeters
                    arrivalNoiseMeters = noiseMetersAtCurrentFrame
                }
            }
            val (_, onRoute) = truth(time)
            if (onRoute && decision.result.nextState.offRoute) offRouteFrames++
            if (onRoute && decision.result.nextState.direction == ProgressDirection.REVERSE) opposedFrames++
        }
        val unresolved = startupEnabled && resolvedAtSeconds == null
        val undecidedEvents = session.finish().count { it.kind == "direction.undecided-at-end" }
        return StartRunEvidence(
            arrived,
            offRouteFrames,
            opposedFrames,
            prematureGuidance,
            unresolved,
            undecidedEvents,
            unresolved && undecidedEvents != 1,
            hiddenArrivalReason,
            eventCounts,
            unresolvedArrival = unresolved && arrived,
            unresolvedOnRouteOffRouteFrames = if (unresolved) offRouteFrames else 0,
            unresolvedDirectionDependentGuidance = if (unresolved) prematureGuidance else 0,
            arrivalTimeSeconds = arrivalTimeSeconds,
            arrivalProjectedMeters = arrivalProjectedMeters,
            arrivalCreditedMeters = arrivalCreditedMeters,
            arrivalNoiseMeters = arrivalNoiseMeters,
        )
    }

    private fun findDirectionDependentGuidanceEvents(root: Any): Set<String> {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>()
        val events = linkedSetOf<String>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (value, depth) = queue.removeFirst()
            if (!visited.add(value)) continue
            if (value is GuideResult && value.guidance != null) {
                value.reason.details["event"]?.let(events::add)
            }
            if (depth >= 3) continue
            for (field in value.javaClass.declaredFields) {
                if (field.isSynthetic || field.type.isPrimitive || field.type.name.startsWith("java.")) continue
                val child = runCatching { field.isAccessible = true; field.get(value) }.getOrNull() ?: continue
                if (child.javaClass.name.startsWith("com.trailnav")) queue.add(child to depth + 1)
            }
            for (method in value.javaClass.methods) {
                if (method.parameterCount != 0 || !method.name.startsWith("get")) continue
                val child = runCatching { method.invoke(value) }.getOrNull() ?: continue
                if (child.javaClass.name.startsWith("com.trailnav")) queue.add(child to depth + 1)
            }
        }
        return events
    }

    private val DIRECTION_DEPENDENT_EVENTS = setOf("E1", "E3", "E4", "E6", "TURN_AHEAD", "TURN_NOW")

    private fun StartScenario.label(): String = listOf(
        route.name,
        positionName,
        if (ingressMeters > 0.0) "ingress-${ingressMeters.toInt()}m" else "on-route",
        "stand-$standSeconds",
        if (direction > 0) "forward" else "reverse",
        "sigma-${sigmaMeters.toInt()}m",
    ).joinToString("/")

    private const val WALK_SPEED_MPS = 1.2
}

private object StartPhaseMilestoneProbe {
    private val config = GuideConfig().copy(
        milestoneEnabled = true,
        elapsedEnabled = true,
        remainingEnabled = true,
        slopeEnabled = true,
        elevationEnabled = true,
        waypointEnabled = true,
        sunsetEnabled = true,
        sunriseEnabled = true,
    )

    fun violations(): List<String> {
        val route = StartProbeRoutes.oneWay()
        val startMeters = route.lengthMeters * 0.50
        val session = GuideSession(RouteModel.fromGpx(route.xml, config), config, startupEnabled = true)
        val locations = syntheticTrack(route, startMeters, direction = 1, standSeconds = 0, sigmaMeters = 0.0, seconds = 900, seed = 84_159_177L)
        val violations = mutableListOf<String>()
        var guidanceStartedAtMillis: Long? = null
        for (location in locations) {
            val decision = session.accept(location)
            if (decision.startupJustResolved) guidanceStartedAtMillis = location.timestampMillis
            checkMilestone(decision, location, guidanceStartedAtMillis, violations)
        }
        return violations
    }

    private fun checkMilestone(
        decision: SessionDecision,
        location: TrailLocation,
        guidanceStartedAtMillis: Long?,
        violations: MutableList<String>,
    ) {
        val milestone = decision.result.guidance as? Guidance.Milestone ?: return
        val guidanceStart = guidanceStartedAtMillis ?: run {
            violations += "E1 emitted before direction resolution at ${location.timestampMillis}"
            return
        }
        val walkedMeters = (location.timestampMillis - guidanceStart) / 1_000.0 * WALK_SPEED_MPS
        val announcedMeters = decision.result.reason.details["thresholdMeters"]?.toDoubleOrNull()
            ?: milestone.distanceMeters
        if (abs(walkedMeters - announcedMeters) > WALK_SPEED_MPS * 2.0) {
            violations += "E1 threshold=$announcedMeters walked-from-guidance-start=$walkedMeters at ${location.timestampMillis}"
        }
    }

    private const val WALK_SPEED_MPS = 1.2
}

private object StartPhaseEarlyEventProbe {
    private const val DURATION_SECONDS = 60
    private const val START_MILLIS = 0L
    private const val METERS_PER_DEGREE = 111_320.0
    private val latitudeOrigin = 37.0
    private val longitudeOrigin = 127.0
    private val metersPerDegreeLongitude = METERS_PER_DEGREE * cos(Math.toRadians(latitudeOrigin))
    private val fieldConfig = GuideConfig().copy(
        milestoneEnabled = true,
        elapsedEnabled = true,
        remainingEnabled = true,
        slopeEnabled = true,
        elevationEnabled = true,
        waypointEnabled = true,
        sunsetEnabled = true,
        sunriseEnabled = true,
    )

    fun violations(): List<String> {
        val violations = mutableListOf<String>()
        val raisedHill = StartProbeRoutes.raisedLoop(waypoints = listOf(500.0 to "북쪽 쉼터", 800.0 to "능선 갈림길", 1_050.0 to "전망대"))
        val hillDecisions = run(raisedHill, startMeters = 0.0, startMillis = START_MILLIS)
        violations += validateEarlyGuidance("hill-forward", raisedHill, hillDecisions)

        val waypointRoute = StartProbeRoutes.raisedLoop(waypoints = listOf(100.0 to "첫 쉼터", 500.0 to "갈림길", 900.0 to "전망대"))
        val waypointDecisions = run(
            waypointRoute,
            startMeters = 0.0,
            startMillis = START_MILLIS,
            config = fieldConfig.copy(slopeEnabled = false),
        )
        violations += validateEarlyGuidance("waypoint-forward", waypointRoute, waypointDecisions)

        val solarRoute = StartProbeRoutes.raisedLoop(waypoints = listOf(500.0 to "첫 쉼터", 800.0 to "갈림길", 1_050.0 to "전망대"))
        val solarEvents = listOf(
            "sunset" to solarThresholdStart(sunrise = false),
            "sunrise" to solarThresholdStart(sunrise = true),
        )
        for ((label, startMillis) in solarEvents) {
            val decisions = run(
                solarRoute,
                startMeters = 0.0,
                startMillis = startMillis,
                config = fieldConfig.copy(slopeEnabled = false),
            )
            violations += validateEarlyGuidance("solar-$label", solarRoute, decisions)
            val expectedRule = if (label == "sunset") "event.sunset" else "event.sunrise"
            if (decisions.none { it.result.reason.rule == expectedRule && it.timestampMillis <= startMillis + DURATION_SECONDS * 1_000L }) {
                violations += "solar-$label: no threshold event observed in the first 60 seconds"
            }
            val expectedEvent = if (label == "sunset") "E7" else "E8"
            violations += requireEarlyEvent("solar-$label", expectedEvent, decisions)
        }
        return violations
    }

    private data class TimedDecision(val timestampMillis: Long, val result: com.trailnav.core.GuideResult)

    private fun run(
        route: StartRoute,
        startMeters: Double,
        startMillis: Long,
        config: GuideConfig = fieldConfig,
    ): List<TimedDecision> {
        val locations = syntheticTrack(route, startMeters, direction = 1, standSeconds = 0, sigmaMeters = 0.0, seconds = DURATION_SECONDS, seed = 84_159_178L, startMillis = startMillis)
        val session = GuideSession(RouteModel.fromGpx(route.xml, config), config, startupEnabled = true)
        val output = mutableListOf<TimedDecision>()
        for (location in locations) {
            output += TimedDecision(location.timestampMillis, session.accept(location).result)
        }
        output.filter { it.result.guidance != null }.forEach { decision ->
            println("START-EARLY-GUIDANCE ${route.name} t=${(decision.timestampMillis - startMillis) / 1_000} rule=${decision.result.reason.rule} details=${decision.result.reason.details} guidance=${decision.result.guidance}")
        }
        val snapshot = session?.snapshot()
        println(
            "START-EARLY-DISCARDED ${route.name} slope=${snapshot?.discardedSlopePeaks} " +
                "waypoints=${snapshot?.discardedWaypoints}",
        )
        return output
    }

    private fun requireEarlyEvent(label: String, event: String, decisions: List<TimedDecision>): List<String> {
        val observed = decisions.filter { it.result.reason.details["event"] == event }
        val counts = listOf("E4", "E6", "E7", "E8").joinToString(",") { name ->
            "$name=${decisions.count { it.result.reason.details["event"] == name }}"
        }
        println("START-EARLY-EVENTS $label $counts")
        return if (observed.isEmpty()) listOf("$label: expected $event was not observed in the startup window") else emptyList()
    }

    private fun validateEarlyGuidance(label: String, route: StartRoute, decisions: List<TimedDecision>): List<String> {
        val violations = mutableListOf<String>()
        val early = decisions.filter { it.timestampMillis <= (decisions.firstOrNull()?.timestampMillis ?: 0L) + DURATION_SECONDS * 1_000L }
        for (decision in early) {
            val guidance = decision.result.guidance ?: continue
            val reason = decision.result.reason
            when (guidance) {
                is Guidance.Slope -> {
                    val peakS = reason.details["peakS"]?.toDoubleOrNull()
                    val progress = decision.result.nextState.lastMatch?.projectedMeters
                    if (reason.details["event"] != "E4" || decision.result.nextState.direction != ProgressDirection.FORWARD ||
                        peakS == null || progress == null || peakS < progress
                    ) {
                        violations += "$label: unjustified E4 at ${decision.timestampMillis} details=${reason.details}"
                    }
                }
                is Guidance.Waypoint -> {
                    val targetIndex = reason.details["waypointIndex"]?.toIntOrNull()
                    val target = route.waypointMeters.getOrNull(targetIndex ?: -1)
                    val progress = decision.result.nextState.lastMatch?.projectedMeters
                    if (reason.details["event"] != "E6" || decision.result.nextState.direction != ProgressDirection.FORWARD ||
                        target == null || progress == null || target < progress
                    ) {
                        violations += "$label: unjustified E6 at ${decision.timestampMillis} details=${reason.details}"
                    }
                }
                is Guidance.Sunset -> {
                    val threshold = reason.details["thresholdMinutes"].orEmpty().split(',').mapNotNull(String::toIntOrNull)
                    val sunset = reason.details["sunsetEpochSeconds"]?.toDoubleOrNull()
                    val minutes = sunset?.minus(decision.timestampMillis / 1_000.0)?.div(60.0)
                    val afterSunset = reason.details["afterSunset"] == "true"
                    val firstEvaluation = reason.details["startAnnouncement"] == "true"
                    val delayed = reason.details["delayed"] == "true"
                    val thresholdApplies = threshold.any { value ->
                        if (firstEvaluation || delayed) minutes != null && minutes <= value + 0.1
                        else minutes != null && minutes <= value + 0.1 && minutes > value - 1.1
                    }
                    if (reason.details["event"] != "E7" || sunset == null || threshold.isEmpty() ||
                        (!afterSunset && !thresholdApplies)
                    ) {
                        violations += "$label: unjustified E7 at ${decision.timestampMillis} details=${reason.details}"
                    }
                }
                is Guidance.Sunrise -> {
                    val threshold = reason.details["thresholdMinutes"].orEmpty().split(',').mapNotNull(String::toIntOrNull)
                    val sunrise = reason.details["sunriseEpochSeconds"]?.toDoubleOrNull()
                    val minutes = sunrise?.minus(decision.timestampMillis / 1_000.0)?.div(60.0)
                    if (reason.details["event"] != "E8" || sunrise == null || threshold.isEmpty() ||
                        threshold.none { minutes != null && minutes <= it && minutes > it - 1.1 }
                    ) {
                        violations += "$label: unjustified E8 at ${decision.timestampMillis} details=${reason.details}"
                    }
                }
                else -> Unit
            }
        }
        return violations
    }

    private fun solarThresholdStart(sunrise: Boolean): Long {
        val day = Instant.parse("2026-06-21T12:00:00Z")
        val longitudeDay = day.atZone(ZoneOffset.UTC).plusSeconds((longitudeOrigin * 240.0).toLong()).toLocalDate()
        val dayOfYear = longitudeDay.dayOfYear
        val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + 0.5)
        val declination = 0.006918 - 0.399912 * kotlin.math.cos(gamma) + 0.070257 * kotlin.math.sin(gamma) -
            0.006758 * kotlin.math.cos(2 * gamma) + 0.000907 * kotlin.math.sin(2 * gamma)
        val equation = 229.18 * (0.000075 + 0.001868 * kotlin.math.cos(gamma) - 0.032077 * kotlin.math.sin(gamma) -
            0.014615 * kotlin.math.cos(2 * gamma) - 0.040849 * kotlin.math.sin(2 * gamma))
        val latitudeRadians = Math.toRadians(latitudeOrigin)
        val zenith = Math.toRadians(90.833)
        val cosineHour = (kotlin.math.cos(zenith) - kotlin.math.sin(latitudeRadians) * kotlin.math.sin(declination)) /
            (kotlin.math.cos(latitudeRadians) * kotlin.math.cos(declination))
        val hourAngleMinutes = Math.toDegrees(kotlin.math.acos(cosineHour.coerceIn(-1.0, 1.0))) * 4.0
        val eventMinutesUtc = if (sunrise) {
            720.0 - 4.0 * longitudeOrigin - hourAngleMinutes - equation
        } else {
            720.0 - 4.0 * longitudeOrigin + hourAngleMinutes - equation
        }
        val eventEpoch = longitudeDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + (eventMinutesUtc * 60.0).roundToInt()
        return (eventEpoch - 30 * 60 - 30) * 1_000L
    }
}

private fun independentWholeRouteMinimum(points: List<StartPoint>, point: StartPoint): Double =
    points.zipWithNext { start, end ->
        val deltaEast = end.east - start.east
        val deltaNorth = end.north - start.north
        val lengthSquared = deltaEast * deltaEast + deltaNorth * deltaNorth
        val fraction = if (lengthSquared == 0.0) {
            0.0
        } else {
            (((point.east - start.east) * deltaEast + (point.north - start.north) * deltaNorth) /
                lengthSquared).coerceIn(0.0, 1.0)
        }
        val nearestEast = start.east + fraction * deltaEast
        val nearestNorth = start.north + fraction * deltaNorth
        hypot(point.east - nearestEast, point.north - nearestNorth)
    }.minOrNull() ?: error("whole-route minimum requires at least one segment")

private object StartPhasePreDecisionProbe {
    private const val START_MILLIS = 0L
    private val config = GuideConfig().copy(
        milestoneEnabled = true,
        elapsedEnabled = true,
        remainingEnabled = true,
        slopeEnabled = true,
        elevationEnabled = true,
        waypointEnabled = true,
        sunsetEnabled = true,
        sunriseEnabled = true,
    )

    fun violations(startMeters: Double, nearestWholeRouteDistance: Double): List<String> {
        val route = StartProbeRoutes.oneWay()
        val session = GuideSession(RouteModel.fromGpx(route.xml, config), config, startupEnabled = true)
        val entry = route.pointAt(startMeters)
        val tangent = route.tangentAt(startMeters)
        var preDecisionGuidance: Guidance.OffRoute? = null
        var seenAtSeconds: Int? = null
        val onRoutePoint = route.pointAt(startMeters)
        session.accept(
            TrailLocation(
                timestampMillis = START_MILLIS,
                latitude = StartProbeRoutes.latitude + onRoutePoint.north / StartProbeRoutes.METERS_PER_DEGREE,
                longitude = StartProbeRoutes.longitude + onRoutePoint.east / StartProbeRoutes.metersPerDegreeLongitude,
                accuracyMeters = 5f,
                speedMps = 0f,
                bearingDegrees = null,
                provider = "synthetic-predecision",
            ),
        )
        for (second in 0 until 30) {
            val metersAway = 80.0
            val location = TrailLocation(
                timestampMillis = START_MILLIS + second * 1_000L,
                latitude = StartProbeRoutes.latitude + (entry.north + tangent.east * metersAway) / StartProbeRoutes.METERS_PER_DEGREE,
                longitude = StartProbeRoutes.longitude + (entry.east - tangent.north * metersAway) / StartProbeRoutes.metersPerDegreeLongitude,
                accuracyMeters = 5f,
                speedMps = 0f,
                bearingDegrees = null,
                provider = "synthetic-predecision",
            )
            val result = session.accept(location).result
            if (result.nextState.startupResolved) continue
            preDecisionGuidance = result.guidance as? Guidance.OffRoute
            if (preDecisionGuidance != null) {
                seenAtSeconds = second + 1
                break
            }
        }
        return when (val guidance = preDecisionGuidance) {
            null -> {
                println(
                    "PREDECISION-ENGINE startMeters=" + startMeters +
                        " distance=none expected=" + nearestWholeRouteDistance +
                        " seenAt=" + seenAtSeconds,
                )
                listOf("no decision-before off-route guidance was exposed while nearest whole-route distance was $nearestWholeRouteDistance m")
            }
            else -> {
                val delta = abs(guidance.distance - nearestWholeRouteDistance)
                println(
                    "PREDECISION-ENGINE startMeters=" + startMeters +
                        " distance=" + guidance.distance +
                        " expected=" + nearestWholeRouteDistance +
                        " delta=" + delta +
                        " seenAt=" + seenAtSeconds,
                )
                if (delta <= 1.0) emptyList() else listOf("pre-decision off-route distance=${guidance.distance}; whole-route minimum=$nearestWholeRouteDistance at second=$seenAtSeconds")
            }
        }
    }

}

internal object StartProbeRoutes {
    const val METERS_PER_DEGREE = 111_320.0
    const val latitude = 37.0
    const val longitude = 127.0
    val metersPerDegreeLongitude: Double = METERS_PER_DEGREE * cos(Math.toRadians(latitude))
    val engineDistanceScale: Double by lazy {
        val origin = StartPoint(0.0, 0.0)
        fun routeMathDistance(point: StartPoint): Double =
            RouteModel.fromGpx(routeGpx(listOf(origin, point))).totalLengthMeters

        val northScale = routeMathDistance(StartPoint(0.0, 100.0)) / 100.0
        val eastScale = routeMathDistance(StartPoint(100.0, 0.0)) / 100.0
        require(abs(northScale - eastScale) <= 1e-9) {
            "StartPhaseGrid requires direction-specific engine distance conversion when axes differ: north=$northScale east=$eastScale"
        }
        northScale
    }

    fun oneWay(): StartRoute = StartRoute(
        "C-oneway",
        listOf(
            StartPoint(0.0, 0.0), StartPoint(400.0, 0.0), StartPoint(400.0, 300.0),
            StartPoint(800.0, 300.0), StartPoint(800.0, 700.0), StartPoint(1_200.0, 700.0),
            StartPoint(1_200.0, 1_000.0),
        ),
        closed = false,
    )

    fun loop(): StartRoute = StartRoute(
        "A-loop",
        listOf(
            StartPoint(0.0, 0.0), StartPoint(300.0, 0.0), StartPoint(300.0, 300.0),
            StartPoint(0.0, 300.0), StartPoint(0.0, 0.0),
        ),
        closed = true,
    )

    fun lollipop(offsetMeters: Double = 0.0): StartRoute = StartRoute(
        "B-lollipop-offset-${offsetMeters.toInt()}m",
        listOf(
            StartPoint(0.0, 0.0), StartPoint(0.0, 200.0), StartPoint(200.0, 200.0),
            StartPoint(200.0, 400.0), StartPoint(offsetMeters, 400.0), StartPoint(offsetMeters, 200.0),
            StartPoint(0.0, 200.0),
            StartPoint(0.0, 0.0),
        ),
        closed = true,
    )

    fun raisedLoop(waypoints: List<Pair<Double, String>>): StartRoute {
        val base = listOf(
            StartPoint(0.0, 0.0, 100.0), StartPoint(300.0, 0.0, 100.0),
            StartPoint(300.0, 300.0, 100.0), StartPoint(0.0, 300.0, 100.0),
            StartPoint(0.0, 0.0, 100.0),
        )
        val route = StartRoute("event-loop", base, closed = true, waypointMeters = waypoints.map { it.first })
        val denseWithElevation = densifyWithHill(base)
        return route.copy(
            pointsOverride = denseWithElevation,
            xmlOverride = routeGpx(denseWithElevation, waypoints.map { route.pointAt(it.first) to it.second }),
        )
    }
}

internal fun syntheticTrack(
    route: StartRoute,
    startMeters: Double,
    direction: Int,
    standSeconds: Int,
    sigmaMeters: Double,
    seconds: Int,
    seed: Long,
    startMillis: Long = 0L,
): List<TrailLocation> {
    val random = Random(seed)
    val tangent = route.tangentAt(startMeters)
    return (0..seconds).map { time ->
        val travel = max(0.0, (time - standSeconds).toDouble()) * 1.2
        val center = route.pointAt(startMeters + direction * travel)
        val moved = time >= standSeconds
        val east = center.east + if (sigmaMeters > 0.0) random.nextGaussian() * sigmaMeters else 0.0
        val north = center.north + if (sigmaMeters > 0.0) random.nextGaussian() * sigmaMeters else 0.0
        TrailLocation(
            timestampMillis = startMillis + time * 1_000L,
            latitude = 37.0 + north / 111_320.0,
            longitude = 127.0 + east / (111_320.0 * cos(Math.toRadians(37.0))),
            accuracyMeters = 5f,
            speedMps = if (moved) 1.2f else 0f,
            bearingDegrees = null,
            provider = "synthetic-start-phase",
        )
    }
}

internal fun routeGpx(points: List<StartPoint>, waypoints: List<Pair<StartPoint, String>> = emptyList()): String = buildString {
    append("<gpx version=\"1.1\"><trk><trkseg>")
    for (point in points) {
        append("<trkpt lat=\"").append(37.0 + point.north / 111_320.0)
            .append("\" lon=\"").append(127.0 + point.east / (111_320.0 * cos(Math.toRadians(37.0))))
            .append("\"><ele>").append(point.elevation).append("</ele></trkpt>")
    }
    append("</trkseg></trk>")
    for ((point, name) in waypoints) {
        append("<wpt lat=\"").append(37.0 + point.north / 111_320.0)
            .append("\" lon=\"").append(127.0 + point.east / (111_320.0 * cos(Math.toRadians(37.0))))
            .append("\"><name>").append(xmlEscape(name)).append("</name></wpt>")
    }
    append("</gpx>")
}

private fun densify(corners: List<StartPoint>, stepMeters: Double = 10.0): List<StartPoint> = buildList {
    add(corners.first())
    for (index in 1 until corners.size) {
        val start = corners[index - 1]
        val end = corners[index]
        val distance = hypot(end.east - start.east, end.north - start.north)
        val segments = max(1, (distance / stepMeters).roundToInt())
        for (step in 1..segments) add(interpolate(start, end, step.toDouble() / segments))
    }
}

private fun densifyWithHill(corners: List<StartPoint>, stepMeters: Double = 10.0): List<StartPoint> {
    val flat = densify(corners, stepMeters)
    val cumulative = cumulativeMeters(flat)
    return flat.mapIndexed { index, point ->
        val s = cumulative[index]
        val elevation = when {
            s in 50.0..100.0 -> 100.0 + (s - 50.0) * 1.6
            s in 100.0..150.0 -> 180.0 - (s - 100.0) * 1.6
            else -> 100.0
        }
        point.copy(elevation = elevation)
    }
}

private fun cumulativeMeters(points: List<StartPoint>): List<Double> = buildList {
    add(0.0)
    for (index in 1 until points.size) add(last() + hypot(points[index].east - points[index - 1].east, points[index].north - points[index - 1].north))
}

private fun interpolate(start: StartPoint, end: StartPoint, fraction: Double): StartPoint = StartPoint(
    east = start.east + (end.east - start.east) * fraction,
    north = start.north + (end.north - start.north) * fraction,
    elevation = start.elevation + (end.elevation - start.elevation) * fraction,
)

private fun extrapolate(point: StartPoint, neighbor: StartPoint, distance: Double): StartPoint {
    val east = point.east - neighbor.east
    val north = point.north - neighbor.north
    val length = hypot(east, north).coerceAtLeast(1e-9)
    return StartPoint(point.east + east / length * distance, point.north + north / length * distance, point.elevation)
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private object FilesWalk {
    fun kotlinSources(root: java.nio.file.Path): List<java.nio.file.Path> {
        if (!java.nio.file.Files.isDirectory(root)) return emptyList()
        return java.nio.file.Files.walk(root).use { stream ->
            stream.filter { java.nio.file.Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .sorted()
                .toList()
        }
    }
}

private fun assertLowSigmaFalseArrivalCountsDoNotExceedMainBaseline(reportWhenNotN20: Boolean) {
    val lowSigmaCells = StartPhaseGrid.cells.filter { it.sigmaMeters <= 5.0 }
    if (StartPhaseGrid.sampleCount != 20) {
        if (reportWhenNotN20) {
            lowSigmaCells.sortedBy { it.label }.forEach { cell ->
                println(
                    "D223-MEASUREMENT sigma=" + cell.sigmaMeters +
                        " label=" + cell.label +
                        " N=" + cell.samples +
                        " falseArrivalRuns=" + cell.falseArrivalRuns,
                )
            }
        }
        return
    }
    val currentCounts = lowSigmaCells.associate { it.label to it.falseArrivalRuns }
    assertEquals(lowSigmaCells.size, currentCounts.size, "sigma<=5 grid labels must be unique")
    val baselineCounts = readStartPhaseGridBaselineN20()
    val violations = lowSigmaFalseArrivalBaselineViolations(currentCounts, baselineCounts)
    assertTrue(violations.isEmpty(), violations.take(30).joinToString("\n"))
}

private fun readStartPhaseGridBaselineN20(): Map<String, Int> {
    val source = Files.newBufferedReader(repositoryRoot().resolve("tools/start_phase_grid_baseline_n20.json"))
        .use { it.readText() }
    val sampleCount = Regex("\"sampleCount\"\\s*:\\s*(\\d+)")
        .find(source)?.groupValues?.get(1)?.toIntOrNull()
        ?: error("start-phase baseline is missing sampleCount")
    require(sampleCount == 20) { "start-phase baseline sampleCount must be 20, got " + sampleCount }
    val cellsSource = Regex("\"cells\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
        .find(source)?.groupValues?.get(1)
        ?: error("start-phase baseline is missing cells")
    val entries = Regex("\"([^\"]+)\"\\s*:\\s*(\\d+)").findAll(cellsSource).map { match ->
        match.groupValues[1] to (match.groupValues[2].toIntOrNull()
            ?: error("invalid start-phase baseline count for " + match.groupValues[1]))
    }.toList()
    val declaredEntries = Regex("\"[^\"]+\"\\s*:").findAll(cellsSource).count()
    require(entries.isNotEmpty()) { "start-phase baseline has no cells" }
    require(entries.size == declaredEntries) { "start-phase baseline cell values must all be integer counts" }
    require(entries.all { it.second >= 0 }) { "start-phase baseline counts must be non-negative" }
    require(entries.map { it.first }.toSet().size == entries.size) { "start-phase baseline has duplicate labels" }
    return entries.toMap()
}

internal fun lowSigmaFalseArrivalBaselineViolations(
    current: Map<String, Int>,
    baseline: Map<String, Int>,
): List<String> {
    val missingFromBaseline = current.keys - baseline.keys
    val missingFromGrid = baseline.keys - current.keys
    if (missingFromBaseline.isNotEmpty() || missingFromGrid.isNotEmpty()) {
        return listOf(
            "label mismatch: missing from baseline=" + missingFromBaseline.sorted() +
                ", missing from grid=" + missingFromGrid.sorted(),
        )
    }
    return current.entries
        .filter { (label, count) -> count > baseline.getValue(label) }
        .map { (label, count) -> label + ": falseArrivalRuns=" + count + " exceeds main=" + baseline.getValue(label) }
}
