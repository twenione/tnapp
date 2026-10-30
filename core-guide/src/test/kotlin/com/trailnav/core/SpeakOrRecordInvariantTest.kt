package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** D-153 §3-§5 frame-by-frame consumed-target accounting for replayable sessions. */
class SpeakOrRecordInvariantTest {
    private data class Change(val frameIndex: Int, val event: String, val enabled: Boolean)
    private data class Gap(val event: String, val category: String, val session: String, val frame: Int, val target: String)
    private data class RunResult(
        val directory: Path,
        val route: RouteModel,
        val transitions: List<Transition>,
        val changes: List<Change>,
    )
    private data class Transition(
        val frameIndex: Int,
        val before: GuideState,
        val result: GuideResult,
        val config: GuideConfig,
    )

    // D-153 §5: temporary, observed unrecorded outcomes pending a separate decision.
    // E4/E6 are closed by D-152 except for targets skipped without an observed announce window.
    private val KNOWN_UNRECORDED = setOf(
        "E4" to "jump",
        "E1" to "disabled",
        "E1" to "interval",
        "E3" to "disabled",
        "E3" to "interval",
    )

    @Test
    fun everyConsumedAnnouncementTargetIsSpokenOrRecorded() {
        val root = E5EventCountSupport.repositoryRoot().resolve("testdata/sessions")
        val sessions = buildList {
            addAll(sessionDirectories(root.resolve("golden")).filterNot { it.fileName.toString() == "log_shape_fixture" })
            addAll(sessionDirectories(root.resolve("synth")))
            addAll(sessionDirectories(root.resolve("property")))
        }
        check(sessions.isNotEmpty()) { "no replayable sessions found" }

        val gaps = mutableListOf<Gap>()
        val duplicates = mutableListOf<String>()
        val runs = sessions.map { directory -> runSession(directory, gaps, duplicates) }
        val observed = gaps.map { it.event to it.category }.toSet()
        gaps.forEach { gap ->
            println("PROPERTY_GAP event=${gap.event} category=${gap.category} session=${gap.session} frame=${gap.frame} target=${gap.target}")
        }
        duplicates.forEach { println("PROPERTY_DUPLICATE $it") }
        check(duplicates.isEmpty()) { "a consumed target was both spoken and recorded or was recorded more than once: ${duplicates.joinToString()}" }
        check(observed == KNOWN_UNRECORDED) {
            "D-153 §5 allowed-list mismatch: new=${observed - KNOWN_UNRECORDED}; stale=${KNOWN_UNRECORDED - observed}"
        }
        check(observed.none { (event, category) -> event in setOf("E4", "E6") && category != "jump" }) {
            "D-152 E4/E6 gap is not an unobserved jump: $observed"
        }

        val propertyRuns = runs.filter { it.directory.parent.fileName.toString() == "property" }
            .associateBy { it.directory.fileName.toString() }
        check(propertyRuns.keys == setOf(
            "e4_disabled_pending", "e4_reverse_pending", "e6_disabled", "e4_direction_unknown",
            "e6_direction_unknown", "e6_offroute",
            "e6_reverse", "e6_interval", "e6_priority", "all_events_off_on",
        )) { "generated property fixture set changed: ${propertyRuns.keys}" }
        assertRecord(propertyRuns.getValue("e4_disabled_pending"), "E4", "disabled")
        assertRecord(propertyRuns.getValue("e4_reverse_pending"), "E4", "reverse")
        assertRecord(propertyRuns.getValue("e6_disabled"), "E6", "disabled")
        assertRecord(propertyRuns.getValue("e4_direction_unknown"), "E4", "direction-unknown")
        assertRecord(propertyRuns.getValue("e6_direction_unknown"), "E6", "direction-unknown")
        assertRecord(propertyRuns.getValue("e6_offroute"), "E6", "offroute")
        assertRecord(propertyRuns.getValue("e6_reverse"), "E6", "reverse")
        assertRecord(propertyRuns.getValue("e6_interval"), "E6", "interval")
        assertRecord(propertyRuns.getValue("e6_priority"), "E6", "priority")
        val toggleChanges = propertyRuns.getValue("all_events_off_on").changes
        check(toggleChanges.size == 16 && EVENTS.all { event ->
            toggleChanges.any { it.event == event && !it.enabled } && toggleChanges.any { it.event == event && it.enabled }
        }) { "the all-events session did not switch E1-E8 off and back on" }
        check(runs.size == sessions.size) { "not every golden, synth, and property session was replayed" }
        println("speak_or_record_sessions=${runs.size} gaps=${gaps.size} duplicates=${duplicates.size} property_config_changes=${toggleChanges.size}")
    }

