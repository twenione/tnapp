package com.trailnav.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Android TTS adapter. Queue policy and utterance lifecycle are owned by the service. */
class TtsController(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onReady: () -> Unit,
    private val onInitFailure: () -> Unit,
    private val onStarted: (String) -> Unit,
    private val onFinished: (String, String) -> Unit,
) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private val navigationAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    @Volatile private var closed = false

    override fun onInit(status: Int) {
        mainHandler.post {
            if (closed) return@post
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                onStatus("tts.init-failed:$status")
                onInitFailure()
                return@post
            }

            // Match the TTS output route to the transient navigation focus request.
            val attributeResult = tts.setAudioAttributes(navigationAudioAttributes)
            if (attributeResult != TextToSpeech.SUCCESS) {
                onStatus("tts.audio-attributes-failed:$attributeResult")
            }
            tts.language = Locale.KOREAN
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = dispatch { onStarted(utteranceId) }
                override fun onDone(utteranceId: String) = dispatch { onFinished(utteranceId, OUTCOME_DONE) }
                override fun onError(utteranceId: String) = dispatch { onFinished(utteranceId, OUTCOME_ERROR) }
                override fun onStop(utteranceId: String, interrupted: Boolean) =
                    dispatch { onFinished(utteranceId, OUTCOME_STOPPED) }
            })
            ready = true
            onStatus("tts.ready")
            onReady()
        }
    }

    val isReady: Boolean get() = ready && !closed

    /** Starts one item in Android's queue. Service policy guarantees one active item. */
    internal fun start(item: VoiceQueueItem): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "TTS must be controlled on the main thread" }
        if (!isReady || item.text.isBlank()) return false
        if (!requestFocus()) {
            onStatus("tts.audio-focus-denied")
            return false
        }
        val result = tts.speak(item.text, TextToSpeech.QUEUE_ADD, null, item.id)
        onStatus(if (result == TextToSpeech.SUCCESS) "tts.queued" else "tts.failed:$result")
        return result == TextToSpeech.SUCCESS
    }

    fun reportPending() {
        if (!isReady) onStatus("tts.pending")
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "TTS must be controlled on the main thread" }
        if (!closed) tts.stop()
    }

    fun close() {
        if (closed) return
        closed = true
        ready = false
        tts.stop()
        tts.shutdown()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun dispatch(callback: () -> Unit) {
        mainHandler.post {
            if (!closed) callback()
        }
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

    companion object {
        const val OUTCOME_DONE = "done"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_STOPPED = "stopped"
    }
}
