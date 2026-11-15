package com.trailnav.replay

import com.trailnav.core.GeoPoint
import com.trailnav.core.Engine
import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.Guidance
import com.trailnav.core.RouteModel
import com.trailnav.core.SensorFrame
import com.trailnav.core.guide
import com.trailnav.core.routeStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.cos
import kotlin.math.PI

/**
 * The only executable bridge used by replay, accuracy, and config checks.
 * It loads data as input, calls the compiled :core-guide engine, and writes a
 * deterministic JSONL trace.  It has no role in the pure engine module.
 */
fun main(args: Array<String>) {
    val options = parseArgs(args.toList())
    val config = GuideConfig().withOverrides(options.overrides)
    if (options.probe) {
        runConfigProbe(config)
        return
    }
    if (options.probeSubsecond) {
        runSubsecondProbe(config)
        return
    }
    val session = requireNotNull(options.session) { "--session is required" }
    val routePath = requireNotNull(options.route) { "--route is required" }
    val route = RouteModel.fromGpx(Files.readString(Path.of(routePath)), config)
    var state = GuideState.starting(route, config)
    var locCount = 0
    var guideCount = 0
    val locOutputs = mutableListOf<Output>()
    var latest: Output? = null
    Files.readAllLines(Path.of(session, "events.ndjson")).forEach { line ->
        if (line.isBlank()) return@forEach
        val stream = stringField(line, "stream") ?: return@forEach
        if (stream == "loc") {
            val t = numberField(line, "t") ?: return@forEach
            val frame = SensorFrame(
                timestamp = Math.round(t * 1000.0),
                lat = numberField(line, "lat") ?: return@forEach,
                lon = numberField(line, "lon") ?: return@forEach,
                accuracy = (numberField(line, "accuracy") ?: 0.0).toFloat(),
                speed = numberField(line, "speed_mps")?.toFloat(),
                gpsBearing = numberField(line, "bearing_deg")?.toFloat()
            )
            val result = guide(state, frame, config)
            state = result.nextState
            locCount += 1
            val distance = result.guidanceDistance() ?: result.reason.details["distanceMeters"]?.toDoubleOrNull()
            val sourceSeq = numberField(line, "seq")?.toLong() ?: error("loc event is missing seq")
            latest = Output(locCount, t, decision(result.guidance), result.reason.rule, distance, result.nextState.direction.name.lowercase(), sourceSeq, result.reason.details, result.nextState.startupResolved, result.nextState.decidedDirection?.name?.lowercase() ?: "unknown", result.nextState.routeReversed)
            locOutputs += latest!!
            if (options.everyFrame) println(latest!!.json())
        } else if (stream == "guide" && !options.everyFrame) {
            val seq = numberField(line, "seq")?.toLong() ?: -1L
            val trigger = stringField(line, "trigger")
            if (trigger != null) {
                val triggerSourceSeq = numberField(line, "src_seq")?.toLong()
                    ?: error("trigger guide event is missing src_seq at seq=$seq")
                val status = routeStatus(state, config)
                val statusDetails = status?.let {
                    linkedMapOf(
                        "on_route" to it.onRoute.toString(),
                        "off_route_distance_m" to (it.offRouteDistanceMeters?.toString() ?: ""),
                        "direction" to it.direction.name,
                        "remaining_m" to it.remainingMeters.toString(),
                        "arrived" to it.arrived.toString(),
                        "next_turn_index" to (it.nextTurn?.index?.toString() ?: ""),
                        "next_turn_side" to (it.nextTurn?.side?.name ?: ""),
                        "next_turn_distance_m" to (it.nextTurn?.distanceMeters?.toString() ?: ""),
                    )
                } ?: emptyMap()
                val statusReason = if (status == null) "no-match" else "route-status"
                println("{\"kind\":\"trigger\",\"seq\":$seq,\"trigger\":\"${escape(trigger)}\",\"src_seq\":$triggerSourceSeq,\"status_reason\":\"$statusReason\",\"status_details\":${mapJson(statusDetails)}}")
                return@forEach
            }
            val recorded = stringField(line, "decision") ?: ""
            val output = locOutputs.getOrNull(guideCount)
                ?: error("guide event count exceeds loc event count at seq=$seq")
            val recordedSourceSeq = numberField(line, "src_seq")?.toLong()
            if (recordedSourceSeq != null && recordedSourceSeq != output.sourceSeq) {
                error("guide src_seq=$recordedSourceSeq does not match loc seq=${output.sourceSeq} at guide seq=$seq")
            }
            println("{\"kind\":\"guide\",\"seq\":$seq,\"loc_index\":${output.locIndex},\"source_seq\":${output.sourceSeq},\"recorded_src_seq\":${recordedSourceSeq ?: "null"},\"recorded_decision\":\"${escape(recorded)}\",\"decision\":\"${output.decision}\",\"reason_rule\":\"${escape(output.reasonRule)}\",\"reason_details\":${mapJson(output.details)},\"distance_m\":${output.distance ?: "null"},\"direction\":\"${output.direction}\",\"startup_resolved\":${output.startupResolved},\"decided_direction\":\"${escape(output.decidedDirection)}\",\"route_reversed\":${output.routeReversed}}")
            guideCount += 1
        }
    }
    if (!options.everyFrame && guideCount != locCount) {
        error("loc event count $locCount does not match guide event count $guideCount")
    }
    Engine.finish(state)
}

