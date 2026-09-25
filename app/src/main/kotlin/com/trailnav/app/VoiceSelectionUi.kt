package com.trailnav.app

/** Pure selection rules for the periodic tone interval row. */
internal object VoiceSelectionUi {
    val INTERVAL_CHOICES = listOf(
        0L to "끄기",
        60L to "1분",
        180L to "3분",
        300L to "5분",
        600L to "10분",
    )

    fun intervalSelected(intervalSeconds: Long, candidateSeconds: Long): Boolean =
        intervalSeconds == candidateSeconds
}
