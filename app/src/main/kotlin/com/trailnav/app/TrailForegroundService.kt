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
import android.os.SystemClock
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
    private var currentRoute: RouteModel? = null
    private var currentGuideConfig: GuideConfig? = null
    private var routeStartupCoordinator: RouteStartupCoordinator? = null
    private var previousOffRoute = false
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

    private val routeOrientationTimeout = Runnable {
        val coordinator = routeStartupCoordinator ?: return@Runnable
        routeStartupCoordinator = null
        finishRouteStartup(coordinator.timeout(SystemClock.elapsedRealtime()))
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
        if (routeUri != null) {
            val voiceEnabled = intent?.getBooleanExtra(EXTRA_ON_ROUTE_VOICE_ENABLED, false) ?: false
            val voiceIntervalSeconds = intent?.getLongExtra(EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, 0L) ?: 0L
            if (!sessionStarted) {
                startSession(
                    routeUri = Uri.parse(routeUri),
                    onRouteVoiceEnabled = voiceEnabled,
                    onRouteVoiceIntervalSeconds = voiceIntervalSeconds,
                )
            } else {
                updateOnRouteVoiceConfiguration(voiceEnabled, voiceIntervalSeconds)
            }
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
        try {
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
        guideSession = null
        currentRoute = null
        currentGuideConfig = config
        routeStartupCoordinator = RouteStartupCoordinator(xml, config, SystemClock.elapsedRealtime())
        previousOffRoute = false
        gpsSignalMonitor = GpsSignalMonitor().also { it.start() }
        onRouteVoiceScheduler = OnRouteVoiceScheduler(onRouteVoiceEnabled, onRouteVoiceIntervalSeconds)
        sessionStarted = true
        startForegroundCompat()
        publishRoutePreparation(RoutePreparationStage.PREPARING)
        tts?.speak("안내를 준비중입니다.")
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
        mainHandler.postDelayed(routeOrientationTimeout, ROUTE_ORIENTATION_TIMEOUT_MILLIS)
    }

    private fun formatDistance(value: Double?): String = value?.let { "%.2f".format(it) } ?: ""

    private fun updateOnRouteVoiceConfiguration(enabled: Boolean, intervalSeconds: Long) {
        onRouteVoiceScheduler?.configure(enabled, intervalSeconds)
        logger?.appendSystem(
            "voice.on-route-config",
            mapOf(
                "enabled" to (enabled && intervalSeconds > 0L).toString(),
                "interval_seconds" to (if (intervalSeconds > 0L) intervalSeconds else 0L).toString(),
                "updated_while_running" to "true",
            ),
        )
    }

    private fun onLocation(location: TrailLocation) {
        val gpsEvent = gpsSignalMonitor?.onLocation(location)
        // A first callback, even with poor accuracy, means the no-fix timer
        // must stop. The monitor also emits the weak-signal warning for the
        // first invalid or over-threshold accuracy value.
        mainHandler.removeCallbacks(noLocationWarning)
        gpsEvent?.let(::announceGpsSignal)
        logger?.appendEnvelope(location, "loc", "location")
        logger?.appendLocation(location)
        val startup = routeStartupCoordinator
        if (startup != null) {
            val update = startup.accept(location, SystemClock.elapsedRealtime())
            publishRoutePreparation(update.stage)
            if (update.orientation != null) {
                routeStartupCoordinator = null
                finishRouteStartup(update)
            }
            return
        }
        processGuidance(location)
    }

    private fun finishRouteStartup(update: RouteStartupUpdate) {
        val orientation = update.orientation ?: return
        mainHandler.removeCallbacks(routeOrientationTimeout)
        val config = currentGuideConfig ?: return
        val route = try {
            RouteModel.fromGpx(orientation.gpxXml, config)
        } catch (error: Throwable) {
            logger?.appendError("route-parse", error.message ?: error::class.java.simpleName)
            stopSelf()
            return
        }
        currentRoute = route
        guideSession = GuideSession(route, config)
        previousOffRoute = false
        logger?.appendSystem(
            "route.orientation",
            mapOf(
                "reversed" to orientation.reversed.toString(),
                "reason" to orientation.reason,
                "observed_net_displacement_m" to formatDistance(orientation.observedNetDisplacementMeters),
                "observation_elapsed_seconds" to "%.3f".format(orientation.observationElapsedSeconds),
            ),
        )
        publishRoutePreparation(RoutePreparationStage.DIRECTION_CONFIRMED)
        tts?.speak("안내를 시작합니다.")
        update.replayLocations.forEach(::processGuidance)
    }

    private fun processGuidance(location: TrailLocation) {
        val session = guideSession ?: return
        val decision = session.accept(location)
        publishRouteRibbon(location, decision)
        val guidance = decision.result.guidance
        val recoveryPrompt = recoveryVoicePrompt(
            previousOffRoute = previousOffRoute,
            currentOffRoute = decision.result.nextState.offRoute,
        )
        previousOffRoute = decision.result.nextState.offRoute
        val reverseStatus = guidance.isReverseStatus()
        val spoken = guidance.toSpeech()
        val gpsAccuracyRejected = decision.result.reason.rule == "input.accuracy-filter"
        val periodic = onRouteVoiceScheduler?.onFrame(
            // Provider timestamps can be stale or repeat across batched fixes.
            // Cadence is an app playback concern, so use a monotonic clock for
            // the one-minute/three-minute/five-minute interval.
            timestampMillis = SystemClock.elapsedRealtime(),
            onRoute = !decision.result.nextState.offRoute && !gpsAccuracyRejected,
            arrived = decision.result.nextState.arrived,
            suppressAnnouncement = reverseStatus,
        ) == true
        val loggedSpeech = listOfNotNull(
            spoken,
            recoveryPrompt,
            if (periodic) ON_ROUTE_VOICE_PROMPT else null,
        ).joinToString(" ").ifBlank { null }
        logger?.appendEnvelope(location, "guide", "decision")
        logger?.appendGuide(location, decision.result, loggedSpeech)
        if (!spoken.isNullOrBlank()) tts?.speak(spoken)
        if (recoveryPrompt != null) {
            logger?.appendSystem(
                "voice.recovered",
                mapOf("prompt" to recoveryPrompt),
            )
            tts?.speak(recoveryPrompt)
        }
        if (periodic) {
            logger?.appendSystem(
                "voice.on-route",
                mapOf("prompt" to ON_ROUTE_VOICE_PROMPT),
            )
            tts?.speak(ON_ROUTE_VOICE_PROMPT)
        }
        updateNotification(decision.result.guidance)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(noLocationWarning)
        mainHandler.removeCallbacks(routeOrientationTimeout)
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
        currentRoute = null
        currentGuideConfig = null
        routeStartupCoordinator = null
        previousOffRoute = false
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

    private fun publishRouteRibbon(location: TrailLocation, decision: SessionDecision) {
        val route = currentRoute ?: return
        val config = currentGuideConfig ?: return
        val ribbon = RouteRibbonCalculator.calculate(location, decision.result, route, config) ?: return
        sendBroadcast(
            Intent(ACTION_ROUTE_RIBBON_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_RIBBON_DISTANCE_METERS, ribbon.perpendicularDistanceMeters)
                .putExtra(EXTRA_RIBBON_SIGNED_OFFSET_METERS, ribbon.signedOffsetMeters)
                .putExtra(EXTRA_RIBBON_DIRECTION, ribbon.direction.name)
                .putExtra(EXTRA_RIBBON_OFF_ROUTE, ribbon.offRoute)
                .putExtra(EXTRA_RIBBON_ENTER_BAND_METERS, ribbon.enterBandMeters)
                .putExtra(EXTRA_RIBBON_EXIT_BAND_METERS, ribbon.exitBandMeters)
                .putExtra(EXTRA_RIBBON_ACCURACY_METERS, ribbon.accuracyRadiusMeters)
                .putExtra(EXTRA_RIBBON_REMAINING_METERS, ribbon.remainingDistanceMeters),
        )
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

    private fun publishRoutePreparation(stage: RoutePreparationStage) {
        sendBroadcast(
            Intent(ACTION_ROUTE_PREPARATION_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_ROUTE_PREPARATION_STAGE, stage.wireName),
        )
    }

    companion object {
        const val EXTRA_ROUTE_URI = "com.trailnav.app.ROUTE_URI"
        const val EXTRA_ON_ROUTE_VOICE_ENABLED = "com.trailnav.app.ON_ROUTE_VOICE_ENABLED"
        const val EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS = "com.trailnav.app.ON_ROUTE_VOICE_INTERVAL_SECONDS"
        const val ON_ROUTE_VOICE_PROMPT = "정상적으로 경로를 따라가고 있습니다."
        const val ACTION_ROUTE_PREPARATION_UPDATE = "com.trailnav.app.ROUTE_PREPARATION_UPDATE"
        const val EXTRA_ROUTE_PREPARATION_STAGE = "route_preparation_stage"
        const val ACTION_ROUTE_RIBBON_UPDATE = "com.trailnav.app.ROUTE_RIBBON_UPDATE"
        const val EXTRA_RIBBON_DISTANCE_METERS = "ribbon_distance_meters"
        const val EXTRA_RIBBON_SIGNED_OFFSET_METERS = "ribbon_signed_offset_meters"
        const val EXTRA_RIBBON_DIRECTION = "ribbon_direction"
        const val EXTRA_RIBBON_OFF_ROUTE = "ribbon_off_route"
        const val EXTRA_RIBBON_ENTER_BAND_METERS = "ribbon_enter_band_meters"
        const val EXTRA_RIBBON_EXIT_BAND_METERS = "ribbon_exit_band_meters"
        const val EXTRA_RIBBON_ACCURACY_METERS = "ribbon_accuracy_meters"
        const val EXTRA_RIBBON_REMAINING_METERS = "ribbon_remaining_meters"
        private const val LOCATION_FIX_TIMEOUT_MILLIS = 15_000L
        private const val ROUTE_ORIENTATION_TIMEOUT_MILLIS = 30_000L
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
