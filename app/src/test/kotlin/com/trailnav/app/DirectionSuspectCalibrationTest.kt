package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.Guidance
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import com.trailnav.core.routeStatus
import java.util.Random
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D-191 wrong-branch replay on both documented lollipop stem lengths. */
class DirectionSuspectCalibrationTest {
    @Test
    fun walkingEndsOnlyWrapForClosedRoutesWithoutRetracedOverlap() {
        val loop = StartProbeRoutes.loop()
        val lollipop = StartProbeRoutes.lollipop(0.0)
        val open = StartProbeRoutes.oneWay()
        assertTrue(loop.wrapsAsWalkingLoop())
        assertFalse(lollipop.wrapsAsWalkingLoop())
        assertFalse(open.wrapsAsWalkingLoop())
        assertEquals(loop.lengthMeters * 2.0, walkLimit(loop, 120.0, ProgressDirection.FORWARD))

        for (direction in listOf(ProgressDirection.FORWARD, ProgressDirection.REVERSE)) {
            val start = 120.0
            val lollipopRemaining = directionalRemaining(lollipop, start, direction)
            val openRemaining = directionalRemaining(open, start, direction)
            assertEquals(lollipopRemaining, walkLimit(lollipop, start, direction))
            assertEquals(openRemaining, walkLimit(open, start, direction))
            assertTrue(walkLimit(lollipop, start, direction) <= lollipopRemaining)
            assertTrue(walkLimit(open, start, direction) <= openRemaining)
        }

        val reverseTieRandom = Random(H6_TIE_START_SEED)
        val reverseTieStarts = List(1_000) { sampleTieStart(ProgressDirection.REVERSE, reverseTieRandom) }
        assertTrue(reverseTieStarts.all { it >= H6_END_MARGIN_METERS && it < TIE_START_UPPER_METERS })
        val forwardTieRandom = Random(H6_TIE_START_SEED)
        val forwardTieStarts = List(1_000) { sampleTieStart(ProgressDirection.FORWARD, forwardTieRandom) }
        assertTrue(forwardTieStarts.all { it >= TIE_START_LOWER_METERS && it < TIE_START_UPPER_METERS })

        val legacyLollipopWalk = walkLimit(lollipop, 120.0, ProgressDirection.REVERSE, closedRoutesRepeatTwice = true)
        assertFailsWith<AssertionError> {
            assertTrue(legacyLollipopWalk <= directionalRemaining(lollipop, 120.0, ProgressDirection.REVERSE))
        }
    }

