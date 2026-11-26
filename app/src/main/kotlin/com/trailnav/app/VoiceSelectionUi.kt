package com.trailnav.app

/** Pure selection rules for the periodic tone interval row. */
internal object VoiceSelectionUi {
    val INTERVAL_CHOICES: List<Pair<Long, Int>> = listOf(
        0L to R.string.voice_interval_off,
        60L to R.string.voice_interval_one_minute,
        180L to R.string.voice_interval_three_minutes,
        300L to R.string.voice_interval_five_minutes,
        600L to R.string.voice_interval_ten_minutes,
    )

    fun intervalSelected(intervalSeconds: Long, candidateSeconds: Long): Boolean =
        intervalSeconds == candidateSeconds
}
