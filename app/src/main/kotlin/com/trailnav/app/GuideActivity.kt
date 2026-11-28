package com.trailnav.app

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

internal class GuideActivity : AppCompatActivity() {
    private lateinit var routeHeader: TextView
    private lateinit var gpsIndicator: TextView
    private lateinit var routeRibbon: RouteRibbonView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusSummary: TextView
    private lateinit var directionLine: TextView
    private lateinit var nextTurnLine: TextView
    private lateinit var offRouteLine: TextView
    private lateinit var remainingLine: TextView
    private lateinit var auxiliaryLine: TextView
    private lateinit var toneButton: Button
    private var lastServiceState: String? = null
    private var lastRibbonState: RouteRibbonState? = null
    private var lastStatusWaiting = false
    private var lastStatusStartupPending = true
    private var lastStatusBroadcastReceived = false
    private var lastWaitingDistanceMeters: Double? = null

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                TrailForegroundService.ACTION_SERVICE_STATE_UPDATE -> applyServiceState(
                    intent.getStringExtra(TrailForegroundService.EXTRA_SERVICE_STATE)
                        ?: NavigationPreferences.STATE_IDLE,
                )
                TrailForegroundService.ACTION_ROUTE_PREPARATION_UPDATE -> routeRibbon.updatePreparation(
                    RoutePreparationStage.fromWire(
                        intent.getStringExtra(TrailForegroundService.EXTRA_ROUTE_PREPARATION_STAGE),
                    ),
                )
                TrailForegroundService.ACTION_GPS_SIGNAL_UPDATE -> updateGpsIndicator(
                    intent.getDoubleExtra(TrailForegroundService.EXTRA_GPS_ACCURACY_METERS, 0.0),
                )
                TrailForegroundService.ACTION_ROUTE_RIBBON_UPDATE -> {
                    val state = routeRibbonStateFromIntent(intent)
                    lastRibbonState = state
                    routeRibbon.update(state)
                    updateGpsIndicator(state.accuracyRadiusMeters)
                    renderGuideStatus()
                }
                TrailForegroundService.ACTION_GUIDE_STATUS_UPDATE -> {
                    val waiting = intent.getBooleanExtra(TrailForegroundService.EXTRA_STATUS_WAITING, false)
                    val distance = intent.getDoubleExtra(
                        TrailForegroundService.EXTRA_STATUS_WAITING_DISTANCE_METERS,
                        -1.0,
                    ).takeIf { it >= 0.0 && it.isFinite() }
                    lastWaitingDistanceMeters = GuideStatusText.retainWaitingDistance(
                        previousMeters = lastWaitingDistanceMeters,
                        waiting = waiting,
                        latestMeters = distance,
                    )
                    lastStatusWaiting = waiting
                    lastStatusStartupPending = intent.getBooleanExtra(
                        TrailForegroundService.EXTRA_STATUS_STARTUP_PENDING,
                        false,
                    )
                    lastStatusBroadcastReceived = true
                    renderGuideStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        routeHeader = TextView(this).apply {
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }
        gpsIndicator = TextView(this).apply {
            text = getString(R.string.gps_waiting)
            textSize = 16f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(0xff1b5e20.toInt())
            contentDescription = getString(R.string.gps_signal_state_description)
        }
        headerRow.addView(routeHeader, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(gpsIndicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusSummary = TextView(this).apply {
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        directionLine = statusTextView(16f)
        nextTurnLine = statusTextView(30f)
        offRouteLine = statusTextView(30f)
        remainingLine = statusTextView(30f)
        auxiliaryLine = statusTextView(16f)
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(0xff263238.toInt())
                cornerRadius = dp(16).toFloat()
            }
            addView(statusSummary)
            addView(directionLine, marginParams(top = 6))
            addView(nextTurnLine, marginParams(top = 8))
            addView(offRouteLine, marginParams(top = 8))
            addView(remainingLine, marginParams(top = 8))
            addView(auxiliaryLine, marginParams(top = 8))
        }
        routeRibbon = RouteRibbonView(this).apply {
            minimumHeight = dp(220)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val onDemandButton = Button(this).apply {
            text = getString(R.string.guide_on_demand_now)
            minHeight = dp(60)
            setOnClickListener { sendServiceAction(TrailForegroundService.ACTION_ON_DEMAND_NOW) }
        }
        toneButton = Button(this).apply {
            minHeight = dp(60)
            setOnClickListener { showToneSheet() }
        }
        val endButton = Button(this).apply {
            text = getString(R.string.guidance_end)
            minHeight = dp(60)
            contentDescription = getString(R.string.guide_end_long_press_accessibility)
            setOnClickListener { }
            setOnLongClickListener {
                sendServiceAction(TrailForegroundService.ACTION_END_GUIDANCE)
                true
            }
        }
        val endControl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(endButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
            addView(TextView(this@GuideActivity).apply {
                text = getString(R.string.guide_end_long_press)
                textSize = 12f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        controls.addView(onDemandButton, buttonParams())
        controls.addView(toneButton, buttonParams(start = 6, end = 6))
        controls.addView(endControl, buttonParams())

        root.addView(headerRow, marginParams(bottom = 10))
        root.addView(statusCard, marginParams(bottom = 10))
        root.addView(
            routeRibbon,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)).apply {
                bottomMargin = dp(10)
            },
        )
        root.addView(controls)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(0, safeInsets.top, 0, safeInsets.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        if (intent.getBooleanExtra(EXTRA_START_WITH_PREPARING, false)) {
            routeRibbon.updatePreparation(RoutePreparationStage.PREPARING)
        }
        updateRouteHeader()
        updateToneButton()
        renderGuideStatus()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TrailForegroundService.ACTION_ROUTE_RIBBON_UPDATE).apply {
            addAction(TrailForegroundService.ACTION_GUIDE_STATUS_UPDATE)
            addAction(TrailForegroundService.ACTION_ROUTE_PREPARATION_UPDATE)
            addAction(TrailForegroundService.ACTION_GPS_SIGNAL_UPDATE)
            addAction(TrailForegroundService.ACTION_SERVICE_STATE_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceReceiver, filter)
        }
        updateRouteHeader()
        updateToneButton()
        sendServiceAction(TrailForegroundService.ACTION_QUERY_SERVICE_STATE)
    }

    override fun onStop() {
        unregisterReceiver(serviceReceiver)
        super.onStop()
    }

    private fun applyServiceState(state: String) {
        if (shouldResetRibbon(lastServiceState, state)) {
            routeRibbon.reset()
            lastRibbonState = null
            lastStatusWaiting = false
            lastStatusStartupPending = true
            lastStatusBroadcastReceived = false
            lastWaitingDistanceMeters = null
            updateGpsIndicator(0.0)
            renderGuideStatus()
        }
        lastServiceState = state
        if (state != NavigationPreferences.STATE_RUNNING) {
            finish()
        }
        updateRouteHeader()
        updateToneButton()
    }

    private fun renderGuideStatus() {
        val values = GuideStatusValues(
            ribbon = lastRibbonState,
            waiting = lastStatusWaiting,
            startupPending = if (lastStatusBroadcastReceived) lastStatusStartupPending else true,
            waitingDistanceMeters = lastWaitingDistanceMeters,
        )
        val presentation = GuideStatusText.present(values)
        statusSummary.text = resolveGuideText(presentation.summary)
        statusSummary.setTextColor(
            when (presentation.kind) {
                GuideStatusKind.ON_ROUTE -> 0xff7ee081.toInt()
                GuideStatusKind.OFF_ROUTE -> 0xffffab40.toInt()
                GuideStatusKind.WAITING, GuideStatusKind.DIRECTION_CONFIRMING -> Color.WHITE
            },
        )
        setVisibleText(directionLine, presentation.directionLabel)
        setVisibleText(nextTurnLine, presentation.nextTurn?.let(::resolveGuideText))
        setVisibleText(offRouteLine, presentation.offRouteDistance?.let(::resolveGuideText))
        setVisibleText(remainingLine, presentation.remaining?.let(::resolveGuideText))
        setVisibleText(auxiliaryLine, presentation.auxiliary?.let(::resolveGuideText))
        updateRouteHeader()
    }

    private fun setVisibleText(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun updateRouteHeader() {
        if (!::routeHeader.isInitialized) return
        val isRoundTrip = NavigationPreferences.roundTripEnabled(this) && lastRibbonState?.closedRoute != true
        val shape = if (isRoundTrip) R.string.guide_route_shape_round_trip else R.string.guide_route_shape_one_way
        val routeName = NavigationPreferences.activeRouteName(this)?.trim()?.takeIf { it.isNotEmpty() }
        routeHeader.text = if (routeName == null) getString(shape) else getString(R.string.guide_route_name_shape, routeName, getString(shape))
    }

    private fun updateToneButton() {
        if (!::toneButton.isInitialized) return
        toneButton.text = resolveGuideText(GuideStatusText.toneSelection(NavigationPreferences.voice(this).intervalSeconds).buttonLabel)
    }

    private fun showToneSheet() {
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.guide_tone_sheet_description)
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        })
        val currentSeconds = NavigationPreferences.voice(this).intervalSeconds
        lateinit var dialog: AlertDialog
        VoiceSelectionUi.INTERVAL_CHOICES.forEach { (seconds, labelResource) ->
            val selected = VoiceSelectionUi.intervalSelected(currentSeconds, seconds)
            content.addView(Button(this).apply {
                val label = getString(labelResource)
                text = if (selected) "✓ $label" else label
                minHeight = dp(56)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                contentDescription = if (selected) getString(R.string.item_selected, label) else label
                setOnClickListener {
                    val selection = GuideStatusText.toneSelection(seconds)
                    NavigationPreferences.saveVoice(
                        this@GuideActivity,
                        selection.enabled,
                        selection.intervalSeconds,
                        selection.mode,
                    )
                    updateToneButton()
                    sendServiceAction(TrailForegroundService.ACTION_UPDATE_ON_ROUTE_VOICE)
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                bottomMargin = (4 * density).roundToInt()
            })
        }
        val scroll = ScrollView(this).apply { addView(content) }
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.guide_tone_sheet_title))
            .setView(scroll)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.setCanceledOnTouchOutside(true)
    }

