package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.ArrivalBlockedRecord
import com.trailnav.core.DiscardedEvent
import com.trailnav.core.DiscardedSlopePeak
import com.trailnav.core.DiscardedWaypoint
import com.trailnav.core.Engine
import com.trailnav.core.EngineSystemEvent
import com.trailnav.core.GuideState
import com.trailnav.core.RouteEtaTracker
import com.trailnav.core.RouteModel
import com.trailnav.core.RouteStatus
import com.trailnav.core.guide

/** Stateful host-side bridge; the core engine remains a pure function. */
class GuideSession(
    route: RouteModel,
    private val config: GuideConfig = GuideConfig(),
    startupEnabled: Boolean = false,
) {
    private data class StartupEtaObservation(
        val timestampMillis: Long,
        val projectedMeters: Double,
        val onRoute: Boolean,
    )

    private var state: GuideState = if (startupEnabled) GuideState.starting(route, config) else GuideState.initial(route)
    private var etaRoute: RouteModel = route
    private var etaTracker = RouteEtaTracker(route, config)
    private var startEtaTracker = RouteEtaTracker(route.withoutIntermediateEtaTargets(), config)
    private val startupEtaObservations = mutableListOf<StartupEtaObservation>()

    /** Immutable snapshot used by app-layer status surfaces and on-demand voice. */
    fun snapshot(): GuideState = state

    fun routeStatus(): RouteStatus? {
        if (state.startupEnabled && !state.startupResolved) return null
        val status = com.trailnav.core.routeStatus(state, config) ?: return null
        val match = state.lastMatch ?: return status
        return status.copy(target = etaTracker.estimate(match.projectedMeters, state.direction, state.arrived))
    }

    /** ETA to the route endpoint, used for the spoken return-to-start estimate. */
    fun startEstimate(): com.trailnav.core.RouteTargetEstimate? {
        if (state.startupEnabled && !state.startupResolved) return null
        val match = state.lastMatch ?: return null
        return startEtaTracker.estimate(match.projectedMeters, state.direction, state.arrived)
    }

    fun accept(location: TrailLocation): SessionDecision {
        val frame = location.toSensorFrame()
        val previousDiscardCount = state.discardedSlopePeaks.size
        val previousDiscardedWaypointCount = state.discardedWaypoints.size
        val previousDiscardedEventCount = state.discardedEvents.size
        val previousArrivalBlockedCount = state.arrivalBlockedRecords.size
        val previousSystemEventCount = state.systemEvents.size
        val previousState = state
        val result = guide(state, frame, config)
        val newlyDiscardedSlopePeaks = result.nextState.discardedSlopePeaks.drop(previousDiscardCount)
        val newlyDiscardedWaypoints = result.nextState.discardedWaypoints.drop(previousDiscardedWaypointCount)
        val newlyDiscardedEvents = result.nextState.discardedEvents.drop(previousDiscardedEventCount)
        val newlyBlockedArrivals = result.nextState.arrivalBlockedRecords.drop(previousArrivalBlockedCount)
        val newlyEmittedSystemEvents = result.nextState.systemEvents.drop(previousSystemEventCount)
        state = result.nextState
        if (state.route !== etaRoute) {
            etaRoute = state.route
            etaTracker = RouteEtaTracker(etaRoute, config)
            startEtaTracker = RouteEtaTracker(etaRoute.withoutIntermediateEtaTargets(), config)
        }
        if (previousState.startupEnabled && !previousState.startupResolved) {
            if (!state.startupResolved) {
                state.lastMatch?.let { match ->
                    startupEtaObservations += StartupEtaObservation(
                        timestampMillis = location.timestampMillis,
                        projectedMeters = match.projectedMeters,
                        onRoute = !state.offRoute,
                    )
                }
            } else {
                val reversed = state.routeReversed
                // The first buffered fix seeds the tracker but cannot represent a speed interval.
                startupEtaObservations.drop(1).forEach { observation ->
                    observeEta(
                        timestampMillis = observation.timestampMillis,
                        projectedMeters = if (reversed) {
                            (state.route.totalLengthMeters - observation.projectedMeters).coerceIn(0.0, state.route.totalLengthMeters)
                        } else {
                            observation.projectedMeters
                        },
                        direction = com.trailnav.core.ProgressDirection.FORWARD,
                        onRoute = observation.onRoute,
                    )
                }
                startupEtaObservations.clear()
                state.lastMatch?.let { match ->
                    observeEta(
                        timestampMillis = location.timestampMillis,
                        projectedMeters = match.projectedMeters,
                        direction = state.direction,
                        onRoute = !state.offRoute,
                    )
                }
            }
        } else if (state.startupResolved) {
            state.lastMatch?.let { match ->
                observeEta(
                    timestampMillis = location.timestampMillis,
                    projectedMeters = match.projectedMeters,
                    direction = state.direction,
                    onRoute = !state.offRoute,
                )
            }
        }
        return SessionDecision(
            location = location,
            result = result,
            newlyDiscardedSlopePeaks = newlyDiscardedSlopePeaks,
            newlyDiscardedWaypoints = newlyDiscardedWaypoints,
            newlyBlockedArrivals = newlyBlockedArrivals,
            newlyDiscardedEvents = newlyDiscardedEvents,
            newlyEmittedSystemEvents = newlyEmittedSystemEvents,
            startupJustResolved = previousState.startupEnabled && !previousState.startupResolved && state.startupResolved,
        )
    }

    fun finish(): List<EngineSystemEvent> {
        val previousSystemEventCount = state.systemEvents.size
        state = Engine.finish(state)
        return state.systemEvents.drop(previousSystemEventCount)
    }

    private fun observeEta(timestampMillis: Long, projectedMeters: Double, direction: com.trailnav.core.ProgressDirection, onRoute: Boolean) {
        etaTracker.observe(timestampMillis, projectedMeters, direction, onRoute)
        startEtaTracker.observe(timestampMillis, projectedMeters, direction, onRoute)
    }
}

