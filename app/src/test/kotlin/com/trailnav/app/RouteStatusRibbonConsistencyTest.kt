package com.trailnav.app

import com.trailnav.core.ElevationUse
import com.trailnav.core.GuideConfig
import com.trailnav.core.NextTurn
import com.trailnav.core.Peak
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import com.trailnav.core.RouteStatus
import com.trailnav.core.RouteTargetEstimate
import com.trailnav.core.Side
import com.trailnav.core.TargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-039/D-112 rows compare route facts that appear in both the ribbon and actual on-demand speech. */
class RouteStatusRibbonConsistencyTest {
    @Test
    fun onDemandVoiceAndRibbonRowsAgreeForEachDistanceTimeMode() {
        val rows = listOf(
            Row(
                name = "before-summit-on-route",
                status = status(
                    direction = ProgressDirection.FORWARD,
                    remaining = 2_000.0,
                    nextTurn = NextTurn(2, Side.LEFT, 85.0, 90.0),
                    target = target(TargetKind.NEXT_SUMMIT, 850.0, 600.0),
                ),
                ribbon = ribbon(
                    offRoute = false,
                    direction = ProgressDirection.FORWARD,
                    remaining = 2_000.0,
                    nextTurn = RibbonNextTurn(85.0, RibbonTurnSide.LEFT),
                    targetKind = TargetKind.NEXT_SUMMIT,
                    targetRemaining = 850.0,
                ),
            ),
            Row(
                name = "after-summit-on-route",
                status = status(
                    direction = ProgressDirection.FORWARD,
                    remaining = 1_234.4,
                    nextTurn = NextTurn(3, Side.RIGHT, 120.0, 90.0),
                    target = target(TargetKind.DESTINATION, 1_234.4, 900.0),
                ),
                ribbon = ribbon(
                    offRoute = false,
                    direction = ProgressDirection.FORWARD,
                    remaining = 1_234.4,
                    nextTurn = RibbonNextTurn(120.0, RibbonTurnSide.RIGHT),
                    targetKind = TargetKind.DESTINATION,
                    targetRemaining = 1_234.4,
                ),
            ),
            Row(
                name = "off-route",
                status = status(
                    onRoute = false,
                    offRouteDistance = 42.0,
                    direction = ProgressDirection.FORWARD,
                    remaining = 1_234.0,
                    target = target(TargetKind.NEXT_SUMMIT, 850.0, 600.0),
                ),
                ribbon = ribbon(
                    perpendicular = 42.0,
                    offRoute = true,
                    direction = ProgressDirection.FORWARD,
                    remaining = 1_234.0,
                    targetKind = TargetKind.NEXT_SUMMIT,
                    targetRemaining = 850.0,
                ),
            ),
            Row(
                name = "reverse",
                status = status(
                    direction = ProgressDirection.REVERSE,
                    remaining = 450.0,
                ),
                ribbon = ribbon(
                    direction = ProgressDirection.REVERSE,
                    remaining = 450.0,
                ),
            ),
            Row(
                name = "stationary",
                status = status(
                    direction = ProgressDirection.STATIONARY,
                    remaining = 2_000.0,
                    target = target(TargetKind.NEXT_SUMMIT, 1_234.0, 1_500.0),
                ),
                ribbon = ribbon(
                    direction = ProgressDirection.STATIONARY,
                    remaining = 2_000.0,
                    targetKind = TargetKind.NEXT_SUMMIT,
                    targetRemaining = 1_234.0,
                ),
            ),
            Row(
                name = "arrival",
                status = status(
                    direction = ProgressDirection.FORWARD,
                    remaining = 0.0,
                    target = target(TargetKind.DESTINATION, 0.0, 0.0),
                    arrived = true,
                ),
                ribbon = ribbon(
                    direction = ProgressDirection.FORWARD,
                    remaining = 0.0,
                    targetKind = TargetKind.DESTINATION,
                    targetRemaining = 0.0,
                ),
            ),
            Row(
                name = "before-route-entry",
                status = status(
                    onRoute = false,
                    offRouteDistance = 65.0,
                    direction = ProgressDirection.UNKNOWN,
                    remaining = 2_000.0,
                    target = target(TargetKind.NEXT_SUMMIT, 1_234.0, 1_500.0),
                ),
                ribbon = ribbon(
                    perpendicular = 65.0,
                    offRoute = true,
                    direction = ProgressDirection.UNKNOWN,
                    remaining = 2_000.0,
                    targetKind = TargetKind.NEXT_SUMMIT,
                    targetRemaining = 1_234.0,
                ),
                hasEnteredRoute = false,
            ),
        )

        rows.forEach { row ->
            DistanceTimeMode.values().forEach { mode ->
                val speech = GuidancePhrases.onDemandResponse(row.status, mode, row.hasEnteredRoute)
                val differences = compareRibbonAndVoice(row, mode, speech)
                assertTrue(differences.isEmpty(), "${row.name}/$mode: ${differences.joinToString()}")
            }
        }
    }

