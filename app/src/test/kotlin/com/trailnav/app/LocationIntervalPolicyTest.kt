package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.Guidance
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationIntervalPolicyTest {
    @Test
    fun intervalChangesOnlyOnWaitingTransitionsAndWaitingSpeechIsExact() {
        val config = GuideConfig()
        val source = RecordingLocationSource()
        val controller = LocationIntervalController(source, config)

        assertEquals(1_000L, locationIntervalMillisFor(waiting = false, config))
        assertEquals(5_000L, locationIntervalMillisFor(waiting = true, config))
        controller.updateWaitingState(false)
        controller.updateWaitingState(true)
        controller.updateWaitingState(true)
        controller.updateWaitingState(true)
        controller.updateWaitingState(false)
        controller.updateWaitingState(false)
        assertEquals(listOf(5_000L, 1_000L), source.intervals)
        assertEquals(
            "경로에서 5킬로미터 떨어져 있습니다. 가까워지면 안내를 시작합니다",
            Guidance.WaitingStart(5_000.0).toSpeech(),
        )
    }

    private class RecordingLocationSource : LocationSource {
        val intervals = mutableListOf<Long>()
        override fun start(onLocation: (TrailLocation) -> Unit, onError: (Throwable) -> Unit) = Unit
        override fun stop() = Unit
        override fun setUpdateIntervalMillis(intervalMillis: Long) {
            intervals += intervalMillis
        }
    }
}
