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
        fun from(quality: RouteQuality): RouteImportWarning? {
            val count = quality.gapOver100Count
            if (count < 1) return null
            return RouteImportWarning(
                count = count,
                title = "경로 점 간격 안내",
                message = "이 경로에는 점과 점 사이가 100m 넘게 떨어진 구간이 ${count}곳 있습니다. 그 구간은 고도·경사 정보가 비어 있어 정상·고도 안내가 부정확할 수 있습니다. 경로는 그대로 사용할 수 있습니다.",
                button = "확인",
            )
        }
    }
}