    @Test
    fun summitSwitchChangesRibbonAndVoiceOnTheSameRouteFrame() {
        val route = routeWithSummitSwitch()
        val config = GuideConfig(
            offRouteEnterDwellSeconds = 0.0,
            offRouteExitDwellSeconds = 0.0,
            minimumSessionSecondsBeforeArrival = 0.0,
        )
        val session = GuideSession(route, config)
        val frames = listOf(0.0, 300.0, 499.0, 500.0).mapIndexed { index, progress ->
            val location = location(progress, index * 20_000L)
            val decision = session.accept(location)
            val status = session.routeStatus()!!
            val target = status.target!!
            val ribbon = RouteRibbonCalculator.calculate(location, decision.result, route, config, target)!!
            val speech = GuidancePhrases.onDemandResponse(status, DistanceTimeMode.DistanceOnly, hasEnteredRoute = true)
            val differences = compareRibbonAndVoice(
                Row("frame-$progress", status, ribbon),
                DistanceTimeMode.DistanceOnly,
                speech,
            )
            assertTrue(differences.isEmpty(), "frame-$progress: ${differences.joinToString()}")
            FrameTarget(
                progressMeters = progress,
                statusKind = target.kind,
                ribbonKind = ribbon.targetKind,
                label = ribbonDistanceLabel(ribbon),
                speech = speech,
            )
        }

        val beforeSwitch = frames.last { it.progressMeters == 499.0 }
        val afterSwitch = frames.last { it.progressMeters == 500.0 }
        assertEquals(TargetKind.NEXT_SUMMIT, beforeSwitch.statusKind)
        assertEquals(TargetKind.NEXT_SUMMIT, beforeSwitch.ribbonKind)
        assertTrue(beforeSwitch.label.startsWith("다음 정상까지 "))
        assertTrue(beforeSwitch.speech.contains("다음 정상까지는 "))
        assertEquals(TargetKind.DESTINATION, afterSwitch.statusKind)
        assertEquals(TargetKind.DESTINATION, afterSwitch.ribbonKind)
        assertTrue(afterSwitch.label.startsWith("최종 목적지까지 "))
        assertTrue(afterSwitch.speech.contains("최종 목적지까지는 "))
    }

    @Test
    fun destinationDistanceInRibbonForNextSummitIsReportedAsMismatch() {
        val row = Row(
            name = "wrong-summit-distance",
            status = status(
                direction = ProgressDirection.FORWARD,
                remaining = 2_000.0,
                target = target(TargetKind.NEXT_SUMMIT, 850.0, 600.0),
            ),
            ribbon = ribbon(
                direction = ProgressDirection.FORWARD,
                remaining = 2_000.0,
                targetKind = TargetKind.NEXT_SUMMIT,
                targetRemaining = 2_000.0,
            ),
        )
        val speech = GuidancePhrases.onDemandResponse(row.status, DistanceTimeMode.DistanceOnly, true)

        assertFalse(compareRibbonAndVoice(row, DistanceTimeMode.DistanceOnly, speech).isEmpty())
    }

    @Test
    fun reverseTargetLabelMismatchIsReportedAndVoiceHasNoDistance() {
        val row = Row(
            name = "reverse-with-wrong-target-label",
            status = status(direction = ProgressDirection.REVERSE, remaining = 450.0),
            ribbon = ribbon(
                direction = ProgressDirection.REVERSE,
                remaining = 450.0,
                targetKind = TargetKind.NEXT_SUMMIT,
                targetRemaining = 100.0,
            ),
        )
        val speech = GuidancePhrases.onDemandResponse(row.status, DistanceTimeMode.Both, true)

        assertTrue(compareRibbonAndVoice(row, DistanceTimeMode.Both, speech).isNotEmpty())
        assertEquals("역방향 진행 중입니다.", speech)
        assertFalse(Regex("\\d+(?:\\.\\d+)?(?:미터|킬로미터)").containsMatchIn(speech))
    }

