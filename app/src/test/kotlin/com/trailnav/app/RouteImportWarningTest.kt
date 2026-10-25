package com.trailnav.app

import com.trailnav.core.RouteQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteImportWarningTest {
    @Test
    fun warnsOnlyWhenAnyGapExceedsHundredMeters() {
        assertNull(RouteImportWarning.from(RouteQuality.EMPTY.copy(gapOver50Count = 4, gapOver100Count = 0)))

        val warning = RouteImportWarning.from(RouteQuality.EMPTY.copy(gapOver50Count = 4, gapOver100Count = 3))

        assertEquals(3, warning?.count)
        assertEquals("경로 점 간격 안내", warning?.title)
        assertEquals(
            "이 경로에는 점과 점 사이가 100m 넘게 떨어진 구간이 3곳 있습니다. 그 구간은 고도·경사 정보가 비어 있어 정상·고도 안내가 부정확할 수 있습니다. 경로는 그대로 사용할 수 있습니다.",
            warning?.message,
        )
        assertEquals("확인", warning?.button)
    }
}