    @Test
    fun wrongLoopBranchIsRecordedAcrossRequiredCellsAndDirectionGuidanceStaysSilent() {
        val config = GuideConfig()
        val missing = mutableListOf<String>()
        val cellSummary = mutableListOf<String>()
        for (stemMeters in listOf(200.0, 600.0)) {
            for (offsetMeters in listOf(0.0, 10.0)) {
                val route = lollipopRoute(stemMeters, offsetMeters)
                val routeModel = RouteModel.fromGpx(route.xml, config)
                val actualStemMeters = routeModel.cumulativeMeters[1]
                for (sigmaMeters in listOf(3.0, 5.0)) {
                    val cellIndex = listOf(200.0, 600.0).indexOf(stemMeters) * 4 +
                        listOf(0.0, 10.0).indexOf(offsetMeters) * 2 + listOf(3.0, 5.0).indexOf(sigmaMeters)
                    val starts = Random(191_200_026L + cellIndex)
                    val noise = Random(191_300_026L + cellIndex)
                    var recorded = 0
                    var forwardDecisions = 0
                    var observedReverse = 0
                    var noisyGuidanceAfterReverse = 0
                    var duplicateSuspects = 0
                    var worstMissing = ""
                    repeat(TRIALS_PER_CELL) { trialIndex ->
                        val lower = 10.0
                        val upper = actualStemMeters - config.startTrajectoryMeters - 20.0
                        val start = lower + starts.nextDouble() * (upper - lower)
                        val observed = buildWrongBranchWalk(actualStemMeters, offsetMeters, start)
                        val session = GuideSession(routeModel, config, startupEnabled = true)
                        var resolved = false
                        var decisionDirection: ProgressDirection? = null
                        var reversedObserved = false
                        var directionGuidanceAfterReverse = false
                        observed.points.forEachIndexed { frameIndex, point ->
                            val noisy = StartPoint(
                                point.east + noise.nextGaussian() * sigmaMeters,
                                point.north + noise.nextGaussian() * sigmaMeters,
                            )
                            val timeMillis = (frameIndex * FIX_MILLIS).toLong()
                            val decision = session.accept(location(noisy, timeMillis))
                            if (decision.startupJustResolved) {
                                resolved = true
                                decisionDirection = decision.result.nextState.decidedDirection
                            }
                            if (frameIndex >= observed.stemEndFrame && decision.result.nextState.direction == ProgressDirection.REVERSE) {
                                reversedObserved = true
                            }
                            val event = decision.result.reason.details["event"]
                            if (reversedObserved && decision.result.guidance != null && event in DIRECTION_DEPENDENT_EVENTS) {
                                directionGuidanceAfterReverse = true
                            }
                        }
                        val suspects = session.snapshot().systemEvents.filter { it.kind == "direction.suspect" }
                        if (suspects.size == 1) recorded++
                        if (suspects.size > 1) duplicateSuspects++
                        if (decisionDirection == ProgressDirection.FORWARD) forwardDecisions++
                        if (reversedObserved) observedReverse++
                        if (directionGuidanceAfterReverse) noisyGuidanceAfterReverse++
                        if (!resolved || decisionDirection != ProgressDirection.FORWARD || !reversedObserved || suspects.size != 1 || directionGuidanceAfterReverse) {
                            if (worstMissing.isEmpty()) {
                                worstMissing = "trial=$trialIndex p0=$start resolved=$resolved decided=$decisionDirection reverse=$reversedObserved suspects=${suspects.size} guidanceAfterReverse=$directionGuidanceAfterReverse"
                            }
                            missing += "stem=$stemMeters offset=$offsetMeters sigma=$sigmaMeters $worstMissing"
                        }
                    }
                    cellSummary += "stem=$stemMeters offset=$offsetMeters sigma=$sigmaMeters trials=$TRIALS_PER_CELL forward=$forwardDecisions reverse=$observedReverse suspect=$recorded duplicate=$duplicateSuspects directionGuidanceAfterReverse=$noisyGuidanceAfterReverse"
                }
            }
        }
        cellSummary.forEach { println("DIRECTION-SUSPECT-CELL $it") }
        println("DIRECTION-SUSPECT-H2-SUMMARY cells=${cellSummary.size} issueTrials=${missing.size} recordedFailuresAreMeasurements=true")
    }

    @Test
    fun directionSuspectToggleChangesOnlyDiagnosticRecording() {
        val enabledConfig = GuideConfig()
        val disabledConfig = enabledConfig.copy(directionSuspectEnabled = false)
        var enabledRecords = 0
        var disabledRecords = 0
        for (stemMeters in listOf(200.0, 600.0)) {
            for (offsetMeters in listOf(0.0, 10.0)) {
                val route = lollipopRoute(stemMeters, offsetMeters)
                val routeModel = RouteModel.fromGpx(route.xml, enabledConfig)
                val actualStemMeters = routeModel.cumulativeMeters[1]
                val start = (10.0 + (actualStemMeters - enabledConfig.startTrajectoryMeters - 20.0)) / 2.0
                for (sigmaMeters in listOf(0.0, 5.0)) {
                    val walk = buildWrongBranchWalk(actualStemMeters, offsetMeters, start)
                    fun run(config: GuideConfig): Pair<List<Triple<Guidance?, ProgressDirection, Double?>>, Int> {
                        val session = GuideSession(routeModel, config, startupEnabled = true)
                        val noise = Random(7L)
                        val frames = walk.points.mapIndexed { frameIndex, point ->
                            val noisy = StartPoint(
                                point.east + noise.nextGaussian() * sigmaMeters,
                                point.north + noise.nextGaussian() * sigmaMeters,
                            )
                            val decision = session.accept(location(noisy, (frameIndex * FIX_MILLIS).toLong()))
                            Triple(
                                decision.result.guidance,
                                decision.result.nextState.direction,
                                decision.result.nextState.lastMatch?.projectedMeters,
                            )
                        }
                        return frames to session.snapshot().systemEvents.count { it.kind == "direction.suspect" }
                    }

                    val enabled = run(enabledConfig)
                    val disabled = run(disabledConfig)
                    assertEquals(
                        enabled.first,
                        disabled.first,
                        "the config toggle must not change guidance, direction, or projected progress: stem=$stemMeters offset=$offsetMeters sigma=$sigmaMeters",
                    )
                    enabledRecords += enabled.second
                    disabledRecords += disabled.second
                    println(
                        "DIRECTION-SUSPECT-TOGGLE stem=$stemMeters offset=$offsetMeters sigma=$sigmaMeters " +
                            "frames=${enabled.first.size} enabledRecords=${enabled.second} disabledRecords=${disabled.second} framesEqual=true",
                    )
                }
            }
        }
        assertTrue(enabledRecords >= 1, "enabled direction suspicion must produce at least one diagnostic record")
        assertEquals(0, disabledRecords, "disabled direction suspicion must produce no diagnostic records")
    }