private data class CliOptions(
    val route: String?,
    val session: String?,
    val everyFrame: Boolean,
    val probe: Boolean,
    val probeSubsecond: Boolean,
    val overrides: Map<String, Double>
)

private fun parseArgs(args: List<String>): CliOptions {
    var route: String? = null
    var session: String? = null
    var everyFrame = false
    var probe = false
    var probeSubsecond = false
    val overrides = linkedMapOf<String, Double>()
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--route" -> route = args[++index]
            "--session" -> session = args[++index]
            "--emit-every-frame" -> everyFrame = true
            "--probe" -> probe = true
            "--probe-subsecond" -> probeSubsecond = true
            "--config" -> {
                val assignment = args[++index]
                val split = assignment.split('=', limit = 2)
                require(split.size == 2) { "config must be name=value" }
                val name = split[0]
                val rawValue = split[1]
                val value = rawValue.toDoubleOrNull()
                    ?: error("config $name must be numeric: $rawValue")
                if (name in EVENT_TOGGLE_FIELDS) {
                    require(value == 0.0 || value == 1.0) {
                        "config $name must be 0 or 1, was $rawValue"
                    }
                }
                overrides[name] = value
            }
            else -> error("unknown argument: ${args[index]}")
        }
        index += 1
    }
    return CliOptions(route, session, everyFrame, probe, probeSubsecond, overrides)
}

private data class Output(
    val locIndex: Int,
    val t: Double,
    val decision: String,
    val reasonRule: String,
    val distance: Double?,
    val direction: String,
    val sourceSeq: Long = -1L,
    val details: Map<String, String> = emptyMap(),
    val startupResolved: Boolean = true,
    val decidedDirection: String = "unknown",
    val routeReversed: Boolean = false
) {
    fun json(): String = "{\"kind\":\"frame\",\"loc_index\":$locIndex,\"source_seq\":${if (sourceSeq >= 0) sourceSeq else "null"},\"t\":$t,\"decision\":\"$decision\",\"reason_rule\":\"${escape(reasonRule)}\",\"reason_details\":${mapJson(details)},\"distance_m\":${distance ?: "null"},\"direction\":\"$direction\",\"startup_resolved\":$startupResolved,\"decided_direction\":\"${escape(decidedDirection)}\",\"route_reversed\":$routeReversed}"
}

