package com.trailnav.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
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
import com.trailnav.core.GuideResult
import com.trailnav.core.Reason
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
    private var onRouteVoiceMode = NavigationPreferences.PeriodicVoiceMode.OFF
    private var currentRoute: RouteModel? = null
    private var currentGuideConfig: GuideConfig? = null
    private var routeStartupCoordinator: RouteStartupCoordinator? = null
    private var previousOffRoute = false
    private var sessionStarted = false
    private var paused = false
    private var offRoutePausePrompted = false
    private val pauseAvailability = GuidancePauseAvailability()
    private var endReason = "service-destroy"
    private var activeSessionId: String? = null
    private var lastLocation: TrailLocation? = null
    private var lastLocationSeq: Long? = null
    private var onDemandConfig = OnDemandConfig()
    private var onDemandRouter = OnDemandRequestRouter(onDemandConfig)
    private var shakeDetector = ShakeDetector(onDemandConfig)
    private var shakeStats = ShakeStats(onDemandConfig.shakeThresholdMetersPerSecondSquared)
    private var mediaSession: MediaSession? = null
    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var tonePlayer: TonePlayer? = null
    private var ending = false
    private var endStartId: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val endTimeout = Runnable { finishUserEnd() }

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
                    val mode = NavigationPreferences.PeriodicVoiceMode.fromWire(
                        intent.getStringExtra(EXTRA_ON_ROUTE_VOICE_MODE) ?: voice.mode.name,
                    )
                    NavigationPreferences.saveVoice(this, enabled, interval, mode)
                    if (sessionStarted) updateOnRouteVoiceConfiguration(enabled, interval, mode)
                } else if (sessionStarted) {
                    updateOnRouteVoiceConfiguration(voice.enabled, voice.intervalSeconds, voice.mode)
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
                    beginUserEnd(startId)
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
            val voiceMode = NavigationPreferences.PeriodicVoiceMode.fromWire(
                intent?.getStringExtra(EXTRA_ON_ROUTE_VOICE_MODE) ?: storedVoice.mode.name,
            )
            NavigationPreferences.saveVoice(this, voiceEnabled, voiceIntervalSeconds, voiceMode)
            if (!sessionStarted) {
                startSession(
                    routeUri = Uri.parse(routeUri),
                    onRouteVoiceEnabled = voiceEnabled,
                    onRouteVoiceIntervalSeconds = voiceIntervalSeconds,
                    onRouteVoiceMode = voiceMode,
                )
            } else {
                updateOnRouteVoiceConfiguration(voiceEnabled, voiceIntervalSeconds, voiceMode)
            }
        }
        return START_STICKY
    }

    private fun startSession(
        routeUri: Uri,
        onRouteVoiceEnabled: Boolean,
        onRouteVoiceIntervalSeconds: Long,
        onRouteVoiceMode: NavigationPreferences.PeriodicVoiceMode,
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
        val parsedRoute = try {
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
            routeElevationUsed = parsedRoute.elevationUsed,
            routeElevationReason = parsedRoute.elevationReason,
            routeWaypointCount = parsedRoute.waypoints.size,
        )
        tts = TtsController(this) { status -> logger?.appendSystem(status) }
        tonePlayer = TonePlayer()
        guideSession = null
        currentRoute = null
        currentGuideConfig = config
        routeStartupCoordinator = RouteStartupCoordinator(xml, config, SystemClock.elapsedRealtime())
        previousOffRoute = false
        paused = false
        pauseAvailability.reset()
        offRoutePausePrompted = false
        endReason = "service-destroy"
        ending = false
        endStartId = null
        activeSessionId = sessionId
        lastLocation = null
        lastLocationSeq = null
        gpsSignalMonitor = GpsSignalMonitor().also { it.start() }
        this.onRouteVoiceMode = onRouteVoiceMode
        onRouteVoiceScheduler = OnRouteVoiceScheduler(
            onRouteVoiceEnabled && onRouteVoiceMode != NavigationPreferences.PeriodicVoiceMode.OFF,
            onRouteVoiceIntervalSeconds,
        )
        onDemandConfig = OnDemandConfig()
        onDemandRouter = OnDemandRequestRouter(onDemandConfig)
        shakeDetector = ShakeDetector(onDemandConfig)
        shakeStats = ShakeStats(onDemandConfig.shakeThresholdMetersPerSecondSquared)
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
                "mode" to onRouteVoiceMode.name,
            ),
        )
        logger?.appendSystem(
            "ondemand.config",
            mapOf(
                "media_button_enabled" to onDemandConfig.mediaButtonEnabled.toString(),
                "shake_enabled" to onDemandConfig.shakeEnabled.toString(),
                "debounce_ms" to onDemandConfig.debounceMillis.toString(),
                "shake_threshold" to onDemandConfig.shakeThresholdMetersPerSecondSquared.toString(),
                "shake_hits" to onDemandConfig.shakeHitsRequired.toString(),
                "shake_window_ms" to onDemandConfig.shakeWindowMillis.toString(),
                "shake_sampling" to "SENSOR_DELAY_GAME",
                "shake_stats" to "per-minute-aggregates",
            ),
        )
        installOnDemandTriggers()
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

    private fun beginUserEnd(startId: Int) {
        if (ending) return
        ending = true
        endReason = "user-end"
        endStartId = startId
        mainHandler.removeCallbacks(noLocationWarning)
        mainHandler.removeCallbacks(routeOrientationTimeout)
        source?.stop()
        source = null
        uninstallOnDemandTriggers()
        tonePlayer?.close()
        onRouteVoiceScheduler?.reset()
        val finished = tts?.speakWithCompletion(GuidancePhrases.ended(), flush = true) {
            mainHandler.post { finishUserEnd() }
        } == true
        if (finished) {
            mainHandler.removeCallbacks(endTimeout)
            mainHandler.postDelayed(endTimeout, END_TTS_TIMEOUT_MILLIS)
        } else {
            finishUserEnd()
        }
    }

    private fun finishUserEnd() {
        if (!ending) return
        mainHandler.removeCallbacks(endTimeout)
        stopSelfResult(endStartId ?: return)
    }

    private fun formatDistance(value: Double?): String = value?.let { "%.2f".format(it) } ?: ""

    private fun updateOnRouteVoiceConfiguration(
        enabled: Boolean,
        intervalSeconds: Long,
        mode: NavigationPreferences.PeriodicVoiceMode,
    ) {
        onRouteVoiceMode = mode
        onRouteVoiceScheduler?.configure(
            enabled && mode != NavigationPreferences.PeriodicVoiceMode.OFF,
            intervalSeconds,
        )
        NavigationPreferences.saveVoice(this, enabled, intervalSeconds, mode)
        logger?.appendSystem(
            "voice.on-route-config",
            mapOf(
                "enabled" to (enabled && intervalSeconds > 0L).toString(),
                "interval_seconds" to (if (intervalSeconds > 0L) intervalSeconds else 0L).toString(),
                "mode" to mode.name,
                "updated_while_running" to "true",
            ),
        )
    }

    private fun onLocation(location: TrailLocation) {
        if (ending) return
        val gpsEvent = gpsSignalMonitor?.onLocation(location)
        // A first callback, even with poor accuracy, means the no-fix timer
        // must stop. The monitor also emits the weak-signal warning for the
        // first invalid or over-threshold accuracy value.
        mainHandler.removeCallbacks(noLocationWarning)
        publishGpsSignal(location.accuracyMeters)
        gpsEvent?.let(::announceGpsSignal)
        logger?.appendEnvelope(location, "loc", "location")
        val locationSeq = logger?.appendLocation(location)
        lastLocation = location
        lastLocationSeq = locationSeq
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
        val guidanceVoiceKind = if (guidance is Guidance.Sunset) VoiceKind.SUNSET else VoiceKind.GUIDANCE
        val guidanceVoiceAllowed = shouldSpeakVoice(ending, paused, guidanceVoiceKind)
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
        val frameSpeech = listOfNotNull(
            spoken?.takeIf { guidanceVoiceAllowed },
            recoveryPrompt?.takeIf { !paused },
        ).joinToString(" ").ifBlank { null }
        logger?.appendEnvelope(location, "guide", "decision")
        logger?.appendGuide(
            location,
            decision.result,
            frameSpeech,
            requireNotNull(sourceSeq),
            trigger = null,
        )
        if (paused) {
            listOfNotNull(
                spoken?.takeIf { !guidanceVoiceAllowed }?.let { guidanceVoiceKind to it },
                recoveryPrompt?.let { VoiceKind.RECOVERY to it },
            ).forEach { (kind, _) ->
                logger?.appendSystem(
                    "voice.suppressed",
                    mapOf(
                        "type" to kind.name.lowercase(),
                        "reason" to "paused",
                    ),
                )
            }
            if (guidanceVoiceAllowed && !spoken.isNullOrBlank()) {
                tts?.speak(spoken, flush = shouldFlushVoiceQueue(guidance))
            }
        } else if (!spoken.isNullOrBlank()) {
            tts?.speak(spoken, flush = shouldFlushVoiceQueue(guidance))
        }
        if (!paused && recoveryPrompt != null) {
            logger?.appendSystem(
                "voice.recovered",
                mapOf("prompt" to recoveryPrompt),
            )
            tts?.speak(recoveryPrompt)
        }
        if (!ending && !paused && periodic && onRouteVoiceMode == NavigationPreferences.PeriodicVoiceMode.TONE) {
            logger?.appendSystem("voice.on-route-tone", mapOf("mode" to "TONE"))
            tonePlayer?.play(ToneSynth.periodicSignal())
        }
        if (periodic) {
            logger?.appendGuide(
                location,
                decision.result,
                null,
                requireNotNull(sourceSeq),
                trigger = "slot",
            )
        }
        updateNotification(decision.result.guidance)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(noLocationWarning)
        mainHandler.removeCallbacks(routeOrientationTimeout)
        mainHandler.removeCallbacks(endTimeout)
        source?.stop()
        source = null
        if (sessionStarted) logger?.appendSystem(
            "service.stopped",
            mapOf("battery_pct" to batteryPercent(), "reason" to endReason),
        )
        tts?.close()
        tts = null
        tonePlayer?.close()
        tonePlayer = null
        gpsSignalMonitor?.stop()
        gpsSignalMonitor = null
        onRouteVoiceScheduler?.reset()
        onRouteVoiceScheduler = null
        uninstallOnDemandTriggers()
        logger?.close()
        logger = null
        currentRoute = null
        currentGuideConfig = null
        routeStartupCoordinator = null
        previousOffRoute = false
        paused = false
        pauseAvailability.reset()
        offRoutePausePrompted = false
        activeSessionId = null
        lastLocation = null
        lastLocationSeq = null
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
        if (ending) return
        if (paused) return
        tts?.speak("안내를 일시중지했습니다")
        paused = true
        onRouteVoiceScheduler?.reset()
        logger?.appendSystem("guidance.paused", mapOf("source" to source))
        NavigationPreferences.setState(this, NavigationPreferences.STATE_PAUSED, activeSessionId)
        publishServiceState()
        updateNotification(null)
    }

    private fun resumeGuidance(source: String) {
        if (ending) return
        if (!paused) return
        paused = false
        tts?.speak("안내를 다시 시작합니다")
        onRouteVoiceScheduler?.reset()
        logger?.appendSystem("guidance.resumed", mapOf("source" to source))
        NavigationPreferences.setState(this, NavigationPreferences.STATE_RUNNING, activeSessionId)
        publishServiceState()
        updateNotification(null)
    }

    private fun updateOffRoutePauseAvailability(offRoute: Boolean) {
        if (ending) return
        val now = SystemClock.elapsedRealtime()
        if (!offRoute) offRoutePausePrompted = false
        if (!paused && pauseAvailability.onFrame(offRoute, now)) {
            offRoutePausePrompted = true
            logger?.appendSystem("guidance.pause-available")
            tts?.speak("안내를 멈추려면 알림에서 일시중지를 누르세요")
            updateNotification(null)
        }
    }

    private fun announceGpsSignal(event: GpsSignalEvent) {
        if (ending) return
        if (event is GpsSignalEvent.WeakSignal) {
            logger?.appendSystem(
                "location.accuracy-warning",
                mapOf("accuracy_m" to event.accuracyMeters.toString()),
            )
        }
        if (!shouldSpeakVoice(ending, paused, VoiceKind.GPS)) {
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
                .putExtra(EXTRA_RIBBON_REMAINING_METERS, ribbon.remainingDistanceMeters)
                .putExtra(EXTRA_RIBBON_NEXT_TURN_DISTANCE_METERS, ribbon.nextTurn?.distanceMeters ?: -1.0)
                .putExtra(EXTRA_RIBBON_NEXT_TURN_SIDE, ribbon.nextTurn?.side?.name.orEmpty()),
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

    private fun installOnDemandTriggers() {
        mediaSession = MediaSession(this, "TrailNav").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE,
                    )
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build(),
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event?.action == KeyEvent.ACTION_DOWN && event.keyCode in setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                        handleOnDemand(OnDemandSource.MEDIA_BUTTON)
                        return true
                    }
                    return false
                }
            })
            isActive = true
        }
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val magnitude = kotlin.math.sqrt(event.values.sumOf { it.toDouble() * it.toDouble() })
                    val timestamp = SystemClock.elapsedRealtime()
                    val deviation = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH)
                    shakeStats.record(timestamp, deviation)?.let(::appendShakeStats)
                    if (shakeDetector.onSample(timestamp, deviation)) {
                        handleOnDemand(OnDemandSource.SHAKE)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            shakeListener = listener
            sensorManager = manager
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun uninstallOnDemandTriggers() {
        mediaSession?.run { isActive = false; release() }
        mediaSession = null
        val manager = sensorManager
        val listener = shakeListener
        if (manager != null && listener != null) manager.unregisterListener(listener)
        sensorManager = null
        shakeListener = null
        shakeStats.flush()?.let(::appendShakeStats)
    }

    private fun handleOnDemand(source: OnDemandSource) {
        if (ending) return
        if (onDemandRouter.accept(source, SystemClock.elapsedRealtime()) == null) return
        logger?.appendSystem("ondemand.request", mapOf("source" to source.wireName, "paused" to paused.toString()))
        tonePlayer?.play(ToneSynth.acknowledgement())
        val session = guideSession
        val location = lastLocation
        val sourceSeq = lastLocationSeq
        if (session == null || location == null || sourceSeq == null) {
            val text = GuidancePhrases.noLocationStatus()
            tts?.speak(text, flush = true)
            logger?.appendSystem("ondemand.response", mapOf("output_text" to text, "reason" to "no-location", "paused" to paused.toString()))
            return
        }
        val status = session.routeStatus()
        if (status == null) {
            val text = GuidancePhrases.noLocationStatus()
            tts?.speak(text, flush = true)
            logger?.appendSystem("ondemand.response", mapOf("output_text" to text, "reason" to "no-match", "paused" to paused.toString()))
            return
        }
        val text = GuidancePhrases.routeStatus(status)
        val result = GuideResult(
            guidance = Guidance.Status(text),
            nextState = session.snapshot(),
            reason = Reason(
                rule = "on-demand.route-status",
                details = linkedMapOf(
                    "source" to source.wireName,
                    "on_route" to status.onRoute.toString(),
                    "off_route_distance_m" to (status.offRouteDistanceMeters?.toString() ?: ""),
                    "direction" to status.direction.name,
                    "remaining_m" to status.remainingMeters.toString(),
                    "arrived" to status.arrived.toString(),
                    "next_turn_index" to (status.nextTurn?.index?.toString() ?: ""),
                    "next_turn_side" to (status.nextTurn?.side?.name ?: ""),
                    "next_turn_distance_m" to (status.nextTurn?.distanceMeters?.toString() ?: ""),
                ),
            ),
        )
        logger?.appendGuide(location, result, text, sourceSeq, trigger = "on-demand")
        tts?.speak(text, flush = true)
        logger?.appendSystem("ondemand.response", mapOf("output_text" to text, "reason" to "route-status", "paused" to paused.toString()))
    }

    private fun appendShakeStats(snapshot: ShakeStatsSnapshot) {
        logger?.appendSystem(
            "ondemand.shake_stats",
            mapOf(
                "minute_index" to snapshot.minuteIndex.toString(),
                "sample_count" to snapshot.sampleCount.toString(),
                "maximum_deviation" to snapshot.maximumDeviation.toString(),
                "p95_deviation" to snapshot.p95Deviation.toString(),
                "threshold_exceedances" to snapshot.thresholdExceedances.toString(),
            ),
        )
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
                .putExtra(EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, NavigationPreferences.voice(this).intervalSeconds)
                .putExtra(EXTRA_ON_ROUTE_VOICE_MODE, NavigationPreferences.voice(this).mode.name),
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
        const val EXTRA_ON_ROUTE_VOICE_MODE = "com.trailnav.app.ON_ROUTE_VOICE_MODE"
        const val EXTRA_ACTION_SOURCE = "com.trailnav.app.ACTION_SOURCE"
        const val EXTRA_SERVICE_STATE = "com.trailnav.app.SERVICE_STATE"
        const val EXTRA_ACTIVE_SESSION_ID = "com.trailnav.app.ACTIVE_SESSION_ID"
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
        const val EXTRA_RIBBON_NEXT_TURN_DISTANCE_METERS = "ribbon_next_turn_distance_meters"
        const val EXTRA_RIBBON_NEXT_TURN_SIDE = "ribbon_next_turn_side"
        private const val LOCATION_FIX_TIMEOUT_MILLIS = 15_000L
        private const val ROUTE_ORIENTATION_TIMEOUT_MILLIS = 30_000L
        private const val END_TTS_TIMEOUT_MILLIS = 3_000L
        private const val CHANNEL_ID = "trailnav.navigation"
        private const val NOTIFICATION_ID = 1001
    }
}