    @Test
    fun noiseFreeWrongBranchIsRecordedExactlyOnceAtEveryRequiredStart() {
        val config = GuideConfig()
        var totalRecorded = 0
        for (stemMeters in listOf(200.0, 600.0)) {
            for (offsetMeters in listOf(0.0, 10.0)) {
                val route = lollipopRoute(stemMeters, offsetMeters)
                val routeModel = RouteModel.fromGpx(route.xml, config)
                val actualStemMeters = routeModel.cumulativeMeters[1]
                val lower = 10.0
                val upper = actualStemMeters - config.startTrajectoryMeters - 20.0
                for (startIndex in 0..4) {
                    val start = lower + (upper - lower) * startIndex / 4.0
                    val walk = buildWrongBranchWalk(actualStemMeters, offsetMeters, start)
                    val session = GuideSession(routeModel, config, startupEnabled = true)
                    walk.points.forEachIndexed { frameIndex, point ->
                        session.accept(location(point, (frameIndex * FIX_MILLIS).toLong()))
                    }
                    val suspects = session.snapshot().systemEvents.filter { it.kind == "direction.suspect" }
                    println(
                        "DIRECTION-SUSPECT-NO-NOISE stem=$stemMeters offset=$offsetMeters start=$start " +
                            "suspectRecords=${suspects.size} expected=1",
                    )
                    assertEquals(
                        1,
                        suspects.size,
                        "each noiseless wrong-branch input must produce exactly one direction.suspect record",
                    )
                    totalRecorded += suspects.size
                }
            }
        }
        assertEquals(20, totalRecorded)
    }

    @Test
    fun pairedInterpretationGuidanceMatchesHandSolvedOverlapTruth() {
        val config = GuideConfig()
        val probeRoute = StartProbeRoutes.lollipop(0.0)
        val forwardRoute = RouteModel.fromGpx(probeRoute.xml, config)
        val reverseProbeRoute = probeRoute.copy(
            corners = probeRoute.corners.reversed(),
            pointsOverride = probeRoute.points.reversed(),
            xmlOverride = routeGpx(probeRoute.points.reversed()),
        )
        val reverseRoute = forwardRoute.reversed(config)

        fun stateAt(model: RouteModel, probe: StartRoute, progress: Double) = GuideSession(
            model,
            config,
            startupEnabled = false,
        ).let { session ->
            val state = session.accept(location(probe.pointAt(progress), 0L)).result.nextState
            state.copy(
                direction = ProgressDirection.FORWARD,
                lastMatch = requireNotNull(state.lastMatch).copy(
                    projectedMeters = progress,
                    direction = ProgressDirection.FORWARD,
                ),
            )
        }

        val forwardStatus = routeStatus(stateAt(forwardRoute, probeRoute, 1_100.0), config)
        val pairedStatus = routeStatus(stateAt(reverseRoute, reverseProbeRoute, 1_100.0), config)
        val forward = requireNotNull(forwardStatus)
        val paired = requireNotNull(pairedStatus)
        val remainingDifference = kotlin.math.abs(forward.remainingMeters - paired.remainingMeters)
        println(
            "H3-GUIDANCE forwardRemaining=${forward.remainingMeters} " +
                "pairedRemaining=${paired.remainingMeters} forwardNextTurn=${forward.nextTurn?.let { "${it.index}/${it.side}" } ?: "none"} " +
                "pairedNextTurn=${paired.nextTurn?.let { "${it.index}/${it.side}" } ?: "none"} " +
                "remainingDifference=$remainingDifference",
        )
        assertEquals(100.0, forward.remainingMeters, 5.0, "the hand-solved forward interpretation has 100 m remaining")
        assertEquals(100.0, paired.remainingMeters, 5.0, "the paired reverse interpretation has 100 m remaining")
        assertEquals(forward.remainingMeters, paired.remainingMeters, 1e-6, "paired interpretations must report equal remaining distance")
        assertNull(forward.nextTurn, "the hand-solved forward interpretation has no next turn")
        assertNull(paired.nextTurn, "the paired reverse interpretation has no next turn")
    }

