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
    private var gpsSignalMonitor: GpsSignalMonitor? = null
    private var onRouteVoiceScheduler: OnRouteVoiceScheduler? = null
    private var sessionStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val noLocationWarning = Runnable {
        if (sessionStarted) {
            gpsSignalMonitor?.onNoFixTimeout()?.let {
                logger?.appendSystem("location.no-fix-warning")
                announceGpsSignal(it)
            }
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
        if (!sessionStarted && routeUri != null) {
            startSession(
                routeUri = Uri.parse(routeUri),
                onRouteVoiceEnabled = intent?.getBooleanExtra(EXTRA_ON_ROUTE_VOICE_ENABLED, false) ?: false,
                onRouteVoiceIntervalSeconds = intent?.getLongExtra(EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, 0L) ?: 0L,
            )
        }
        return START_STICKY
    }

    private fun startSession(
        routeUri: Uri,
        onRouteVoiceEnabled: Boolean,
        onRouteVoiceIntervalSeconds: Long,
    ) {
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
        gpsSignalMonitor = GpsSignalMonitor().also { it.start() }
        onRouteVoiceScheduler = OnRouteVoiceScheduler(onRouteVoiceEnabled, onRouteVoiceIntervalSeconds)
        sessionStarted = true
        startForegroundCompat()
        tts?.speak("안내 서비스를 시작합니다.")
        logger?.appendSystem(
            "service.started",
            mapOf("provider" to "fused", "interval_ms" to "1000", "battery_pct" to batteryPercent()),
        )
        logger?.appendSystem(
            "voice.on-route-config",
            mapOf(
                "enabled" to (onRouteVoiceEnabled && onRouteVoiceIntervalSeconds > 0L).toString(),
                "interval_seconds" to (if (onRouteVoiceIntervalSeconds > 0L) onRouteVoiceIntervalSeconds else 0L).toString(),
            ),
        )
        source = FusedLocationSource(this).also { locationSource ->
            locationSource.start(
                onLocation = ::onLocation,
                onError = { error ->
                    logger?.appendError("location", error.message ?: error::class.java.simpleName)
                    gpsSignalMonitor?.onProviderError()?.let {
                        logger?.appendSystem("location.error-warning")
                        announceGpsSignal(it)
                    }
                },
            )
        }
        mainHandler.postDelayed(noLocationWarning, LOCATION_FIX_TIMEOUT_MILLIS)
    }

    private fun onLocation(location: TrailLocation) {
        val session = guideSession ?: return
        val gpsEvent = gpsSignalMonitor?.onLocation(location)
        // A first callback, even with poor accuracy, means the no-fix timer
        // must stop. The monitor also emits the weak-signal warning for the
        // first invalid or over-threshold accuracy value.
        mainHandler.removeCallbacks(noLocationWarning)
        gpsEvent?.let(::announceGpsSignal)
        logger?.appendEnvelope(location, "loc", "location")
        logger?.appendLocation(location)
        val decision = session.accept(location)
        val guidance = decision.result.guidance
        val reverseStatus = guidance.isReverseStatus()
        val spoken = guidance.toSpeech()
        val gpsAccuracyRejected = decision.result.reason.rule == "input.accuracy-filter"
        val periodic = onRouteVoiceScheduler?.onFrame(
            timestampMillis = location.timestampMillis,
            onRoute = !decision.result.nextState.offRoute && !gpsAccuracyRejected,
            arrived = decision.result.nextState.arrived,
            suppressAnnouncement = reverseStatus,
        ) == true
        val loggedSpeech = listOfNotNull(
            spoken,
            if (periodic) ON_ROUTE_VOICE_PROMPT else null,
        ).joinToString(" ").ifBlank { null }
        logger?.appendEnvelope(location, "guide", "decision")
        logger?.appendGuide(location, decision.result, loggedSpeech)
        if (!spoken.isNullOrBlank()) tts?.speak(spoken)
        if (periodic) tts?.speak(ON_ROUTE_VOICE_PROMPT)
        updateNotification(decision.result.guidance)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(noLocationWarning)
        source?.stop()
        source = null
        if (sessionStarted) logger?.appendSystem("service.stopped", mapOf("battery_pct" to batteryPercent()))
        tts?.close()
        tts = null
        gpsSignalMonitor?.stop()
        gpsSignalMonitor = null
        onRouteVoiceScheduler?.reset()
        onRouteVoiceScheduler = null
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
            is Guidance.Status -> if (guidance.isReverseStatus()) "경로를 안내하는 중" else guidance.message
            else -> "경로를 안내하는 중"
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(message))
    }

    private fun announceGpsSignal(event: GpsSignalEvent) {
        when (event) {
            GpsSignalEvent.NoFixTimeout -> tts?.speak(
                "GPS 신호를 확인할 수 없습니다. 위치 권한과 실외 GPS 상태를 확인하세요.",
            )
            GpsSignalEvent.ProviderError -> tts?.speak(
                "위치 정보를 받을 수 없습니다. 위치 권한과 GPS 상태를 확인하세요.",
            )
            is GpsSignalEvent.WeakSignal -> {
                logger?.appendSystem(
                    "location.accuracy-warning",
                    mapOf("accuracy_m" to event.accuracyMeters.toString()),
                )
                tts?.speak("GPS 신호가 약합니다. 안내 정확도가 떨어질 수 있습니다.")
            }
        }
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
        const val EXTRA_ON_ROUTE_VOICE_ENABLED = "com.trailnav.app.ON_ROUTE_VOICE_ENABLED"
        const val EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS = "com.trailnav.app.ON_ROUTE_VOICE_INTERVAL_SECONDS"
        const val ON_ROUTE_VOICE_PROMPT = "정상적으로 경로를 따라가고 있습니다."
        private const val LOCATION_FIX_TIMEOUT_MILLIS = 15_000L
        private const val CHANNEL_ID = "trailnav.navigation"
        private const val NOTIFICATION_ID = 1001
    }
}

internal fun Guidance?.isReverseStatus(): Boolean = this is Guidance.Status && message == "역방향 진행 중"

internal fun Guidance?.toSpeech(): String? = when (this) {
    is Guidance.OffRoute -> "경로를 벗어났습니다. ${"%.0f".format(distance)}미터"
    is Guidance.Status -> if (isReverseStatus()) null else message
    Guidance.Arrived -> "목적지에 도착했습니다"
    is Guidance.TurnAhead, is Guidance.TurnNow, null -> null
}
