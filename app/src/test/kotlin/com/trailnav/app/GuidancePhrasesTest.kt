package com.trailnav.app

import com.trailnav.core.Side
import com.trailnav.core.SlopeKind
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

    @Test
    fun elevationPhraseNamesTheCrossedBoundaryRoundedToAnInteger() {
        assertEquals("고도 300미터 통과", GuidancePhrases.elevation(300.0))
        assertEquals("고도 300미터 통과", GuidancePhrases.elevation(300.4))
    }

    @Test
    fun waypointPhrasesRemoveHashAndNormalizeSpacingForRealNaverNames() {
        val examples = mapOf(
            "출발지" to "잠시 후 출발지입니다",
            "경유지 #1" to "잠시 후 경유지 1입니다",
            "경유지 #3" to "잠시 후 경유지 3입니다",
            "망경대" to "잠시 후 망경대입니다",
            "불곡산" to "잠시 후 불곡산입니다",
            "도착지" to "잠시 후 도착지입니다",
        )
        examples.forEach { (name, expected) -> assertEquals(expected, GuidancePhrases.waypoint(name)) }
        assertEquals("잠시 후 경유지 3입니다", GuidancePhrases.waypoint(" 경유지   #3 "))
    }

    @Test
    fun otherEventPhrasesRemainUnchanged() {
        assertEquals("1.0킬로미터 지점입니다", GuidancePhrases.milestone(1_000.0))
        assertEquals("출발 1시간 경과", GuidancePhrases.elapsed(1))
        assertEquals("목적지까지 500미터", GuidancePhrases.remaining(500.0))
        assertEquals("잠시 후 오르막입니다", GuidancePhrases.slope(SlopeKind.ASCENT))
        assertEquals("일몰까지 30분입니다", GuidancePhrases.sunset(30, afterSunset = false))
        assertEquals("일출까지 10분입니다", GuidancePhrases.sunrise(10))
    }
}
