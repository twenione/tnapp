package com.trailnav.app

import com.trailnav.core.GuideConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventSettingsTest {
    @Test
    fun defaultsMatchDecisionD062() {
        val settings = EventSettings.defaults()

        assertEquals(
            mapOf(
                GuideEvent.MILESTONE to false,
                GuideEvent.ELAPSED to false,
                GuideEvent.REMAINING to true,
                GuideEvent.SLOPE to true,
                GuideEvent.ELEVATION to false,
                GuideEvent.WAYPOINT to true,
                GuideEvent.SUNSET to true,
                GuideEvent.SUNRISE to true,
            ),
            GuideEvent.values().associateWith { settings[it] },
        )
    }

    @Test
    fun eachSingleEventControlsOnlyItsMatchingGuideConfigToggle() {
        GuideEvent.values().forEach { enabledEvent ->
            val settings = GuideEvent.values().fold(EventSettings.defaults()) { current, event ->
                current.withEnabled(event, event == enabledEvent)
            }

            assertEquals(
                GuideEvent.values().associateWith { it == enabledEvent },
                toggleValues(settings.toGuideConfig()),
                "${enabledEvent.id} must control only its matching engine toggle",
            )
        }
    }

    @Test
    fun allEventsCanBeEnabledOrDisabled() {
        val defaults = EventSettings.defaults()
        val allOn = GuideEvent.values().fold(defaults) { settings, event -> settings.withEnabled(event, true) }
        val allOff = GuideEvent.values().fold(defaults) { settings, event -> settings.withEnabled(event, false) }

        assertTrue(toggleValues(allOn.toGuideConfig()).values.all { it })
        assertTrue(toggleValues(allOff.toGuideConfig()).values.none { it })
    }

    @Test
    fun missingAndUnknownStoredValuesFallBackToD062Defaults() {
        val parsed = EventSettings.fromPreferenceValues(
            mapOf(
                "event_e1" to true,
                "event_e3" to null,
                "event_e8" to false,
                "unknown_event" to true,
            ),
        )

        assertTrue(parsed[GuideEvent.MILESTONE])
        assertEquals(false, parsed[GuideEvent.ELAPSED])
        assertTrue(parsed[GuideEvent.REMAINING])
        assertTrue(parsed[GuideEvent.SLOPE])
        assertEquals(false, parsed[GuideEvent.ELEVATION])
        assertTrue(parsed[GuideEvent.WAYPOINT])
        assertTrue(parsed[GuideEvent.SUNSET])
        assertEquals(false, parsed[GuideEvent.SUNRISE])
        assertEquals(EventSettings.defaults(), EventSettings.fromPreferenceValues(emptyMap()))
    }

    @Test
    fun generatedGuideConfigPassesValidationAndPreservesOtherFields() {
        val base = GuideConfig(
            accuracyRejectMeters = 37.0,
            turnAheadDistanceMeters = 90.0,
            milestoneEnabled = true,
            sunsetEnabled = false,
        )
        val settings = EventSettings.defaults()
        val actual = settings.toGuideConfig(base)
        val expected = base.copy(
            milestoneEnabled = false,
            elapsedEnabled = false,
            remainingEnabled = true,
            slopeEnabled = true,
            elevationEnabled = false,
            waypointEnabled = true,
            sunsetEnabled = true,
            sunriseEnabled = true,
        )

        assertEquals(expected, actual)
    }

    private fun toggleValues(config: GuideConfig): Map<GuideEvent, Boolean> = mapOf(
        GuideEvent.MILESTONE to config.milestoneEnabled,
        GuideEvent.ELAPSED to config.elapsedEnabled,
        GuideEvent.REMAINING to config.remainingEnabled,
        GuideEvent.SLOPE to config.slopeEnabled,
        GuideEvent.ELEVATION to config.elevationEnabled,
        GuideEvent.WAYPOINT to config.waypointEnabled,
        GuideEvent.SUNSET to config.sunsetEnabled,
        GuideEvent.SUNRISE to config.sunriseEnabled,
    )
}
