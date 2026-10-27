package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.DiscardedSlopePeak
import com.trailnav.core.GuideState
import com.trailnav.core.RouteEtaTracker
import com.trailnav.core.RouteModel
import com.trailnav.core.RouteStatus
import com.trailnav.core.guide

/** Stateful host-side bridge; the core engine remains a pure function. */
class GuideSession(route: RouteModel, private val config: GuideConfig = GuideConfig()) {
    private var state: GuideState = GuideState.initial(route)
    private val etaTracker = RouteEtaTracker(route, config)

    /** Immutable snapshot used by app-layer status surfaces and on-demand voice. */
    fun snapshot(): GuideState = state

    fun routeStatus(): RouteStatus? {
        val status = com.trailnav.core.routeStatus(state, config) ?: return null
        val match = state.lastMatch ?: return status
        return status.copy(target = etaTracker.estimate(match.projectedMeters, state.direction, state.arrived))
    }

    fun accept(location: TrailLocation): SessionDecision {
        val frame = location.toSensorFrame()
        val previousDiscardCount = state.discardedSlopePeaks.size
        val result = guide(state, frame, config)
        val newlyDiscardedSlopePeaks = result.nextState.discardedSlopePeaks.drop(previousDiscardCount)
        state = result.nextState
        state.lastMatch?.let { match ->
            etaTracker.observe(
                timestampMillis = location.timestampMillis,
                projectedMeters = match.projectedMeters,
                direction = state.direction,
                onRoute = !state.offRoute,
            )
        }
        return SessionDecision(location, result, newlyDiscardedSlopePeaks)
    }
}

internal fun SessionEventSink?.appendDiscardedSlopePeaks(records: Iterable<DiscardedSlopePeak>) {
    val sink = this ?: return
    records.forEach { record ->
        try {
            sink.appendSystem(
                "event.discarded",
                mapOf(
                    "event" to "E4",
                    "peak_index" to record.index.toString(),
                    "peak_s" to record.peakS.toString(),
                    "passed_projected_meters" to record.passedProjectedMeters.toString(),
                    "delay_reason" to record.delayReason,
                ),
            )
        } catch (_: Exception) {
            // A failed diagnostic write must not change guidance behavior.
        }
    }
}

data class SessionDecision(
    val location: TrailLocation,
    val result: com.trailnav.core.GuideResult,
    val newlyDiscardedSlopePeaks: List<DiscardedSlopePeak> = emptyList(),
)
