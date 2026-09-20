package com.trailnav.app

import android.content.Context

/** Small process-independent store shared by the activity and foreground service. */
internal object NavigationPreferences {
    const val STATE_IDLE = "IDLE"
    const val STATE_RUNNING = "RUNNING"
    const val STATE_PAUSED = "PAUSED"

    private const val PREFS = "trailnav.navigation"
    private const val KEY_VOICE_ENABLED = "voice_on_route_enabled"
    private const val KEY_VOICE_INTERVAL_SECONDS = "voice_on_route_interval_seconds"
    private const val KEY_SERVICE_STATE = "service_state"
    private const val KEY_ACTIVE_SESSION_ID = "active_session_id"

    data class VoiceConfig(val enabled: Boolean, val intervalSeconds: Long)

    fun voice(context: Context): VoiceConfig {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val interval = values.getLong(KEY_VOICE_INTERVAL_SECONDS, 0L)
        return VoiceConfig(values.getBoolean(KEY_VOICE_ENABLED, false), interval)
    }

    fun saveVoice(context: Context, enabled: Boolean, intervalSeconds: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VOICE_ENABLED, enabled && intervalSeconds > 0L)
            .putLong(KEY_VOICE_INTERVAL_SECONDS, if (intervalSeconds > 0L) intervalSeconds else 0L)
            .apply()
    }

    fun state(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SERVICE_STATE, STATE_IDLE) ?: STATE_IDLE

    fun setState(context: Context, state: String, activeSessionId: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVICE_STATE, state)
            .apply {
                if (state == STATE_IDLE || activeSessionId == null) remove(KEY_ACTIVE_SESSION_ID)
                else putString(KEY_ACTIVE_SESSION_ID, activeSessionId)
            }
            .apply()
    }

    fun activeSessionId(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_ACTIVE_SESSION_ID, null)
}
