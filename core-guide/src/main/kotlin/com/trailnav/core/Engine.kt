package com.trailnav.core

/** Phase 0 placeholder. Guidance logic is intentionally deferred to Phase 1. */
object EnginePlaceholder {
    const val phase = 0
    // Final D-014/D-015 failure capture probe; remove in the follow-up fix.
    fun violation(): Long = System.currentTimeMillis()
}
