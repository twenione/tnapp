package com.trailnav.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pure Phase 1 guidance engine. It reads no clock, random source, or I/O. */
object Engine {
    fun guide(state: GuideState, frame: SensorFrame, config: GuideConfig = GuideConfig()): GuideResult =
        guideFrame(state, frame, config)
}

/** Kotlin-friendly top-level entry point matching the roadmap interface. */
fun guide(state: GuideState, frame: SensorFrame, config: GuideConfig = GuideConfig()): GuideResult =
    Engine.guide(state, frame, config)

private fun guideFrame(state: GuideState, frame: SensorFrame, config: GuideConfig): GuideResult {
    val initializedState = if (state.sessionStartTimestamp == null) {
        state.copy(sessionStartTimestamp = frame.timestamp)
    } else {
        state
    }
    if (initializedState.arrived) {
        return GuideResult(null, initializedState, Reason("arrived.already-complete"))
    }
    if (frame.accuracy.toDouble() > config.accuracyRejectMeters) {
        return GuideResult(
            null,
            initializedState,
            Reason(
                rule = "input.accuracy-filter",
                thresholds = mapOf("accuracyRejectMeters" to config.accuracyRejectMeters),
                details = mapOf("accuracy" to frame.accuracy.toString())
            )
        )
    }
    val match = RouteMatcher.nearest(initializedState.route, frame, initializedState.lastMatch?.projectedMeters, config)
        ?: return GuideResult(null, initializedState, Reason("matching.no-route-segment"))
    val directedMatch = RouteMatcher.withDirection(initializedState, match, frame, config)
    val ema = RouteMatcher.updatedEma(initializedState, directedMatch, config)
    val stationary = frame.speed != null && frame.speed.toDouble() < config.stationarySpeedMps
    val direction = if (stationary) ProgressDirection.STATIONARY else directedMatch.direction
    val reverseSince = when {
        direction == ProgressDirection.REVERSE -> initializedState.reverseSince ?: frame.timestamp
        else -> null
    }
    var next = initializedState.copy(
        lastMatch = directedMatch.copy(direction = direction),
        lastTimestamp = frame.timestamp,
        emaDeltaMeters = ema,
        direction = direction,
        stationary = stationary,
        reverseSince = reverseSince
    )

    if (isArrived(next.route, directedMatch, frame.timestamp, next.sessionStartTimestamp, config)) {
        next = next.copy(
            arrived = true,
            offRoute = false,
            candidateOffRouteSince = null,
            candidateOffRouteRecoverySince = null,
            exitCandidateSince = null
        )
        return GuideResult(
            Guidance.Arrived,
            next,
            Reason(
                rule = "arrival.radius-and-progress",
                thresholds = mapOf(
                    "arriveRadiusMeters" to config.arriveRadiusMeters,
                    "arriveProgressFraction" to config.arriveProgressFraction,
                    "minimumSessionSecondsBeforeArrival" to config.minimumSessionSecondsBeforeArrival
                ),
                details = mapOf("projectedMeters" to directedMatch.projectedMeters.toString())
            )
        )
    }

    next = updateOffRouteState(next, directedMatch, frame.timestamp, config)
    if (next.offRoute) {
        val shouldAnnounce = next.lastAnnouncementAt == null ||
            elapsedSeconds(frame.timestamp, next.lastAnnouncementAt) >= config.reannounceIntervalSeconds ||
            (next.lastAnnouncementDistance != null && directedMatch.distanceMeters > next.lastAnnouncementDistance * 2.0)
        if (shouldAnnounce) {
            next = next.copy(
                lastAnnouncementAt = frame.timestamp,
                lastAnnouncementDistance = directedMatch.distanceMeters
            )
            return GuideResult(
                Guidance.OffRoute(directedMatch.distanceMeters, direction.name.lowercase()),
                next,
                Reason(
                    rule = if (initializedState.offRoute) "off-route.reannounce" else "off-route.enter",
                    thresholds = mapOf(
                        "enterDistMeters" to config.offRouteEnterDistMeters,
                        "enterDwellSeconds" to config.offRouteEnterDwellSeconds,
                        "exitDistMeters" to config.offRouteExitDistMeters,
                        "exitDwellSeconds" to config.offRouteExitDwellSeconds,
                        "reannounceIntervalSeconds" to config.reannounceIntervalSeconds
                    ),
                    details = mapOf("distanceMeters" to directedMatch.distanceMeters.toString())
                )
            )
        }
        return GuideResult(
            null,
            next,
            Reason("off-route.holding", details = mapOf("distanceMeters" to directedMatch.distanceMeters.toString()))
        )
    }

    val reverseWarning = reverseSince != null &&
        elapsedSeconds(frame.timestamp, reverseSince) >= config.reverseWarningDwellSeconds
    if (reverseWarning) {
        return GuideResult(
            Guidance.Status("역방향 진행 중", directedMatch.distanceMeters, direction.name.lowercase()),
            next,
            Reason(
                rule = "matching.reverse-dwell",
                thresholds = mapOf("reverseWarningDwellSeconds" to config.reverseWarningDwellSeconds)
            )
        )
    }

    val turnEvaluation = evaluateTurnGuidance(next, directedMatch, direction, config)
    next = turnEvaluation.state
    turnEvaluation.guidance?.let { guidance ->
        return GuideResult(guidance, next, turnEvaluation.reason)
    }
    val dynamic = evaluateDynamicGuidance(initializedState, next, directedMatch, frame, config)
    if (dynamic.guidance != null) {
        return GuideResult(dynamic.guidance, dynamic.state, dynamic.reason)
    }
    next = dynamic.state
    return GuideResult(
        null,
        next,
        Reason(
            rule = if (stationary) "matching.stationary" else "matching.on-route",
            details = mapOf(
                "distanceMeters" to directedMatch.distanceMeters.toString(),
                "projectedMeters" to directedMatch.projectedMeters.toString()
            )
        )
    )
}