    @Test
    fun routeRibbonFieldStructureMatchesD039AndExcludesHiddenRouteDetails() {
        val fields = RouteRibbonState::class.java.declaredFields.map { it.name }.toSet()
        val expected = setOf(
            "perpendicularDistanceMeters", "signedOffsetMeters", "direction", "offRoute",
            "enterBandMeters", "exitBandMeters", "accuracyRadiusMeters", "remainingDistanceMeters", "nextTurn",
            "targetKind", "targetRemainingMeters",
        )
        assertEquals(expected, fields)
        assertTrue(fields.none { it.contains("elevation", ignoreCase = true) || it.contains("waypoint", ignoreCase = true) })
    }

    private fun compareRibbonAndVoice(row: Row, mode: DistanceTimeMode, speech: String): List<String> {
        val differences = mutableListOf<String>()
        val ribbon = row.ribbon
        val status = row.status
        val label = ribbonDistanceLabel(ribbon)

        if (status.arrived) {
            if (speech != GuidancePhrases.arrived()) differences += "voice is not arrival"
            if (ribbon.targetKind != TargetKind.DESTINATION || ribbon.targetRemainingMeters != 0.0) {
                differences += "ribbon arrival target is not destination at 0m"
            }
            return differences
        }

        if (!row.hasEnteredRoute) {
            val spokenApproachDistance = status.offRouteDistanceMeters
            if (spokenApproachDistance != null && speech.startsWith("경로까지 ")) {
                val expectedDistance = GuidancePhrases.formatDistance(spokenApproachDistance)
                val ribbonDistance = GuidancePhrases.formatDistance(ribbon.perpendicularDistanceMeters)
                if (ribbonDistance != expectedDistance || !speech.startsWith("경로까지 $expectedDistance")) {
                    differences += "approach distance differs: ribbon=$ribbonDistance voice=$expectedDistance"
                }
            }
            return differences
        }

        if (status.direction == ProgressDirection.REVERSE) {
            if (!label.startsWith("목적지까지 ")) differences += "reverse ribbon label is not destination distance"
            if (Regex("\\d+(?:\\.\\d+)?(?:미터|킬로미터)").containsMatchIn(speech)) {
                differences += "reverse voice includes a distance"
            }
            return differences
        }

        status.target?.let { target ->
            val expectedKind = target.kind
            val labelKind = labelTargetKind(label)
            val voiceKind = speechTargetKind(speech)
            if (ribbon.targetKind != expectedKind || labelKind != expectedKind || voiceKind != expectedKind) {
                differences += "target kind differs: state=${ribbon.targetKind} label=$labelKind voice=$voiceKind expected=$expectedKind"
            }

            val includesDistance = mode != DistanceTimeMode.TimeOnly || GuidancePhrases.etaPhrase(target.remainingSeconds) == null
            if (includesDistance) {
                val expectedDistance = GuidancePhrases.formatDistance(target.remainingMeters)
                val actualRibbonDistance = ribbon.targetRemainingMeters?.let(GuidancePhrases::formatDistance)
                val spokenTargetText = targetPhraseText(speech, expectedKind)
                if (actualRibbonDistance != expectedDistance || spokenTargetText?.contains(expectedDistance) != true) {
                    differences += "target distance differs: ribbon=$actualRibbonDistance voice=$expectedDistance"
                }
            }
        }

        val voiceOffRoute = speech.contains("경로를 벗어났습니다")
        if (voiceOffRoute != ribbon.offRoute) differences += "off-route state differs: ribbon=${ribbon.offRoute} voice=$voiceOffRoute"
        val statusOffRouteDistance = status.offRouteDistanceMeters
        if (voiceOffRoute && statusOffRouteDistance != null) {
            val expectedDistance = GuidancePhrases.formatDistance(statusOffRouteDistance)
            val ribbonDistance = GuidancePhrases.formatDistance(ribbon.perpendicularDistanceMeters)
            if (ribbonDistance != expectedDistance || !speech.contains("경로를 벗어났습니다. $expectedDistance")) {
                differences += "off-route distance differs: ribbon=$ribbonDistance voice=$expectedDistance"
            }
        }

        val statusTurn = status.nextTurn
        val ribbonTurn = ribbon.nextTurn
        if (statusTurn != null && ribbonTurn != null && speech.contains("다음 꺾임은 ")) {
            val expectedDistance = GuidancePhrases.formatDistance(statusTurn.distanceMeters)
            val ribbonDistance = GuidancePhrases.formatDistance(ribbonTurn.distanceMeters)
            val expectedSide = if (statusTurn.side == Side.LEFT) "왼쪽" else "오른쪽"
            val ribbonSide = if (ribbonTurn.side == RibbonTurnSide.LEFT) "왼쪽" else "오른쪽"
            if (ribbonDistance != expectedDistance || ribbonSide != expectedSide ||
                !speech.contains("다음 꺾임은 $expectedDistance 앞 $expectedSide")
            ) {
                differences += "next turn differs: ribbon=$ribbonDistance $ribbonSide voice=$expectedDistance $expectedSide"
            }
        }
        return differences
    }