private fun decision(guidance: Guidance?): String = when (guidance) {
    null -> "CONTINUE"
    is Guidance.OffRoute -> "OFF_ROUTE"
    is Guidance.Approach -> "APPROACH"
    is Guidance.TurnAhead -> "TURN_AHEAD"
    is Guidance.TurnNow -> "TURN_NOW"
    is Guidance.Milestone -> "MILESTONE"
    is Guidance.Elapsed -> "ELAPSED"
    is Guidance.Remaining -> "REMAINING"
    is Guidance.Slope -> "SLOPE"
    is Guidance.Elevation -> "ELEVATION"
    is Guidance.Waypoint -> "WAYPOINT"
    is Guidance.Sunset -> "SUNSET"
    is Guidance.Sunrise -> "SUNRISE"
    is Guidance.Status -> "STATUS"
    Guidance.Arrived -> "ARRIVED"
}

private fun com.trailnav.core.GuideResult.guidanceDistance(): Double? = when (val item = guidance) {
    is Guidance.OffRoute -> item.distance
    is Guidance.Approach -> item.distanceMeters
    is Guidance.Status -> item.distance
    is Guidance.Milestone -> item.distanceMeters
    is Guidance.Remaining -> item.thresholdMeters
    is Guidance.Waypoint -> item.distanceMeters
    else -> null
}

private fun GuideConfig.withOverrides(values: Map<String, Double>): GuideConfig = copy(
    offRouteEnterDistMeters = values["offRouteEnterDistMeters"] ?: offRouteEnterDistMeters,
    offRouteEnterDwellSeconds = values["offRouteEnterDwellSeconds"] ?: offRouteEnterDwellSeconds,
    offRouteExitDistMeters = values["offRouteExitDistMeters"] ?: offRouteExitDistMeters,
    offRouteExitDwellSeconds = values["offRouteExitDwellSeconds"] ?: offRouteExitDwellSeconds,
    reannounceIntervalSeconds = values["reannounceIntervalSeconds"] ?: reannounceIntervalSeconds,
    turnAheadDistanceMeters = values["turnAheadDistanceMeters"] ?: turnAheadDistanceMeters,
    turnNowDistanceMeters = values["turnNowDistanceMeters"] ?: turnNowDistanceMeters,
    turnOnRouteMaxOffsetMeters = values["turnOnRouteMaxOffsetMeters"] ?: turnOnRouteMaxOffsetMeters,
    startTrajectoryMeters = values["startTrajectoryMeters"] ?: startTrajectoryMeters,
    startTrajectoryMatchToleranceMeters = values["startTrajectoryMatchToleranceMeters"] ?: startTrajectoryMatchToleranceMeters,
    directionRefitIntervalMeters = values["directionRefitIntervalMeters"] ?: directionRefitIntervalMeters,
    directionSuspectMarginMeters = values["directionSuspectMarginMeters"] ?: directionSuspectMarginMeters,
    directionSuspectEnabled = values["directionSuspectEnabled"]?.let { it == 1.0 } ?: directionSuspectEnabled,
    directionDecidedShiftToleranceMeters = values["directionDecidedShiftToleranceMeters"] ?: directionDecidedShiftToleranceMeters,
    overlapMinRouteGapMeters = values["overlapMinRouteGapMeters"] ?: overlapMinRouteGapMeters,
    overlapNearMeters = values["overlapNearMeters"] ?: overlapNearMeters,
    directionObservationAfterOverlapMeters = values["directionObservationAfterOverlapMeters"] ?: directionObservationAfterOverlapMeters,
    milestoneEnabled = values["milestoneEnabled"]?.let { it == 1.0 } ?: milestoneEnabled,
    elapsedEnabled = values["elapsedEnabled"]?.let { it == 1.0 } ?: elapsedEnabled,
    remainingEnabled = values["remainingEnabled"]?.let { it == 1.0 } ?: remainingEnabled,
    slopeEnabled = values["slopeEnabled"]?.let { it == 1.0 } ?: slopeEnabled,
    elevationEnabled = values["elevationEnabled"]?.let { it == 1.0 } ?: elevationEnabled,
    waypointEnabled = values["waypointEnabled"]?.let { it == 1.0 } ?: waypointEnabled,
    sunsetEnabled = values["sunsetEnabled"]?.let { it == 1.0 } ?: sunsetEnabled,
    sunriseEnabled = values["sunriseEnabled"]?.let { it == 1.0 } ?: sunriseEnabled
)

