package com.trailnav.app

import com.trailnav.core.RouteTargetEstimate

internal fun RouteTargetEstimate?.toOnDemandEtaDetails(): Map<String, String> = linkedMapOf(
    "target_kind" to (this?.kind?.name ?: ""),
    "target_source" to (this?.source ?: ""),
    "target_remaining_m" to (this?.remainingMeters?.toString() ?: ""),
    "target_remaining_s" to (this?.remainingSeconds?.toString() ?: ""),
    "eta_correction" to (this?.correction?.toString() ?: ""),
    "eta_correction_active" to (this?.correctionActive?.toString() ?: ""),
    "eta_slope_source" to (this?.slopeSource ?: ""),
)