    private fun labelTargetKind(label: String): TargetKind? = when {
        label.startsWith("다음 정상까지 ") -> TargetKind.NEXT_SUMMIT
        label.startsWith("최종 목적지까지 ") || label.startsWith("목적지까지 ") -> TargetKind.DESTINATION
        else -> null
    }

    private fun speechTargetKind(speech: String): TargetKind? = when {
        speech.contains("다음 정상까지는 ") -> TargetKind.NEXT_SUMMIT
        speech.contains("최종 목적지까지는 ") -> TargetKind.DESTINATION
        else -> null
    }

    private fun targetPhraseText(speech: String, kind: TargetKind): String? {
        val prefix = when (kind) {
            TargetKind.NEXT_SUMMIT -> "다음 정상까지는 "
            TargetKind.DESTINATION -> "최종 목적지까지는 "
        }
        return speech.substringAfter(prefix, "").takeIf { it.isNotEmpty() }?.substringBefore("입니다")
    }

    private fun routeWithSummitSwitch(): RouteModel {
        val elevations = listOf(100.0, 110.0, 120.0, 130.0, 140.0, 250.0, 100.0)
        val points = (0..6).map { index ->
            val meters = index * 100.0
            val longitude = Math.toDegrees(meters / EARTH_RADIUS_METERS)
            "<trkpt lat=\"0.0\" lon=\"$longitude\"><ele>${elevations[index]}</ele></trkpt>"
        }.joinToString("")
        val parsed = RouteModel.fromGpx("<gpx><trk><trkseg>$points</trkseg></trk></gpx>")
        return parsed.copy(
            elevationMeters = elevations,
            smoothedElevationMeters = elevations,
            elevationUse = ElevationUse(true, "ok"),
            peaks = listOf(Peak(parsed.cumulativeMeters[5], elevations[5], 110.0)),
        )
    }

    private fun location(progressMeters: Double, timestampMillis: Long) = TrailLocation(
        timestampMillis = timestampMillis,
        latitude = 0.0,
        longitude = Math.toDegrees(progressMeters / EARTH_RADIUS_METERS),
        accuracyMeters = 3f,
        speedMps = 1f,
        bearingDegrees = 90f,
        provider = "test",
    )

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
        offRouteDistance: Double? = null,
        direction: ProgressDirection,
        remaining: Double,
        nextTurn: NextTurn? = null,
        target: RouteTargetEstimate? = null,
        arrived: Boolean = false,
    ) = RouteStatus(onRoute, offRouteDistance, direction, remaining, nextTurn, arrived, target)

    private fun ribbon(
        perpendicular: Double = 0.0,
        offRoute: Boolean = false,
        direction: ProgressDirection,
        remaining: Double,
        nextTurn: RibbonNextTurn? = null,
        targetKind: TargetKind? = null,
        targetRemaining: Double? = null,
    ) = RouteRibbonState(
        perpendicularDistanceMeters = perpendicular,
        signedOffsetMeters = 0.0,
        direction = direction,
        offRoute = offRoute,
        enterBandMeters = 25.0,
        exitBandMeters = 15.0,
        accuracyRadiusMeters = 5.0,
        remainingDistanceMeters = remaining,
        nextTurn = nextTurn,
        targetKind = targetKind,
        targetRemainingMeters = targetRemaining,
    )

    private data class Row(
        val name: String,
        val status: RouteStatus,
        val ribbon: RouteRibbonState,
        val hasEnteredRoute: Boolean = true,
    )

    private data class FrameTarget(
        val progressMeters: Double,
        val statusKind: TargetKind,
        val ribbonKind: TargetKind?,
        val label: String,
        val speech: String,
    )

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
