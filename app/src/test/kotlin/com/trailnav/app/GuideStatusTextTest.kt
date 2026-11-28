package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import com.trailnav.core.TargetKind
import com.trailnav.core.TargetLabelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideStatusTextTest {
    @Test
    fun waitingDistanceComesFromActualEngineFramesAndRetentionClearsOnExit() {
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg>
                <trkpt lat="37.0" lon="-122.0"/>
                <trkpt lat="37.0" lon="-121.99"/>
            </trkseg></trk></gpx>""".trimIndent(),
        )
        val session = GuideSession(route, GuideConfig(sunsetEnabled = false))

        val enter = session.accept(location(1_000L, latitude = 37.02, accuracy = 5f))
        assertEquals("waiting.enter", enter.result.reason.rule)
        assertTrue(enter.result.nextState.waitingForRoute)
        val enteredDistance = assertNotNull(GuideStatusText.waitingDistanceMeters(enter.result))
        assertEquals(enter.result.reason.details.getValue("distanceMeters").toDouble(), enteredDistance)

        val continuing = session.accept(location(2_000L, latitude = 37.019, accuracy = 5f))
        assertEquals("waiting.for-route", continuing.result.reason.rule)
        assertTrue(continuing.result.nextState.waitingForRoute)
        val continuedDistance = assertNotNull(GuideStatusText.waitingDistanceMeters(continuing.result))
        assertEquals(continuing.result.reason.details.getValue("distanceMeters").toDouble(), continuedDistance)

        val accuracyRejected = session.accept(location(3_000L, latitude = 37.018, accuracy = 60f))
        assertEquals("waiting.accuracy-filter", accuracyRejected.result.reason.rule)
        assertTrue(accuracyRejected.result.nextState.waitingForRoute)
        assertNull(GuideStatusText.waitingDistanceMeters(accuracyRejected.result))
        assertEquals(
            continuedDistance,
            GuideStatusText.retainWaitingDistance(continuedDistance, waiting = true, latestMeters = null),
        )

        val exit = session.accept(location(4_000L, latitude = 37.003, accuracy = 5f))
        assertFalse(exit.result.nextState.waitingForRoute)
        assertNull(GuideStatusText.waitingDistanceMeters(exit.result))
        assertNull(GuideStatusText.retainWaitingDistance(continuedDistance, waiting = false, latestMeters = null))
    }

    @Test
    fun waitingHasPriorityAndDistanceCanBeMissing() {
        val waiting = GuideStatusText.present(
            GuideStatusValues(ribbon = ribbon(offRoute = true), waiting = true, waitingDistanceMeters = 450.0),
        )
        assertEquals(GuideStatusKind.WAITING, waiting.kind)
        assertEquals(
            TestStrings.text("guide_status_waiting_distance", render(GuideStatusText.formatDistance(450.0))),
            render(waiting.summary),
        )
        assertEquals(
            TestStrings.text("guide_status_waiting"),
            render(GuideStatusText.present(GuideStatusValues(ribbon = null, waiting = true)).summary),
        )
        assertEquals(render(waiting.auxiliary!!), render(waiting.notificationContent))
    }

    @Test
    fun missingRibbonStartupAndUnknownDirectionConfirmDirection() {
        val missing = GuideStatusText.present(GuideStatusValues(ribbon = null))
        val startup = GuideStatusText.present(GuideStatusValues(ribbon = ribbon(), startupPending = true))
        val unknown = GuideStatusText.present(GuideStatusValues(ribbon = ribbon(direction = ProgressDirection.UNKNOWN)))

        assertEquals(GuideStatusKind.DIRECTION_CONFIRMING, missing.kind)
        assertEquals(GuideStatusKind.DIRECTION_CONFIRMING, startup.kind)
        assertEquals(GuideStatusKind.DIRECTION_CONFIRMING, unknown.kind)
        assertEquals(TestStrings.text("guide_status_direction_confirming"), render(missing.summary))
        assertEquals(render(missing.auxiliary!!), render(missing.notificationContent))
    }

    @Test
    fun offRouteAndOnRouteSummariesUseTheSpecifiedPrioritiesAndTurn() {
        val offRoute = GuideStatusText.present(GuideStatusValues(ribbon = ribbon(offRoute = true, distance = 45.4)))
        assertEquals(GuideStatusKind.OFF_ROUTE, offRoute.kind)
        assertEquals(TestStrings.text("guide_status_off_route", 45), render(offRoute.summary))
        assertEquals(TestStrings.text("guide_off_route_distance", 45), render(offRoute.offRouteDistance!!))

        val onRoute = GuideStatusText.present(GuideStatusValues(ribbon = ribbon()))
        assertEquals(GuideStatusKind.ON_ROUTE, onRoute.kind)
        assertEquals(TestStrings.text("guide_status_on_route"), render(onRoute.summary))
        val withTurn = GuideStatusText.present(GuideStatusValues(ribbon = ribbon(turn = RibbonNextTurn(64.0, RibbonTurnSide.LEFT))))
        assertEquals(
            TestStrings.text("guide_status_on_route_turn", render(GuideStatusText.formatDistance(64.0)), render(GuideText(R.string.guide_direction_left))),
            render(withTurn.summary),
        )
        assertEquals(
            TestStrings.text("guide_next_turn", "←", render(GuideStatusText.formatDistance(64.0)), render(GuideText(R.string.guide_direction_left))),
            render(withTurn.nextTurn!!),
        )
        assertTrue(offRoute.remaining != null)
    }

    @Test
    fun distanceRoundingUsesTenMeterStepsAndKilometerBoundary() {
        assertDistance(4.0, 10)
        assertDistance(64.0, 60)
        assertDistance(65.0, 70)
        assertDistance(994.0, 990)
        assertEquals(TestStrings.text("guide_distance_kilometers", "1.0"), render(GuideStatusText.formatDistance(995.0)))
        assertEquals(TestStrings.text("guide_distance_kilometers", "5.2"), render(GuideStatusText.formatDistance(5_249.0)))
    }

    @Test
    fun remainingDistanceAndEtaOmitUnknownTimeAndUseVoiceRoundingBoundaries() {
        val base = ribbon(targetRemainingMeters = 5_249.0, remainingSeconds = null)
        val noEta = GuideStatusText.present(GuideStatusValues(ribbon = base)).remaining!!
        assertEquals(
            TestStrings.text(
                "guide_remaining_distance",
                GuidancePhrases.targetSubject(TargetLabelKind.DESTINATION, null),
                render(GuideStatusText.formatDistance(5_249.0)),
            ),
            render(noEta),
        )
        assertEquals(TestStrings.text("guide_eta_under_minute"), render(assertNotNull(eta(29.0))))
        assertEquals(TestStrings.text("guide_eta_minutes", 2), render(assertNotNull(eta(90.0))))
        assertEquals(TestStrings.text("guide_eta_minutes", 9), render(assertNotNull(eta(9 * 60.0 + 29.0))))
        assertEquals(TestStrings.text("guide_eta_minutes", 10), render(assertNotNull(eta(10 * 60.0))))
        assertEquals(TestStrings.text("guide_eta_minutes", 15), render(assertNotNull(eta(12.5 * 60.0))))
        assertEquals(TestStrings.text("guide_eta_hours_minutes", 1, 40), render(assertNotNull(eta(100 * 60.0))))
        assertEquals(TestStrings.text("guide_eta_hours", 2), render(assertNotNull(eta(120 * 60.0))))
        assertNull(eta(Double.POSITIVE_INFINITY))
    }

    @Test
    fun remainingTargetAndRoundTripReturnUseTheExpectedSubject() {
        val named = ribbon(
            targetKind = TargetKind.NEXT_SUMMIT,
            targetLabelKind = TargetLabelKind.NAMED_WAYPOINT,
            targetName = "Summit",
            targetRemainingMeters = 850.0,
            remainingSeconds = null,
        )
        val namedLine = GuideStatusText.present(GuideStatusValues(ribbon = named)).remaining!!
        assertEquals(
            TestStrings.text(
                "guide_remaining_distance",
                GuidancePhrases.targetSubject(TargetLabelKind.NAMED_WAYPOINT, "Summit"),
                render(GuideStatusText.formatDistance(850.0)),
            ),
            render(namedLine),
        )

        val returnLine = GuideStatusText.present(
            GuideStatusValues(ribbon = ribbon(roundTripLeg = RibbonRoundTripLeg.RETURN, remainingSeconds = null)),
        ).remaining!!
        assertEquals(
            TestStrings.text(
                "guide_remaining_distance",
                GuidancePhrases.targetSubject(TargetLabelKind.START, null),
                render(GuideStatusText.formatDistance(1_234.0)),
            ),
            render(returnLine),
        )
    }

    @Test
    fun notificationOnlyUpdatesWhenTitleOrContentChanges() {
        assertFalse(GuideStatusText.shouldUpdateNotification("title", "body", "title", "body"))
        assertTrue(GuideStatusText.shouldUpdateNotification("title", "body", "other", "body"))
        assertTrue(GuideStatusText.shouldUpdateNotification("title", "body", "title", "other"))
        assertTrue(GuideStatusText.shouldUpdateNotification(null, null, "title", "body"))
        assertTrue(
            GuideStatusText.shouldUpdateNotification(
                "title", "body", "title", "body", previousReturnAction = false, returnAction = true,
            ),
        )
        assertFalse(
            GuideStatusText.shouldUpdateNotification(
                "title", "body", "title", "body", previousReturnAction = true, returnAction = true,
            ),
        )
    }

    @Test
    fun returnPromptHasPriorityOnlyForAnAvailableOffRouteReturnAction() {
        val offRoute = GuideStatusText.present(
            GuideStatusValues(ribbon = ribbon(offRoute = true), returnAvailable = true),
        )
        val prompt = assertNotNull(offRoute.returnPrompt)
        assertEquals(TestStrings.text("guide_status_return_prompt"), render(prompt))
        assertEquals(prompt, offRoute.notificationContent)

        val unavailable = GuideStatusText.present(GuideStatusValues(ribbon = ribbon(offRoute = true)))
        assertNull(unavailable.returnPrompt)
        assertEquals(unavailable.remaining, unavailable.notificationContent)

        val notOffRoute = GuideStatusText.present(
            GuideStatusValues(ribbon = ribbon(offRoute = false), returnAvailable = true),
        )
        val waiting = GuideStatusText.present(
            GuideStatusValues(ribbon = ribbon(offRoute = true), waiting = true, returnAvailable = true),
        )
        val directionConfirming = GuideStatusText.present(
            GuideStatusValues(
                ribbon = ribbon(offRoute = true, direction = ProgressDirection.UNKNOWN),
                returnAvailable = true,
            ),
        )
        assertNull(notOffRoute.returnPrompt)
        assertNull(waiting.returnPrompt)
        assertNull(directionConfirming.returnPrompt)
    }

    @Test
    fun returnActionIsDebouncedForFifteenHundredMillisecondsAndRejectsClockReversal() {
        assertTrue(shouldAcceptReturnRequest(null, 0L))
        assertFalse(shouldAcceptReturnRequest(0L, 1_499L))
        assertTrue(shouldAcceptReturnRequest(0L, 1_500L))
        assertFalse(shouldAcceptReturnRequest(1_500L, 1_499L))
    }

    @Test
    fun notificationActionKindsKeepTheSpecifiedOrderForAllAvailabilityCombinations() {
        assertEquals(
            listOf(NotificationActionKind.ON_DEMAND),
            GuideStatusText.notificationActionKinds(returnAvailable = false, imuEnabled = false),
        )
        assertEquals(
            listOf(NotificationActionKind.ON_DEMAND, NotificationActionKind.RETURN),
            GuideStatusText.notificationActionKinds(returnAvailable = true, imuEnabled = false),
        )
        assertEquals(
            listOf(NotificationActionKind.ON_DEMAND, NotificationActionKind.SHAKE_MARK),
            GuideStatusText.notificationActionKinds(returnAvailable = false, imuEnabled = true),
        )
        assertEquals(
            listOf(NotificationActionKind.ON_DEMAND, NotificationActionKind.RETURN, NotificationActionKind.SHAKE_MARK),
            GuideStatusText.notificationActionKinds(returnAvailable = true, imuEnabled = true),
        )
    }

    @Test
    fun toneChoiceMapsAllFiveIntervalsToStoredConfigurationAndLabel() {
        val choices = listOf(
            0L to Triple(false, 0L, NavigationPreferences.PeriodicVoiceMode.OFF),
            60L to Triple(true, 60L, NavigationPreferences.PeriodicVoiceMode.TONE),
            180L to Triple(true, 180L, NavigationPreferences.PeriodicVoiceMode.TONE),
            300L to Triple(true, 300L, NavigationPreferences.PeriodicVoiceMode.TONE),
            600L to Triple(true, 600L, NavigationPreferences.PeriodicVoiceMode.TONE),
        )
        choices.forEach { (seconds, expected) ->
            val selection = GuideStatusText.toneSelection(seconds)
            assertEquals(expected.first, selection.enabled)
            assertEquals(expected.second, selection.intervalSeconds)
            assertEquals(expected.third, selection.mode)
            val expectedLabel = if (seconds == 0L) {
                TestStrings.text("guide_tone_button_disabled")
            } else {
                TestStrings.text("guide_tone_button_enabled", (seconds / 60L).toInt())
            }
            assertEquals(expectedLabel, render(selection.buttonLabel))
        }
    }

    private fun assertDistance(meters: Double, rounded: Int) {
        assertEquals(TestStrings.text("guide_distance_meters", rounded), render(GuideStatusText.formatDistance(meters)))
    }

    private fun eta(seconds: Double): GuideText? {
        val remaining = GuideStatusText.present(
            GuideStatusValues(ribbon = ribbon(remainingSeconds = seconds)),
        ).remaining ?: return null
        return if (remaining.resourceId == R.string.guide_remaining_distance_eta) {
            remaining.arguments.lastOrNull() as? GuideText
        } else {
            null
        }
    }

    private fun render(value: GuideText): String = TestStrings.text(
        resourceName(value.resourceId),
        *value.arguments.map { argument ->
            when (argument) {
                is GuideText -> render(argument)
                else -> argument
            }
        }.toTypedArray(),
    )

    private fun resourceName(resourceId: Int): String = when (resourceId) {
        R.string.guide_status_waiting -> "guide_status_waiting"
        R.string.guide_status_waiting_distance -> "guide_status_waiting_distance"
        R.string.guide_status_direction_confirming -> "guide_status_direction_confirming"
        R.string.guide_status_off_route -> "guide_status_off_route"
        R.string.guide_status_return_prompt -> "guide_status_return_prompt"
        R.string.guide_status_on_route -> "guide_status_on_route"
        R.string.guide_status_on_route_turn -> "guide_status_on_route_turn"
        R.string.guide_waiting_hint -> "guide_waiting_hint"
        R.string.guide_direction_confirming_hint -> "guide_direction_confirming_hint"
        R.string.guide_off_route_distance -> "guide_off_route_distance"
        R.string.guide_next_turn -> "guide_next_turn"
        R.string.guide_remaining_distance -> "guide_remaining_distance"
        R.string.guide_remaining_distance_eta -> "guide_remaining_distance_eta"
        R.string.guide_distance_meters -> "guide_distance_meters"
        R.string.guide_distance_kilometers -> "guide_distance_kilometers"
        R.string.guide_eta_under_minute -> "guide_eta_under_minute"
        R.string.guide_eta_minutes -> "guide_eta_minutes"
        R.string.guide_eta_hours -> "guide_eta_hours"
        R.string.guide_eta_hours_minutes -> "guide_eta_hours_minutes"
        R.string.guide_direction_left -> "guide_direction_left"
        R.string.guide_direction_right -> "guide_direction_right"
        R.string.guide_tone_button_enabled -> "guide_tone_button_enabled"
        R.string.guide_tone_button_disabled -> "guide_tone_button_disabled"
        else -> error("Unknown resource ID: $resourceId")
    }

    private fun location(timestamp: Long, latitude: Double, accuracy: Float) = TrailLocation(
        timestampMillis = timestamp,
        latitude = latitude,
        longitude = -122.0,
        accuracyMeters = accuracy,
        speedMps = null,
        bearingDegrees = null,
        provider = "test",
    )

    private fun ribbon(
        offRoute: Boolean = false,
        distance: Double = 45.0,
        direction: ProgressDirection = ProgressDirection.FORWARD,
        turn: RibbonNextTurn? = null,
        remainingSeconds: Double? = 60.0,
        roundTripLeg: RibbonRoundTripLeg? = null,
        targetKind: TargetKind? = null,
        targetLabelKind: TargetLabelKind? = null,
        targetName: String? = null,
        targetRemainingMeters: Double? = null,
    ) = RouteRibbonState(
        perpendicularDistanceMeters = distance,
        signedOffsetMeters = 0.0,
        direction = direction,
        offRoute = offRoute,
        enterBandMeters = 25.0,
        exitBandMeters = 15.0,
        accuracyRadiusMeters = 5.0,
        remainingDistanceMeters = 1_234.0,
        nextTurn = turn,
        targetKind = targetKind,
        targetRemainingMeters = targetRemainingMeters,
        targetRemainingSeconds = remainingSeconds,
        targetLabelKind = targetLabelKind,
        targetName = targetName,
        roundTripLeg = roundTripLeg,
    )
}
