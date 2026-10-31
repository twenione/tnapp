package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** D-153 §3-§5 frame-by-frame consumed-target accounting for replayable sessions. */
class SpeakOrRecordInvariantTest {
    private data class Change(val frameIndex: Int, val event: String, val enabled: Boolean)
    private data class Gap(val event: String, val category: String, val session: String, val frame: Int, val target: String)
    private data class Record(val event: String, val threshold: String, val reason: String, val progressMeters: Double)
    private data class RecordCase(val session: String, val frame: Int, val before: GuideState, val transition: Transition, val record: Record)
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
        val frame: SensorFrame,
    )

    private val RECORD_REASONS = mapOf(
        "E1" to setOf("disabled", "offroute", "reverse", "direction-unknown", "interval", "priority", "jump"),
        "E2" to setOf("disabled", "offroute", "interval", "priority", "jump"),
        "E3" to setOf("disabled", "reverse", "direction-unknown"),
        "E4" to setOf("passed", "disabled", "reverse", "direction-unknown", "jump"),
        "E6" to setOf("disabled", "offroute", "reverse", "direction-unknown", "interval", "priority", "jump"),
        "E8" to setOf("disabled", "offroute", "interval", "priority"),
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
        val exceptionFile = Files.readString(E5EventCountSupport.repositoryRoot().resolve("tools/known_exceptions.json"))
        val allowlist = jsonObjects(exceptionFile, "unrecorded_allowlist").map { fields ->
            fields.getValue("event") to fields.getValue("reason")
        }.toSet()
        val structurallyUnreachable = jsonObjects(exceptionFile, "structurally_unreachable").map { fields ->
            fields.getValue("event") to fields.getValue("reason")
        }.toSet()
        val observationExempt = jsonObjects(exceptionFile, "observation_exempt").map { it.getValue("event") }.toSet()
        val observed = gaps.map { it.event to it.category }.toSet()
        gaps.forEach { gap -> println("PROPERTY_GAP event=${gap.event} category=${gap.category} session=${gap.session} frame=${gap.frame} target=${gap.target}") }
        duplicates.forEach { println("PROPERTY_DUPLICATE $it") }
        check(duplicates.isEmpty()) { "a consumed target was both spoken and recorded or was recorded more than once: ${duplicates.joinToString()}" }
        check(observed == allowlist) {
            "D-168 unrecorded_allowlist mismatch: new=${observed - allowlist}; stale=${allowlist - observed}"
        }

        val recordCases = runs.flatMap(::recordCases)
        val recordPairs = recordCases.map { it.record.event to it.record.reason }.toSet()
        val allRecordPairs = RECORD_REASONS.flatMap { (event, reasons) -> reasons.map { event to it } }.toSet()
        val invalidRecords = recordPairs - allRecordPairs
        check(invalidRecords.isEmpty()) { "record reason outside TASK-080 §4.1: $invalidRecords" }
        val invalidUnreachablePairs = structurallyUnreachable - allRecordPairs
        check(invalidUnreachablePairs.isEmpty()) {
            "structurally_unreachable contains a pair absent from RECORD_REASONS: $invalidUnreachablePairs"
        }
        val newlyReachablePairs = structurallyUnreachable intersect recordPairs
        check(newlyReachablePairs.isEmpty()) {
            "엔진이 바뀌어 도달 가능해짐 — 목록에서 빼고 필수로 옮길 것 (D-179): $newlyReachablePairs"
        }
        val requiredRecordPairs = allRecordPairs - structurallyUnreachable
        val missingRecordPairs = requiredRecordPairs - recordPairs

        val eventCounts = (1..8).map { "E$it" } + listOf("TURN_AHEAD", "TURN_NOW")
        val observedEventCounts = eventCounts.associateWith { event -> targetCount(runs, event) }
        println("EVENT_TARGET_COUNTS " + observedEventCounts.entries.joinToString(" ") { "${it.key}=${it.value}" })
        val unobserved = observedEventCounts.filter { (event, count) -> count == 0 && event !in observationExempt }
        check(unobserved.isEmpty()) { "D-174 events with zero observed targets: $unobserved; exemptions=$observationExempt" }

        recordCases.distinctBy { it.record.event to it.record.reason }.forEach { sample ->
            println("RECORD_CASE event=${sample.record.event} reason=${sample.record.reason} session=${sample.session} frame=${sample.frame} threshold=${sample.record.threshold} before=${stateSummary(sample.before)} after=${stateSummary(sample.transition.result.nextState)} result=${sample.transition.result.guidance} rule=${sample.transition.result.reason.rule}")
        }
        val pendingAtEnd = runs.flatMap(::pendingAtEnd)
        pendingAtEnd.forEach { println("SESSION_END_PENDING $it") }
        println("RECORD_REASON_PAIRS observed=${recordPairs.size} required=${requiredRecordPairs.size} structurally_unreachable=$structurallyUnreachable missing=$missingRecordPairs")

        val propertyRuns = runs.filter { it.directory.parent.fileName.toString() == "property" }
            .associateBy { it.directory.fileName.toString() }
        check(propertyRuns.keys == setOf(
            "e4_disabled_pending", "e4_reverse_pending", "e6_disabled", "e4_direction_unknown",
            "e6_direction_unknown", "e6_offroute",
            "e6_reverse", "e6_reverse_in_window", "e6_interval", "e6_priority", "all_events_off_on",
            "e1_direction_unknown_attempt", "e1_disabled", "e1_interval_priority", "e1_jump", "e1_offroute", "e1_priority", "e1_reverse",
            "e2_disabled", "e2_interval", "e2_jump", "e2_offroute", "e2_priority", "e2_spoken",
            "e3_disabled", "e3_direction_unknown_attempt", "e3_pending_then_spoken", "e3_reverse", "e3_session_end_pending",
            "e4_jump", "e6_jump", "first_match_excludes_then_jumps", "e4_passed",
            "e8_disabled", "e8_offroute", "e8_interval", "e8_priority", "e8_spoken", "e7_spoken", "e7_disabled_pending",
        )) { "generated property fixture set changed: ${propertyRuns.keys}" }
        assertRecord(propertyRuns.getValue("e4_disabled_pending"), "E4", "disabled")
        assertRecord(propertyRuns.getValue("e4_reverse_pending"), "E4", "reverse")
        assertRecord(propertyRuns.getValue("e6_disabled"), "E6", "disabled")
        assertRecord(propertyRuns.getValue("e4_direction_unknown"), "E4", "direction-unknown")
        assertRecord(propertyRuns.getValue("e6_direction_unknown"), "E6", "direction-unknown")
        assertRecord(propertyRuns.getValue("e6_offroute"), "E6", "offroute")
        assertRecord(propertyRuns.getValue("e6_reverse_in_window"), "E6", "reverse")
        assertRecord(propertyRuns.getValue("e6_interval"), "E6", "interval")
        assertRecord(propertyRuns.getValue("e6_priority"), "E6", "priority")
        assertRecord(propertyRuns.getValue("e1_disabled"), "E1", "disabled")
        assertRecord(propertyRuns.getValue("e1_offroute"), "E1", "offroute")
        assertRecord(propertyRuns.getValue("e1_priority"), "E1", "priority")
        assertRecord(propertyRuns.getValue("e1_reverse"), "E1", "reverse")
        assertRecord(propertyRuns.getValue("e1_interval_priority"), "E1", "interval")
        assertRecord(propertyRuns.getValue("e1_jump"), "E1", "jump")
        assertRecord(propertyRuns.getValue("e2_disabled"), "E2", "disabled")
        assertRecord(propertyRuns.getValue("e2_offroute"), "E2", "offroute")
        assertRecord(propertyRuns.getValue("e2_interval"), "E2", "interval")
        assertRecord(propertyRuns.getValue("e2_priority"), "E2", "priority")
        assertRecord(propertyRuns.getValue("e2_jump"), "E2", "jump")
        assertRecord(propertyRuns.getValue("e3_disabled"), "E3", "disabled")
        assertRecord(propertyRuns.getValue("e3_reverse"), "E3", "reverse")
        assertRecord(propertyRuns.getValue("e4_jump"), "E4", "jump")
        assertRecord(propertyRuns.getValue("e6_jump"), "E6", "jump")
        val firstMatchRun = propertyRuns.getValue("first_match_excludes_then_jumps")
        assertRecord(firstMatchRun, "E4", "jump")
        assertRecord(firstMatchRun, "E6", "jump")
        val firstMatchTransition = firstMatchRun.transitions.first()
        val firstMatch = firstMatchTransition.result.nextState.firstMatchProgressMeters
            ?: error("first-match fixture did not capture its first route position")
        val excludedPeakIndices = firstMatchRun.route.peaks.indices.filter { firstMatchRun.route.peaks[it].s < firstMatch }.toSet()
        val excludedWaypointIndices = firstMatchRun.route.waypoints.indices.filter { firstMatchRun.route.waypoints[it].s < firstMatch }.toSet()
        check(excludedPeakIndices.isNotEmpty() && excludedWaypointIndices.isNotEmpty()) {
            "first-match fixture must include E4 and E6 targets behind the first matched position"
        }
        check(firstMatchTransition.result.nextState.consumedSlopeIndices.containsAll(excludedPeakIndices) &&
            firstMatchTransition.result.nextState.consumedWaypointIndices.containsAll(excludedWaypointIndices) &&
            records(firstMatchTransition).none { (it.event == "E4" && it.threshold.toInt() in excludedPeakIndices) || (it.event == "E6" && it.threshold.toInt() in excludedWaypointIndices) }
        ) { "targets behind first-match progress were not silently consumed without discard records" }
        assertRecord(propertyRuns.getValue("e8_disabled"), "E8", "disabled")
        assertRecord(propertyRuns.getValue("e8_offroute"), "E8", "offroute")
        assertRecord(propertyRuns.getValue("e8_interval"), "E8", "interval")
        assertRecord(propertyRuns.getValue("e8_priority"), "E8", "priority")
        check(propertyRuns.getValue("e2_spoken").transitions.any { it.result.guidance is Guidance.Elapsed }) {
            "E2 normal spoken target was not reached"
        }
        check(propertyRuns.getValue("e3_pending_then_spoken").transitions.any { it.result.guidance is Guidance.Remaining }) {
            "E3 pending target did not later speak"
        }
        check(propertyRuns.getValue("e3_session_end_pending").transitions.last().result.nextState.pendingRemainingThresholds.isNotEmpty()) {
            "E3 session-end pending target was not retained"
        }
        check(propertyRuns.getValue("e1_direction_unknown_attempt").transitions.single().let {
            it.result.nextState.direction == ProgressDirection.UNKNOWN && it.result.nextState.consumedMilestoneIndices.isEmpty()
        }) { "E1 first-frame probe no longer demonstrates the direction-unknown no-crossing boundary" }
        check(propertyRuns.getValue("e3_direction_unknown_attempt").transitions.single().let {
            it.result.nextState.direction == ProgressDirection.UNKNOWN && it.result.nextState.consumedRemainingThresholds.isEmpty()
        }) { "E3 first-frame probe no longer demonstrates the direction-unknown no-crossing boundary" }
        val toggleChanges = propertyRuns.getValue("all_events_off_on").changes
        check(toggleChanges.size == 16 && EVENTS.all { event ->
            toggleChanges.any { it.event == event && !it.enabled } && toggleChanges.any { it.event == event && it.enabled }
        }) { "the all-events session did not switch E1-E8 off and back on" }
        check(runs.size == sessions.size) { "not every golden, synth, and property session was replayed" }
        println("speak_or_record_sessions=${runs.size} gaps=${gaps.size} duplicates=${duplicates.size} property_config_changes=${toggleChanges.size} observation_exempt=$observationExempt")
        check(missingRecordPairs.isEmpty()) {
            "D-174 §6.1-5 required record reasons not reached: $missingRecordPairs"
        }
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
            elapsedAnnounceIntervalSeconds = configNumber(manifest, "elapsedAnnounceIntervalSeconds") ?: GuideConfig().elapsedAnnounceIntervalSeconds,
            remainingAnnounceMeters = configDoubleList(manifest, "remainingAnnounceMeters") ?: GuideConfig().remainingAnnounceMeters,
            peakAnnounceLeadMeters = configNumber(manifest, "peakAnnounceLeadMeters") ?: GuideConfig().peakAnnounceLeadMeters,
            waypointAnnounceLeadMeters = configNumber(manifest, "waypointAnnounceLeadMeters") ?: GuideConfig().waypointAnnounceLeadMeters,
            sunsetAnnounceMinutes = configIntList(manifest, "sunsetAnnounceMinutes") ?: GuideConfig().sunsetAnnounceMinutes,
            sunriseAnnounceMinutes = configIntList(manifest, "sunriseAnnounceMinutes") ?: GuideConfig().sunriseAnnounceMinutes,
            offRouteEnterDwellSeconds = configNumber(manifest, "offRouteEnterDwellSeconds") ?: GuideConfig().offRouteEnterDwellSeconds,
        )
        val seededProgress = configNumber(manifest, "property_seed_projected_meters")
        val seededEma = configNumber(manifest, "property_seed_ema_delta_meters")
        val seededPeriodic = configNumber(manifest, "property_seed_last_periodic_event_at")?.times(1_000.0)?.toLong()
        var state = GuideState.initial(route)
        if (seededProgress != null || seededEma != null || seededPeriodic != null) {
            if (seededProgress == null) {
                state = state.copy(lastPeriodicEventAt = seededPeriodic)
            } else {
            val point = E5EventCountSupport.pointAt(route, seededProgress)
            val segment = route.cumulativeMeters.indexOfLast { it <= seededProgress }.coerceIn(0, route.cumulativeMeters.lastIndex - 1)
            state = state.copy(
                hasEnteredRoute = true,
                lastMatch = MatchResult(0.0, seededProgress, segment, route.toEnu(point), ProgressDirection.FORWARD),
                lastTimestamp = 0L,
                sessionStartTimestamp = 0L,
                firstMatchProgressMeters = seededProgress,
                emaDeltaMeters = seededEma ?: 0.0,
                lastPeriodicEventAt = seededPeriodic,
                direction = ProgressDirection.FORWARD,
            )
            }
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
            val beforePending = pendingTargets(before)
            val afterPending = pendingTargets(result.nextState)
            val spoken = spokenTargets(result).toMutableSet()
            if (result.guidance is Guidance.Sunset && result.reason.details["afterSunset"] == "true") {
                (result.nextState.consumedSunsetThresholds - before.consumedSunsetThresholds)
                    .forEach { spoken += "E7" to it.toString() }
            }
            val transition = Transition(frameIndex, before, result, config, frame)
            val newRecords = records(transition)
            (afterTargets.keys).forEach { event ->
                val newlyConsumed = afterTargets.getValue(event) - beforeTargets[event].orEmpty()
                newlyConsumed.forEach { target ->
                    if (isNonTarget(event, target, result.nextState, route, frame)) return@forEach
                    val targetId = event to target
                    val recordCount = when (event) {
                        else -> newRecords.count { it.event == event && it.threshold == target }
                    }
                    val count = (if (targetId in spoken) 1 else 0) + recordCount
                    when {
                        count == 0 && target !in afterPending[event].orEmpty() -> gaps += Gap(event, gapCategory(event, target, before, result.nextState, config, route), directory.fileName.toString(), frameIndex, target)
                        count > 1 -> duplicates += "${directory.fileName}:$frameIndex $event target=$target count=$count"
                    }
                }
            }
            val pendingRemoved = beforePending.flatMap { (event, targets) ->
                (targets - afterPending[event].orEmpty()).map { event to it }
            }
            pendingRemoved.forEach { (event, target) ->
                if (isNonTarget(event, target, result.nextState, route, frame)) return@forEach
                val targetId = event to target
                val hasRecord = newRecords.any { it.event == event && it.threshold == target }
                if (targetId !in spoken && !hasRecord) {
                    gaps += Gap(event, gapCategory(event, target, before, result.nextState, config, route), directory.fileName.toString(), frameIndex, target)
                }
            }
            transitions += transition
            state = result.nextState
        }
        check(changeCursor == changes.size) { "${directory.fileName}: config change points outside the frame stream" }
        return RunResult(directory, route, transitions, changes)
    }

    private fun assertRecord(run: RunResult, event: String, reason: String) {
        val matching = run.transitions.flatMap(::records).filter { it.event == event && it.reason == reason }
        check(matching.isNotEmpty()) { "${run.directory.fileName}: expected at least one $event/$reason record, found none" }
    }

    private fun recordCases(run: RunResult): List<RecordCase> = run.transitions.flatMap { transition ->
        records(transition).map { record ->
            RecordCase(run.directory.fileName.toString(), transition.frameIndex, transition.before, transition, record)
        }
    }

    private fun records(transition: Transition): List<Record> {
        val before = transition.before
        val after = transition.result.nextState
        val generic = after.discardedEvents.drop(before.discardedEvents.size).map {
            Record(it.event, it.threshold, it.reason, it.progressMeters)
        }
        val slopes = after.discardedSlopePeaks.drop(before.discardedSlopePeaks.size).map {
            Record("E4", it.index.toString(), it.reason, it.passedProjectedMeters)
        }
        val waypoints = after.discardedWaypoints.drop(before.discardedWaypoints.size).map {
            Record("E6", it.index.toString(), it.reason, after.lastMatch?.projectedMeters ?: 0.0)
        }
        return generic + slopes + waypoints
    }

    private fun targetCount(runs: List<RunResult>, event: String): Int {
        val targets = mutableSetOf<String>()
        runs.forEach { run ->
            run.transitions.forEach { transition ->
                val state = transition.result.nextState
                val session = run.directory.fileName.toString()
                consumedTargets(state)[event].orEmpty().filterNot { isNonTarget(event, it, state, run.route, transition.frame) }
                    .forEach { targets += "$session:$it" }
                pendingTargets(state)[event].orEmpty().forEach { targets += "$session:$it" }
                spokenTargets(transition.result).filter { it.first == event }.forEach { targets += "$session:${it.second}" }
                records(transition).filter { it.event == event }.forEach { targets += "$session:${it.threshold}" }
            }
        }
        return targets.size
    }

    private fun pendingAtEnd(run: RunResult): List<String> {
        val state = run.transitions.lastOrNull()?.result?.nextState ?: return emptyList()
        return pendingTargets(state).flatMap { (event, targets) ->
            targets.map { "session=${run.directory.fileName} event=$event target=$it" }
        }
    }

    private fun pendingTargets(state: GuideState): Map<String, Set<String>> = mapOf(
        "E3" to state.pendingRemainingThresholds.map(::canonical).toSet(),
        "E4" to state.pendingSlopePeaks.keys.map { it.toString() }.toSet(),
        "E7" to state.pendingSunsetThresholds.map { it.toString() }.toSet(),
    )

    private fun isNonTarget(event: String, target: String, state: GuideState, route: RouteModel, frame: SensorFrame): Boolean {
        if (event == "E8") {
            return sunriseEpochSeconds(frame.timestamp, frame.lat, frame.lon)
                ?.let { (it - frame.timestamp / 1_000.0) <= 0.0 } == true
        }
        val firstMatch = state.firstMatchProgressMeters ?: return false
        return when (event) {
            "E4" -> route.peaks[target.toInt()].s < firstMatch
            "E6" -> route.waypoints[target.toInt()].s < firstMatch
            else -> false
        }
    }

    private fun stateSummary(state: GuideState): String =
        "consumed(E1=${state.consumedMilestoneIndices},E2=${state.consumedElapsedIndices},E3=${state.consumedRemainingThresholds},E4=${state.consumedSlopeIndices},E6=${state.consumedWaypointIndices},E7=${state.consumedSunsetThresholds},E8=${state.consumedSunriseThresholds}) pending(E3=${state.pendingRemainingThresholds},E4=${state.pendingSlopePeaks},E7=${state.pendingSunsetThresholds}) records(E=${state.discardedEvents.size},E4=${state.discardedSlopePeaks.size},E6=${state.discardedWaypoints.size})"

    private fun jsonObjects(source: String, key: String): List<Map<String, String>> {
        val array = Regex(""""${Regex.escape(key)}"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.groupValues?.get(1) ?: error("missing JSON array $key")
        return Regex("""\{([^{}]*)}""").findAll(array).map { objectMatch ->
            Regex(""""([A-Za-z_]+)"\s*:\s*"([^"]*)"""").findAll(objectMatch.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.toList()
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

    private fun configIntList(source: String, name: String): List<Int>? {
        val body = Regex(""""${Regex.escape(name)}"\s*:\s*\[([^]]*)]""")
            .find(source)?.groupValues?.get(1) ?: return null
        return Regex("-?\\d+").findAll(body).map { it.value.toInt() }.toList()
    }

    private fun configDoubleList(source: String, name: String): List<Double>? {
        val body = Regex(""""${Regex.escape(name)}"\s*:\s*\[([^]]*)]""")
            .find(source)?.groupValues?.get(1) ?: return null
        return Regex("-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")
            .findAll(body).map { it.value.toDouble() }.toList()
    }

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
