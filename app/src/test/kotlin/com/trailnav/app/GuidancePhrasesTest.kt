package com.trailnav.app

import java.text.DecimalFormatSymbols

import com.trailnav.core.NextTurn
import com.trailnav.core.Guidance
import com.trailnav.core.GuideState
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import com.trailnav.core.RouteEtaTracker
import com.trailnav.core.RouteStatus
import com.trailnav.core.RouteTargetEstimate
import com.trailnav.core.Side
import com.trailnav.core.SlopeKind
import com.trailnav.core.TargetLabelKind
import com.trailnav.core.TargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GuidancePhrasesTest {
    @Test
    fun distanceFormattingUsesTheAdoptedBands() {
        assertEquals("99미터", GuidancePhrases.formatDistance(99.4))
        assertEquals("100미터", GuidancePhrases.formatDistance(99.5))
        assertEquals("100미터", GuidancePhrases.formatDistance(100.0))
        assertEquals("560미터", GuidancePhrases.formatDistance(556.0))
        assertEquals("990미터", GuidancePhrases.formatDistance(994.0))
        assertEquals("1킬로미터", GuidancePhrases.formatDistance(995.0))
        assertEquals("1킬로미터", GuidancePhrases.formatDistance(997.0))
        assertEquals("1킬로미터", GuidancePhrases.formatDistance(999.9))
        assertEquals("1킬로미터", GuidancePhrases.formatDistance(1_000.0))
        assertEquals("1.2킬로미터", GuidancePhrases.formatDistance(1_234.0))
        assertEquals("4.4킬로미터", GuidancePhrases.formatDistance(4_350.0))
        assertEquals("2킬로미터", GuidancePhrases.formatDistance(1_950.0))
        assertEquals("2킬로미터", GuidancePhrases.formatDistance(2_000.0))
        assertEquals("10킬로미터", GuidancePhrases.formatDistance(10_000.0))
    }

    @Test
    fun kilometerFormattingMatchesTheLegacyRoundedNumberForEveryIntegerMeter() {
        val decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator
        val fractionalMeters = listOf(1_234.5, 1_999.5, 4_350.5, 10_999.9)
        (1_000..20_000).map(Int::toDouble).plus(fractionalMeters).forEach { meters ->
            val legacy = "%.1f킬로미터".format(meters / 1_000.0)
            val expected = legacy.replace("${decimalSeparator}0킬로미터", "킬로미터")
            assertEquals(expected, GuidancePhrases.formatDistance(meters), "meters=$meters")
        }
    }

    @Test
    fun turnPhrasesUseFixedPromptAndImmediateDirection() {
        val prompt = GuidancePhrases.turnAhead(60.0, Side.LEFT)
        assertEquals("잠시 후 왼쪽으로 꺾입니다", prompt)
        assertFalse(Regex("\\d").containsMatchIn(prompt))
        assertEquals("지금 오른쪽입니다", GuidancePhrases.turnNow(Side.RIGHT))
        assertEquals("지금 왼쪽입니다", GuidancePhrases.turnNow(Side.LEFT))
    }

    @Test
    fun turnAheadUsesTheSameWordingFor59And60Meters() {
        assertEquals(
            GuidancePhrases.turnAhead(60.0, Side.RIGHT),
            GuidancePhrases.turnAhead(59.0, Side.RIGHT),
        )
    }

    @Test
    fun roundTripGuidanceAndTargetsNameTheTurnaroundAndStart() {
        assertEquals("왕복 경로로 안내합니다", Guidance.RoundTripStart.toSpeech())
        assertEquals("잠시 후 반환점입니다", Guidance.TurnaroundAhead(60.0).toSpeech())
        assertEquals("반환점입니다. 왔던 길로 돌아갑니다", Guidance.Turnaround.toSpeech())
        assertEquals("출발지에 도착했습니다", Guidance.ArrivedAtStart.toSpeech())
        assertEquals("반환점", GuidancePhrases.targetSubject(TargetLabelKind.TURNAROUND, null))
        assertEquals("출발지", GuidancePhrases.targetSubject(TargetLabelKind.START, null))

        for (mode in DistanceTimeMode.values()) {
            val turnaround = GuidancePhrases.onDemandResponse(
                RouteStatus(true, null, ProgressDirection.FORWARD, 2_000.0, null, false,
                    unavailableTarget(TargetLabelKind.TURNAROUND)),
                mode,
                hasEnteredRoute = true,
            )
            val start = GuidancePhrases.onDemandResponse(
                RouteStatus(true, null, ProgressDirection.FORWARD, 1_000.0, null, false,
                    unavailableTarget(TargetLabelKind.START)),
                mode,
                hasEnteredRoute = true,
            )
            assertEquals("반환점까지는 1.2킬로미터입니다. 다음 꺾임 정보가 없습니다", turnaround)
            assertEquals("출발지까지는 1.2킬로미터입니다. 다음 꺾임 정보가 없습니다", start)
        }
    }

    @Test
    fun coincidentRoundTripEndpointWaypointUsesTurnaroundForOnDemandTarget() {
        val config = com.trailnav.core.GuideConfig(roundTripEnabled = true)
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="0" lon="0"/><trkpt lat="0" lon="0.005"/><trkpt lat="0" lon="0.01"/></trkseg></trk><wpt lat="0" lon="0.01"><name>도착지</name></wpt></gpx>""",
            config,
        ).roundTrip(config)
        val target = RouteEtaTracker(route, config).estimate(0.0, ProgressDirection.FORWARD, arrived = false)
        val status = RouteStatus(
            onRoute = true,
            offRouteDistanceMeters = null,
            direction = ProgressDirection.FORWARD,
            remainingMeters = route.totalLengthMeters,
            nextTurn = null,
            arrived = false,
            target = target,
        )

        assertEquals(TargetLabelKind.TURNAROUND, target?.labelKind)
        assertEquals(null, target?.name)
        assertEquals(
            "반환점까지는 ${GuidancePhrases.formatDistance(target!!.remainingMeters)}입니다. 다음 꺾임 정보가 없습니다",
            GuidancePhrases.onDemandResponse(status, DistanceTimeMode.DistanceOnly, hasEnteredRoute = true),
        )
    }

    @Test
    fun sunrisePhraseIncludesMinutes() {
        assertEquals("일출까지 10분입니다", GuidancePhrases.sunrise(10))
    }

    @Test
    fun sunsetZeroMinutesUsesLessThanOneMinuteAndNullKeepsItsExistingWording() {
        assertEquals("일몰까지 1분 미만입니다", GuidancePhrases.sunset(0, afterSunset = false))
        assertEquals("일몰까지 0분입니다", GuidancePhrases.sunset(null, afterSunset = false))
    }

    @Test
    fun routeDirectionNamesTheRelevantEndpointForOpenRoutesAndKeepsClosedRouteWording() {
        assertEquals("정방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.FORWARD, true, null))
        assertEquals("역방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.REVERSE, true, null))

        assertEquals("반환점 방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.FORWARD, false, false))
        assertEquals("출발지 방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.REVERSE, false, false))
        assertEquals("출발지 방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.FORWARD, false, true))
        assertEquals("반환점 방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.REVERSE, false, true))

        assertEquals("도착지 방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.FORWARD, false, null))
        assertEquals("출발지 방향", GuidancePhrases.routeDirectionLabel(ProgressDirection.REVERSE, false, null))
        assertEquals("정지", GuidancePhrases.routeDirectionLabel(ProgressDirection.STATIONARY, false, null))
        assertEquals("방향 확인 중", GuidancePhrases.routeDirectionLabel(ProgressDirection.UNKNOWN, false, null))
    }

    @Test
    fun remainingPhrasesNameTheStartAndTurnaroundWithoutChangingDestinationPhrase() {
        assertEquals("출발지까지는 2킬로미터, 약 20분입니다", GuidancePhrases.remainingToStart(2_000.0, 1_200.0))
        assertEquals("출발지까지는 500미터입니다", GuidancePhrases.remainingToStart(500.0, null))
        assertEquals("출발지까지는 1킬로미터, 약 2분입니다", GuidancePhrases.remainingToStart(1_000.0, 90.0))
        assertEquals("반환점까지 1킬로미터", GuidancePhrases.remainingToTurnaround(1_000.0))
        assertEquals("목적지까지 1킬로미터", GuidancePhrases.remaining(1_000.0))
        assertEquals("반환점입니다. 왔던 길로 돌아갑니다", GuidancePhrases.turnaround())
    }

    @Test
    fun remainingSpeechSelectsTurnaroundStartOrDestinationFromEngineRouteState() {
        val ordinaryRoute = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="0" lon="0"/><trkpt lat="0" lon="0.01"/></trkseg></trk></gpx>""",
        )
        val roundTripRoute = ordinaryRoute.copy(roundTripTurnaroundMeters = 500.0)
        val outbound = GuideState.initial(roundTripRoute)
        val returning = outbound.copy(roundTripOnReturnLeg = true)
        val inactive = GuideState.initial(ordinaryRoute)

        assertEquals(
            "반환점까지 1킬로미터",
            Guidance.Remaining(500.0, 1_000.0).toSpeech(outbound),
        )
        assertEquals(
            "출발지까지는 1킬로미터, 약 20분입니다",
            Guidance.Remaining(500.0, 1_000.0, returnLeg = true).toSpeech(returning, 1_200.0),
        )
        assertEquals(
            "출발지까지는 1킬로미터입니다",
            Guidance.Remaining(500.0, 1_000.0, returnLeg = true).toSpeech(returning),
        )
        assertEquals("목적지까지 1킬로미터", Guidance.Remaining(500.0, 1_000.0).toSpeech(inactive))
        assertEquals("목적지까지 1킬로미터", Guidance.Remaining(500.0, 1_000.0).toSpeech())
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
    fun startBasedPhrasesNameTheirReferencePoint() {
        assertEquals("출발지로부터 1킬로미터", GuidancePhrases.milestone(1_000.0))
        assertEquals("출발지로부터 2킬로미터", GuidancePhrases.milestone(2_000.0))
        assertFalse(GuidancePhrases.milestone(1_000.0).contains("지점"))
        assertEquals("출발지로부터 1시간 경과", GuidancePhrases.elapsed(1))
        assertEquals("출발지로부터 2시간 경과", GuidancePhrases.elapsed(2))
        assertEquals("목적지까지 500미터", GuidancePhrases.remaining(500.0))
        assertEquals("목적지까지 400미터", GuidancePhrases.remaining(400.0))
        assertEquals("목적지까지 2킬로미터", GuidancePhrases.remaining(1_996.0))
        assertEquals("목적지까지 1킬로미터", GuidancePhrases.remaining(998.0))
        assertEquals("목적지까지 1.2킬로미터", GuidancePhrases.remaining(1_234.0))
        assertEquals("잠시 후 오르막이 끝납니다", GuidancePhrases.slope(SlopeKind.ASCENT))
        assertEquals("일몰까지 30분입니다", GuidancePhrases.sunset(30, afterSunset = false))
        assertEquals("일출까지 10분입니다", GuidancePhrases.sunrise(10))
    }

    @Test
    fun unavailableEtaSpeaksDistanceOnlyInEveryMode() {
        val targets = listOf(
            unavailableTarget(TargetLabelKind.NAMED_WAYPOINT, "바라산") to "바라산까지는 1.2킬로미터입니다",
            unavailableTarget(TargetLabelKind.UNNAMED_WAYPOINT) to "다음 경유지까지는 1.2킬로미터입니다",
            unavailableTarget(TargetLabelKind.ELEVATION_PEAK) to "다음 정상까지는 1.2킬로미터입니다",
            unavailableTarget(TargetLabelKind.DESTINATION) to "도착지까지는 1.2킬로미터입니다",
        )

        targets.forEach { (target, expected) ->
            DistanceTimeMode.values().forEach { mode ->
                val phrase = unavailableEtaResponse(target, mode)

                assertEquals(expected, phrase, "label=${target.labelKind}/$mode")
                assertFalse(phrase.contains("분"), "label=${target.labelKind}/$mode: $phrase")
                assertFalse(phrase.contains("시간"), "label=${target.labelKind}/$mode: $phrase")
            }
        }
    }

    @Test
    fun unavailableEtaNeverBecomesZeroMinutes() {
        val phrase = unavailableEtaResponse(
            unavailableTarget(TargetLabelKind.ELEVATION_PEAK),
            DistanceTimeMode.Both,
        )

        assertFalse(phrase.contains("1분 미만"), phrase)
    }

    @Test
    fun unavailableEtaKeepsNextTurnSuffixWhenForward() {
        val phrase = unavailableEtaResponse(
            unavailableTarget(TargetLabelKind.NAMED_WAYPOINT, "바라산"),
            DistanceTimeMode.TimeOnly,
            direction = ProgressDirection.FORWARD,
            nextTurn = NextTurn(2, Side.LEFT, 85.0, 90.0),
        )

        assertEquals("바라산까지는 1.2킬로미터입니다. 다음 꺾임은 85미터 앞 왼쪽", phrase)
    }

    private fun unavailableTarget(labelKind: TargetLabelKind, name: String? = null) = RouteTargetEstimate(
        kind = if (labelKind == TargetLabelKind.DESTINATION) TargetKind.DESTINATION else TargetKind.NEXT_SUMMIT,
        source = "test",
        remainingMeters = 1_234.0,
        remainingSeconds = null,
        correction = 1.0,
        correctionActive = false,
        slopeSource = "tobler",
        labelKind = labelKind,
        name = name,
    )

    private fun unavailableEtaResponse(
        target: RouteTargetEstimate,
        mode: DistanceTimeMode,
        direction: ProgressDirection = ProgressDirection.STATIONARY,
        nextTurn: NextTurn? = null,
    ): String = GuidancePhrases.onDemandResponse(
        status = RouteStatus(
            onRoute = true,
            offRouteDistanceMeters = null,
            direction = direction,
            remainingMeters = 2_000.0,
            nextTurn = nextTurn,
            arrived = false,
            target = target,
        ),
        mode = mode,
        hasEnteredRoute = true,
    )
}