private data class DynamicEvaluation(
    val state: GuideState,
    val guidance: Guidance?,
    val reason: Reason,
)

private data class DynamicCandidate(
    val priority: Int,
    val kind: String,
    val guidance: Guidance,
    val reason: Reason,
    val sunsetThreshold: Int? = null,
)

/** Evaluate frame-derived Phase 3 events after arrival/off-route/turn gates. */
private fun evaluateDynamicGuidance(
    previous: GuideState,
    state: GuideState,
    match: MatchResult,
    frame: SensorFrame,
    config: GuideConfig,
): DynamicEvaluation {
    var next = state
    val oldProgress = previous.lastMatch?.projectedMeters
    val oldRemaining = oldProgress?.let { (state.route.totalLengthMeters - it).coerceAtLeast(0.0) }
    val remaining = (state.route.totalLengthMeters - match.projectedMeters).coerceAtLeast(0.0)
    val candidates = mutableListOf<DynamicCandidate>()
    val onRoute = !state.offRoute
    val forward = state.direction == ProgressDirection.FORWARD || state.direction == ProgressDirection.STATIONARY
    val eventIntervalOpen = state.lastPeriodicEventAt == null ||
        elapsedSeconds(frame.timestamp, state.lastPeriodicEventAt) >= config.eventMinIntervalSeconds

    fun reason(rule: String, kind: String, details: Map<String, String> = emptyMap()) =
        Reason(rule, thresholds = mapOf("eventMinIntervalSeconds" to config.eventMinIntervalSeconds), details = details + ("event" to kind))

    // E1: distance milestones are based on projected route distance.
    val milestoneCount = floor(match.projectedMeters / config.milestoneIntervalMeters).toInt()
    val oldMilestoneCount = oldProgress?.let { floor(it / config.milestoneIntervalMeters).toInt() } ?: milestoneCount
    if (milestoneCount > 0) {
        val crossed = (oldMilestoneCount + 1..milestoneCount).toList()
        next = next.copy(consumedMilestoneIndices = next.consumedMilestoneIndices + crossed)
        if (config.milestoneEnabled && onRoute && forward && eventIntervalOpen && crossed.isNotEmpty()) {
            val index = crossed.maxOrNull()!!
            candidates += DynamicCandidate(
                60, "event.milestone", Guidance.Milestone(index * config.milestoneIntervalMeters),
                reason("event.milestone", "E1", mapOf("index" to index.toString(), "thresholdMeters" to (index * config.milestoneIntervalMeters).toString()))
            )
        }
    }

    // E2: elapsed time uses the first session frame as its origin.
    val start = state.sessionStartTimestamp
    if (start != null) {
        val elapsed = elapsedSeconds(frame.timestamp, start)
        val count = floor(elapsed / config.elapsedAnnounceIntervalSeconds).toInt()
        val oldCount = previous.lastTimestamp?.let { floor(elapsedSeconds(it, start) / config.elapsedAnnounceIntervalSeconds).toInt() } ?: count
        val crossed = (oldCount + 1..count).toList()
        next = next.copy(consumedElapsedIndices = next.consumedElapsedIndices + crossed)
        if (config.elapsedEnabled && config.periodicEnabled && eventIntervalOpen && crossed.isNotEmpty()) {
            val index = crossed.maxOrNull()!!
            candidates += DynamicCandidate(
                50, "event.elapsed", Guidance.Elapsed(index),
                reason("event.elapsed", "E2", mapOf("hours" to index.toString()))
            )
        }
    }

    // E3: remaining-distance thresholds are consumed while moving forward.
    val oldRemainingValue = oldRemaining ?: remaining
    val crossedRemaining = config.remainingAnnounceMeters.filter { oldRemainingValue > it && remaining <= it }
    next = next.copy(consumedRemainingThresholds = next.consumedRemainingThresholds + crossedRemaining)
    if (config.remainingEnabled && config.periodicEnabled && onRoute && forward && eventIntervalOpen && crossedRemaining.isNotEmpty()) {
        val threshold = crossedRemaining.minOrNull()!!
        candidates += DynamicCandidate(
            70, "event.remaining", Guidance.Remaining(threshold),
            reason("event.remaining", "E3", mapOf("thresholdMeters" to threshold.toString(), "remainingMeters" to remaining.toString()))
        )
    }

    // E4: the precomputed elevation segments are approached once.
    state.route.slopeSegments.forEachIndexed { index, segment ->
        val distance = segment.startS - match.projectedMeters
        if (distance < 0.0) next = next.copy(consumedSlopeIndices = next.consumedSlopeIndices + index)
        else if (distance <= config.slopeAnnounceLeadMeters) {
            next = next.copy(consumedSlopeIndices = next.consumedSlopeIndices + index)
            if (config.slopeEnabled && config.periodicEnabled && onRoute && forward && eventIntervalOpen && index !in previous.consumedSlopeIndices) {
                candidates += DynamicCandidate(
                    80, "event.slope", Guidance.Slope(segment.kind, segment.deltaMeters),
                    reason("event.slope", "E4", mapOf("segmentIndex" to index.toString(), "startS" to segment.startS.toString(), "deltaMeters" to segment.deltaMeters.toString()))
                )
            }
        }
    }

    // E6: eligible waypoints are approached once; their names were sanitized at load time.
    state.route.waypoints.forEachIndexed { index, waypoint ->
        val distance = waypoint.s - match.projectedMeters
        if (distance < 0.0) next = next.copy(consumedWaypointIndices = next.consumedWaypointIndices + index)
        else if (distance <= config.waypointAnnounceLeadMeters) {
            next = next.copy(consumedWaypointIndices = next.consumedWaypointIndices + index)
            if (config.waypointEnabled && config.periodicEnabled && onRoute && forward && eventIntervalOpen && index !in previous.consumedWaypointIndices) {
                candidates += DynamicCandidate(
                    65, "event.waypoint", Guidance.Waypoint(index, waypoint.name, distance),
                    reason("event.waypoint", "E6", mapOf("waypointIndex" to index.toString(), "name" to waypoint.name, "distanceMeters" to distance.toString()))
                )
            }
        }
    }

    // E7 is independent of periodicEnabled and is the sole deferred event.
    if (config.sunsetEnabled) {
        val sunset = sunsetEpochSeconds(frame.timestamp, frame.lat, frame.lon)
        if (sunset != null) {
            val minutes = (sunset - frame.timestamp / 1000.0) / 60.0
            if (minutes <= 0.0) {
                if (0 !in previous.consumedSunsetThresholds && previous.lastTimestamp == null) {
                    next = next.copy(consumedSunsetThresholds = next.consumedSunsetThresholds + 0)
                    candidates += DynamicCandidate(100, "event.sunset", Guidance.Sunset(null, true), reason("event.sunset", "E7", mapOf("afterSunset" to "true")), 0)
                }
            } else {
                val crossed = config.sunsetAnnounceMinutes.filter { threshold ->
                    val oldMinutes = previous.lastTimestamp?.let { (sunset - it / 1000.0) / 60.0 } ?: minutes
                    oldMinutes > threshold && minutes <= threshold && threshold !in previous.consumedSunsetThresholds
                }
                next = next.copy(consumedSunsetThresholds = next.consumedSunsetThresholds + crossed)
                if (crossed.isNotEmpty()) {
                    val threshold = crossed.minOrNull()!!
                    if (eventIntervalOpen) {
                        candidates += DynamicCandidate(100, "event.sunset", Guidance.Sunset(max(0, minutes.toInt())), reason("event.sunset", "E7", mapOf("thresholdMinutes" to threshold.toString(), "minutesRemaining" to minutes.toInt().toString())), threshold)
                    } else {
                        next = next.copy(pendingSunsetThresholds = next.pendingSunsetThresholds + threshold)
                    }
                }
            }
        }
    }

    val selected = candidates.maxByOrNull { it.priority }
    if (selected == null) {
        return DynamicEvaluation(next, null, reason("event.none", "none"))
    }
    // A higher-priority candidate delays E7; all other candidates are consumed.
    val pending = next.pendingSunsetThresholds - (selected.sunsetThreshold ?: Int.MIN_VALUE)
    next = next.copy(
        pendingSunsetThresholds = pending,
        lastPeriodicEventAt = frame.timestamp,
    )
    return DynamicEvaluation(next, selected.guidance, selected.reason)
}

