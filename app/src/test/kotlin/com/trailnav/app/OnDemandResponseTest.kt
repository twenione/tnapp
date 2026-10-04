package com.trailnav.app

import com.trailnav.core.NextTurn
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteStatus
import com.trailnav.core.RouteTargetEstimate
import com.trailnav.core.Side
import com.trailnav.core.TargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnDemandResponseTest {
    @Test
    fun etaRoundingUsesTheSpecifiedBoundaries() {
        val cases = listOf(
            0.0 to "1분 미만",
            29.0 to "1분 미만",
            60.0 to "약 1분",
            540.0 to "약 9분",
            570.0 to "약 10분",
            1_194.0 to "약 20분",
            1_500.0 to "약 25분",
            1_680.0 to "약 30분",
        )
        cases.forEach { (seconds, expected) ->
            assertEquals(expected, GuidancePhrases.etaPhrase(seconds), "seconds=$seconds")
        }
        assertNull(GuidancePhrases.etaPhrase(null))
        assertNull(GuidancePhrases.etaPhrase(Double.POSITIVE_INFINITY))
        assertNull(GuidancePhrases.etaPhrase(Double.NaN))
    }

    @Test
    fun nextSummitUsesSegmentGoalDistanceEtaAndTurn() {
        assertEquals(
            "다음 정상까지는 1.2킬로미터, 약 25분입니다. 다음 꺾임은 85미터 앞 왼쪽",
            GuidancePhrases.onDemandResponse(
                status(
                    direction = ProgressDirection.FORWARD,
                    target = target(TargetKind.NEXT_SUMMIT, 1_234.0, 1_500.0),
                    nextTurn = NextTurn(2, Side.LEFT, 85.0, 90.0),
                ),
                DistanceTimeMode.Both,
                hasEnteredRoute = true,
            ),
        )
    }

    @Test
    fun destinationAndDistanceTimeModesComposeTheRequestedFields() {
        val destination = status(
            direction = ProgressDirection.FORWARD,
            target = target(TargetKind.DESTINATION, 2_000.0, 1_194.0),
        )
        assertEquals(
            "최종 목적지까지는 2킬로미터, 약 20분입니다. 다음 꺾임 정보가 없습니다",
            GuidancePhrases.onDemandResponse(destination, DistanceTimeMode.Both, hasEnteredRoute = true),
        )
        assertEquals(
            "최종 목적지까지는 2킬로미터입니다. 다음 꺾임 정보가 없습니다",
            GuidancePhrases.onDemandResponse(destination, DistanceTimeMode.DistanceOnly, hasEnteredRoute = true),
        )
        assertEquals(
            "최종 목적지까지는 약 20분입니다. 다음 꺾임 정보가 없습니다",
            GuidancePhrases.onDemandResponse(destination, DistanceTimeMode.TimeOnly, hasEnteredRoute = true),
        )
        val unknownEta = destination.copy(target = target(TargetKind.DESTINATION, 2_000.0, null))
        assertEquals(
            "최종 목적지까지는 2킬로미터입니다. 다음 꺾임 정보가 없습니다",
            GuidancePhrases.onDemandResponse(unknownEta, DistanceTimeMode.Both, hasEnteredRoute = true),
        )
        assertEquals(
            "최종 목적지까지는 2킬로미터입니다. 다음 꺾임 정보가 없습니다",
            GuidancePhrases.onDemandResponse(unknownEta, DistanceTimeMode.TimeOnly, hasEnteredRoute = true),
        )
    }

    @Test
    fun arrivalOffRoutePreEntryAndReverseUseTheirDedicatedResponses() {
        assertEquals(
            "목적지에 도착했습니다",
            GuidancePhrases.onDemandResponse(
                status(arrived = true, direction = ProgressDirection.FORWARD),
                DistanceTimeMode.Both,
                hasEnteredRoute = false,
            ),
        )

        val offRoute = GuidancePhrases.onDemandResponse(
            status(
                onRoute = false,
                offRouteDistanceMeters = 42.0,
                direction = ProgressDirection.FORWARD,
                target = target(TargetKind.NEXT_SUMMIT, 1_234.0, 1_500.0),
            ),
            DistanceTimeMode.Both,
            hasEnteredRoute = true,
        )
        assertTrue(offRoute.startsWith("경로를 벗어났습니다. 42미터. 다음 정상까지는"))

        val preEntry = GuidancePhrases.onDemandResponse(
            status(
                onRoute = false,
                offRouteDistanceMeters = 65.0,
                direction = ProgressDirection.UNKNOWN,
                target = target(TargetKind.NEXT_SUMMIT, 1_234.0, 1_500.0),
            ),
            DistanceTimeMode.Both,
            hasEnteredRoute = false,
        )
        assertTrue(preEntry.startsWith("경로까지 65미터"))
        assertFalse(preEntry.contains("정상"))
        assertFalse(preEntry.contains("목적지"))

        assertEquals(
            "역방향 진행 중입니다.",
            GuidancePhrases.onDemandResponse(
                status(direction = ProgressDirection.REVERSE),
                DistanceTimeMode.Both,
                hasEnteredRoute = true,
            ),
        )
    }

    @Test
    fun stationaryAndUnknownDirectionSayOnlyTheTargetPhraseWithoutStatusWords() {
        listOf(ProgressDirection.STATIONARY, ProgressDirection.UNKNOWN).forEach { direction ->
            val response = GuidancePhrases.onDemandResponse(
                status(
                    direction = direction,
                    target = target(TargetKind.NEXT_SUMMIT, 1_234.0, 1_500.0),
                    nextTurn = NextTurn(2, Side.LEFT, 85.0, 90.0),
                ),
                DistanceTimeMode.Both,
                hasEnteredRoute = true,
            )
            assertEquals("다음 정상까지는 1.2킬로미터, 약 25분입니다", response)
            assertFalse(response.contains("경로 위"))
            assertFalse(response.contains("정방향"))
            assertFalse(response.contains("방향 확인 중"))
        }
    }

    private fun target(kind: TargetKind, meters: Double, seconds: Double?) = RouteTargetEstimate(
        kind = kind,
        source = "test",
        remainingMeters = meters,
        remainingSeconds = seconds,
        correction = 1.0,
        correctionActive = false,
        slopeSource = "flat",
    )

    private fun status(
        onRoute: Boolean = true,
        offRouteDistanceMeters: Double? = null,
        direction: ProgressDirection,
        target: RouteTargetEstimate? = null,
        nextTurn: NextTurn? = null,
        arrived: Boolean = false,
    ) = RouteStatus(onRoute, offRouteDistanceMeters, direction, 2_000.0, nextTurn, arrived, target)
}