private fun RouteModel.withoutIntermediateEtaTargets(): RouteModel = copy(waypoints = emptyList(), peaks = emptyList())

internal fun SessionEventSink?.appendEngineSystemEvents(records: Iterable<EngineSystemEvent>) {
    val sink = this ?: return
    records.forEach { record ->
        try {
            sink.appendSystem(record.kind, record.fields)
        } catch (_: Exception) {
            // A failed diagnostic write must not change guidance behavior.
        }
    }
}

internal fun SessionEventSink?.appendDiscardedEvents(
    slopePeaks: Iterable<DiscardedSlopePeak>,
    waypoints: Iterable<DiscardedWaypoint>,
    events: Iterable<DiscardedEvent> = emptyList(),
) {
    val sink = this ?: return
    events.forEach { record ->
        try {
            sink.appendSystem(
                "event.discarded",
                mapOf(
                    "event" to record.event,
                    "threshold" to record.threshold,
                    "reason" to record.reason,
                    "progress_meters" to record.progressMeters.toString(),
                ),
            )
        } catch (_: Exception) {
            // A failed diagnostic write must not change guidance behavior.
        }
    }
    slopePeaks.forEach { record ->
        try {
            sink.appendSystem(
                "event.discarded",
                mapOf(
                    "event" to "E4",
                    "threshold" to record.index.toString(),
                    "progress_meters" to record.passedProjectedMeters.toString(),
                    "peak_index" to record.index.toString(),
                    "peak_s" to record.peakS.toString(),
                    "passed_projected_meters" to record.passedProjectedMeters.toString(),
                    "delay_reason" to record.delayReason,
                    "reason" to record.reason,
                ),
            )
        } catch (_: Exception) {
            // A failed diagnostic write must not change guidance behavior.
        }
    }
    waypoints.forEach { record ->
        try {
            sink.appendSystem(
                "event.discarded",
                mapOf(
                    "event" to "E6",
                    "threshold" to record.index.toString(),
                    "progress_meters" to (record.waypointS - record.distanceMeters).toString(),
                    "waypoint_index" to record.index.toString(),
                    "waypoint_s" to record.waypointS.toString(),
                    "distance_meters" to record.distanceMeters.toString(),
                    "reason" to record.reason,
                ),
            )
        } catch (_: Exception) {
            // A failed diagnostic write must not change guidance behavior.
        }
    }
}

internal fun SessionEventSink?.appendDiscardedSlopePeaks(records: Iterable<DiscardedSlopePeak>) =
    appendDiscardedEvents(records, emptyList())

internal fun SessionEventSink?.appendArrivalBlockedEvents(records: Iterable<ArrivalBlockedRecord>) {
    val sink = this ?: return
    records.forEach { record ->
        try {
            sink.appendSystem(
                "arrival.blocked",
                mapOf(
                    "creditedMeters" to record.creditedMeters.toString(),
                    "requiredMeters" to record.requiredMeters.toString(),
                    "baselineRemainingMeters" to (record.baselineRemainingMeters?.toString() ?: "none"),
                    "projectedMeters" to record.projectedMeters.toString(),
                    "reason" to record.reason,
                ),
            )
        } catch (_: Exception) {
            // Diagnostic persistence must never change guidance behavior.
        }
    }
}

data class SessionDecision(
    val location: TrailLocation,
    val result: com.trailnav.core.GuideResult,
    val newlyDiscardedSlopePeaks: List<DiscardedSlopePeak> = emptyList(),
    val newlyDiscardedWaypoints: List<DiscardedWaypoint> = emptyList(),
    val newlyBlockedArrivals: List<ArrivalBlockedRecord> = emptyList(),
    val newlyDiscardedEvents: List<DiscardedEvent> = emptyList(),
    val newlyEmittedSystemEvents: List<EngineSystemEvent> = emptyList(),
    val startupJustResolved: Boolean = false,
)