/** Deterministic NOAA-style sunset approximation; null denotes polar no-sunset. */
private fun sunsetEpochSeconds(timestamp: Long, latitude: Double, longitude: Double): Double? {
    val instant = Instant.ofEpochMilli(timestamp)
    val localDate = instant.atZone(ZoneOffset.UTC).plusSeconds((longitude * 240.0).toLong()).toLocalDate()
    val day = localDate.toEpochDay() - LocalDate.of(2000, 1, 1).toEpochDay()
    val gamma = 2.0 * Math.PI / 365.0 * (day + 0.5)
    val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma)
    val equation = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
    val zenith = Math.toRadians(90.833)
    val latitudeRadians = Math.toRadians(latitude)
    val cosHour = (cos(zenith) - sin(latitudeRadians) * sin(declination)) / (cos(latitudeRadians) * cos(declination))
    if (cosHour !in -1.0..1.0) return null
    val hourAngleMinutes = Math.toDegrees(kotlin.math.acos(cosHour)) * 4.0
    val sunsetMinutesUtc = 720.0 - 4.0 * longitude + hourAngleMinutes - equation
    val midnight = localDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    return midnight + sunsetMinutesUtc * 60.0
}

private data class TurnEvaluation(
    val state: GuideState,
    val guidance: Guidance?,
    val reason: Reason
)