    private fun updateGpsIndicator(accuracyMeters: Double) {
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0.0) {
            gpsIndicator.text = getString(R.string.gps_waiting)
            gpsIndicator.setTextColor(0xff6d6d6d.toInt())
            return
        }
        val rounded = "%.0f".format(accuracyMeters)
        val quality = when {
            accuracyMeters <= 10.0 -> getString(R.string.gps_quality_good)
            accuracyMeters <= 30.0 -> getString(R.string.gps_quality_normal)
            else -> getString(R.string.gps_quality_weak)
        }
        gpsIndicator.text = getString(R.string.gps_accuracy_value, quality, rounded)
        gpsIndicator.setTextColor(if (accuracyMeters > 30.0) 0xffb71c1c.toInt() else 0xff1b5e20.toInt())
    }

    private fun resolveGuideText(text: GuideText): String = getString(
        text.resourceId,
        *text.arguments.map { argument ->
            if (argument is GuideText) resolveGuideText(argument) else argument
        }.toTypedArray(),
    )

    private fun statusTextView(sizeSp: Float) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(Color.WHITE)
    }

    private fun sendServiceAction(action: String) {
        startService(
            Intent(this, TrailForegroundService::class.java)
                .setAction(action)
                .putExtra(TrailForegroundService.EXTRA_ACTION_SOURCE, "ui"),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun buttonParams(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply {
        marginStart = dp(start)
        marginEnd = dp(end)
    }

    companion object {
        const val EXTRA_START_WITH_PREPARING = "guide_start_with_preparing"
    }
}