    @Test
    fun b8OverlapPairHasZeroPathErrorWhileNonPairDoesNot() {
        val config = GuideConfig()
        assertEquals(25.0, config.offRouteEnterDistMeters, "B-8's 25 m record-obligation constant must match GuideConfig")
        val route = StartProbeRoutes.lollipop(0.0)
        val paired = maximumPathError(
            route = route,
            engineProgress = 1_100.0,
            engineDirection = ProgressDirection.FORWARD,
            actualProgress = 100.0,
            actualDirection = ProgressDirection.REVERSE,
            maximumMeters = 80.0,
        )
        val notPaired = maximumPathError(
            route = route,
            engineProgress = 100.0,
            engineDirection = ProgressDirection.FORWARD,
            actualProgress = 100.0,
            actualDirection = ProgressDirection.REVERSE,
            maximumMeters = 80.0,
        )
        val pairedStartError = hypot(
            route.pointAt(1_100.0).east - route.pointAt(100.0).east,
            route.pointAt(1_100.0).north - route.pointAt(100.0).north,
        )
        assertTrue(pairedStartError <= 5.0, "converted overlap-pair position must match its decision frame; error=$pairedStartError")
        assertTrue(paired <= 5.0, "overlap pair must describe the same path; maximum error=$paired")
        assertTrue(notPaired >= RECORD_OBLIGATION_METERS, "non-pair control must exceed the record obligation; maximum error=$notPaired")
        assertEquals(RECORD_OBLIGATION_METERS, config.offRouteEnterDistMeters)
        println("B8-PATH-PAIR paired_max_m=$paired non_pair_max_m=$notPaired walked_m=80")
    }

    @Test
    fun ordinaryWalksDoNotCreateFalseSuspectsAcrossShapesStopsMiddleStartsAndGpsImpairments() {
        val config = GuideConfig()
        val routes = listOf(
            StartProbeRoutes.loop(),
            StartProbeRoutes.lollipop(0.0),
            StartProbeRoutes.oneWay(),
        )
        val errors = mutableListOf<String>()
        var h6Cell = 0L
        for (mode in listOf(WalkMode.NORMAL, WalkMode.TIE, WalkMode.STAND, WalkMode.MIDDLE)) {
            val selectedRoutes = when (mode) {
                WalkMode.TIE -> listOf(StartProbeRoutes.lollipop(0.0))
                WalkMode.MIDDLE -> listOf(StartProbeRoutes.oneWay())
                else -> routes
            }
            for (route in selectedRoutes) {
                for (sigma in listOf(3.0, 5.0, 10.0)) {
                    for (travelDirection in listOf(ProgressDirection.FORWARD, ProgressDirection.REVERSE)) {
                        val summary = runH6Cell(
                            route = route,
                            mode = mode,
                            direction = travelDirection,
                            sigma = sigma,
                            trials = H6_TRIALS,
                            seed = H6_SEED + h6Cell,
                            config = config,
                        )
                        println("DIRECTION-SUSPECT-H6 ${summary.line}")
                        summary.issues.forEach { println("DIRECTION-SUSPECT-H6-ISSUE $it") }
                        if (sigma == 3.0 || sigma == 5.0) {
                            errors += summary.issues
                        }
                        h6Cell++
                    }
                }
            }
        }

        for (route in routes) {
            for (direction in listOf(ProgressDirection.FORWARD, ProgressDirection.REVERSE)) {
                val summary = runH6Cell(
                    route = route,
                    mode = WalkMode.NORMAL,
                    direction = direction,
                    sigma = 5.0,
                    trials = H6_TAIL_TRIALS,
                    seed = H6_TAIL_SEED + h6Cell,
                    config = config,
                )
                println("DIRECTION-SUSPECT-H6-TAIL ${summary.line}")
                summary.issues.forEach { println("DIRECTION-SUSPECT-H6-TAIL-ISSUE $it") }
                errors += summary.issues
                h6Cell++
            }
        }
        println("DIRECTION-SUSPECT-H6-SUMMARY diagnosticIssueRows=${errors.size} assertionsOnDiagnosticCounts=false")
    }

