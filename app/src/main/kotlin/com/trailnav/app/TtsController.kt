package com.trailnav.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.ArrayDeque
import java.util.Locale

/** TTS adapter that requests transient spoken-audio focus before each utterance. */
class TtsController(
    context: Context,
    private val onStatus: (String) -> Unit,
) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val navigationAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val pending = ArrayDeque<String>()
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // Apply the same navigation attributes to the TTS playback itself
            // as to the focus request so Android routes speech through the
            // active media output (for example, a connected Bluetooth headset).
            val attributeResult = tts.setAudioAttributes(navigationAudioAttributes)
            if (attributeResult != TextToSpeech.SUCCESS) {
                onStatus("tts.audio-attributes-failed:$attributeResult")
            }
            tts.language = Locale.KOREAN
            onStatus("tts.ready")
            while (pending.isNotEmpty()) speakReady(pending.removeFirst())
        } else {
            onStatus("tts.init-failed:$status")
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pending.addLast(text)
            onStatus("tts.pending")
            return
        }
        speakReady(text)
    }

    private fun speakReady(text: String) {
        if (!requestFocus()) {
            onStatus("tts.audio-focus-denied")
            return
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "guide-${System.nanoTime()}")
        onStatus(if (result == TextToSpeech.SUCCESS) "tts.queued" else "tts.failed:$result")
    }

    fun close() {
        tts.stop()
        tts.shutdown()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navigationAudioAttributes)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }
}
