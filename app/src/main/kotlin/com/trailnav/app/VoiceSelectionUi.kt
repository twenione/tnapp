package com.trailnav.app

/** Pure selection rules used by the two periodic-voice button rows. */
internal object VoiceSelectionUi {
    fun intervalSelected(intervalSeconds: Long, candidateSeconds: Long): Boolean =
        intervalSeconds == candidateSeconds

    fun modeSelected(
        enabled: Boolean,
        mode: NavigationPreferences.PeriodicVoiceMode,
        candidate: NavigationPreferences.PeriodicVoiceMode,
    ): Boolean = enabled && mode != NavigationPreferences.PeriodicVoiceMode.OFF && mode == candidate

    fun modeLabel(mode: NavigationPreferences.PeriodicVoiceMode): String = when (mode) {
        NavigationPreferences.PeriodicVoiceMode.PROMPT -> "일반안내"
        NavigationPreferences.PeriodicVoiceMode.TONE -> "신호음"
        NavigationPreferences.PeriodicVoiceMode.OFF -> "끄기"
    }
}
