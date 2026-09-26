package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventSettingsUiTest {
    @Test
    fun labelsAppearInE1ThroughE8Order() {
        assertEquals(
            listOf(
                "이정표 (1km마다)",
                "경과 시간 (1시간마다)",
                "남은 거리 (2·1·0.5km)",
                "오르막·내리막 예고",
                "고도 통과 (100m마다)",
                "지점 접근 (이름 있는 지점)",
                "일몰 안내 (안전 안내)",
                "일출 안내",
            ),
            EventSettingsUi.labels(),
        )
    }

    @Test
    fun checkedSelectionRoundTripsWithoutLosingValues() {
        val initial = GuideEvent.values().fold(EventSettings.defaults()) { settings, event ->
            settings.withEnabled(event, event.ordinal % 2 == 0)
        }

        assertEquals(
            initial,
            EventSettingsUi.settingsFromCheckedItems(EventSettingsUi.checkedItems(initial)),
        )
    }

    @Test
    fun cancellingDialogProducesNoSettingsToPersist() {
        val saved = EventSettings.defaults()
        val draft = EventSettingsUi.checkedItems(saved).apply { this[0] = true }

        assertNull(EventSettingsUi.resolveDialogSelection(draft, accepted = false))
        assertEquals(EventSettings.defaults(), saved)
    }
}
