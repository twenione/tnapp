package com.trailnav.app

import com.trailnav.core.GuideConfig
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
        val result = guide(state, frame, config)
        state = result.nextState
        state.lastMatch?.let { match ->
            etaTracker.observe(
                timestampMillis = location.timestampMillis,
                projectedMeters = match.projectedMeters,
                direction = state.direction,
                onRoute = !state.offRoute,
            )
        }
        return SessionDecision(location, result)
    }
}

data class SessionDecision(
    val location: TrailLocation,
    val result: com.trailnav.core.GuideResult,
)
