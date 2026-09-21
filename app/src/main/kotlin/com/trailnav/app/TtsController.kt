package com.trailnav.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
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
    private val pending = ArrayDeque<PendingUtterance>()
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

    /** Plays the periodic signal with navigation attributes and without focus. */
    fun playSignalTone() {
        val sampleRate = 8_000
        val durationMillis = 150
        val samples = sampleRate * durationMillis / 1_000
        val pcm = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val envelope = 1.0 - (index.toDouble() / samples)
            val value = (kotlin.math.sin(2.0 * Math.PI * 880.0 * index / sampleRate) * envelope * Short.MAX_VALUE * 0.25).toInt().toShort()
            pcm[index * 2] = (value.toInt() and 0xff).toByte()
            pcm[index * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        val format = android.media.AudioFormat.Builder()
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val toneAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        AudioTrack.Builder()
            .setAudioAttributes(toneAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
            .also { track ->
                track.write(pcm, 0, pcm.size)
                track.setNotificationMarkerPosition(samples)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack) = track.release()
                    override fun onPeriodicNotification(track: AudioTrack) = Unit
                })
                track.play()
            }
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
