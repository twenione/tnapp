package com.trailnav.app

import com.trailnav.core.GuideResult
import com.trailnav.core.ProgressDirection
import com.trailnav.core.TargetKind
import com.trailnav.core.TargetLabelKind
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

internal enum class GuideStatusKind {
    WAITING,
    DIRECTION_CONFIRMING,
    OFF_ROUTE,
    ON_ROUTE,
}

internal data class GuideStatusValues(
    val ribbon: RouteRibbonState?,
    val waiting: Boolean = false,
    val startupPending: Boolean = false,
    val waitingDistanceMeters: Double? = null,
)

internal data class GuideText(
    val resourceId: Int,
    val arguments: List<Any> = emptyList(),
)

internal data class GuideStatusPresentation(
    val kind: GuideStatusKind,
    val summary: GuideText,
    val directionLabel: String? = null,
    val nextTurn: GuideText? = null,
    val offRouteDistance: GuideText? = null,
    val remaining: GuideText? = null,
    val auxiliary: GuideText? = null,
) {
    val notificationContent: GuideText
        get() = remaining ?: requireNotNull(auxiliary)
}

internal data class PeriodicToneSelection(
    val enabled: Boolean,
    val intervalSeconds: Long,
    val mode: NavigationPreferences.PeriodicVoiceMode,
    val buttonLabel: GuideText,
)

/** Pure status copy and formatting shared by the guide screen and notification. */
internal object GuideStatusText {
    fun present(values: GuideStatusValues): GuideStatusPresentation {
        val state = values.ribbon
        val kind = when {
            values.waiting -> GuideStatusKind.WAITING
            values.startupPending || state == null || state.direction == ProgressDirection.UNKNOWN ->
                GuideStatusKind.DIRECTION_CONFIRMING
            state.offRoute -> GuideStatusKind.OFF_ROUTE
            else -> GuideStatusKind.ON_ROUTE
        }

        if (kind == GuideStatusKind.WAITING) {
            val distance = values.waitingDistanceMeters
            return GuideStatusPresentation(
                kind = kind,
                summary = if (distance == null) {
                    GuideText(R.string.guide_status_waiting)
                } else {
                    GuideText(R.string.guide_status_waiting_distance, listOf(formatDistance(distance)))
                },
                auxiliary = GuideText(R.string.guide_waiting_hint),
            )
        }
        if (kind == GuideStatusKind.DIRECTION_CONFIRMING) {
            return GuideStatusPresentation(
                kind = kind,
                summary = GuideText(R.string.guide_status_direction_confirming),
                auxiliary = GuideText(R.string.guide_direction_confirming_hint),
            )
        }

        val ribbon = requireNotNull(state)
        val directionLabel = GuidancePhrases.routeDirectionLabel(
            ribbon.direction,
            ribbon.closedRoute,
            when (ribbon.roundTripLeg) {
                RibbonRoundTripLeg.RETURN -> true
                RibbonRoundTripLeg.OUTBOUND -> false
                null -> null
            },
        )
        val remaining = remainingLine(ribbon)
        if (kind == GuideStatusKind.OFF_ROUTE) {
            val distance = ribbon.perpendicularDistanceMeters.roundToInt()
            return GuideStatusPresentation(
                kind = kind,
                summary = GuideText(R.string.guide_status_off_route, listOf(distance)),
                directionLabel = directionLabel,
                offRouteDistance = GuideText(R.string.guide_off_route_distance, listOf(distance)),
                remaining = remaining,
            )
        }

        val turn = ribbon.nextTurn
        return GuideStatusPresentation(
            kind = kind,
            summary = if (turn == null) {
                GuideText(R.string.guide_status_on_route)
            } else {
                GuideText(
                    R.string.guide_status_on_route_turn,
                    listOf(
                        formatDistance(turn.distanceMeters),
                        directionResource(turn.side),
                    ),
                )
            },
            directionLabel = directionLabel,
            nextTurn = turn?.let {
                GuideText(
                    R.string.guide_next_turn,
                    listOf(
                        if (it.side == RibbonTurnSide.LEFT) "←" else "→",
                        formatDistance(it.distanceMeters),
                        directionResource(it.side),
                    ),
                )
            },
            remaining = remaining,
        )
    }

