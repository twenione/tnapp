package com.trailnav.app

import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteStatus
import com.trailnav.core.Side
import com.trailnav.core.SlopeKind
import kotlin.math.round

/** Single source of truth for spoken guidance and on-demand status wording. */
object GuidancePhrases {
    fun offRoute(distanceMeters: Double): String = "경로를 벗어났습니다. ${formatDistance(distanceMeters)}"
    fun arrived(): String = "목적지에 도착했습니다"
    fun milestone(distanceMeters: Double): String = "${formatDistance(distanceMeters)} 지점입니다"
    fun elapsed(hours: Int): String = "출발 ${hours}시간 경과"
    fun remaining(distanceMeters: Double): String = "목적지까지 ${formatDistance(distanceMeters)}"
    fun ended(): String = "안내를 종료합니다."
    fun slope(kind: SlopeKind): String = if (kind == SlopeKind.ASCENT) "잠시 후 오르막입니다" else "잠시 후 내리막입니다"
    fun elevation(elevationMeters: Double): String = "현재 고도 약 ${round(elevationMeters).toInt()}미터"
    fun waypoint(name: String): String = "잠시 후 ${name}입니다"
    fun sunset(minutesRemaining: Int?, afterSunset: Boolean): String =
        if (afterSunset) "일몰 시각이 지났습니다" else "일몰까지 ${minutesRemaining ?: 0}분입니다"

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

    /** <100 m: 1 m, 100–999 m: 10 m, >=1 km: 0.1 km. */
    fun formatDistance(valueMeters: Double): String {
        val value = valueMeters.coerceAtLeast(0.0)
        return when {
            value < 100.0 -> "${round(value).toInt()}미터"
            value < 1_000.0 -> "${(round(value / 10.0) * 10.0).toInt()}미터"
            else -> "%.1f킬로미터".format(value / 1_000.0)
        }
    }

    private fun sideLabel(side: Side): String = if (side == Side.LEFT) "왼쪽" else "오른쪽"
}