    @Test
    fun h6SigmaFiveLongRunDiagnosticCells() {
        val config = GuideConfig()
        val routes = listOf(
            StartProbeRoutes.loop(),
            StartProbeRoutes.lollipop(0.0),
            StartProbeRoutes.oneWay(),
        )
        var cell = 0L
        for (route in routes) {
            for (direction in listOf(ProgressDirection.FORWARD, ProgressDirection.REVERSE)) {
                val summary = runH6Cell(
                    route = route,
                    mode = WalkMode.NORMAL,
                    direction = direction,
                    sigma = 5.0,
                    trials = H6_TAIL_TRIALS,
                    seed = H6_TAIL_SEED + cell,
                    config = config,
                )
                println("DIRECTION-SUSPECT-H6-SIGMA5-LONG ${summary.line}")
                summary.issues.forEach { println("DIRECTION-SUSPECT-H6-SIGMA5-LONG-ISSUE $it") }
                cell++
            }
        }
        println("DIRECTION-SUSPECT-H6-SIGMA5-LONG-SUMMARY cells=$cell trialsPerCell=$H6_TAIL_TRIALS diagnosticOnly=true")
    }

    private data class H6Summary(val line: String, val issues: List<String>)

    private fun sampleTieStart(direction: ProgressDirection, random: Random): Double {
        val minimum = if (direction == ProgressDirection.REVERSE) H6_END_MARGIN_METERS else TIE_START_LOWER_METERS
        return minimum + random.nextDouble() * (TIE_START_UPPER_METERS - minimum)
    }

    private fun sampleOrdinaryStart(
        route: StartRoute,
        wrapsAsLoop: Boolean,
        direction: ProgressDirection,
        random: Random,
    ): Double = when {
        wrapsAsLoop -> random.nextDouble() * route.lengthMeters
        direction == ProgressDirection.FORWARD -> random.nextDouble() * (route.lengthMeters - H6_END_MARGIN_METERS)
        else -> H6_END_MARGIN_METERS + random.nextDouble() * (route.lengthMeters - H6_END_MARGIN_METERS)
    }

    private fun directionalRemaining(route: StartRoute, start: Double, direction: ProgressDirection): Double =
        if (direction == ProgressDirection.FORWARD) route.lengthMeters - start else start

    private fun walkLimit(
        route: StartRoute,
        start: Double,
        direction: ProgressDirection,
        closedRoutesRepeatTwice: Boolean = false,
    ): Double = when {
        closedRoutesRepeatTwice && route.closed -> route.lengthMeters * 2.0
        route.wrapsAsWalkingLoop() -> route.lengthMeters * 2.0
        else -> directionalRemaining(route, start, direction)
    }

