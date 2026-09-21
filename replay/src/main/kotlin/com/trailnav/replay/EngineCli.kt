package com.trailnav.replay

import com.trailnav.core.GeoPoint
import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.Guidance
import com.trailnav.core.RouteModel
import com.trailnav.core.SensorFrame
import com.trailnav.core.guide
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.cos

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
    var state = GuideState.initial(route)
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
            latest = Output(locCount, t, decision(result.guidance), result.reason.rule, distance, result.nextState.direction.name.lowercase(), sourceSeq)
            locOutputs += latest!!
            if (options.everyFrame) println(latest!!.json())
        } else if (stream == "guide" && !options.everyFrame) {
            val seq = numberField(line, "seq")?.toLong() ?: -1L
            val recorded = stringField(line, "decision") ?: ""
            val output = locOutputs.getOrNull(guideCount)
                ?: error("guide event count exceeds loc event count at seq=$seq")
            val recordedSourceSeq = numberField(line, "src_seq")?.toLong()
            if (recordedSourceSeq != null && recordedSourceSeq != output.sourceSeq) {
                error("guide src_seq=$recordedSourceSeq does not match loc seq=${output.sourceSeq} at guide seq=$seq")
            }
            println("{\"kind\":\"guide\",\"seq\":$seq,\"loc_index\":${output.locIndex},\"source_seq\":${output.sourceSeq},\"recorded_src_seq\":${recordedSourceSeq ?: "null"},\"recorded_decision\":\"${escape(recorded)}\",\"decision\":\"${output.decision}\",\"reason_rule\":\"${escape(output.reasonRule)}\",\"distance_m\":${output.distance ?: "null"},\"direction\":\"${output.direction}\"}")
            guideCount += 1
        }
    }
    if (!options.everyFrame && guideCount != locCount) {
        error("loc event count $locCount does not match guide event count $guideCount")
    }
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
                overrides[split[0]] = split[1].toDouble()
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
    val sourceSeq: Long = -1L
) {
    fun json(): String = "{\"kind\":\"frame\",\"loc_index\":$locIndex,\"source_seq\":${if (sourceSeq >= 0) sourceSeq else "null"},\"t\":$t,\"decision\":\"$decision\",\"reason_rule\":\"${escape(reasonRule)}\",\"distance_m\":${distance ?: "null"},\"direction\":\"$direction\"}"
}

private fun decision(guidance: Guidance?): String = when (guidance) {
    null -> "CONTINUE"
    is Guidance.OffRoute -> "OFF_ROUTE"
    is Guidance.TurnAhead -> "TURN_AHEAD"
    is Guidance.TurnNow -> "TURN_NOW"
    is Guidance.Status -> "STATUS"
    Guidance.Arrived -> "ARRIVED"
}

private fun com.trailnav.core.GuideResult.guidanceDistance(): Double? = when (val item = guidance) {
    is Guidance.OffRoute -> item.distance
    is Guidance.Status -> item.distance
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
    turnOnRouteMaxOffsetMeters = values["turnOnRouteMaxOffsetMeters"] ?: turnOnRouteMaxOffsetMeters
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
        val item = Output(index, frame.timestamp / 1000.0, decision(result.guidance), result.reason.rule, distance, result.nextState.direction.name.lowercase())
        println(item.json())
    }
}

private fun runSubsecondProbe(config: GuideConfig) {
    val route = RouteModel.fromGpx("<gpx><trk><trkseg><trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.002\" lon=\"20.0\"/></trkseg></trk></gpx>", config)
    val metersPerDegreeLon = 6_371_008.8 * cos(Math.toRadians(10.0)) * Math.PI / 180.0
    val east30 = 30.0 / metersPerDegreeLon
    var state = GuideState.initial(route)
    listOf(0L, 944L, 1_941L).forEachIndexed { index, timestamp ->
        val result = guide(state, SensorFrame(timestamp, 10.0005, 20.0 + east30, 5f, 1f, null), config)
        state = result.nextState
        val distance = result.guidanceDistance() ?: result.reason.details["distanceMeters"]?.toDoubleOrNull()
        Output(index, timestamp / 1000.0, decision(result.guidance), result.reason.rule, distance, state.direction.name.lowercase()).also { println(it.json()) }
    }
}

private fun numberField(json: String, key: String): Double? =
    Regex("\\\"$key\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

private fun stringField(json: String, key: String): String? =
    Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.get(1)

private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
