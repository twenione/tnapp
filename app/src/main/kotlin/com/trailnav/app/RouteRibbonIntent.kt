package com.trailnav.app

import android.content.Intent
import com.trailnav.core.ProgressDirection
import com.trailnav.core.TargetKind
import com.trailnav.core.TargetLabelKind

internal fun routeRibbonStateFromIntent(intent: Intent): RouteRibbonState = RouteRibbonState(
    perpendicularDistanceMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_DISTANCE_METERS, 0.0),
    signedOffsetMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_SIGNED_OFFSET_METERS, 0.0),
    direction = runCatching {
        ProgressDirection.valueOf(intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_DIRECTION).orEmpty())
    }.getOrDefault(ProgressDirection.UNKNOWN),
    offRoute = intent.getBooleanExtra(TrailForegroundService.EXTRA_RIBBON_OFF_ROUTE, false),
    enterBandMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_ENTER_BAND_METERS, 0.0),
    exitBandMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_EXIT_BAND_METERS, 0.0),
    accuracyRadiusMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_ACCURACY_METERS, 0.0),
    remainingDistanceMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_REMAINING_METERS, 0.0),
    targetKind = runCatching {
        TargetKind.valueOf(intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_TARGET_KIND).orEmpty())
    }.getOrNull(),
    targetLabelKind = runCatching {
        TargetLabelKind.valueOf(intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_TARGET_LABEL_KIND).orEmpty())
    }.getOrNull(),
    targetName = intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_TARGET_NAME)?.takeIf { it.isNotBlank() },
    roundTripLeg = runCatching {
        RibbonRoundTripLeg.valueOf(intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_ROUND_TRIP_LEG).orEmpty())
    }.getOrNull(),
    closedRoute = intent.getBooleanExtra(TrailForegroundService.EXTRA_RIBBON_CLOSED_ROUTE, false),
    targetRemainingMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_TARGET_REMAINING_METERS, -1.0)
        .takeIf { it >= 0.0 },
    nextTurn = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_NEXT_TURN_DISTANCE_METERS, -1.0)
        .takeIf { it >= 0.0 }
        ?.let { distance ->
            RibbonNextTurn(
                distanceMeters = distance,
                side = if (intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_NEXT_TURN_SIDE) == RibbonTurnSide.LEFT.name) {
                    RibbonTurnSide.LEFT
                } else {
                    RibbonTurnSide.RIGHT
                },
            )
        },
)