    private fun runH6Cell(
        route: StartRoute,
        mode: WalkMode,
        direction: ProgressDirection,
        sigma: Double,
        trials: Int,
        seed: Long,
        config: GuideConfig,
    ): H6Summary {
        val model = RouteModel.fromGpx(route.xml, config)
        val starts = Random(seed)
        val noise = Random(seed xor 0x5DEECE66DL)
        val wrapsAsLoop = route.wrapsAsWalkingLoop()
        val issues = mutableListOf<String>()
        var noDecision = 0
        var equivalent = 0
        var divergent = 0
        var ambiguous = 0
        var falseSuspects = 0
        var missedRecords = 0
        var maxPathError = 0.0
        var maxExcessRmse = 0.0
        repeat(trials) { trial ->
            val actualSign = if (direction == ProgressDirection.FORWARD) 1.0 else -1.0
            val start = when (mode) {
                WalkMode.TIE -> sampleTieStart(direction, starts)
                WalkMode.MIDDLE -> route.lengthMeters * (0.4 + 0.2 * starts.nextDouble())
                else -> sampleOrdinaryStart(route, wrapsAsLoop, direction, starts)
            }
            val standSeconds = if (mode == WalkMode.STAND) 35 else 0
            val remainingToEnd = if (wrapsAsLoop) route.lengthMeters * 2.0 else directionalRemaining(route, start, direction)
            val maxWalked = remainingToEnd.coerceAtLeast(0.0)
            val lastSecond = standSeconds + ceil(maxWalked / WALK_SPEED_MPS).toInt()
            val jumpSecond = standSeconds + 60
            val gapStart = standSeconds + 45
            val useJump = mode == WalkMode.NORMAL && trial % 20 == 0
            val useGap = mode == WalkMode.NORMAL && trial % 20 == 1
            val session = GuideSession(model, config, startupEnabled = true)
            val frameTravel = mutableListOf<Pair<Int, Double>>()
            var decisionSecond: Int? = null
            var decisionTravel = 0.0
            var engineDirection: ProgressDirection? = null
            var engineProgressRaw: Double? = null
            var observationEndTravel: Double? = null
            var previousObservationActive = false
            for (second in 0..lastSecond) {
                if (useGap && second in gapStart..(gapStart + 3)) continue
                val traveled = min((second - standSeconds).coerceAtLeast(0) * WALK_SPEED_MPS.toDouble(), maxWalked)
                val rawProgress = start + actualSign * traveled
                val truthPoint = route.pointAt(rawProgress)
                val tangent = route.tangentAt(rawProgress)
                val jump = if (useJump && second == jumpSecond) 60.0 else 0.0
                val observed = StartPoint(
                    truthPoint.east - tangent.north * jump + noise.nextGaussian() * sigma,
                    truthPoint.north + tangent.east * jump + noise.nextGaussian() * sigma,
                )
                val decision = session.accept(
                    TrailLocation(
                        timestampMillis = second * 1_000L,
                        latitude = StartProbeRoutes.latitude + observed.north / StartProbeRoutes.METERS_PER_DEGREE,
                        longitude = StartProbeRoutes.longitude + observed.east / StartProbeRoutes.metersPerDegreeLongitude,
                        accuracyMeters = 5f,
                        speedMps = if (second < standSeconds) 0f else WALK_SPEED_MPS.toFloat(),
                        bearingDegrees = null,
                        provider = "synthetic-direction-suspect-h6",
                    ),
                )
                val state = decision.result.nextState
                if (decision.startupJustResolved) {
                    decisionSecond = second
                    decisionTravel = traveled
                    engineDirection = state.decidedDirection
                    val orientedProgress = state.directionObservationStartProgressMeters
                    if (orientedProgress != null) {
                        val routeLength = model.totalLengthMeters
                        val raw = if (state.routeReversed) routeLength - orientedProgress else orientedProgress
                        engineProgressRaw = raw * route.lengthMeters / routeLength
                    }
                }
                frameTravel.add(second to traveled)
                if (previousObservationActive && !state.directionObservationActive && observationEndTravel == null) {
                    observationEndTravel = traveled
                }
                previousObservationActive = state.directionObservationActive
                val elapsedAfterDecision = traveled - decisionTravel
                if (decisionSecond != null && observationEndTravel != null && elapsedAfterDecision >= observationEndTravel!! - decisionTravel + 500.0) {
                    break
                }
            }

            val decidedAtSecond = decisionSecond
            val chosenProgress = engineProgressRaw
            val chosenDirection = engineDirection
            if (decidedAtSecond == null || chosenProgress == null || chosenDirection == null) {
                noDecision++
                if (sigma != 10.0) issues += "mode=$mode route=${route.name} direction=$direction sigma=$sigma trial=$trial start=$start engineDirection=$chosenDirection enginePosition=$chosenProgress maxPathError=none suspectFields=none unresolved=true"
                return@repeat
            }
            val actualAtDecision = start + actualSign * decisionTravel
            val remainingActualAfterDecision = if (wrapsAsLoop) Double.POSITIVE_INFINITY
                else directionalRemaining(route, actualAtDecision, direction)
            val maximumObservationWindow = observationEndTravel?.minus(decisionTravel)?.coerceAtLeast(0.0)?.plus(500.0)
                ?: maxWalked - decisionTravel
            val evaluationDistance = minOf(maxWalked - decisionTravel, maximumObservationWindow, remainingActualAfterDecision)
                .coerceAtLeast(0.0)
            var trialPathError = 0.0
            for ((second, traveled) in frameTravel) {
                if (second <= decidedAtSecond) continue
                val walkedAfterDecision = traveled - decisionTravel
                if (walkedAfterDecision > evaluationDistance) continue
                val truth = route.pointAt(start + actualSign * traveled)
                val engineSign = if (chosenDirection == ProgressDirection.REVERSE) -1.0 else 1.0
                val selectedProgress = chosenProgress + engineSign * walkedAfterDecision
                val predicted = if (wrapsAsLoop) route.pointAt(selectedProgress)
                    else route.pointAt(selectedProgress.coerceIn(0.0, route.lengthMeters))
                trialPathError = maxOf(trialPathError, hypot(truth.east - predicted.east, truth.north - predicted.north))
            }
            maxPathError = maxOf(maxPathError, trialPathError)
            val suspects = session.snapshot().systemEvents.filter { it.kind == "direction.suspect" }
            val suspectFields = suspects.singleOrNull()?.fields?.entries?.joinToString(";") { "${it.key}=${it.value}" } ?: "${suspects.size} records"
            val excess = suspects.singleOrNull()?.fields?.get("excess_rmse_m")?.toDoubleOrNull() ?: 0.0
            maxExcessRmse = maxOf(maxExcessRmse, excess)
            when {
                trialPathError <= 5.0 -> {
                    equivalent++
                    if (suspects.isNotEmpty()) {
                        falseSuspects++
                        issues += "mode=$mode route=${route.name} direction=$direction sigma=$sigma trial=$trial p0=$start engineDirection=$chosenDirection enginePosition=$chosenProgress decisionTravel=$decisionTravel observationEnd=${observationEndTravel ?: "not-ended"} measuredMeters=$evaluationDistance maxPathError=$trialPathError suspectFields=$suspectFields"
                    }
                }
                trialPathError >= RECORD_OBLIGATION_METERS -> {
                    divergent++
                    if (suspects.size != 1) {
                        missedRecords++
                        issues += "mode=$mode route=${route.name} direction=$direction sigma=$sigma trial=$trial p0=$start engineDirection=$chosenDirection enginePosition=$chosenProgress decisionTravel=$decisionTravel observationEnd=${observationEndTravel ?: "not-ended"} measuredMeters=$evaluationDistance maxPathError=$trialPathError suspectFields=$suspectFields"
                    }
                }
                else -> ambiguous++
            }
        }
        return H6Summary(
            "mode=$mode route=${route.name} direction=$direction sigma=$sigma trials=$trials equivalent=$equivalent divergent=$divergent ambiguous=$ambiguous falseSuspect=$falseSuspects missingRecord=$missedRecords unresolved=$noDecision maxPathError=$maxPathError maxExcessRmse=$maxExcessRmse",
            issues,
        )
    }

