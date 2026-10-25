package com.trailnav.app

import com.trailnav.core.RouteTargetEstimate
import com.trailnav.core.TargetLabelKind
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
            labelKind = TargetLabelKind.NAMED_WAYPOINT,
            name = "바라산",
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
                "target_label_kind" to "NAMED_WAYPOINT",
                "target_name" to "바라산",
                "eta_unavailable_reason" to "",
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
                "target_label_kind" to "",
                "target_name" to "",
                "eta_unavailable_reason" to "",
            ),
            null.toOnDemandEtaDetails(),
        )
    }

    @Test
    fun unavailableReasonIsLoggedAndEmptyWhenAvailable() {
        val estimate = RouteTargetEstimate(
            kind = TargetKind.NEXT_SUMMIT,
            source = "waypoint",
            remainingMeters = 123.4,
            remainingSeconds = null,
            correction = 1.0,
            correctionActive = false,
            slopeSource = "tobler",
            labelKind = TargetLabelKind.NAMED_WAYPOINT,
            name = "바라산",
            etaUnavailableReason = "total-exceeds-max",
        )
        val available = estimate.copy(remainingSeconds = 60.0, etaUnavailableReason = null)

        assertEquals("total-exceeds-max", estimate.toOnDemandEtaDetails()["eta_unavailable_reason"])
        assertEquals("", available.toOnDemandEtaDetails()["eta_unavailable_reason"])
        assertEquals("", null.toOnDemandEtaDetails()["eta_unavailable_reason"])
    }

    @Test
    fun existingEtaDetailKeysKeepNamesAndOrder() {
        val estimate = RouteTargetEstimate(
            kind = TargetKind.NEXT_SUMMIT,
            source = "waypoint",
            remainingMeters = 123.4,
            remainingSeconds = null,
            correction = 1.0,
            correctionActive = false,
            slopeSource = "tobler",
            labelKind = TargetLabelKind.NAMED_WAYPOINT,
            name = "바라산",
        )

        assertEquals(
            listOf(
                "target_kind",
                "target_source",
                "target_remaining_m",
                "target_remaining_s",
                "eta_correction",
                "eta_correction_active",
                "eta_slope_source",
                "target_label_kind",
                "target_name",
                "eta_unavailable_reason",
            ),
            estimate.toOnDemandEtaDetails().keys.toList(),
        )
    }
}
