package com.trailnav.app

import kotlin.math.PI
import kotlin.math.sin

/** Android-free deterministic tones used by periodic and on-demand feedback. */
internal object ToneSynth {
    const val SAMPLE_RATE = 8_000
    const val FREQUENCY_HZ = 880.0
    const val AMPLITUDE = 0.25

    fun periodicSignal(): ShortArray = beep(150)

    fun acknowledgement(): ShortArray = beep(80) + silence(70) + beep(80)

    private fun beep(durationMillis: Int): ShortArray {
        val count = SAMPLE_RATE * durationMillis / 1_000
        return ShortArray(count) { index ->
            val envelope = 1.0 - index.toDouble() / count
            (sin(2.0 * PI * FREQUENCY_HZ * index / SAMPLE_RATE) * envelope * Short.MAX_VALUE * AMPLITUDE).toInt().toShort()
        }
    }

    private fun silence(durationMillis: Int): ShortArray = ShortArray(SAMPLE_RATE * durationMillis / 1_000)
}
