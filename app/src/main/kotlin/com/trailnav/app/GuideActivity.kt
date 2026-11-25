package com.trailnav.app

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

internal class GuideActivity : AppCompatActivity() {
    private lateinit var gpsIndicator: TextView
    private lateinit var routeRibbon: RouteRibbonView
    private lateinit var navigationState: TextView
    private lateinit var pauseResumeButton: Button
    private lateinit var voiceControls: OnRouteVoiceControls
    private var lastServiceState: String? = null

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
                    val ribbonState = routeRibbonStateFromIntent(intent)
                    routeRibbon.update(ribbonState)
                    updateGpsIndicator(ribbonState.accuracyRadiusMeters)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        navigationState = TextView(this).apply {
            text = "안내 중"
            textSize = 20f
        }
        gpsIndicator = TextView(this).apply {
            text = "GPS 대기"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xff1b5e20.toInt())
            contentDescription = "GPS 신호 상태"
        }
        routeRibbon = RouteRibbonView(this).apply {
            minimumHeight = (220 * resources.displayMetrics.density).toInt()
        }
        voiceControls = OnRouteVoiceControls(
            context = this,
            isGuidanceActive = { NavigationPreferences.state(this) != NavigationPreferences.STATE_IDLE },
            onActiveSelectionChanged = { sendServiceAction(TrailForegroundService.ACTION_UPDATE_ON_ROUTE_VOICE) },
        )
        pauseResumeButton = Button(this).apply {
            text = "안내 일시중지"
            setOnClickListener {
                if (NavigationPreferences.state(this@GuideActivity) == NavigationPreferences.STATE_PAUSED) {
                    sendServiceAction(TrailForegroundService.ACTION_RESUME_GUIDANCE)
                } else {
                    sendServiceAction(TrailForegroundService.ACTION_PAUSE_GUIDANCE)
                }
            }
        }
        val endButton = Button(this).apply {
            text = "안내 종료"
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@GuideActivity)
                    .setTitle("안내 종료")
                    .setMessage("안내를 종료하시겠습니까?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("종료") { _, _ ->
                        sendServiceAction(TrailForegroundService.ACTION_END_GUIDANCE)
                    }
                    .show()
            }
        }
        root.addView(navigationState)
        root.addView(gpsIndicator)
        root.addView(
            routeRibbon,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (250 * resources.displayMetrics.density).toInt()),
        )
        root.addView(voiceControls.view)
        root.addView(pauseResumeButton)
        root.addView(endButton)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val horizontalPadding = (24f * resources.displayMetrics.density).roundToInt()
            val bottomPadding = (24f * resources.displayMetrics.density).roundToInt()
            view.setPadding(horizontalPadding, safeInsets.top, horizontalPadding, bottomPadding + safeInsets.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        voiceControls.render()
        if (intent.getBooleanExtra(EXTRA_START_WITH_PREPARING, false)) {
            routeRibbon.updatePreparation(RoutePreparationStage.PREPARING)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TrailForegroundService.ACTION_ROUTE_RIBBON_UPDATE).apply {
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
        voiceControls.render()
        sendServiceAction(TrailForegroundService.ACTION_QUERY_SERVICE_STATE)
    }

    override fun onStop() {
        unregisterReceiver(serviceReceiver)
        super.onStop()
    }

    private fun applyServiceState(state: String) {
        if (shouldResetRibbon(lastServiceState, state)) {
            routeRibbon.reset()
            updateGpsIndicator(0.0)
        }
        lastServiceState = state
        when (state) {
            NavigationPreferences.STATE_RUNNING -> {
                navigationState.text = "안내 중"
                pauseResumeButton.visibility = android.view.View.VISIBLE
                pauseResumeButton.text = "안내 일시중지"
            }
            NavigationPreferences.STATE_PAUSED -> {
                navigationState.text = "안내 일시중지 중"
                pauseResumeButton.visibility = android.view.View.VISIBLE
                pauseResumeButton.text = "안내 재개"
            }
            else -> {
                navigationState.text = "안내 대기 중"
                pauseResumeButton.visibility = android.view.View.GONE
                finish()
            }
        }
        voiceControls.render()
    }

    private fun updateGpsIndicator(accuracyMeters: Double) {
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0.0) {
            gpsIndicator.text = "GPS 대기"
            gpsIndicator.setTextColor(0xff6d6d6d.toInt())
            return
        }
        val rounded = "%.0f".format(accuracyMeters)
        val quality = when {
            accuracyMeters <= 10.0 -> "좋음"
            accuracyMeters <= 30.0 -> "보통"
            else -> "약함"
        }
        gpsIndicator.text = "GPS $quality ${rounded}m"
        gpsIndicator.setTextColor(if (quality == "약함") 0xffb71c1c.toInt() else 0xff1b5e20.toInt())
    }

    private fun sendServiceAction(action: String) {
        startService(
            Intent(this, TrailForegroundService::class.java)
                .setAction(action)
                .putExtra(TrailForegroundService.EXTRA_ACTION_SOURCE, "ui"),
        )
    }

    companion object {
        const val EXTRA_START_WITH_PREPARING = "guide_start_with_preparing"
    }
}
