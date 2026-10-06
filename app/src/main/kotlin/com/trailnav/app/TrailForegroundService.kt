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
import com.trailnav.core.turnPassed
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
    private val voiceQueue = VoiceQueuePolicy()
    private val voiceCompletionCallbacks = mutableMapOf<String, () -> Unit>()
    private val turnExpiryCallbacks = mutableMapOf<String, Runnable>()
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
    private val pausedRecoveryNotificationState = PausedRecoveryNotificationState()
    private var endReason = "service-destroy"
    private var activeSessionId: String? = null
    private var lastLocation: TrailLocation? = null
    private var lastLocationSeq: Long? = null
    private var onDemandSessionPlan = createOnDemandSessionPlan(shakeEnabled = true)
    private var onDemandConfig = onDemandSessionPlan.config
    private var onDemandRouter = OnDemandRequestRouter(onDemandConfig)
    private var stopGate = StopGate()
    private var stopGateSuppressions = StopGateSuppressionAggregator()
    private var shakeDetector = ShakeDetector(onDemandConfig)
    private var shakeStats = ShakeStats(onDemandConfig.shakeThresholdMetersPerSecondSquared)
    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var imuCollectEnabled = false
    private var imuRecorder: ImuRecorder? = null
    private var imuSensorManager: SensorManager? = null
    private var imuListener: SensorEventListener? = null
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
            ACTION_NOTIFICATION_MARK -> {
                if (sessionStarted && !ending && imuCollectEnabled) {
                    val timestamp = SystemClock.elapsedRealtime()
                    if (imuRecorder?.mark(timestamp) == true) {
                        logger?.appendSystem("imu.mark", mapOf("t_ms" to timestamp.toString()))
                    }
                }
                return START_STICKY
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
        val eventSettings = NavigationPreferences.eventSettings(this)
        val config = eventSettings.toGuideConfig()
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
            eventSettings = eventSettings.toWireMap(),
            routeHash = "sha256:${JsonlSessionLogger.sha256(xml)}",
            appVersion = BuildConfig.VERSION_NAME,
            routeElevationUsed = parsedRoute.elevationUsed,
            routeElevationReason = parsedRoute.elevationReason,
            routeWaypointCount = parsedRoute.waypoints.size,
        )
        tts = TtsController(
            context = this,
            onStatus = { status -> logger?.appendSystem(status) },
            onReady = ::pumpVoiceQueue,
            onInitFailure = ::failVoiceQueue,
            onStarted = ::onVoiceStarted,
            onFinished = ::onVoiceFinished,
        )
        tonePlayer = TonePlayer()
        guideSession = null
        currentRoute = null
        currentGuideConfig = config
        routeStartupCoordinator = RouteStartupCoordinator(xml, config, SystemClock.elapsedRealtime())
        previousOffRoute = false
        paused = false
        pausedRecoveryNotificationState.onResume()
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
        imuCollectEnabled = NavigationPreferences.imuCollectEnabled(this)
        onDemandSessionPlan = createOnDemandSessionPlan(NavigationPreferences.onDemandEnabled(this))
        onDemandConfig = onDemandSessionPlan.config
        onDemandRouter = OnDemandRequestRouter(onDemandConfig)
        stopGate = StopGate(
            speedThresholdMps = onDemandConfig.stopGateSpeedThresholdMps,
            settleMillis = onDemandConfig.stopGateSettleMillis,
            staleMillis = onDemandConfig.stopGateStaleMillis,
            minAccuracyMeters = onDemandConfig.stopGateMinAccuracyMeters,
        )
        stopGateSuppressions = StopGateSuppressionAggregator()
        shakeDetector = ShakeDetector(onDemandConfig)
        shakeStats = ShakeStats(onDemandConfig.shakeThresholdMetersPerSecondSquared)
        sessionStarted = true
        NavigationPreferences.setState(this, NavigationPreferences.STATE_RUNNING, activeSessionId)
        startForegroundCompat()
        publishServiceState()
        publishRoutePreparation(RoutePreparationStage.PREPARING)
        enqueueVoice("안내를 준비중입니다.", source = "system")
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
            "events.config",
            eventSettings.toWireMap().mapValues { (_, enabled) -> enabled.toString() },
        )
        logger?.appendSystem(
            "ondemand.config",
            onDemandSessionPlan.configEventFields,
        )
        logger?.appendSystem(
            "imu.config",
            mapOf(
                "enabled" to imuCollectEnabled.toString(),
                "file" to if (imuCollectEnabled) "imu.ndjson" else "",
                "sampling" to if (imuCollectEnabled) "SENSOR_DELAY_GAME" else "disabled",
            ),
        )
        installOnDemandTriggers()
        installImuRecorder(sessionDirectory)
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
        val enqueued = enqueueVoice(GuidancePhrases.ended(), source = "system", onFinished = ::finishUserEnd)
        if (enqueued) {
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
        stopGate.onLocation(SystemClock.elapsedRealtime(), location.speedMps, location.accuracyMeters)
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

    private fun enqueueVoice(
        text: String,
        source: String,
        priority: VoicePriority = VoicePriority.NORMAL,
        protected: Boolean = false,
        turnIndex: Int? = null,
        onFinished: (() -> Unit)? = null,
    ): Boolean {
        if (text.isBlank() || ending && source != "system") return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                enqueueVoice(text, source, priority, protected, turnIndex, onFinished)
            }
            return true
        }
        val item = VoiceQueueItem(
            id = UUID.randomUUID().toString(),
            text = text,
            priority = priority,
            protected = protected,
            source = source,
            turnIndex = turnIndex,
            enqueuedAtMillis = SystemClock.elapsedRealtime(),
        )
        if (onFinished != null) voiceCompletionCallbacks[item.id] = onFinished
        val result = voiceQueue.enqueue(item)
        logVoiceDrops(result.dropped)
        result.stopActive?.let { tts?.stop() }
        if (priority == VoicePriority.TIME_CRITICAL && item in voiceQueue.pendingItems) {
            scheduleTurnExpiry(item)
        }
        val controller = tts
        if (controller == null || !controller.isReady) controller?.reportPending()
        else pumpVoiceQueue()
        return true
    }

    private fun scheduleTurnExpiry(item: VoiceQueueItem) {
        cancelTurnExpiry(item.id)
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - item.enqueuedAtMillis).coerceAtLeast(0L)
        val delay = if (elapsed < TURN_NOW_UNPASSED_CAP_MILLIS) {
            TURN_NOW_UNPASSED_CAP_MILLIS - elapsed
        } else {
            TURN_STALE_RECHECK_MILLIS
        }
        val callback = Runnable {
            turnExpiryCallbacks.remove(item.id)
            if (voiceQueue.pendingItems.any { it.id == item.id }) {
                refreshPendingVoiceTurns(guideSession?.snapshot())
                if (voiceQueue.pendingItems.any { it.id == item.id }) scheduleTurnExpiry(item)
                pumpVoiceQueue()
            }
        }
        turnExpiryCallbacks[item.id] = callback
        mainHandler.postDelayed(callback, delay)
    }

    private fun cancelTurnExpiry(id: String) {
        turnExpiryCallbacks.remove(id)?.let(mainHandler::removeCallbacks)
    }

    private fun locationIsStale(nowMillis: Long): Boolean =
        stopGate.check(nowMillis).reason == StopGateReason.SPEED_UNAVAILABLE

    private fun refreshPendingVoiceTurns(snapshot: com.trailnav.core.GuideState?) {
        val now = SystemClock.elapsedRealtime()
        val dropped = voiceQueue.refreshPendingTurns(
            nowMillis = now,
            locationStale = locationIsStale(now),
            hasPassed = { index -> snapshot?.let { turnPassed(it, index) } ?: false },
        )
        logVoiceDrops(dropped)
    }

    private fun pumpVoiceQueue() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::pumpVoiceQueue)
            return
        }
        val controller = tts ?: return
        if (!controller.isReady) return
        while (controller.isReady) {
            val now = SystemClock.elapsedRealtime()
            val snapshot = guideSession?.snapshot()
            val next = voiceQueue.beginNext(
                nowMillis = now,
                locationStale = locationIsStale(now),
                hasPassed = { index -> snapshot?.let { turnPassed(it, index) } ?: false },
            )
            logVoiceDrops(next.dropped)
            val item = next.started ?: return
            cancelTurnExpiry(item.id)
            if (controller.start(item)) return
            onVoiceFinished(item.id, TtsController.OUTCOME_ERROR)
        }
    }

    private fun onVoiceStarted(id: String) {
        val item = voiceQueue.activeItem?.takeIf { it.id == id } ?: return
        logger?.appendSystem("tts.started", voiceItemFields(item))
    }

    private fun onVoiceFinished(id: String, outcome: String) {
        val item = voiceQueue.finishActive(id) ?: return
        cancelTurnExpiry(item.id)
        logger?.appendSystem("tts.done", voiceItemFields(item) + ("outcome" to outcome))
        voiceCompletionCallbacks.remove(id)?.invoke()
        pumpVoiceQueue()
    }

    private fun failVoiceQueue() {
        val failed = voiceQueue.failAll()
        failed.forEach { item ->
            cancelTurnExpiry(item.id)
            logger?.appendSystem("tts.done", voiceItemFields(item) + ("outcome" to TtsController.OUTCOME_ERROR))
            voiceCompletionCallbacks.remove(item.id)?.invoke()
        }
    }

    private fun logVoiceDrops(dropped: List<VoiceDropped>) {
        dropped.forEach { entry ->
            val item = entry.item
            cancelTurnExpiry(item.id)
            logger?.appendSystem("voice.dropped", voiceItemFields(item) + ("reason" to entry.reason))
            voiceCompletionCallbacks.remove(item.id)?.invoke()
        }
    }

    private fun voiceItemFields(item: VoiceQueueItem): Map<String, String> = mapOf(
        "utterance_id" to item.id,
        "priority" to item.priority.name,
        "source" to item.source,
        "protected" to item.protected.toString(),
    )

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
        enqueueVoice("안내를 시작합니다", source = "system")
        update.replayLocations.forEachIndexed { index, replayLocation ->
            processGuidance(replayLocation, update.replaySourceSeqs.getOrNull(index))
        }
    }

    private fun processGuidance(location: TrailLocation, sourceSeq: Long?) {
        val session = guideSession ?: return
        val decision = session.accept(location)
        publishRouteRibbon(location, decision)
        val guidance = decision.result.guidance
        val currentOffRoute = decision.result.nextState.offRoute
        val recoveryPrompt = recoveryVoicePrompt(
            previousOffRoute = previousOffRoute,
            currentOffRoute = currentOffRoute,
        )
        if (!previousOffRoute && currentOffRoute) pausedRecoveryNotificationState.onNewOffRouteEntry()
        if (recoveryPrompt != null && paused) pausedRecoveryNotificationState.onRecovery(paused = true)
        updateOffRoutePauseAvailability(currentOffRoute)
        previousOffRoute = currentOffRoute
        refreshPendingVoiceTurns(decision.result.nextState)
        val spoken = guidance.toSpeech()
        val guidanceVoiceKind = voiceKindFor(guidance)
        val guidanceVoiceAllowed = shouldSpeakVoice(ending, paused, guidanceVoiceKind)
        val recoveryVoiceAllowed = shouldSpeakVoice(ending, paused, VoiceKind.RECOVERY)
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
        ) == true
        val frameSpeech = listOfNotNull(
            spoken?.takeIf { guidanceVoiceAllowed },
            recoveryPrompt?.takeIf { recoveryVoiceAllowed },
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
                recoveryPrompt?.takeIf { !recoveryVoiceAllowed }?.let { VoiceKind.RECOVERY to it },
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
                enqueueGuidanceVoice(guidance, decision.result.reason.rule, spoken)
            }
        } else if (!spoken.isNullOrBlank()) {
            enqueueGuidanceVoice(guidance, decision.result.reason.rule, spoken)
        }
        if (recoveryVoiceAllowed && recoveryPrompt != null) {
            logger?.appendSystem(
                "voice.recovered",
                mapOf("prompt" to recoveryPrompt),
            )
            enqueueVoice(
                recoveryPrompt,
                source = "recovery",
                priority = VoicePriority.STATE_TRANSITION,
                protected = true,
            )
        }
        if (!ending && !paused && periodic && onRouteVoiceMode == NavigationPreferences.PeriodicVoiceMode.TONE) {
            logger?.appendSystem("voice.on-route-tone", mapOf("mode" to "TONE"))
            tonePlayer?.play(ToneSynth.periodicSignal())
        }
        updateNotification(decision.result.guidance)
        pumpVoiceQueue()
    }

    private fun enqueueGuidanceVoice(guidance: Guidance?, reasonRule: String, text: String) {
        enqueueVoice(
            text = text,
            source = "guidance",
            priority = priorityFor(guidance, reasonRule, recovery = false),
            protected = isProtected(guidance, recovery = false, reasonRule = reasonRule),
            turnIndex = (guidance as? Guidance.TurnNow)?.turnIndex,
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(noLocationWarning)
        mainHandler.removeCallbacks(routeOrientationTimeout)
        mainHandler.removeCallbacks(endTimeout)
        turnExpiryCallbacks.values.forEach(mainHandler::removeCallbacks)
        turnExpiryCallbacks.clear()
        source?.stop()
        source = null
        val voiceShutdown = voiceQueue.shutdown()
        voiceShutdown.active?.let { item ->
            logger?.appendSystem("tts.done", voiceItemFields(item) + ("outcome" to TtsController.OUTCOME_STOPPED))
        }
        voiceShutdown.pending.forEach { drop ->
            logger?.appendSystem("voice.dropped", voiceItemFields(drop.item) + ("reason" to drop.reason))
        }
        voiceCompletionCallbacks.clear()
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
            is Guidance.Approach -> "경로까지 ${"%.0f".format(guidance.distanceMeters)}m"
            Guidance.Arrived -> "목적지에 도착했습니다"
            is Guidance.Status -> if (guidance.isReverseStatus()) "경로를 안내하는 중" else guidance.message
            else -> "경로를 안내하는 중"
        }
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            notification(
                if (paused) pausedRecoveryNotificationState.recoveryTextOrNull() ?: "안내 일시중지 중" else message,
                showPauseAction = !paused && offRoutePausePrompted,
            ),
        )
    }

    private fun pauseGuidance(source: String) {
        if (ending) return
        if (paused) return
        enqueueVoice("안내를 일시중지했습니다", source = "system")
        paused = true
        pausedRecoveryNotificationState.onPause()
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
        pausedRecoveryNotificationState.onResume()
        enqueueVoice("안내를 다시 시작합니다", source = "system")
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
            enqueueVoice("안내를 멈추려면 알림에서 일시중지를 누르세요", source = "system")
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
            enqueueVoice(event.toSpeechPrompt(), source = "gps")
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
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (paused) {
            builder.addAction(notificationAction(ACTION_NOTIFICATION_RESUME, 1002, "안내 재개"))
            builder.addAction(notificationAction(ACTION_NOTIFICATION_END, 1003, "종료"))
        } else if (showPauseAction) {
            builder.addAction(notificationAction(ACTION_NOTIFICATION_PAUSE, 1004, "안내 일시중지"))
        }
        if (sessionStarted && imuCollectEnabled) {
            builder.addAction(notificationAction(ACTION_NOTIFICATION_MARK, 1005, "흔들기 표시"))
        }
        return builder.build()
    }

    private fun installOnDemandTriggers() {
        if (!onDemandSessionPlan.registerShakeListener) return
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

    private fun installImuRecorder(sessionDirectory: File) {
        if (!imuCollectEnabled) return
        val recorder = ImuRecorder(File(sessionDirectory, "imu.ndjson")).apply {
            onWriteFailure = { error ->
                logger?.appendError("imu.write-failed", error.message ?: error::class.java.simpleName)
            }
        }
        imuRecorder = recorder
        val manager = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                recorder.onSample(
                    elapsedMillis = SystemClock.elapsedRealtime(),
                    sensorTimestampNanos = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        imuListener = listener
        imuSensorManager = manager
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun uninstallImuRecorder() {
        val manager = imuSensorManager
        val listener = imuListener
        if (manager != null && listener != null) manager.unregisterListener(listener)
        imuSensorManager = null
        imuListener = null
        imuRecorder?.close()
        imuRecorder = null
        imuCollectEnabled = false
    }

    private fun uninstallOnDemandTriggers() {
        val manager = sensorManager
        val listener = shakeListener
        if (manager != null && listener != null) manager.unregisterListener(listener)
        sensorManager = null
        shakeListener = null
        uninstallImuRecorder()
        if (onDemandSessionPlan.registerShakeListener) shakeStats.flush()?.let(::appendShakeStats)
        onDemandRouter.flushSuppressedEvents().forEach(::appendShakeCooldownSuppression)
        stopGateSuppressions.flush().forEach(::appendStopGateSuppression)
    }

    private fun handleOnDemand(source: OnDemandSource) {
        if (ending) return
        val timestamp = SystemClock.elapsedRealtime()
        val decision = routeOnDemandRequest(source, timestamp, stopGate, onDemandRouter)
        decision.stopGateReason?.let { reason ->
            stopGateSuppressions.record(timestamp, reason)?.let(::appendStopGateSuppression)
        }
        onDemandRouter.takeSuppressedEvents().forEach(::appendShakeCooldownSuppression)
        val accepted = decision.acceptedSource
        if (accepted == null) return
        logger?.appendSystem("ondemand.request", mapOf("source" to source.wireName, "paused" to paused.toString()))
        tonePlayer?.play(ToneSynth.acknowledgement())
        val session = guideSession
        val location = lastLocation
        val sourceSeq = lastLocationSeq
        if (session == null || location == null || sourceSeq == null) {
            val text = GuidancePhrases.noLocationStatus()
            enqueueOnDemandVoice(text)
            logger?.appendSystem("ondemand.response", mapOf("output_text" to text, "reason" to "no-location", "paused" to paused.toString()))
            return
        }
        val snapshot = session.snapshot()
        val status = session.routeStatus()
        if (status == null) {
            val text = GuidancePhrases.noLocationStatus()
            enqueueOnDemandVoice(text)
            logger?.appendSystem("ondemand.response", mapOf("output_text" to text, "reason" to "no-match", "paused" to paused.toString()))
            return
        }
        val responseStatus = if (!snapshot.hasEnteredRoute && status.offRouteDistanceMeters == null) {
            status.copy(offRouteDistanceMeters = snapshot.lastMatch?.distanceMeters)
        } else {
            status
        }
        val text = GuidancePhrases.onDemandResponse(
            status = responseStatus,
            mode = NavigationPreferences.onDemandResponseMode(this),
            hasEnteredRoute = snapshot.hasEnteredRoute,
        )
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
                ).apply { putAll(status.target.toOnDemandEtaDetails()) },
            ),
        )
        logger?.appendGuide(location, result, text, sourceSeq, trigger = "on-demand")
        enqueueOnDemandVoice(text)
        logger?.appendSystem("ondemand.response", mapOf("output_text" to text, "reason" to "route-status", "paused" to paused.toString()))
    }

    private fun enqueueOnDemandVoice(text: String) {
        val guidance = Guidance.Status(text)
        enqueueVoice(
            text = text,
            source = "ondemand",
            priority = onDemandVoicePriority(guidance),
            protected = isProtected(guidance, recovery = false),
        )
    }

    private fun appendShakeCooldownSuppression(suppression: ShakeCooldownSuppression) {
        logger?.appendSystem("ondemand.suppressed", suppression.toWireMap())
    }

    private fun appendStopGateSuppression(suppression: StopGateSuppression) {
        logger?.appendSystem("ondemand.suppressed", suppression.toWireMap())
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
        const val ACTION_NOTIFICATION_MARK = "com.trailnav.app.NOTIFICATION_MARK"
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
        private const val TURN_STALE_RECHECK_MILLIS = 1_000L
        private const val CHANNEL_ID = "trailnav.navigation"
        private const val NOTIFICATION_ID = 1001
    }
}

internal fun Guidance?.isReverseStatus(): Boolean = this is Guidance.Status && message == "역방향 진행 중"

internal fun Guidance?.toSpeech(): String? = when (this) {
    is Guidance.OffRoute -> GuidancePhrases.offRoute(distance)
    is Guidance.Approach -> GuidancePhrases.approach(distanceMeters, bearingDegrees)
    is Guidance.Status -> if (isReverseStatus()) null else message
    Guidance.Arrived -> GuidancePhrases.arrived()
    is Guidance.Milestone -> GuidancePhrases.milestone(distanceMeters)
    is Guidance.Elapsed -> GuidancePhrases.elapsed(hours)
    is Guidance.Remaining -> GuidancePhrases.remaining(remainingMeters)
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