private fun evaluateTurnGuidance(
    state: GuideState,
    match: MatchResult,
    direction: ProgressDirection,
    config: GuideConfig
): TurnEvaluation {
    var next = state
    // A turn already behind the projected position is consumed without a
    // voice. This also handles a session that starts after a turn.
    state.route.turns.forEachIndexed { index, turn ->
        if (turn.s < match.projectedMeters) {
            next = next.copy(
                completedTurnAheadIndices = next.completedTurnAheadIndices + index,
                completedTurnNowIndices = next.completedTurnNowIndices + index
            )
        }
    }
    if (direction != ProgressDirection.FORWARD || match.distanceMeters >= config.turnOnRouteMaxOffsetMeters) {
        return TurnEvaluation(next, null, Reason("turn.ineligible", details = mapOf("direction" to direction.name.lowercase())))
    }
    val candidate = state.route.turns.asSequence()
        .withIndex()
        .map { (index, turn) -> Triple(index, turn, turn.s - match.projectedMeters) }
        .filter { (_, _, remaining) -> remaining >= 0.0 && remaining <= config.turnAheadDistanceMeters }
        .firstOrNull { (index, _, remaining) ->
            remaining <= config.turnNowDistanceMeters && index !in next.completedTurnNowIndices ||
                remaining > config.turnNowDistanceMeters && index !in next.completedTurnAheadIndices
        }
        ?: return TurnEvaluation(next, null, Reason("turn.none-eligible"))
    val index = candidate.first
    val turn = candidate.second
    val remaining = candidate.third
    val thresholds = mapOf(
        "turnAheadDistanceMeters" to config.turnAheadDistanceMeters,
        "turnNowDistanceMeters" to config.turnNowDistanceMeters,
        "turnOnRouteMaxOffsetMeters" to config.turnOnRouteMaxOffsetMeters
    )
    val details = mapOf(
        "turnIndex" to index.toString(),
        "turnS" to turn.s.toString(),
        "remainingMeters" to remaining.toString(),
        "side" to turn.side.name,
        "angleDegrees" to turn.angleDegrees.toString()
    )
    return if (remaining <= config.turnNowDistanceMeters) {
        TurnEvaluation(
            next.copy(
                completedTurnAheadIndices = next.completedTurnAheadIndices + index,
                completedTurnNowIndices = next.completedTurnNowIndices + index
            ),
            Guidance.TurnNow(turn.side),
            Reason("turn.now", thresholds, details = details)
        )
    } else {
        TurnEvaluation(
            next.copy(completedTurnAheadIndices = next.completedTurnAheadIndices + index),
            Guidance.TurnAhead(remaining, turn.side),
            Reason("turn.ahead", thresholds, details = details)
        )
    }
}