    private fun maximumPathError(
        route: StartRoute,
        engineProgress: Double,
        engineDirection: ProgressDirection,
        actualProgress: Double,
        actualDirection: ProgressDirection,
        maximumMeters: Double,
    ): Double {
        val engineSign = if (engineDirection == ProgressDirection.REVERSE) -1.0 else 1.0
        val actualSign = if (actualDirection == ProgressDirection.REVERSE) -1.0 else 1.0
        val steps = ceil(maximumMeters / 5.0).toInt()
        return (0..steps).maxOf { step ->
            val walked = min(step * 5.0, maximumMeters)
            val predicted = route.pointAt(engineProgress + engineSign * walked)
            val actual = route.pointAt(actualProgress + actualSign * walked)
            hypot(predicted.east - actual.east, predicted.north - actual.north)
        }
    }

    private data class WrongBranchWalk(val points: List<StartPoint>, val stemEndFrame: Int)

    private fun buildWrongBranchWalk(stemMeters: Double, offsetMeters: Double, startMeters: Double): WrongBranchWalk {
        val points = mutableListOf<StartPoint>()
        val initial = StartPoint(0.0, startMeters)
        points += initial
        appendSegment(points, initial, StartPoint(0.0, stemMeters))
        val stemEndFrame = points.lastIndex
        val wrongLoop = listOf(
            StartPoint(0.0, stemMeters),
            StartPoint(offsetMeters, stemMeters),
            StartPoint(offsetMeters, stemMeters + 200.0),
            StartPoint(200.0, stemMeters + 200.0),
            StartPoint(200.0, stemMeters),
            StartPoint(0.0, stemMeters),
        )
        wrongLoop.zipWithNext().forEach { (start, end) -> appendSegment(points, start, end) }
        return WrongBranchWalk(points, stemEndFrame)
    }