    private fun runSession(directory: Path, gaps: MutableList<Gap>, duplicates: MutableList<String>): RunResult {
        val manifest = Files.readString(directory.resolve("manifest.json"))
        val route = RouteModel.fromGpx(Files.readString(directory.resolve("route.gpx")))
        val changes = Regex(
            """\{\s*"frame_index"\s*:\s*(\d+)\s*,\s*"event"\s*:\s*"(E[1-8])"\s*,\s*"value"\s*:\s*(true|false)\s*}""",
        ).findAll(manifest).map { match ->
            Change(match.groupValues[1].toInt(), match.groupValues[2], match.groupValues[3].toBoolean())
        }.toList()
        val configRoot = Regex(""""config"\s*:\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1).orEmpty()
        val eventConfig = EVENTS.associateWith { event ->
            Regex(""""$event"\s*:\s*(true|false)""").find(configRoot)?.groupValues?.get(1)?.toBoolean()
        }.filterValues { it != null }.mapValues { it.value!! }
        var config = GuideConfig(
            milestoneEnabled = eventConfig["E1"] ?: GuideConfig().milestoneEnabled,
            elapsedEnabled = eventConfig["E2"] ?: GuideConfig().elapsedEnabled,
            remainingEnabled = eventConfig["E3"] ?: GuideConfig().remainingEnabled,
            slopeEnabled = eventConfig["E4"] ?: GuideConfig().slopeEnabled,
            elevationEnabled = eventConfig["E5"] ?: GuideConfig().elevationEnabled,
            waypointEnabled = eventConfig["E6"] ?: GuideConfig().waypointEnabled,
            sunsetEnabled = eventConfig["E7"] ?: GuideConfig().sunsetEnabled,
            sunriseEnabled = eventConfig["E8"] ?: GuideConfig().sunriseEnabled,
            eventMinIntervalSeconds = configNumber(manifest, "eventMinIntervalSeconds") ?: GuideConfig().eventMinIntervalSeconds,
            milestoneIntervalMeters = configNumber(manifest, "milestoneIntervalMeters") ?: GuideConfig().milestoneIntervalMeters,
            peakAnnounceLeadMeters = configNumber(manifest, "peakAnnounceLeadMeters") ?: GuideConfig().peakAnnounceLeadMeters,
            waypointAnnounceLeadMeters = configNumber(manifest, "waypointAnnounceLeadMeters") ?: GuideConfig().waypointAnnounceLeadMeters,
            offRouteEnterDwellSeconds = configNumber(manifest, "offRouteEnterDwellSeconds") ?: GuideConfig().offRouteEnterDwellSeconds,
        )
        val seededProgress = configNumber(manifest, "property_seed_projected_meters")
        var state = GuideState.initial(route)
        if (seededProgress != null) {
            val point = E5EventCountSupport.pointAt(route, seededProgress)
            val segment = route.cumulativeMeters.indexOfLast { it <= seededProgress }.coerceIn(0, route.cumulativeMeters.lastIndex - 1)
            state = state.copy(
                hasEnteredRoute = true,
                lastMatch = MatchResult(0.0, seededProgress, segment, route.toEnu(point), ProgressDirection.FORWARD),
                lastTimestamp = 0L,
                sessionStartTimestamp = 0L,
                direction = ProgressDirection.FORWARD,
            )
        }

        val rows = Files.readAllLines(directory.resolve("events.ndjson"))
        val locations = rows.filter { it.contains("\"stream\":\"loc\"") || it.contains("\"stream\": \"loc\"") }
        val transitions = mutableListOf<Transition>()
        var changeCursor = 0
        locations.forEachIndexed { frameIndex, line ->
            while (changeCursor < changes.size && changes[changeCursor].frameIndex == frameIndex) {
                config = withEvent(config, changes[changeCursor].event, changes[changeCursor].enabled)
                changeCursor += 1
            }
            val frame = SensorFrame(
                timestamp = (number(line, "t") * 1_000.0).toLong(),
                lat = number(line, "lat"),
                lon = number(line, "lon"),
                accuracy = number(line, "accuracy").toFloat(),
                speed = number(line, "speed_mps").toFloat(),
                gpsBearing = numberOrNull(line, "bearing_deg")?.toFloat(),
            )
            val before = state
            val result = guide(before, frame, config)
            val beforeTargets = consumedTargets(before)
            val afterTargets = consumedTargets(result.nextState)
            val spoken = spokenTargets(result).toMutableSet()
            if (result.guidance is Guidance.Sunset && result.reason.details["afterSunset"] == "true") {
                (result.nextState.consumedSunsetThresholds - before.consumedSunsetThresholds)
                    .forEach { spoken += "E7" to it.toString() }
            }
            val newSlopeRecords = result.nextState.discardedSlopePeaks.drop(before.discardedSlopePeaks.size)
            val newWaypointRecords = result.nextState.discardedWaypoints.drop(before.discardedWaypoints.size)
            (afterTargets.keys).forEach { event ->
                val newlyConsumed = afterTargets.getValue(event) - beforeTargets[event].orEmpty()
                newlyConsumed.forEach { target ->
                    val targetId = event to target
                    val recordCount = when (event) {
                        "E4" -> newSlopeRecords.count { it.index.toString() == target }
                        "E6" -> newWaypointRecords.count { it.index.toString() == target }
                        else -> 0
                    }
                    val count = (if (targetId in spoken) 1 else 0) + recordCount
                    when {
                        count == 0 -> gaps += Gap(event, gapCategory(event, target, before, result.nextState, config, route), directory.fileName.toString(), frameIndex, target)
                        count > 1 -> duplicates += "${directory.fileName}:$frameIndex $event target=$target count=$count"
                    }
                }
            }
            transitions += Transition(frameIndex, before, result, config)
            state = result.nextState
        }
        check(changeCursor == changes.size) { "${directory.fileName}: config change points outside the frame stream" }
        return RunResult(directory, route, transitions, changes)
    }

    private fun assertRecord(run: RunResult, event: String, reason: String) {
        val matching = run.transitions.flatMap { transition ->
            val after = transition.result.nextState
            when (event) {
                "E4" -> after.discardedSlopePeaks.drop(transition.before.discardedSlopePeaks.size)
                    .filter { it.reason == reason }.map { event to it.index }
                "E6" -> after.discardedWaypoints.drop(transition.before.discardedWaypoints.size)
                    .filter { it.reason == reason }.map { event to it.index }
                else -> emptyList()
            }
        }
        check(matching.size == 1) { "${run.directory.fileName}: expected exactly one $event/$reason record, found $matching" }
    }

    private fun consumedTargets(state: GuideState): Map<String, Set<String>> = mapOf(
        "E1" to state.consumedMilestoneIndices.map(Int::toString).toSet(),
        "E2" to state.consumedElapsedIndices.map(Int::toString).toSet(),
        "E3" to state.consumedRemainingThresholds.map(::canonical).toSet(),
        "E4" to state.consumedSlopeIndices.map(Int::toString).toSet(),
        "E6" to state.consumedWaypointIndices.map(Int::toString).toSet(),
        "E7" to state.consumedSunsetThresholds.map(Int::toString).toSet(),
        "E8" to state.consumedSunriseThresholds.map(Int::toString).toSet(),
        "TURN_AHEAD" to state.completedTurnAheadIndices.map(Int::toString).toSet(),
        "TURN_NOW" to state.completedTurnNowIndices.map(Int::toString).toSet(),
    )

    private fun spokenTargets(result: GuideResult): Set<Pair<String, String>> = buildSet {
        val details = result.reason.details
        when (val guidance = result.guidance) {
            is Guidance.Milestone -> details["index"]?.let { add("E1" to it) }
            is Guidance.Elapsed -> details["hours"]?.let { add("E2" to it) }
            is Guidance.Remaining -> details["thresholdMeters"]?.toDoubleOrNull()?.let { add("E3" to canonical(it)) }
            is Guidance.Slope -> details["peakIndex"]?.let { add("E4" to it) }
            is Guidance.Waypoint -> details["waypointIndex"]?.let { add("E6" to it) }
            is Guidance.Sunset -> {
                val thresholds = details["thresholdMinutes"].orEmpty().split(',').filter(String::isNotBlank)
                thresholds.mapNotNull(String::toIntOrNull).forEach { add("E7" to it.toString()) }
                if (details["afterSunset"] == "true") add("E7" to "0")
            }
            is Guidance.Sunrise -> details["thresholdMinutes"].orEmpty().split(',')
                .mapNotNull(String::toIntOrNull).forEach { add("E8" to it.toString()) }
            is Guidance.TurnAhead -> details["turnIndex"]?.let { add("TURN_AHEAD" to it) }
            is Guidance.TurnNow -> {
                details["turnIndex"]?.let { add("TURN_NOW" to it); add("TURN_AHEAD" to it) }
            }
            else -> Unit
        }
    }

    private fun gapCategory(
        event: String,
        target: String,
        before: GuideState,
        after: GuideState,
        config: GuideConfig,
        route: RouteModel,
    ): String {
        if (event == "E4") {
            val s = route.peaks[target.toInt()].s
            val previousDistance = before.lastMatch?.let { s - it.projectedMeters }
            val currentDistance = after.lastMatch?.let { s - it.projectedMeters }
            return if (target.toInt() !in before.pendingSlopePeaks &&
                (previousDistance == null || previousDistance < 0.0 || previousDistance > config.peakAnnounceLeadMeters) && currentDistance != null && currentDistance < 0.0
            ) "jump" else "missing-record"
        }
        if (event == "E6") {
            val s = route.waypoints[target.toInt()].s
            val previousDistance = before.lastMatch?.let { s - it.projectedMeters }
            val currentDistance = after.lastMatch?.let { s - it.projectedMeters }
            return if ((previousDistance == null || previousDistance < 0.0 || previousDistance > config.waypointAnnounceLeadMeters) && currentDistance != null && currentDistance < 0.0) {
                "jump"
            } else "missing-record"
        }
        val onRoute = !after.offRoute
        val intervalOpen = before.lastPeriodicEventAt == null ||
            (after.lastTimestamp!! - before.lastPeriodicEventAt) / 1_000.0 >= config.eventMinIntervalSeconds
        val eventEnabled = when (event) {
            "E1" -> config.milestoneEnabled
            "E2" -> config.elapsedEnabled
            "E3" -> config.remainingEnabled
            "E5" -> config.elevationEnabled
            "E7" -> config.sunsetEnabled
            "E8" -> config.sunriseEnabled
            else -> true
        }
        return when {
            !eventEnabled -> "disabled"
            !onRoute -> "offroute"
            !intervalOpen -> "interval"
            event in setOf("E1", "E3") && after.direction == ProgressDirection.REVERSE -> "reverse"
            event in setOf("E1", "E3") && after.direction == ProgressDirection.UNKNOWN -> "direction-unknown"
            event.startsWith("TURN_") && after.direction != ProgressDirection.FORWARD -> "direction-gated"
            event.startsWith("TURN_") -> "passed-or-merged"
            else -> "coalesced-or-priority"
        }
    }

    private fun withEvent(config: GuideConfig, event: String, enabled: Boolean): GuideConfig = when (event) {
        "E1" -> config.copy(milestoneEnabled = enabled)
        "E2" -> config.copy(elapsedEnabled = enabled)
        "E3" -> config.copy(remainingEnabled = enabled)
        "E4" -> config.copy(slopeEnabled = enabled)
        "E5" -> config.copy(elevationEnabled = enabled)
        "E6" -> config.copy(waypointEnabled = enabled)
        "E7" -> config.copy(sunsetEnabled = enabled)
        "E8" -> config.copy(sunriseEnabled = enabled)
        else -> error("unknown event toggle: $event")
    }

    private fun sessionDirectories(parent: Path): List<Path> = Files.list(parent).use { stream ->
        stream.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve("manifest.json")) &&
            Files.isRegularFile(it.resolve("events.ndjson")) && Files.isRegularFile(it.resolve("route.gpx")) }
            .sorted().toList()
    }

    private fun configNumber(source: String, name: String): Double? =
        Regex(""""${Regex.escape(name)}"\s*:\s*(-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)""")
            .find(source)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun number(source: String, name: String): Double =
        numberOrNull(source, name) ?: error("missing numeric field $name in $source")

    private fun numberOrNull(source: String, name: String): Double? =
        Regex(""""${Regex.escape(name)}"\s*:\s*(-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)""")
            .find(source)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun canonical(value: Double): String = value.toBigDecimal().stripTrailingZeros().toPlainString()

    private companion object {
        val EVENTS = (1..8).map { "E$it" }
    }
}
