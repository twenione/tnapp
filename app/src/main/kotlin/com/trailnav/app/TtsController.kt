package com.trailnav.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private val pending = ArrayDeque<PendingUtterance>()
    private val completions = mutableMapOf<String, () -> Unit>()
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
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) = complete(utteranceId)
                override fun onError(utteranceId: String) = complete(utteranceId)
                override fun onStop(utteranceId: String, interrupted: Boolean) = complete(utteranceId)
            })
            onStatus("tts.ready")
            while (pending.isNotEmpty()) {
                val utterance = pending.removeFirst()
                speakReady(utterance.text, utterance.flush)
            }
        } else {
            onStatus("tts.init-failed:$status")
        }
    }

    fun speak(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) {
            pending.addLast(PendingUtterance(text, flush))
            onStatus("tts.pending")
            return
        }
        speakReady(text, flush)
    }

    /** Returns false when the utterance cannot be started and must not block shutdown. */
    fun speakWithCompletion(text: String, flush: Boolean = false, onFinished: () -> Unit): Boolean {
        if (text.isBlank() || !ready || !requestFocus()) return false
        val id = "guide-${System.nanoTime()}"
        completions[id] = onFinished
        val result = tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            completions.remove(id)
            onStatus("tts.failed:$result")
            return false
        }
        onStatus("tts.queued")
        return true
    }

    private fun complete(utteranceId: String) {
        completions.remove(utteranceId)?.invoke()
    }

    private fun speakReady(text: String, flush: Boolean) {
        if (!requestFocus()) {
            onStatus("tts.audio-focus-denied")
            return
        }
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts.speak(text, queueMode, null, "guide-${System.nanoTime()}")
        onStatus(if (result == TextToSpeech.SUCCESS) "tts.queued" else "tts.failed:$result")
    }

    private data class PendingUtterance(val text: String, val flush: Boolean)

    fun close() {
        completions.clear()
        pending.clear()
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
