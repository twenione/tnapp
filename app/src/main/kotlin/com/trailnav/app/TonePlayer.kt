package com.trailnav.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Plays independent navigation tones and owns every active AudioTrack. */
internal class TonePlayer {
    private val tracks = mutableSetOf<AudioTrack>()

    @Synchronized
    fun play(samples: ShortArray) {
        if (samples.isEmpty()) return
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ToneSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes.size)
                .build()
        } catch (_: Throwable) {
            return
        }
        tracks += track
        track.write(bytes, 0, bytes.size)
        track.setNotificationMarkerPosition(samples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(marker: AudioTrack) {
                synchronized(this@TonePlayer) { tracks.remove(marker) }
                runCatching { marker.stop() }
                marker.release()
            }

            override fun onPeriodicNotification(marker: AudioTrack) = Unit
        })
        runCatching { track.play() }.onFailure {
            tracks.remove(track)
            track.release()
        }
    }

    @Synchronized
    fun close() {
        tracks.toList().forEach { track ->
            runCatching { track.stop() }
            track.release()
        }
        tracks.clear()
    }
}
