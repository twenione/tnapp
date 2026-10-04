package com.trailnav.app

import com.trailnav.core.RouteTargetEstimate
import com.trailnav.core.TargetKind
import kotlin.test.Test
import kotlin.test.assertEquals

class OnDemandEtaDetailsTest {
    @Test
    fun routeStatusResponseWritesEveryEtaFieldOnceInItsDetailsMap() {
        val target = RouteTargetEstimate(
            kind = TargetKind.NEXT_SUMMIT,
            source = "waypoint",
            remainingMeters = 123.4,
            remainingSeconds = 60.0,
            correction = 1.2,
            correctionActive = true,
            slopeSource = "tobler",
        )

        assertEquals(
            linkedMapOf(
                "target_kind" to "NEXT_SUMMIT",
                "target_source" to "waypoint",
                "target_remaining_m" to "123.4",
                "target_remaining_s" to "60.0",
                "eta_correction" to "1.2",
                "eta_correction_active" to "true",
                "eta_slope_source" to "tobler",
            ),
            target.toOnDemandEtaDetails(),
        )
    }

    @Test
    fun nullTargetWritesBlankStringsForEveryEtaField() {
        assertEquals(
            linkedMapOf(
                "target_kind" to "",
                "target_source" to "",
                "target_remaining_m" to "",
                "target_remaining_s" to "",
                "eta_correction" to "",
                "eta_correction_active" to "",
                "eta_slope_source" to "",
            ),
            null.toOnDemandEtaDetails(),
        )
    }
}