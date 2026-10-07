package com.trailnav.core

// Temporary probe for XCHG-008 §D.2 (AKB-requested live purity-gate verification).
// Deliberately violates the core-guide purity rule (direct clock access).
// This branch is never merged; it exists only to capture a real "purity" job
// failure and a real merge-block, then gets deleted.
internal fun d2PurityProbe(): Long = System.currentTimeMillis()
