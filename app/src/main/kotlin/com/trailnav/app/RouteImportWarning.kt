package com.trailnav.app

import com.trailnav.core.RouteQuality

/** Import-time notice for routes with elevation and slope information gaps. */
internal data class RouteImportWarning(
    val count: Int,
    val title: String,
    val message: String,
    val button: String,
) {
    companion object {
        const val SHORT_ROUTE_WARNING_METERS = 90.0
        const val SHORT_ROUTE_WARNING_MESSAGE = "이 경로는 짧아서 도착 안내가 나오지 않을 수 있습니다"

        fun from(quality: RouteQuality): RouteImportWarning? {
            return from(quality, routeLengthMeters = null)
        }

        fun from(quality: RouteQuality, routeLengthMeters: Double?): RouteImportWarning? {
            val count = quality.gapOver100Count
            val gapMessage = if (count < 1) null else
                "이 경로에는 점과 점 사이가 100m 넘게 떨어진 구간이 ${count}곳 있습니다. 그 구간은 고도·경사 정보가 비어 있어 정상·고도 안내가 부정확할 수 있습니다. 경로는 그대로 사용할 수 있습니다."
            val shortMessage = routeLengthMeters?.let(::shortRoute)
            if (gapMessage == null && shortMessage == null) return null
            val gapOnly = gapMessage != null && shortMessage == null
            val combinedGapMessage = if (shortMessage != null && gapMessage != null) {
                "이 경로에는 점과 점 사이가 100m 넘게 떨어진 구간이 ${count}곳 있어, 고도·경사 정보가 비고 정상·고도 안내가 부정확할 수 있지만 경로는 그대로 사용할 수 있습니다."
            } else gapMessage
            return RouteImportWarning(
                count = count,
                title = if (gapOnly) "경로 점 간격 안내" else "경로 안내",
                message = listOfNotNull(combinedGapMessage, shortMessage).joinToString(" "),
                button = "확인",
            )
        }

        fun shortRoute(lengthMeters: Double): String? =
            SHORT_ROUTE_WARNING_MESSAGE.takeIf { lengthMeters < SHORT_ROUTE_WARNING_METERS }
    }
}
