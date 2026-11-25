package com.trailnav.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/** One shared implementation of the periodic voice interval row on both screens. */
internal class OnRouteVoiceControls(
    context: Context,
    private val isGuidanceActive: () -> Boolean,
    private val onActiveSelectionChanged: () -> Unit,
) {
    val view = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val buttons = VoiceSelectionUi.INTERVAL_CHOICES.map { (seconds, label) ->
        Button(context).apply {
            text = label
            setOnClickListener {
                val enabled = seconds > 0L
                NavigationPreferences.saveVoice(
                    context,
                    enabled,
                    seconds,
                    if (enabled) NavigationPreferences.PeriodicVoiceMode.TONE else NavigationPreferences.PeriodicVoiceMode.OFF,
                )
                render()
                if (isGuidanceActive()) onActiveSelectionChanged()
            }
        }.also { button ->
            view.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } to seconds
    }

    fun render() {
        val selectedSeconds = NavigationPreferences.voice(view.context).intervalSeconds
        buttons.forEach { (button, seconds) ->
            val selected = VoiceSelectionUi.intervalSelected(selectedSeconds, seconds)
            val label = button.text.toString().removePrefix("✓ ")
            button.text = if (selected) "✓ $label" else label
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            button.setBackgroundColor(if (selected) 0xff2962ff.toInt() else Color.TRANSPARENT)
            button.setTextColor(if (selected) Color.WHITE else Color.DKGRAY)
            button.contentDescription = if (selected) "$label 선택됨" else label
        }
    }
}