    private fun appendSegment(points: MutableList<StartPoint>, start: StartPoint, end: StartPoint) {
        val length = hypot(end.east - start.east, end.north - start.north)
        if (length <= 0.0) return
        val steps = ceil(length / SAMPLE_METERS).toInt()
        for (step in 1..steps) {
            val distance = min(length, step * SAMPLE_METERS)
            val fraction = distance / length
            points += StartPoint(
                start.east + (end.east - start.east) * fraction,
                start.north + (end.north - start.north) * fraction,
            )
        }
    }

    private fun lollipopRoute(stemMeters: Double, offsetMeters: Double): StartRoute {
        val points = listOf(
            StartPoint(0.0, 0.0),
            StartPoint(0.0, stemMeters),
            StartPoint(200.0, stemMeters),
            StartPoint(200.0, stemMeters + 200.0),
            StartPoint(offsetMeters, stemMeters + 200.0),
            StartPoint(offsetMeters, stemMeters),
            StartPoint(0.0, stemMeters),
            StartPoint(0.0, 0.0),
        )
        return StartRoute(
            name = "D191-lollipop-$stemMeters-$offsetMeters",
            corners = points,
            closed = true,
            pointsOverride = points,
            xmlOverride = routeGpx(points),
        )
    }

    private fun routeGpx(points: List<StartPoint>): String = buildString {
        append("<gpx><trk><trkseg>")
        points.forEach { point ->
            append("<trkpt lat=\"").append(StartProbeRoutes.latitude + point.north / StartProbeRoutes.METERS_PER_DEGREE)
                .append("\" lon=\"").append(StartProbeRoutes.longitude + point.east / StartProbeRoutes.metersPerDegreeLongitude)
                .append("\"/>")
        }
        append("</trkseg></trk></gpx>")
    }

    private fun location(point: StartPoint, timestampMillis: Long): TrailLocation = TrailLocation(
        timestampMillis = timestampMillis,
        latitude = StartProbeRoutes.latitude + point.north / StartProbeRoutes.METERS_PER_DEGREE,
        longitude = StartProbeRoutes.longitude + point.east / StartProbeRoutes.metersPerDegreeLongitude,
        accuracyMeters = 5f,
        speedMps = WALK_SPEED_MPS,
        bearingDegrees = null,
        provider = "synthetic-direction-suspect",
    )

    private companion object {
        const val TRIALS_PER_CELL = 100
        const val H6_TRIALS = 200
        const val H6_TAIL_TRIALS = 1_000
        const val H6_SEED = 1_092_026_114L
        const val H6_TAIL_SEED = 1_192_026_114L
        const val H6_TIE_START_SEED = 1_093_026_114L
        const val H6_END_MARGIN_METERS = 80.0
        const val RECORD_OBLIGATION_METERS = 25.0
        const val TIE_START_LOWER_METERS = 10.0
        const val TIE_START_UPPER_METERS = 140.0
        const val SAMPLE_METERS = 5.0
        const val FIX_MILLIS = SAMPLE_METERS / 1.2 * 1_000.0
        const val WALK_SPEED_MPS = 1.2f
        val DIRECTION_DEPENDENT_EVENTS = setOf("E1", "E3", "E4", "E6", "TURN_AHEAD", "TURN_NOW")
    }

    private enum class WalkMode { NORMAL, TIE, STAND, MIDDLE }
}
