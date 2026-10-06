package com.trailnav.core

/** Returns whether the matched route position has reached the indexed turn. */
fun turnPassed(state: GuideState, turnIndex: Int): Boolean {
    val match = state.lastMatch ?: return false
    val turn = state.route.turns.getOrNull(turnIndex)
        ?: throw IllegalArgumentException("turnIndex out of range: $turnIndex")
    return match.projectedMeters >= turn.s
}