    fun waitingDistanceMeters(result: GuideResult): Double? {
        if (!result.nextState.waitingForRoute) return null
        if (result.reason.rule != "waiting.enter" && result.reason.rule != "waiting.for-route") return null
        return result.reason.details["distanceMeters"]
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun retainWaitingDistance(
        previousMeters: Double?,
        waiting: Boolean,
        latestMeters: Double?,
    ): Double? = when {
        !waiting -> null
        latestMeters != null -> latestMeters
        else -> previousMeters
    }

    fun shouldUpdateNotification(
        previousTitle: String?,
        previousContent: String?,
        title: String,
        content: String,
    ): Boolean = previousTitle != title || previousContent != content

    fun toneSelection(intervalSeconds: Long): PeriodicToneSelection {
        val interval = intervalSeconds.takeIf { it in TONE_INTERVALS } ?: 0L
        val enabled = interval > 0L
        return PeriodicToneSelection(
            enabled = enabled,
            intervalSeconds = interval,
            mode = if (enabled) NavigationPreferences.PeriodicVoiceMode.TONE else NavigationPreferences.PeriodicVoiceMode.OFF,
            buttonLabel = if (enabled) {
                GuideText(R.string.guide_tone_button_enabled, listOf((interval / 60L).toInt()))
            } else {
                GuideText(R.string.guide_tone_button_disabled)
            },
        )
    }

    fun formatDistance(meters: Double): GuideText {
        val finiteMeters = meters.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val roundedMeters = (floor(finiteMeters / 10.0 + 0.5) * 10.0).coerceAtLeast(10.0)
        return if (roundedMeters < 1_000.0) {
            GuideText(R.string.guide_distance_meters, listOf(roundedMeters.toInt()))
        } else {
            val kilometerTenths = floor(roundedMeters / 100.0).toInt()
            GuideText(
                R.string.guide_distance_kilometers,
                listOf(String.format(Locale.US, "%.1f", kilometerTenths / 10.0)),
            )
        }
    }

    private fun remainingLine(ribbon: RouteRibbonState): GuideText {
        val subject = when {
            ribbon.targetKind != null -> {
                val kind = ribbon.targetLabelKind ?: when (ribbon.targetKind) {
                    TargetKind.NEXT_SUMMIT -> TargetLabelKind.ELEVATION_PEAK
                    TargetKind.DESTINATION -> TargetLabelKind.DESTINATION
                }
                GuidancePhrases.targetSubject(kind, ribbon.targetName)
            }
            ribbon.roundTripLeg == RibbonRoundTripLeg.RETURN -> GuidancePhrases.targetSubject(TargetLabelKind.START, null)
            else -> GuidancePhrases.targetSubject(TargetLabelKind.DESTINATION, null)
        }
        val distance = formatDistance(ribbon.targetRemainingMeters ?: ribbon.remainingDistanceMeters)
        val seconds = ribbon.targetRemainingSeconds
        val eta = formatEta(seconds)
        return if (eta == null) {
            GuideText(R.string.guide_remaining_distance, listOf(subject, distance))
        } else {
            GuideText(R.string.guide_remaining_distance_eta, listOf(subject, distance, eta))
        }
    }

    private fun formatEta(seconds: Double?): GuideText? {
        if (seconds == null || !seconds.isFinite()) return null
        if (seconds < 30.0) return GuideText(R.string.guide_eta_under_minute)
        val minutes = seconds / 60.0
        val roundedMinutes = floor(minutes + 0.5).toInt()
        if (minutes < 10.0) return GuideText(R.string.guide_eta_minutes, listOf(roundedMinutes))
        val roundedFiveMinutes = (floor(minutes / 5.0 + 0.5) * 5.0).toInt()
        val hours = roundedFiveMinutes / 60
        val remainder = roundedFiveMinutes % 60
        return when {
            hours <= 0 -> GuideText(R.string.guide_eta_minutes, listOf(roundedFiveMinutes))
            remainder == 0 -> GuideText(R.string.guide_eta_hours, listOf(hours))
            else -> GuideText(R.string.guide_eta_hours_minutes, listOf(hours, remainder))
        }
    }

    private fun directionResource(side: RibbonTurnSide): GuideText = when (side) {
        RibbonTurnSide.LEFT -> GuideText(R.string.guide_direction_left)
        RibbonTurnSide.RIGHT -> GuideText(R.string.guide_direction_right)
    }

    private val TONE_INTERVALS = setOf(60L, 180L, 300L, 600L)
}
