package com.trailnav.app

import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteStatus
import com.trailnav.core.RouteTargetEstimate
import com.trailnav.core.TargetKind
import com.trailnav.core.Side
import com.trailnav.core.SlopeKind
import kotlin.math.floor
import kotlin.math.round

enum class DistanceTimeMode {
    Both,
    DistanceOnly,
    TimeOnly,
}

/** Single source of truth for spoken guidance and on-demand status wording. */
object GuidancePhrases {
    fun offRoute(distanceMeters: Double): String = "경로를 벗어났습니다. ${formatDistance(distanceMeters)}"
    fun approach(distanceMeters: Double, bearingDegrees: Double?): String =
        if (bearingDegrees == null) "경로까지 ${formatDistance(distanceMeters)}"
        else "경로까지 ${formatDistance(distanceMeters)}, ${cardinal(bearingDegrees)}쪽입니다"
    fun arrived(): String = "목적지에 도착했습니다"
    fun milestone(distanceMeters: Double): String = "${formatDistance(distanceMeters)} 지점입니다"
    fun elapsed(hours: Int): String = "출발, ${hours}시간 경과"
    fun remaining(distanceMeters: Double): String = "목적지까지 ${formatDistance(distanceMeters)}"
    fun ended(): String = "안내를 종료합니다."
    fun slope(kind: SlopeKind): String = if (kind == SlopeKind.ASCENT) "잠시 후 오르막이 끝납니다" else "잠시 후 내리막입니다"
    fun elevation(elevationMeters: Double): String = "고도 ${round(elevationMeters).toInt()}미터 통과"
    fun waypoint(name: String): String {
        val spokenName = name.replace("#", "").replace(Regex("\\s+"), " ").trim().ifBlank { "지점" }
        return "잠시 후 ${spokenName}입니다"
    }
    fun sunset(minutesRemaining: Int?, afterSunset: Boolean): String =
        if (afterSunset) "일몰 시각이 지났습니다" else "일몰까지 ${minutesRemaining ?: 0}분입니다"

    fun sunrise(minutesRemaining: Int): String = "일출까지 ${minutesRemaining}분입니다"

    fun turnAhead(distanceMeters: Double, side: Side): String =
        "${formatDistance(distanceMeters)} 앞, ${sideLabel(side)}으로 꺾입니다"

    fun turnNow(side: Side): String = "${sideLabel(side)}입니다"

    fun noLocationStatus(): String = "위치를 확인하는 중입니다. 잠시 기다려 주세요."

    fun routeStatus(status: RouteStatus): String {
        if (status.arrived) return arrived()
        val route = if (status.onRoute) {
            "경로 위"
        } else {
            "경로 밖, 경로에서 ${formatDistance(status.offRouteDistanceMeters ?: 0.0)} 떨어져 있습니다"
        }
        val direction = when (status.direction) {
            ProgressDirection.FORWARD -> "정방향"
            ProgressDirection.REVERSE -> "역방향"
            ProgressDirection.STATIONARY -> "정지"
            ProgressDirection.UNKNOWN -> "방향 확인 중"
        }
        val remaining = "목적지까지 ${formatDistance(status.remainingMeters)}"
        val next = status.nextTurn?.let {
            "다음 꺾임은 ${formatDistance(it.distanceMeters)} 앞 ${sideLabel(it.side)}"
        } ?: "다음 꺾임 정보가 없습니다"
        return "$route, $direction, $remaining, $next"
    }

    /** On-demand response wording. This deliberately stays separate from [routeStatus], which feeds the ribbon consistency surface. */
    fun onDemandResponse(
        status: RouteStatus,
        mode: DistanceTimeMode,
        hasEnteredRoute: Boolean,
    ): String {
        if (status.arrived) return arrived()

        if (!hasEnteredRoute) {
            return approach(status.offRouteDistanceMeters ?: 0.0, bearingDegrees = null)
        }

        val target = status.target
        if (status.direction == ProgressDirection.REVERSE && target == null) {
            return listOfNotNull("역방향 진행 중입니다.", status.nextTurn?.let(::nextTurnPhrase)).joinToString(" ")
        }

        val targetPhrase = target?.let { targetPhrase(it, mode) }
            ?: "최종 목적지까지는 ${formatDistance(status.remainingMeters)}입니다"

        if (!status.onRoute) {
            return "${offRoute(status.offRouteDistanceMeters ?: 0.0)}. $targetPhrase"
        }

        return if (status.direction == ProgressDirection.FORWARD) {
            "$targetPhrase. ${status.nextTurn?.let(::nextTurnPhrase) ?: "다음 꺾임 정보가 없습니다"}"
        } else {
            targetPhrase
        }
    }

    /** Returns null when the engine has no finite ETA estimate. */
    fun etaPhrase(seconds: Double?): String? {
        if (seconds == null || !seconds.isFinite()) return null
        val minutes = seconds.coerceAtLeast(0.0) / 60.0
        return when {
            seconds < 30.0 -> "1분 미만"
            minutes < 10.0 -> "약 ${floor(minutes + 0.5).toInt()}분"
            else -> "약 ${(floor(minutes / 5.0 + 0.5) * 5.0).toInt()}분"
        }
    }

    private fun targetPhrase(target: RouteTargetEstimate, mode: DistanceTimeMode): String {
        val goal = when (target.kind) {
            TargetKind.NEXT_SUMMIT -> "다음 정상까지는"
            TargetKind.DESTINATION -> "최종 목적지까지는"
        }
        val distance = formatDistance(target.remainingMeters)
        val eta = etaPhrase(target.remainingSeconds)
        val includeDistance = mode != DistanceTimeMode.TimeOnly || eta == null
        val includeTime = mode != DistanceTimeMode.DistanceOnly && eta != null
        val detail = when {
            includeDistance && includeTime -> "$distance, $eta"
            includeDistance -> distance
            includeTime -> eta!!
            else -> distance
        }
        return "$goal ${detail}입니다"
    }

    private fun nextTurnPhrase(turn: com.trailnav.core.NextTurn): String =
        "다음 꺾임은 ${formatDistance(turn.distanceMeters)} 앞 ${sideLabel(turn.side)}"

    /** <100 m: 1 m, 100–994 m: 10 m, >=995 m: 0.1 km or 1 km. */
    fun formatDistance(valueMeters: Double): String {
        val value = valueMeters.coerceAtLeast(0.0)
        return when {
            value < 100.0 -> "${round(value).toInt()}미터"
            value < 995.0 -> "${(round(value / 10.0) * 10.0).toInt()}미터"
            value < 1_000.0 -> "1킬로미터"
            else -> {
                val formattedKilometers = "%.1f".format(value / 1_000.0)
                val zeroDecimalSuffix = "${java.text.DecimalFormatSymbols.getInstance().decimalSeparator}0"
                "${formattedKilometers.removeSuffix(zeroDecimalSuffix)}킬로미터"
            }
        }
    }

    private fun sideLabel(side: Side): String = if (side == Side.LEFT) "왼쪽" else "오른쪽"

    private fun cardinal(bearingDegrees: Double): String {
        val labels = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
        val index = ((bearingDegrees + 22.5) / 45.0).toInt() % labels.size
        return labels[index]
    }
}