private val EVENT_TOGGLE_FIELDS = setOf(
    "milestoneEnabled",
    "elapsedEnabled",
    "remainingEnabled",
    "slopeEnabled",
    "elevationEnabled",
    "waypointEnabled",
    "sunsetEnabled",
    "sunriseEnabled",
    "directionSuspectEnabled"
)

private fun runConfigProbe(config: GuideConfig) {
    // One right-angle turn makes the three turn thresholds observable by the
    // real compiled engine. The preceding off-route/recovery frames retain
    // the Phase 1 probes used by the existing sensitivity checks.
    val route = RouteModel.fromGpx("<gpx><trk><trkseg><trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.0018\" lon=\"20.0\"/><trkpt lat=\"10.0018\" lon=\"20.0018\"/></trkseg></trk></gpx>", config)
    var state = GuideState.initial(route)
    val eastOffset = 30.0 / (6_371_008.8 * cos(Math.toRadians(10.0))) * 180.0 / Math.PI
    val frames = listOf(
        SensorFrame(0, 10.0, 20.0, 5f, 1f, null),
        SensorFrame(1_000, 10.0001, 20.0 + eastOffset, 5f, 1f, null),
        SensorFrame(25_000, 10.0002, 20.0 + eastOffset, 5f, 1f, null),
        SensorFrame(30_000, 10.0003, 20.0 + eastOffset, 5f, 1f, null),
        SensorFrame(40_000, 10.0004, 20.0 + eastOffset / 3.0, 5f, 1f, null),
        SensorFrame(50_000, 10.0005, 20.0 + eastOffset / 3.0, 5f, 1f, null),
        SensorFrame(51_000, 10.0013, 20.0 + eastOffset / 3.0, 5f, 1f, null),
        SensorFrame(51_500, 10.00135, 20.0 + eastOffset / 3.0, 5f, 1f, null),
        SensorFrame(52_000, 10.0017, 20.0, 5f, 1f, null),
        SensorFrame(53_000, 10.00175, 20.0, 5f, 1f, null)
    )
    frames.forEachIndexed { index, frame ->
        val result = guide(state, frame, config)
        state = result.nextState
        val distance = result.guidanceDistance() ?: result.reason.details["distanceMeters"]?.toDoubleOrNull()
        val item = Output(index, frame.timestamp / 1000.0, decision(result.guidance), result.reason.rule, distance, result.nextState.direction.name.lowercase(), details = result.reason.details)
        println(item.json())
    }
    runStartupSensitivityProbe(config)
}

private data class ProbePoint(val east: Double, val north: Double)

