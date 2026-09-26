package com.trailnav.app

import com.trailnav.core.GuideConfig

/** User-facing guidance events. Defaults are fixed by D-062. */
internal enum class GuideEvent(
    val id: String,
    val label: String,
    val defaultEnabled: Boolean,
) {
    MILESTONE("E1", "이정표 (1km마다)", false),
    ELAPSED("E2", "경과 시간 (1시간마다)", false),
    REMAINING("E3", "남은 거리 (2·1·0.5km)", true),
    SLOPE("E4", "오르막·내리막 예고", true),
    ELEVATION("E5", "고도 통과 (100m마다)", false),
    WAYPOINT("E6", "지점 접근 (이름 있는 지점)", true),
    SUNSET("E7", "일몰 안내 (안전 안내)", true),
    SUNRISE("E8", "일출 안내", true),
}

/** Immutable E1–E8 selection, independent of Android preferences and views. */
internal class EventSettings private constructor(
    private val enabledByEvent: Map<GuideEvent, Boolean>,
) {
    operator fun get(event: GuideEvent): Boolean = enabledByEvent.getValue(event)

    fun withEnabled(event: GuideEvent, enabled: Boolean): EventSettings =
        EventSettings(enabledByEvent.toMutableMap().apply { put(event, enabled) }.toMap())

    fun toPreferenceValues(): Map<String, Boolean> = GuideEvent.values().associate { event ->
        preferenceKey(event) to this[event]
    }

    fun toWireMap(): Map<String, Boolean> = GuideEvent.values().associate { event ->
        event.id to this[event]
    }

    fun toGuideConfig(base: GuideConfig = GuideConfig()): GuideConfig = base.copy(
        milestoneEnabled = this[GuideEvent.MILESTONE],
        elapsedEnabled = this[GuideEvent.ELAPSED],
        remainingEnabled = this[GuideEvent.REMAINING],
        slopeEnabled = this[GuideEvent.SLOPE],
        elevationEnabled = this[GuideEvent.ELEVATION],
        waypointEnabled = this[GuideEvent.WAYPOINT],
        sunsetEnabled = this[GuideEvent.SUNSET],
        sunriseEnabled = this[GuideEvent.SUNRISE],
    )

    fun toCheckedItems(): BooleanArray = GuideEvent.values().map(::get).toBooleanArray()

    override fun equals(other: Any?): Boolean =
        other is EventSettings && enabledByEvent == other.enabledByEvent

    override fun hashCode(): Int = enabledByEvent.hashCode()

    override fun toString(): String = enabledByEvent.toString()

    companion object {
        fun defaults(): EventSettings = EventSettings(
            GuideEvent.values().associateWith { it.defaultEnabled },
        )

        /** Missing, null, or invalid stored values fall back to D-062. */
        fun fromPreferenceValues(values: Map<String, Boolean?>): EventSettings = EventSettings(
            GuideEvent.values().associateWith { event ->
                values[preferenceKey(event)] ?: event.defaultEnabled
            },
        )

        fun fromCheckedItems(checked: BooleanArray): EventSettings {
            require(checked.size == GuideEvent.values().size) { "Exactly eight event selections are required" }
            return EventSettings(GuideEvent.values().mapIndexed { index, event -> event to checked[index] }.toMap())
        }

        private fun preferenceKey(event: GuideEvent): String = "event_${event.id.lowercase()}"
    }
}

/** Pure dialog mapping keeps the Android multi-choice UI easy to verify. */
internal object EventSettingsUi {
    fun labels(): List<String> = GuideEvent.values().map { it.label }

    fun checkedItems(settings: EventSettings): BooleanArray = settings.toCheckedItems()

    fun settingsFromCheckedItems(checked: BooleanArray): EventSettings =
        EventSettings.fromCheckedItems(checked)

    /** A cancelled dialog yields no value, so callers cannot persist its draft. */
    fun resolveDialogSelection(
        checked: BooleanArray,
        accepted: Boolean,
    ): EventSettings? = if (accepted) settingsFromCheckedItems(checked) else null
}