internal fun Guidance?.isReverseStatus(): Boolean = this is Guidance.Status && message == "역방향 진행 중"

/** Only an immediate turn instruction interrupts already queued navigation speech. */
internal fun shouldFlushVoiceQueue(guidance: Guidance?): Boolean = guidance is Guidance.TurnNow

internal fun Guidance?.toSpeech(): String? = when (this) {
    is Guidance.OffRoute -> GuidancePhrases.offRoute(distance)
    is Guidance.Status -> if (isReverseStatus()) null else message
    Guidance.Arrived -> GuidancePhrases.arrived()
    is Guidance.Milestone -> GuidancePhrases.milestone(distanceMeters)
    is Guidance.Elapsed -> GuidancePhrases.elapsed(hours)
    is Guidance.Remaining -> GuidancePhrases.remaining(thresholdMeters)
    is Guidance.Slope -> GuidancePhrases.slope(kind)
    is Guidance.Elevation -> GuidancePhrases.elevation(elevationMeters)
    is Guidance.Waypoint -> GuidancePhrases.waypoint(name)
    is Guidance.Sunset -> GuidancePhrases.sunset(minutesRemaining, afterSunset)
    is Guidance.Sunrise -> GuidancePhrases.sunrise(minutesRemaining)
    is Guidance.TurnAhead -> GuidancePhrases.turnAhead(distance, side)
    is Guidance.TurnNow -> GuidancePhrases.turnNow(side)
    null -> null
}

internal fun GpsSignalEvent.toSpeechPrompt(): String = when (this) {
    GpsSignalEvent.NoFixTimeout -> "GPS 신호를 찾는 중입니다."
    GpsSignalEvent.ProviderError -> "GPS 신호가 일시적으로 끊겼습니다."
    is GpsSignalEvent.WeakSignal -> "GPS 신호가 약합니다."
}
