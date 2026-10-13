package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.Side
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolarVoicePolicyTest {
    @Test
    fun sunsetIsAllowedWhilePausedAndOrdinaryAndSunriseGuidanceAreSuppressed() {
        val sunset = GuidanceVoicePolicy.decide(paused = true, kind = voiceKindFor(Guidance.Sunset(30)))
        assertTrue(sunset.allowed)

        val ordinary = GuidanceVoicePolicy.decide(
            paused = true,
            kind = voiceKindFor(Guidance.TurnAhead(50.0, Side.LEFT, 0)),
        )
        assertFalse(ordinary.allowed)
        assertTrue(ordinary.suppressionReason == "paused")

        val sunrise = GuidanceVoicePolicy.decide(paused = true, kind = voiceKindFor(Guidance.Sunrise(10)))
        assertFalse(sunrise.allowed)
        assertTrue(sunrise.suppressionReason == "paused")
    }
}