private fun runStartupSensitivityProbe(config: GuideConfig) {
    val straight = probeRoute(listOf(ProbePoint(0.0, 0.0), ProbePoint(0.0, 600.0)), config)
    var straightState = GuideState.starting(straight, config)
    var decisionFrame = -1
    var decisionStart = Double.NaN
    var decisionCurrentProgress = 0.0
    var reverseSteps = 0
    for (second in 0..100) {
        val result = guide(straightState, probeFrame(straight, second.toDouble(), second * 1_000L, 1f), config)
        straightState = result.nextState
        if (straightState.startupResolved) {
            decisionFrame = second
            decisionStart = straightState.firstMatchProgressMeters ?: Double.NaN
            decisionCurrentProgress = straightState.lastMatch?.projectedMeters ?: second.toDouble()
            break
        }
    }
    if (decisionFrame >= 0) {
        reverseSteps = decisionCurrentProgress.toInt().coerceAtLeast(0)
        for (step in 1..reverseSteps) {
            val progress = (decisionCurrentProgress - step).coerceAtLeast(0.0)
            straightState = guide(
                straightState,
                probeFrame(straight, progress, (decisionFrame + step) * 1_000L, 1f),
                config,
            ).nextState
        }
    }
    val suspect = straightState.systemEvents.lastOrNull { it.kind == "direction.suspect" }
    val lastRefitWalked = straightState.directionObservationLastRefitWalkedMeters
    println(
        "{\"probe\":\"startup-refit\",\"decision_frame\":$decisionFrame,\"decision_start_m\":$decisionStart," +
            "\"decision_current_m\":$decisionCurrentProgress," +
            "\"reverse_steps\":$reverseSteps,\"suspect\":${suspect != null}," +
            "\"decided_shift_tolerance_m\":${config.directionDecidedShiftToleranceMeters}," +
            "\"suspect_walked_m\":${suspect?.fields?.get("walked_since_decision_m") ?: "null"}," +
            "\"last_refit_walked_m\":${lastRefitWalked ?: "null"},\"observation_active\":${straightState.directionObservationActive}}",
    )

    val nearParallel = probeRoute(
        listOf(
            ProbePoint(100.0, 0.0), ProbePoint(0.0, 0.0), ProbePoint(0.0, 50.0),
            ProbePoint(100.0, 50.0), ProbePoint(100.0, 2.0), ProbePoint(0.0, 2.0),
        ),
        config,
    )
    var parallelState = GuideState.starting(nearParallel, config)
    var parallelFrame = -1
    for (second in 0..100) {
        val result = guide(parallelState, probeFrame(nearParallel, 308.0 + second, second * 1_000L, 1f), config)
        parallelState = result.nextState
        if (parallelState.startupResolved) {
            parallelFrame = second
            break
        }
    }
    println(
        "{\"probe\":\"match-width\",\"decision_frame\":$parallelFrame," +
            "\"decision_direction\":\"${parallelState.decidedDirection ?: "none"}\"," +
            "\"decision_start_m\":${parallelState.firstMatchProgressMeters ?: "null"}}",
    )

    val overlapRoute = nearParallel
    val overlapStart = 50.0
    val overlapsAtStart = overlapRoute.overlapProgressRanges.any { overlapStart in it.startMeters..it.endMeters }
    var observationState = stateOnStartedRoute(overlapRoute, config, overlapStart)
    for (step in 1..120) {
        observationState = guide(
            observationState,
            probeFrame(overlapRoute, overlapStart + step, step * 1_000L, 1f),
            config,
        ).nextState
    }
    println(
        "{\"probe\":\"overlap-observation\",\"overlap_at_start\":$overlapsAtStart," +
            "\"overlap_ranges\":${overlapRoute.overlapProgressRanges.size}," +
            "\"observation_active\":${observationState.directionObservationActive}," +
            "\"last_refit_walked_m\":${observationState.directionObservationLastRefitWalkedMeters ?: "null"}}",
    )
}

private fun stateOnStartedRoute(route: RouteModel, config: GuideConfig, progress: Double): com.trailnav.core.GuideState {
    val segment = route.cumulativeMeters.indexOfLast { it <= progress }.coerceIn(0, route.cumulativeMeters.lastIndex - 1)
    val span = route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment]
    val fraction = if (span <= 0.0) 0.0 else (progress - route.cumulativeMeters[segment]) / span
    val start = route.points[segment]
    val end = route.points[segment + 1]
    val east = start.east + (end.east - start.east) * fraction
    val north = start.north + (end.north - start.north) * fraction
    val point = probePoint(route, east, north)
    val state = GuideState.starting(route, config)
    return state.copy(
        startupResolved = true,
        startupHasMatch = true,
        hasEnteredRoute = true,
        direction = com.trailnav.core.ProgressDirection.FORWARD,
        lastMatch = com.trailnav.core.MatchResult(0.0, progress, segment, start.copy(east = east, north = north), com.trailnav.core.ProgressDirection.FORWARD),
        firstMatchProgressMeters = progress,
        sessionStartTimestamp = 0L,
        lastTimestamp = 0L,
        directionDecidedAtMillis = 0L,
        decidedDirection = com.trailnav.core.ProgressDirection.FORWARD,
        directionObservationActive = true,
        directionObservationStartWalkedMeters = 0.0,
        directionObservationStartProgressMeters = progress,
        directionObservationOverlapExitWalkedMeters = null,
        directionObservationLastRefitWalkedMeters = 0.0,
        arrivalBaselineRemainingMeters = (route.totalLengthMeters - progress).coerceAtLeast(0.0),
        advanceHighWaterMeters = progress,
        trajectorySamples = listOf(com.trailnav.core.StartTrajectorySample(point, 0L, 0.0, 5.0)),
        lastTrajectoryTimestampMillis = 0L,
        lastTrajectoryPoint = point,
        lastTrajectorySpeedMps = 1.0,
        lastTrajectoryAccuracyMeters = 5.0,
    )
}