private fun isArrived(
    route: RouteModel,
    match: MatchResult,
    timestamp: Long,
    sessionStartTimestamp: Long?,
    config: GuideConfig,
): Boolean {
    if (route.points.isEmpty() || route.totalLengthMeters <= 0.0) return false
    val finalPoint = route.points.last()
    val distanceToEnd = RouteMath.distance(match.projectedPoint, finalPoint)
    val sessionElapsed = elapsedSeconds(timestamp, sessionStartTimestamp)
    return sessionElapsed >= config.minimumSessionSecondsBeforeArrival &&
        distanceToEnd <= config.arriveRadiusMeters &&
        match.projectedMeters > route.totalLengthMeters * config.arriveProgressFraction
}

private fun updateOffRouteState(
    state: GuideState,
    match: MatchResult,
    timestamp: Long,
    config: GuideConfig
): GuideState {
    if (!state.offRoute) {
        if (match.distanceMeters > config.offRouteEnterDistMeters) {
            val since = state.candidateOffRouteSince ?: timestamp
            return if (elapsedSeconds(timestamp, since) >= config.offRouteEnterDwellSeconds) {
                state.copy(
                    offRoute = true,
                    offRouteSince = since,
                    candidateOffRouteSince = null,
                    candidateOffRouteRecoverySince = null,
                    exitCandidateSince = null
                )
            } else {
                state.copy(candidateOffRouteSince = since, candidateOffRouteRecoverySince = null)
            }
        }
        val candidateSince = state.candidateOffRouteSince
        if (candidateSince == null) return state.copy(candidateOffRouteRecoverySince = null)
        // A brief recovery inside the enter band is absorbed while the
        // candidate's dwell window is still active.  Track the beginning of
        // the in-band recovery separately so an intermittent noisy sample
        // cannot cancel a departure that is about to complete its dwell.
        val recoverySince = state.candidateOffRouteRecoverySince ?: timestamp
        return if (
            elapsedSeconds(timestamp, candidateSince) < config.offRouteEnterDwellSeconds ||
            elapsedSeconds(timestamp, recoverySince) < config.offRouteEnterDwellSeconds
        ) {
            state.copy(candidateOffRouteRecoverySince = recoverySince)
        } else {
            state.copy(candidateOffRouteSince = null, candidateOffRouteRecoverySince = null)
        }
    }

    if (match.distanceMeters < config.offRouteExitDistMeters) {
        val since = state.exitCandidateSince ?: timestamp
        if (elapsedSeconds(timestamp, since) >= config.offRouteExitDwellSeconds) {
            return state.copy(
                offRoute = false,
                offRouteSince = null,
                exitCandidateSince = null,
                lastAnnouncementAt = null,
                lastAnnouncementDistance = null
            )
        }
        return state.copy(exitCandidateSince = since)
    }
    return state.copy(exitCandidateSince = null)
}

private fun elapsedSeconds(now: Long, then: Long?): Double {
    if (then == null || now < then) return 0.0
    return (now - then) / 1_000.0
}
