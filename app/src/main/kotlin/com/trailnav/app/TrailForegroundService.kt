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
import java.util.UUID

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
    private var paused = false
    private var offRouteSinceElapsed: Long? = null
    private var offRoutePausePrompted = false
    private var endReason = "service-destroy"
    private var activeSessionId: String? = null
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
        when (intent?.action) {
            ACTION_QUERY_SERVICE_STATE -> {
                if (!sessionStarted) NavigationPreferences.setState(this, NavigationPreferences.STATE_IDLE)
                publishServiceState()
                if (!sessionStarted) stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_UPDATE_ON_ROUTE_VOICE -> {
                val voice = NavigationPreferences.voice(this)
                if (intent.hasExtra(EXTRA_ON_ROUTE_VOICE_ENABLED) || intent.hasExtra(EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS)) {
                    val enabled = intent.getBooleanExtra(EXTRA_ON_ROUTE_VOICE_ENABLED, voice.enabled)
                    val interval = intent.getLongExtra(EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, voice.intervalSeconds)
                    NavigationPreferences.saveVoice(this, enabled, interval)
                    if (sessionStarted) updateOnRouteVoiceConfiguration(enabled, interval)
                } else if (sessionStarted) {
                    updateOnRouteVoiceConfiguration(voice.enabled, voice.intervalSeconds)
                }
                publishServiceState()
                return START_STICKY
            }
            ACTION_PAUSE_GUIDANCE, ACTION_NOTIFICATION_PAUSE -> {
                if (sessionStarted) pauseGuidance(intent.getStringExtra(EXTRA_ACTION_SOURCE) ?: "notification")
                return START_STICKY
            }
            ACTION_RESUME_GUIDANCE, ACTION_NOTIFICATION_RESUME -> {
                if (sessionStarted) resumeGuidance(intent.getStringExtra(EXTRA_ACTION_SOURCE) ?: "notification")
                return START_STICKY
            }
            ACTION_END_GUIDANCE, ACTION_NOTIFICATION_END -> {
                if (sessionStarted) {
                    endReason = "user-end"
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
        }
        if (!hasLocationPermission()) {
            logger?.appendError("permission", "location permission is not granted")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val routeUri = intent?.getStringExtra(EXTRA_ROUTE_URI)
        if (routeUri != null) {
            val storedVoice = NavigationPreferences.voice(this)
            val voiceEnabled = intent?.getBooleanExtra(EXTRA_ON_ROUTE_VOICE_ENABLED, storedVoice.enabled) ?: storedVoice.enabled
            val voiceIntervalSeconds = intent?.getLongExtra(EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, storedVoice.intervalSeconds)
                ?: storedVoice.intervalSeconds
            NavigationPreferences.saveVoice(this, voiceEnabled, voiceIntervalSeconds)
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
        val sessionId = UUID.randomUUID().toString()
        logger = JsonlSessionLogger(
            directory = sessionDirectory,
            sessionId = sessionId,
            codeHash = BuildConfig.GIT_CODE_HASH,
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
        paused = false
        offRouteSinceElapsed = null
        offRoutePausePrompted = false
        endReason = "service-destroy"
        activeSessionId = sessionId
        gpsSignalMonitor = GpsSignalMonitor().also { it.start() }
        onRouteVoiceScheduler = OnRouteVoiceScheduler(onRouteVoiceEnabled, onRouteVoiceIntervalSeconds)
        sessionStarted = true
        NavigationPreferences.setState(this, NavigationPreferences.STATE_RUNNING, activeSessionId)
        startForegroundCompat()
        publishServiceState()
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
        NavigationPreferences.saveVoice(this, enabled, intervalSeconds)
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
        publishGpsSignal(location.accuracyMeters)
        gpsEvent?.let(::announceGpsSignal)
        logger?.appendEnvelope(location, "loc", "location")
        val locationSeq = logger?.appendLocation(location)
        val startup = routeStartupCoordinator
        if (startup != null) {
            val update = startup.accept(location, SystemClock.elapsedRealtime(), locationSeq)
            publishRoutePreparation(update.stage)
            if (update.orientation != null) {
                routeStartupCoordinator = null
                finishRouteStartup(update)
            }
            return
        }
        processGuidance(location, locationSeq)
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
        update.replayLocations.forEachIndexed { index, replayLocation ->
            processGuidance(replayLocation, update.replaySourceSeqs.getOrNull(index))
        }
    }

    private fun processGuidance(location: TrailLocation, sourceSeq: Long?) {
        val session = guideSession ?: return
        val decision = session.accept(location)
        publishRouteRibbon(location, decision)
        val guidance = decision.result.guidance
        val recoveryPrompt = recoveryVoicePrompt(
            previousOffRoute = previousOffRoute,
            currentOffRoute = decision.result.nextState.offRoute,
        )
        updateOffRoutePauseAvailability(decision.result.nextState.offRoute)
        previousOffRoute = decision.result.nextState.offRoute
        val reverseStatus = guidance.isReverseStatus()
        val spoken = guidance.toSpeech()
        val gpsAccuracyRejected = decision.result.reason.rule == "input.accuracy-filter"
        val periodic = if (paused) {
            onRouteVoiceScheduler?.reset()
            false
        } else onRouteVoiceScheduler?.onFrame(
            // Provider timestamps can be stale or repeat across batched fixes.
            // Cadence is an app playback concern, so use a monotonic clock for
            // the one-minute/three-minute/five-minute interval.
            timestampMillis = SystemClock.elapsedRealtime(),
            onRoute = !decision.result.nextState.offRoute && !gpsAccuracyRejected,
            arrived = decision.result.nextState.arrived,
            suppressAnnouncement = reverseStatus,
        ) == true
        val speechCandidates = listOfNotNull(
            spoken,
            recoveryPrompt,
            if (periodic) ON_ROUTE_VOICE_PROMPT else null,
        )
        val loggedSpeech = if (paused) null else speechCandidates.joinToString(" ").ifBlank { null }
        logger?.appendEnvelope(location, "guide", "decision")
        logger?.appendGuide(location, decision.result, loggedSpeech, requireNotNull(sourceSeq))
        if (paused) {
            speechCandidates.forEach { candidate ->
                logger?.appendSystem(
                    "voice.suppressed",
                    mapOf(
                        "type" to when (candidate) {
                            spoken -> "guidance"
                            recoveryPrompt -> "recovery"
                            else -> "on-route"
                        },
                        "reason" to "paused",
                    ),
                )
            }
        } else if (!spoken.isNullOrBlank()) tts?.speak(spoken)
        if (!paused && recoveryPrompt != null) {
            logger?.appendSystem(
                "voice.recovered",
                mapOf("prompt" to recoveryPrompt),
            )
            tts?.speak(recoveryPrompt)
        }
        if (!paused && periodic) {
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
        if (sessionStarted) logger?.appendSystem(
            "service.stopped",
            mapOf("battery_pct" to batteryPercent(), "reason" to endReason),
        )
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
        paused = false
        offRouteSinceElapsed = null
        offRoutePausePrompted = false
        activeSessionId = null
        sessionStarted = false
        NavigationPreferences.setState(this, NavigationPreferences.STATE_IDLE)
        publishServiceState()
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
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            notification(
                if (paused) "안내 일시중지 중" else message,
                showPauseAction = !paused && offRoutePausePrompted,
            ),
        )
    }

    private fun pauseGuidance(source: String) {
        if (paused) return
        paused = true
        onRouteVoiceScheduler?.reset()
        logger?.appendSystem("guidance.paused", mapOf("source" to source))
        NavigationPreferences.setState(this, NavigationPreferences.STATE_PAUSED, activeSessionId)
        publishServiceState()
        updateNotification(null)
    }

    private fun resumeGuidance(source: String) {
        if (!paused) return
        paused = false
        onRouteVoiceScheduler?.reset()
        logger?.appendSystem("guidance.resumed", mapOf("source" to source))
        NavigationPreferences.setState(this, NavigationPreferences.STATE_RUNNING, activeSessionId)
        publishServiceState()
        updateNotification(null)
    }

    private fun updateOffRoutePauseAvailability(offRoute: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!offRoute) {
            if (previousOffRoute) {
                offRouteSinceElapsed = null
                offRoutePausePrompted = false
            }
            return
        }
        if (!previousOffRoute) {
            offRouteSinceElapsed = now
            offRoutePausePrompted = false
        }
        val since = offRouteSinceElapsed ?: now.also { offRouteSinceElapsed = it }
        if (!paused && !offRoutePausePrompted && now - since >= OFF_ROUTE_PAUSE_AFTER_MILLIS) {
            offRoutePausePrompted = true
            logger?.appendSystem("guidance.pause-available")
            tts?.speak("안내를 멈추려면 알림에서 일시중지를 누르세요")
            updateNotification(null)
        }
    }

    private fun announceGpsSignal(event: GpsSignalEvent) {
        if (event is GpsSignalEvent.WeakSignal) {
            logger?.appendSystem(
                "location.accuracy-warning",
                mapOf("accuracy_m" to event.accuracyMeters.toString()),
            )
        }
        if (paused) {
            logger?.appendSystem(
                "voice.suppressed",
                mapOf("type" to "gps", "reason" to "paused"),
            )
        } else {
            tts?.speak(event.toSpeechPrompt())
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

    private fun notification(text: String, showPauseAction: Boolean = false): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrailNav")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (paused) {
            builder.addAction(notificationAction(ACTION_NOTIFICATION_RESUME, 1002, "재개"))
            builder.addAction(notificationAction(ACTION_NOTIFICATION_END, 1003, "종료"))
        } else if (showPauseAction) {
            builder.addAction(notificationAction(ACTION_NOTIFICATION_PAUSE, 1004, "안내 일시중지"))
        }
        return builder.build()
    }

    private fun notificationAction(action: String, requestCode: Int, label: String): NotificationCompat.Action {
        val pendingIntent = android.app.PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TrailForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_ACTION_SOURCE, "notification"),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pendingIntent).build()
    }

    private fun publishServiceState() {
        sendBroadcast(
            Intent(ACTION_SERVICE_STATE_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_SERVICE_STATE, NavigationPreferences.state(this))
                .putExtra(EXTRA_ACTIVE_SESSION_ID, activeSessionId)
                .putExtra(EXTRA_ON_ROUTE_VOICE_ENABLED, NavigationPreferences.voice(this).enabled)
                .putExtra(EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, NavigationPreferences.voice(this).intervalSeconds),
        )
    }

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

    private fun publishGpsSignal(accuracyMeters: Float) {
        sendBroadcast(
            Intent(ACTION_GPS_SIGNAL_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_GPS_ACCURACY_METERS, accuracyMeters.toDouble()),
        )
    }

    companion object {
        const val EXTRA_ROUTE_URI = "com.trailnav.app.ROUTE_URI"
        const val EXTRA_ON_ROUTE_VOICE_ENABLED = "com.trailnav.app.ON_ROUTE_VOICE_ENABLED"
        const val EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS = "com.trailnav.app.ON_ROUTE_VOICE_INTERVAL_SECONDS"
        const val EXTRA_ACTION_SOURCE = "com.trailnav.app.ACTION_SOURCE"
        const val EXTRA_SERVICE_STATE = "com.trailnav.app.SERVICE_STATE"
        const val EXTRA_ACTIVE_SESSION_ID = "com.trailnav.app.ACTIVE_SESSION_ID"
        const val ON_ROUTE_VOICE_PROMPT = "정상적으로 경로를 따라가고 있습니다."
        const val ACTION_ROUTE_PREPARATION_UPDATE = "com.trailnav.app.ROUTE_PREPARATION_UPDATE"
        const val EXTRA_ROUTE_PREPARATION_STAGE = "route_preparation_stage"
        const val ACTION_GPS_SIGNAL_UPDATE = "com.trailnav.app.GPS_SIGNAL_UPDATE"
        const val EXTRA_GPS_ACCURACY_METERS = "gps_accuracy_meters"
        const val ACTION_ROUTE_RIBBON_UPDATE = "com.trailnav.app.ROUTE_RIBBON_UPDATE"
        const val ACTION_SERVICE_STATE_UPDATE = "com.trailnav.app.SERVICE_STATE_UPDATE"
        const val ACTION_UPDATE_ON_ROUTE_VOICE = "com.trailnav.app.UPDATE_ON_ROUTE_VOICE"
        const val ACTION_QUERY_SERVICE_STATE = "com.trailnav.app.QUERY_SERVICE_STATE"
        const val ACTION_PAUSE_GUIDANCE = "com.trailnav.app.PAUSE_GUIDANCE"
        const val ACTION_RESUME_GUIDANCE = "com.trailnav.app.RESUME_GUIDANCE"
        const val ACTION_END_GUIDANCE = "com.trailnav.app.END_GUIDANCE"
        const val ACTION_NOTIFICATION_PAUSE = "com.trailnav.app.NOTIFICATION_PAUSE"
        const val ACTION_NOTIFICATION_RESUME = "com.trailnav.app.NOTIFICATION_RESUME"
        const val ACTION_NOTIFICATION_END = "com.trailnav.app.NOTIFICATION_END"
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
        private const val OFF_ROUTE_PAUSE_AFTER_MILLIS = 5 * 60 * 1_000L
    }
}

internal fun Guidance?.isReverseStatus(): Boolean = this is Guidance.Status && message == "역방향 진행 중"

internal fun Guidance?.toSpeech(): String? = when (this) {
    is Guidance.OffRoute -> "경로를 벗어났습니다. ${"%.0f".format(distance)}미터"
    is Guidance.Status -> if (isReverseStatus()) null else message
    Guidance.Arrived -> "목적지에 도착했습니다"
    is Guidance.TurnAhead, is Guidance.TurnNow, null -> null
}

internal fun GpsSignalEvent.toSpeechPrompt(): String = when (this) {
    GpsSignalEvent.NoFixTimeout -> "GPS 신호를 찾는 중입니다. 실외로 이동하면 더 빨리 잡힙니다."
    GpsSignalEvent.ProviderError -> "GPS 신호가 일시적으로 끊겼습니다. 실외로 이동하거나 잠시 기다려 주세요."
    is GpsSignalEvent.WeakSignal -> "GPS 신호가 약합니다. 안내 정확도가 떨어질 수 있습니다."
}
