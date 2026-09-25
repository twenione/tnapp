package com.trailnav.app

import com.trailnav.core.NextTurn
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteStatus
import com.trailnav.core.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-039 rows: the spoken status and ribbon must expose the same route facts. */
class RouteStatusRibbonConsistencyTest {
    @Test
    fun d039SpeechAndRibbonRowsAgreeForOnRouteOffRouteAndDirectionStates() {
        val rows = listOf(
            Row(
                name = "on-route-forward",
                status = RouteStatus(true, null, ProgressDirection.FORWARD, 1_234.0, NextTurn(2, Side.LEFT, 85.0, 90.0), false),
                ribbon = ribbon(offRoute = false, direction = ProgressDirection.FORWARD, remaining = 1_234.0, nextTurn = RibbonNextTurn(85.0, RibbonTurnSide.LEFT)),
                routeText = "경로 위",
                directionText = "정방향",
            ),
            Row(
                name = "off-route-forward",
                status = RouteStatus(false, 42.0, ProgressDirection.FORWARD, 1_234.0, null, false),
                ribbon = ribbon(offRoute = true, direction = ProgressDirection.FORWARD, remaining = 1_234.0),
                routeText = "경로 밖",
                directionText = "정방향",
                extraText = "42미터",
            ),
            Row(
                name = "reverse",
                status = RouteStatus(true, null, ProgressDirection.REVERSE, 450.0, null, false),
                ribbon = ribbon(offRoute = false, direction = ProgressDirection.REVERSE, remaining = 450.0),
                routeText = "경로 위",
                directionText = "역방향",
            ),
            Row(
                name = "stationary",
                status = RouteStatus(true, null, ProgressDirection.STATIONARY, 0.0, null, false),
                ribbon = ribbon(offRoute = false, direction = ProgressDirection.STATIONARY, remaining = 0.0),
                routeText = "경로 위",
                directionText = "정지",
            ),
        )
        rows.forEach { row ->
            val speech = GuidancePhrases.routeStatus(row.status)
            assertTrue(speech.contains(row.routeText), row.name)
            assertTrue(speech.contains(row.directionText), row.name)
            assertTrue(speech.contains("목적지까지 ${GuidancePhrases.formatDistance(row.status.remainingMeters)}"), row.name)
            row.extraText?.let { assertTrue(speech.contains(it), row.name) }
            assertEquals(row.status.onRoute, !row.ribbon.offRoute, row.name)
            assertEquals(row.status.direction, row.ribbon.direction, row.name)
            assertEquals(row.status.remainingMeters, row.ribbon.remainingDistanceMeters, 0.001, row.name)
            val expectedTurn = row.status.nextTurn
            val actualTurn = row.ribbon.nextTurn
            assertEquals(expectedTurn != null, actualTurn != null, row.name)
            if (expectedTurn != null && actualTurn != null) {
                assertEquals(expectedTurn.distanceMeters, actualTurn.distanceMeters, 0.001, row.name)
                assertEquals(expectedTurn.side.name, actualTurn.side.name, row.name)
            }
        }
    }

    @Test
    fun arrivalRowUsesDestinationSpeechAndZeroRibbonDistance() {
        val status = RouteStatus(false, null, ProgressDirection.FORWARD, 0.0, null, true)
        val speech = GuidancePhrases.routeStatus(status)
        assertEquals("목적지에 도착했습니다", speech)
        assertFalse(speech.contains("종착지까지"))
        val ribbon = ribbon(offRoute = false, direction = ProgressDirection.FORWARD, remaining = 0.0)
        assertEquals(0.0, ribbon.remainingDistanceMeters, 0.001)
        assertFalse(ribbon.offRoute)
    }

    @Test
    fun routeRibbonFieldStructureMatchesD039AndExcludesHiddenRouteDetails() {
        val fields = RouteRibbonState::class.java.declaredFields.map { it.name }.toSet()
        val expected = setOf(
            "perpendicularDistanceMeters", "signedOffsetMeters", "direction", "offRoute",
            "enterBandMeters", "exitBandMeters", "accuracyRadiusMeters", "remainingDistanceMeters", "nextTurn",
        )
        assertEquals(expected, fields)
        assertTrue(fields.none { it.contains("elevation", ignoreCase = true) || it.contains("waypoint", ignoreCase = true) })
    }

    private fun ribbon(
        offRoute: Boolean,
        direction: ProgressDirection,
        remaining: Double,
        nextTurn: RibbonNextTurn? = null,
    ) = RouteRibbonState(10.0, 0.0, direction, offRoute, 25.0, 15.0, 5.0, remaining, nextTurn)

    private data class Row(
        val name: String,
        val status: RouteStatus,
        val ribbon: RouteRibbonState,
        val routeText: String,
        val directionText: String,
        val extraText: String? = null,
    )
}
