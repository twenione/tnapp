package com.trailnav.core

/** Phase 0 placeholder. Guidance logic is intentionally deferred to Phase 1. */
object EnginePlaceholder {
    const val phase = 0
    // INTENTIONAL PR-9 NEGATIVE CONTROL: this must be rejected by purity CI.
    fun violation(): Long = System.currentTimeMillis()
}
