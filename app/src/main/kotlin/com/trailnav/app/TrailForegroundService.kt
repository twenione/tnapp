package com.trailnav.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.trailnav.core.GuideConfig
import com.trailnav.core.Guidance
import com.trailnav.core.RouteModel
import java.io.File

/**
 * Android foreground owner of a single local navigation session.  The service
 * contains lifecycle and I/O only; all route decisions remain in :core-guide.
 */
class TrailForegroundService : Service() {
    private var source: LocationSource? = null
    private var logger: JsonlSessionLogger? = null
    private var guideSession: GuideSession? = null
    private var tts: TtsController? = null
    private var sessionStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receivedLocation = false
    private var noLocationWarningIssued = false
    private var weakSignalWarningIssued = false
    private var locationErrorWarningIssued = false

    private val noLocationWarning = Runnable {
        if (sessionStarted && !receivedLocation && !noLocationWarningIssued) {
            noLocationWarningIssued = true
            logger?.appendSystem("location.no-fix-warning")
            tts?.speak("GPS 신호를 확인할 수 없습니다. 위치 권한과 실외 GPS 상태를 확인하세요.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission()) {
            logger?.appendError("permission", "location permission is not granted")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val routeUri = intent?.getStringExtra(EXTRA_ROUTE_URI)
        if (!sessionStarted && routeUri != null) startSession(Uri.parse(routeUri))
        return START_STICKY
    }

    private fun startSession(routeUri: Uri) {
        val xml = try {
            contentResolver.openInputStream(routeUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("unable to read GPX")
        } catch (error: Throwable) {
            logger?.appendError("route-import", error.message ?: error::class.java.simpleName)
            stopSelf()
            return
        }
        val config = GuideConfig()
        val route = try {
            RouteModel.fromGpx(xml, config)
        } catch (error: Throwable) {
            logger?.appendError("route-parse", error.message ?: error::class.java.simpleName)
            stopSelf()
            return
        }
        val sessionDirectory = File(filesDir, "sessions/${System.currentTimeMillis()}")
        logger = JsonlSessionLogger(
            directory = sessionDirectory,
            codeHash = "sha256:${JsonlSessionLogger.sha256(BuildConfig.VERSION_NAME)}",
            configHash = "sha256:${JsonlSessionLogger.sha256(config.toString())}",
            routeHash = "sha256:${JsonlSessionLogger.sha256(xml)}",
            appVersion = BuildConfig.VERSION_NAME,
        )
        tts = TtsController(this) { status -> logger?.appendSystem(status) }
        guideSession = GuideSession(route, config)
        sessionStarted = true
        receivedLocation = false
        noLocationWarningIssued = false
        weakSignalWarningIssued = false
        locationErrorWarningIssued = false
        startForegroundCompat()
        tts?.speak("안내 서비스를 시작합니다.")
        logger?.appendSystem(
            "service.started",
            mapOf("provider" to "fused", "interval_ms" to "1000", "battery_pct" to batteryPercent()),
        )
        source = FusedLocationSource(this).also { locationSource ->
            locationSource.start(
                onLocation = ::onLocation,
                onError = { error ->
                    logger?.appendError("location", error.message ?: error::class.java.simpleName)
                    if (!locationErrorWarningIssued) {
                        locationErrorWarningIssued = true
                        noLocationWarningIssued = true
                        logger?.appendSystem("location.error-warning")
                        tts?.speak("위치 정보를 받을 수 없습니다. 위치 권한과 GPS 상태를 확인하세요.")
                    }
                },
            )
        }
        mainHandler.postDelayed(noLocationWarning, LOCATION_FIX_TIMEOUT_MILLIS)
    }

    private fun onLocation(location: TrailLocation) {
        val session = guideSession ?: return
        if (!receivedLocation) {
            receivedLocation = true
            mainHandler.removeCallbacks(noLocationWarning)
        }
        logger?.appendEnvelope(location, "loc", "location")
        logger?.appendLocation(location)
        val decision = session.accept(location)
        val spoken = decision.result.guidance.toSpeech()
        logger?.appendEnvelope(location, "guide", "decision")
        logger?.appendGuide(location, decision.result, spoken)
        if (decision.result.reason.rule == "input.accuracy-filter" && !weakSignalWarningIssued) {
            weakSignalWarningIssued = true
            logger?.appendSystem(
                "location.accuracy-warning",
                mapOf("accuracy_m" to location.accuracyMeters.toString()),
            )
            tts?.speak("GPS 신호가 약합니다. 안내 정확도가 떨어질 수 있습니다.")
        }
        if (!spoken.isNullOrBlank()) tts?.speak(spoken)
        updateNotification(decision.result.guidance)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(noLocationWarning)
        source?.stop()
        source = null
        if (sessionStarted) logger?.appendSystem("service.stopped", mapOf("battery_pct" to batteryPercent()))
        tts?.close()
        tts = null
        logger?.close()
        logger = null
        sessionStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun batteryPercent(): String {
        val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (value in 0..100) value.toString() else ""
    }

    private fun startForegroundCompat() {
        val notification = notification("측위 준비 중")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(guidance: Guidance?) {
        val message = when (guidance) {
            is Guidance.OffRoute -> "경로에서 ${"%.0f".format(guidance.distance)}m 이탈"
            Guidance.Arrived -> "목적지에 도착했습니다"
            is Guidance.Status -> guidance.message
            else -> "경로를 안내하는 중"
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("TrailNav")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TrailNav 안내", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val EXTRA_ROUTE_URI = "com.trailnav.app.ROUTE_URI"
        private const val LOCATION_FIX_TIMEOUT_MILLIS = 15_000L
        private const val CHANNEL_ID = "trailnav.navigation"
        private const val NOTIFICATION_ID = 1001
    }
}

private fun Guidance?.toSpeech(): String? = when (this) {
    is Guidance.OffRoute -> "경로를 벗어났습니다. ${"%.0f".format(distance)}미터"
    is Guidance.Status -> message
    Guidance.Arrived -> "목적지에 도착했습니다"
    is Guidance.TurnAhead, is Guidance.TurnNow, null -> null
}
