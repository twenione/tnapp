package com.trailnav.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.security.MessageDigest

/** Minimal route list/import screen for Phase 2; maps and turn previews are later phases. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var routes: ArrayAdapter<String>
    private var selectedRoute: Uri? = null
    private var onRouteVoiceEnabled = false
    private var onRouteVoiceIntervalSeconds = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startNavigation()
        } else {
            status.text = "위치 권한이 필요합니다"
        }
    }

    private val openGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers do not offer persistable grants; the current session can still use the URI.
        }
        selectedRoute = uri
        val summary = contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            // Parse once at import time so malformed files are rejected before starting the service.
            com.trailnav.core.RouteModel.fromGpx(bytes.toString(Charsets.UTF_8))
            "${uri.lastPathSegment ?: "GPX 경로"} · sha256:${hash.take(12)}"
        } ?: throw IllegalStateException("GPX를 읽을 수 없습니다")
        routes.add(summary)
        status.text = "경로를 선택했습니다"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hardware volume keys should control the stream used by navigation
        // speech while this activity is in the foreground.
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "경로를 가져오세요" }
        val import = Button(this).apply {
            text = "GPX 가져오기"
            setOnClickListener { openGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "text/plain")) }
        }
        val start = Button(this).apply {
            text = "안내 시작"
            setOnClickListener { ensurePermissionAndStart() }
        }
        val stop = Button(this).apply {
            text = "안내 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TrailForegroundService::class.java))
                status.text = "안내를 중지했습니다"
            }
        }
        routes = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, mutableListOf())
        val list = ListView(this).apply {
            adapter = routes
            choiceMode = ListView.CHOICE_MODE_SINGLE
        }
        val onRouteVoiceLabel = TextView(this).apply {
            text = "경로 위 주기 음성: 끄기"
        }
        val onRouteVoicePresets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            0L to "끄기",
            60L to "1분",
            180L to "3분",
            300L to "5분",
        ).forEach { (intervalSeconds, label) ->
            onRouteVoicePresets.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    onRouteVoiceIntervalSeconds = intervalSeconds
                    onRouteVoiceEnabled = intervalSeconds > 0L
                    onRouteVoiceLabel.text = "경로 위 주기 음성: $label"
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(status)
        root.addView(import)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(onRouteVoiceLabel)
        root.addView(onRouteVoicePresets)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun ensurePermissionAndStart() {
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (locationGranted && notificationGranted) {
            startNavigation()
        } else {
            permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray()
            )
        }
    }

    private fun startNavigation() {
        val route = selectedRoute
        if (route == null) {
            status.text = "먼저 GPX 경로를 가져오세요"
            return
        }
        val intent = Intent(this, TrailForegroundService::class.java).putExtra(
            TrailForegroundService.EXTRA_ROUTE_URI,
            route.toString(),
        ).putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_ENABLED, onRouteVoiceEnabled)
            .putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, onRouteVoiceIntervalSeconds)
        ContextCompat.startForegroundService(this, intent)
        status.text = "안내 서비스를 시작했습니다"
    }
}
