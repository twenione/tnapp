package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.RouteModel
import com.trailnav.core.guide

/** Stateful host-side bridge; the core engine remains a pure function. */
class GuideSession(route: RouteModel, private val config: GuideConfig = GuideConfig()) {
    private var state: GuideState = GuideState.initial(route)

    fun accept(location: TrailLocation): SessionDecision {
        val frame = location.toSensorFrame()
        val result = guide(state, frame, config)
        state = result.nextState
        return SessionDecision(location, result)
    }
}

data class SessionDecision(
    val location: TrailLocation,
    val result: com.trailnav.core.GuideResult,
)