private fun probeRoute(points: List<ProbePoint>, config: GuideConfig): RouteModel = RouteModel.fromGpx(buildString {
    append("<gpx><trk><trkseg>")
    points.forEach { point ->
        val (lat, lon) = probeCoordinates(point.east, point.north)
        append("<trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>")
    }
    append("</trkseg></trk></gpx>")
}, config)

private fun probeFrame(route: RouteModel, progress: Double, timestamp: Long, speed: Float): SensorFrame {
    val clamped = progress.coerceIn(0.0, route.totalLengthMeters)
    val segment = route.cumulativeMeters.indexOfLast { it <= clamped }.coerceIn(0, route.cumulativeMeters.lastIndex - 1)
    val span = route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment]
    val fraction = if (span <= 0.0) 0.0 else (clamped - route.cumulativeMeters[segment]) / span
    val start = route.points[segment]
    val end = route.points[segment + 1]
    val point = probePoint(route, start.east + (end.east - start.east) * fraction, start.north + (end.north - start.north) * fraction)
    return SensorFrame(timestamp, point.lat, point.lon, 5f, speed, null)
}

private fun probePoint(route: RouteModel, east: Double, north: Double): GeoPoint {
    val origin = route.sourcePoints.first()
    val latitudeRadians = Math.toRadians(origin.lat)
    val degrees = 180.0 / PI
    return GeoPoint(
        origin.lat + north / 6_371_008.8 * degrees,
        origin.lon + east / (6_371_008.8 * cos(latitudeRadians)) * degrees,
    )
}

private fun probeCoordinates(east: Double, north: Double): Pair<Double, Double> {
    val latitude = 10.0 + north / 6_371_008.8 * 180.0 / PI
    val longitude = 20.0 + east / (6_371_008.8 * cos(Math.toRadians(10.0))) * 180.0 / PI
    return latitude to longitude
}

private fun runSubsecondProbe(config: GuideConfig) {
    val route = RouteModel.fromGpx("<gpx><trk><trkseg><trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.002\" lon=\"20.0\"/></trkseg></trk></gpx>", config)
    val metersPerDegreeLon = 6_371_008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
    val east30 = 30.0 / metersPerDegreeLon
    var state = GuideState.initial(route).copy(hasEnteredRoute = true)
    listOf(0L, 944L, 1_941L).forEachIndexed { index, timestamp ->
        val result = guide(state, SensorFrame(timestamp, 10.0005, 20.0 + east30, 5f, 1f, null), config)
        state = result.nextState
        val distance = result.guidanceDistance() ?: result.reason.details["distanceMeters"]?.toDoubleOrNull()
        Output(index, timestamp / 1000.0, decision(result.guidance), result.reason.rule, distance, state.direction.name.lowercase(), details = result.reason.details).also { println(it.json()) }
    }
}

private fun numberField(json: String, key: String): Double? =
    Regex("\\\"$key\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

private fun stringField(json: String, key: String): String? =
    Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.get(1)

private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun mapJson(values: Map<String, String>): String = values.entries.sortedBy { it.key }
    .joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"${escape(key)}\":\"${escape(value)}\"" }
