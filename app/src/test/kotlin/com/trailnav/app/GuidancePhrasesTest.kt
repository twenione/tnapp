package com.trailnav.app

import com.trailnav.core.Side
import kotlin.test.Test
import kotlin.test.assertEquals

class GuidancePhrasesTest {
    @Test
    fun distanceFormattingUsesTheAdoptedBands() {
        assertEquals("99미터", GuidancePhrases.formatDistance(99.4))
        assertEquals("100미터", GuidancePhrases.formatDistance(100.0))
        assertEquals("560미터", GuidancePhrases.formatDistance(556.0))
        assertEquals("1.2킬로미터", GuidancePhrases.formatDistance(1_234.0))
    }

    @Test
    fun turnPhrasesUseDirectionAndDistance() {
        assertEquals("45미터 앞, 왼쪽으로 꺾입니다", GuidancePhrases.turnAhead(45.0, Side.LEFT))
        assertEquals("오른쪽입니다", GuidancePhrases.turnNow(Side.RIGHT))
    }

    @Test
    fun sunrisePhraseIncludesMinutes() {
        assertEquals("일출까지 10분입니다", GuidancePhrases.sunrise(10))
    }
}
