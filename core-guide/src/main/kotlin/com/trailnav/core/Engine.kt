package com.trailnav.core

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
    val delta = now - then
    // Android supplies epoch milliseconds; tiny synthetic tests often use seconds.
    return if (delta >= 1_000L) delta / 1_000.0 else delta.toDouble()
}
