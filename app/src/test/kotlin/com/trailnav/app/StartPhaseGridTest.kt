package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideResult
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
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

private data class StartPoint(val east: Double, val north: Double, val elevation: Double = 100.0)

private data class StartRoute(
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

private fun directionLabelMatchesRouteDistance(
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

private fun nearestRouteDistance(route: StartRoute, point: StartPoint): Double {
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
)

private data class StartRunEvidence(
    val arrived: Boolean,
    val onRouteOffRouteFrames: Int,
    val opposedDirectionFrames: Int,
    val directionDependentGuidanceBeforeResolution: Int,
)

internal fun arrivalViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.arrivalRuns > 0 }
    .map { "${it.label}: arrived in ${it.arrivalRuns}/${it.samples} runs" }

internal fun onRouteOffRouteViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.onRouteOffRouteFrames > 0 }
    .map { "${it.label}: off-route frames=${it.onRouteOffRouteFrames}" }

internal fun oppositeDirectionViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.opposedDirectionFrames > 0 }
    .map { "${it.label}: direction-opposed frames=${it.opposedDirectionFrames}" }

internal fun prematureDirectionGuidanceViolations(cells: List<StartCellEvidence>): List<String> = cells
    .filter { it.directionDependentGuidanceBeforeResolution > 0 }
    .map { "${it.label}: premature direction-dependent guidance=${it.directionDependentGuidanceBeforeResolution}" }

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
    fun sampleCountInputUsesDefaultAndRejectsInvalidValues() {
        assertEquals(1_000, parseStartPhaseGridSampleCount(null))
        assertEquals(250, parseStartPhaseGridSampleCount("250"))
        for (invalid in listOf("", "abc", "0", "-1")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                parseStartPhaseGridSampleCount(invalid)
            }
        }
    }

    @Test
    fun startPhaseArrivalAdvanceGuardRejectsNearLoopEndStartMutation() {
        assertFalse(
            StartPhaseGrid.deterministicLoopNearEndDidArrive(),
            "a deterministic start near the loop end must not satisfy arrival without credited advance",
        )
    }

    @Test
    fun startPhaseNoFalseArrivalWithinFiveMinutes() {
        expectedFailureAware("startPhaseNoFalseArrivalWithinFiveMinutes") {
            val violations = arrivalViolations(StartPhaseGrid.cells)
            assertTrue(violations.isEmpty(), violations.take(30).joinToString("\n"))
        }
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
        expectedFailureAware("startPhaseDirectionDependentGuidanceWaitsForResolution") {
            val violations = prematureDirectionGuidanceViolations(StartPhaseGrid.cells)
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
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
        expectedFailureAware("startPhaseEarlyEventsHaveJustifiedTargets") {
            val violations = StartPhaseEarlyEventProbe.violations()
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    @Test
    fun startPhasePreDecisionOffRouteUsesWholeRouteMinimum() {
        expectedFailureAware("startPhasePreDecisionOffRouteUsesWholeRouteMinimum") {
            val violations = StartPhasePreDecisionProbe.violations()
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    @Test
    fun startPhaseSummaryEvaluatorsRejectCounterexamples() {
        val clean = StartCellEvidence("clean", 0.0, 1, 0, 0, 0, 0)
        assertTrue(arrivalViolations(listOf(clean)).isEmpty())
        assertTrue(onRouteOffRouteViolations(listOf(clean)).isEmpty())
        assertTrue(oppositeDirectionViolations(listOf(clean)).isEmpty())
        assertTrue(prematureDirectionGuidanceViolations(listOf(clean)).isEmpty())

        assertTrue(arrivalViolations(listOf(clean.copy(arrivalRuns = 1))).isNotEmpty())
        assertTrue(onRouteOffRouteViolations(listOf(clean.copy(onRouteOffRouteFrames = 1))).isNotEmpty())
        assertTrue(oppositeDirectionViolations(listOf(clean.copy(opposedDirectionFrames = 1))).isNotEmpty())
        assertTrue(
            prematureDirectionGuidanceViolations(
                listOf(clean.copy(directionDependentGuidanceBeforeResolution = 1)),
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

internal fun parseStartPhaseGridSampleCount(raw: String?): Int {
    val value = raw ?: "1000"
    val parsed = value.toIntOrNull()
        ?: throw IllegalArgumentException("start-phase grid sample count must be an integer, got '$value'")
    require(parsed >= 1) { "start-phase grid sample count must be at least 1, got $parsed" }
    return parsed
}

private object StartPhaseGrid {
    private val sampleCount = parseStartPhaseGridSampleCount(
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

    private fun run(): List<StartCellEvidence> {
        val cells = scenarios().mapIndexed { cellIndex, scenario ->
            val samplesForCell = if (scenario.sigmaMeters == 0.0) 1 else sampleCount
            var arrivals = 0
            var offRouteFrames = 0
            var opposedFrames = 0
            var prematureFrames = 0
            for (sample in 0 until samplesForCell) {
                val seed = SEED_BASE + cellIndex * 10_000L + sample
                val run = runScenario(scenario, seed)
                if (run.arrived) arrivals++
                offRouteFrames += run.onRouteOffRouteFrames
                opposedFrames += run.opposedDirectionFrames
                prematureFrames += run.directionDependentGuidanceBeforeResolution
            }
            StartCellEvidence(
                label = scenario.label(),
                sigmaMeters = scenario.sigmaMeters,
                samples = samplesForCell,
                arrivalRuns = arrivals,
                onRouteOffRouteFrames = offRouteFrames,
                opposedDirectionFrames = opposedFrames,
                directionDependentGuidanceBeforeResolution = prematureFrames,
            )
        }
        cells.filter {
            it.arrivalRuns > 0 || it.onRouteOffRouteFrames > 0 ||
                it.opposedDirectionFrames > 0 || it.directionDependentGuidanceBeforeResolution > 0
        }.forEach { cell ->
            println(
                "START-PHASE-CELL ${cell.label} n=${cell.samples} arrivals=${cell.arrivalRuns} " +
                    "onRouteOff=${cell.onRouteOffRouteFrames} opposed=${cell.opposedDirectionFrames}",
            )
        }
        val arrivalCells = arrivalViolations(cells).size
        val offRouteCells = onRouteOffRouteViolations(cells).size
        val directionCells = oppositeDirectionViolations(cells).size
        val prematureCells = prematureDirectionGuidanceViolations(cells).size
        val arrivalRuns = cells.sumOf { it.arrivalRuns }
        val offRouteFrames = cells.sumOf { it.onRouteOffRouteFrames }
        val opposedFrames = cells.sumOf { it.opposedDirectionFrames }
        val prematureFrames = cells.sumOf { it.directionDependentGuidanceBeforeResolution }
        println(
            "START-PHASE-GRID cells=${cells.size} samples=${cells.sumOf { it.samples }} " +
                "arrivalCells=$arrivalCells arrivalRuns=$arrivalRuns " +
                "offRouteCells=$offRouteCells offRouteFrames=$offRouteFrames " +
                "directionCells=$directionCells opposedFrames=$opposedFrames " +
                "prematureCells=$prematureCells prematureFrames=$prematureFrames",
        )
        return cells
    }

    private fun runScenario(scenario: StartScenario, seed: Long): StartRunEvidence {
        val route = scenario.route
        val coordinator = RouteStartupCoordinator(route.xml, config, BASE_MILLIS)
        var session: GuideSession? = null
        var resolvedAtSeconds: Int? = null
        var arrived = false
        var offRouteFrames = 0
        var opposedFrames = 0
        var prematureGuidance = 0
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
            val east = point.east + if (scenario.sigmaMeters > 0.0) random.nextGaussian() * scenario.sigmaMeters else 0.0
            val north = point.north + if (scenario.sigmaMeters > 0.0) random.nextGaussian() * scenario.sigmaMeters else 0.0
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

        fun process(location: TrailLocation, sampleTimeSeconds: Int) {
            val active = session ?: return
            val decision = active.accept(location)
            if (decision.result.nextState.arrived && sampleTimeSeconds <= SECONDS) arrived = true
            val (_, onRoute) = truth(sampleTimeSeconds)
            if (onRoute && decision.result.nextState.offRoute) offRouteFrames++
            if (onRoute && decision.result.nextState.direction == ProgressDirection.REVERSE) opposedFrames++
        }

        for (time in 0..SECONDS) {
            val location = locationAt(time)
            if (session == null) {
                val update = if (time >= STARTUP_TIMEOUT_SECONDS) {
                    coordinator.timeout(location.timestampMillis)
                } else {
                    coordinator.accept(location, location.timestampMillis)
                }
                val orientation = update.orientation
                if (orientation == null) {
                    if (findDirectionDependentGuidance(update)) prematureGuidance++
                    continue
                }
                resolvedAtSeconds = time
                session = GuideSession(RouteModel.fromGpx(orientation.gpxXml, config), config)
                update.replayLocations.forEach { buffered ->
                    val sampleTime = ((buffered.timestampMillis - BASE_MILLIS) / 1_000L).toInt()
                    process(buffered, sampleTime)
                }
                // Match the investigation harness: the timeout-triggering fix is not in the buffer.
                continue
            }
            process(location, time)
        }
        check(resolvedAtSeconds != null) { "startup did not resolve for ${scenario.label()}" }
        return StartRunEvidence(arrived, offRouteFrames, opposedFrames, prematureGuidance)
    }

    private fun findDirectionDependentGuidance(root: Any): Boolean {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (value, depth) = queue.removeFirst()
            if (!visited.add(value)) continue
            if (value is GuideResult && value.guidance != null && value.reason.details["event"] in DIRECTION_DEPENDENT_EVENTS) return true
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
        return false
    }

    private val DIRECTION_DEPENDENT_EVENTS = setOf("E1", "E3", "E4", "E6")

    private fun StartScenario.label(): String = listOf(
        route.name,
        positionName,
        if (ingressMeters > 0.0) "ingress-${ingressMeters.toInt()}m" else "on-route",
        "stand-$standSeconds",
        if (direction > 0) "forward" else "reverse",
        "sigma-${sigmaMeters.toInt()}m",
    ).joinToString("/")

    private const val WALK_SPEED_MPS = 1.2
    private const val STARTUP_TIMEOUT_SECONDS = 30
}

private object StartPhaseMilestoneProbe {
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

    fun violations(): List<String> {
        val route = StartProbeRoutes.oneWay()
        val startMeters = route.lengthMeters * 0.50
        val coordinator = RouteStartupCoordinator(route.xml, config, START_MILLIS)
        val locations = syntheticTrack(route, startMeters, direction = 1, standSeconds = 0, sigmaMeters = 0.0, seconds = 900, seed = 84_159_177L)
        var session: GuideSession? = null
        val violations = mutableListOf<String>()
        for (location in locations) {
            if (session == null) {
                val update = coordinator.accept(location, location.timestampMillis)
                val orientation = update.orientation ?: continue
                println("START-EARLY-DIRECTION ${route.name} outcome=${update.observation.outcome} displacement=${update.observation.netDisplacementMeters}")
                session = GuideSession(RouteModel.fromGpx(orientation.gpxXml, config), config)
                update.replayLocations.forEach { buffered -> checkMilestone(session, buffered, violations) }
            } else {
                checkMilestone(session, location, violations)
            }
        }
        return violations
    }

    private fun checkMilestone(session: GuideSession, location: TrailLocation, violations: MutableList<String>) {
        val decision = session.accept(location)
        val milestone = decision.result.guidance as? Guidance.Milestone ?: return
        val walkedMeters = (location.timestampMillis - START_MILLIS) / 1_000.0 * WALK_SPEED_MPS
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
    private const val STARTUP_TIMEOUT_SECONDS = 30
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
        val coordinator = RouteStartupCoordinator(route.xml, config, startMillis)
        val locations = syntheticTrack(route, startMeters, direction = 1, standSeconds = 0, sigmaMeters = 0.0, seconds = DURATION_SECONDS, seed = 84_159_178L, startMillis = startMillis)
        var session: GuideSession? = null
        val output = mutableListOf<TimedDecision>()
        fun accept(sessionValue: GuideSession, location: TrailLocation) {
            output += TimedDecision(location.timestampMillis, sessionValue.accept(location).result)
        }
        for (location in locations) {
            if (session == null) {
                val elapsedSeconds = (location.timestampMillis - startMillis) / 1_000L
                val update = if (elapsedSeconds >= STARTUP_TIMEOUT_SECONDS) {
                    coordinator.timeout(location.timestampMillis)
                } else {
                    coordinator.accept(location, location.timestampMillis)
                }
                val orientation = update.orientation ?: continue
                val model = RouteModel.fromGpx(orientation.gpxXml, config)
                println(
                    "START-EARLY-ROUTE ${route.name} outcome=${update.observation.outcome} reversed=${orientation.reversed} " +
                        "elevation=${model.elevationUse} peaks=${model.peaks} waypoints=${model.waypoints}",
                )
                session = GuideSession(model, config)
                update.replayLocations.forEach { accept(session!!, it) }
            } else {
                accept(session!!, location)
            }
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

    fun violations(): List<String> {
        val route = StartProbeRoutes.oneWay()
        val startMeters = route.lengthMeters * 0.50
        val coordinator = RouteStartupCoordinator(route.xml, config, START_MILLIS)
        val entry = route.pointAt(startMeters)
        val tangent = route.tangentAt(startMeters)
        var preDecisionGuidance: Guidance.OffRoute? = null
        var seenAtSeconds: Int? = null
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
            val update = coordinator.accept(location, location.timestampMillis)
            if (update.orientation != null) continue
            preDecisionGuidance = findOffRouteGuidance(update)
            if (preDecisionGuidance != null) {
                seenAtSeconds = second
                break
            }
        }
        val nearestWholeRouteDistance = 80.0
        return when (val guidance = preDecisionGuidance) {
            null -> listOf("no decision-before off-route guidance was exposed while nearest whole-route distance was $nearestWholeRouteDistance m")
            else -> {
                val delta = abs(guidance.distance - nearestWholeRouteDistance)
                if (delta <= 1.0) emptyList() else listOf("pre-decision off-route distance=${guidance.distance}; whole-route minimum=$nearestWholeRouteDistance at second=$seenAtSeconds")
            }
        }
    }

    private fun findOffRouteGuidance(root: Any): Guidance.OffRoute? {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (value, depth) = queue.removeFirst()
            if (!visited.add(value)) continue
            if (value is Guidance.OffRoute) return value
            if (depth >= 3) continue
            for (method in value.javaClass.methods) {
                if (method.parameterCount != 0 || !method.name.startsWith("get")) continue
                val name = method.name.lowercase()
                if (!name.contains("guidance") && !name.contains("result") && !Guidance::class.java.isAssignableFrom(method.returnType)) continue
                val child = runCatching { method.invoke(value) }.getOrNull() ?: continue
                if (child is Guidance.OffRoute) return child
                if (child.javaClass.name.startsWith("com.trailnav")) queue.add(child to depth + 1)
            }
        }
        return null
    }
}

private object StartProbeRoutes {
    const val METERS_PER_DEGREE = 111_320.0
    const val latitude = 37.0
    const val longitude = 127.0
    val metersPerDegreeLongitude: Double = METERS_PER_DEGREE * cos(Math.toRadians(latitude))

    fun oneWay(): StartRoute = StartRoute(
        "C-oneway",
        listOf(
            StartPoint(0.0, 0.0), StartPoint(400.0, 0.0), StartPoint(400.0, 300.0),
            StartPoint(800.0, 300.0), StartPoint(800.0, 700.0), StartPoint(1_200.0, 700.0),
            StartPoint(1_200.0, 1_000.0),
        ),
        closed = false,
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

private fun syntheticTrack(
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

private fun routeGpx(points: List<StartPoint>, waypoints: List<Pair<StartPoint, String>> = emptyList()): String = buildString {
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
