package com.trailnav.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Minimal route list/import screen for Phase 2; maps and turn previews are later phases. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var gpsIndicator: TextView
    private lateinit var routeRibbon: RouteRibbonView
    private lateinit var routeListAdapter: RouteListAdapter
    private val savedRoutes = mutableListOf<SavedRoute>()
    private lateinit var startButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var endButton: Button
    private lateinit var mapButton: Button
    private lateinit var navigationState: TextView
    private var intervalButtons: List<Pair<Button, Long>> = emptyList()
    private var modeButtons: List<Pair<Button, NavigationPreferences.PeriodicVoiceMode>> = emptyList()
    private var selectedRoute: Uri? = null
    private var selectedRouteSummary: String? = null
    private var selectedSavedRoute: SavedRoute? = null
    private var onRouteVoiceEnabled = false
    private var onRouteVoiceIntervalSeconds = 0L
    private var onRouteVoiceMode = NavigationPreferences.PeriodicVoiceMode.OFF

    private val ribbonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == TrailForegroundService.ACTION_SERVICE_STATE_UPDATE) {
                applyServiceState(intent.getStringExtra(TrailForegroundService.EXTRA_SERVICE_STATE)
                    ?: NavigationPreferences.STATE_IDLE)
                return
            }
            if (intent.action == TrailForegroundService.ACTION_ROUTE_PREPARATION_UPDATE) {
                routeRibbon.updatePreparation(
                    RoutePreparationStage.fromWire(
                        intent.getStringExtra(TrailForegroundService.EXTRA_ROUTE_PREPARATION_STAGE),
                    ),
                )
                return
            }
            if (intent.action == TrailForegroundService.ACTION_GPS_SIGNAL_UPDATE) {
                updateGpsIndicator(
                    intent.getDoubleExtra(TrailForegroundService.EXTRA_GPS_ACCURACY_METERS, 0.0),
                )
                return
            }
            if (intent.action != TrailForegroundService.ACTION_ROUTE_RIBBON_UPDATE) return
            val ribbonState = RouteRibbonState(
                perpendicularDistanceMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_DISTANCE_METERS, 0.0),
                signedOffsetMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_SIGNED_OFFSET_METERS, 0.0),
                direction = runCatching {
                    com.trailnav.core.ProgressDirection.valueOf(
                        intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_DIRECTION).orEmpty(),
                    )
                }.getOrDefault(com.trailnav.core.ProgressDirection.UNKNOWN),
                offRoute = intent.getBooleanExtra(TrailForegroundService.EXTRA_RIBBON_OFF_ROUTE, false),
                enterBandMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_ENTER_BAND_METERS, 0.0),
                exitBandMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_EXIT_BAND_METERS, 0.0),
                accuracyRadiusMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_ACCURACY_METERS, 0.0),
                remainingDistanceMeters = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_REMAINING_METERS, 0.0),
                nextTurn = intent.getDoubleExtra(TrailForegroundService.EXTRA_RIBBON_NEXT_TURN_DISTANCE_METERS, -1.0)
                    .takeIf { it >= 0.0 }
                    ?.let { distance ->
                        RibbonNextTurn(
                            distanceMeters = distance,
                            side = if (intent.getStringExtra(TrailForegroundService.EXTRA_RIBBON_NEXT_TURN_SIDE) == RibbonTurnSide.LEFT.name) {
                                RibbonTurnSide.LEFT
                            } else {
                                RibbonTurnSide.RIGHT
                            },
                        )
                    },
            )
            routeRibbon.update(ribbonState)
            updateGpsIndicator(ribbonState.accuracyRadiusMeters)
        }
    }

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
            val imported = contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                // Parse once at import time so malformed files are rejected before starting the service.
                val route = com.trailnav.core.RouteModel.fromGpx(bytes.toString(Charsets.UTF_8))
                val displayName = contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                    ?: "GPX 경로"
                SavedRoute(uri.toString(), displayName, hash, route.totalLengthMeters, System.currentTimeMillis())
            } ?: throw IllegalStateException("GPX를 읽을 수 없습니다")
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers do not offer persistable grants; the current session can still use the URI.
            }
            val selected = RouteCatalog.upsert(savedRoutes, imported)
            RouteCatalog.save(this, savedRoutes, selected.uri)
            selectSavedRoute(selected)
            routeListAdapter.notifyDataSetChanged()
        } catch (_: Exception) {
            // The wildcard picker can show provider files whose MIME type is
            // generic or unknown. Keep the picker broad, then reject invalid
            // content here without crashing or replacing the current route.
            status.text = "경로 파일을 읽을 수 없습니다. GPX 파일을 선택하세요."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SessionExportCatalog.cleanupExports(this)
        // Hardware volume keys should control the stream used by navigation
        // speech while this activity is in the foreground.
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        status = TextView(this).apply { text = "경로를 가져오세요" }
        gpsIndicator = TextView(this).apply {
            text = "GPS 대기"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xff1b5e20.toInt())
            contentDescription = "GPS 신호 상태"
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            gpsIndicator,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        routeRibbon = RouteRibbonView(this).apply {
            minimumHeight = (220 * resources.displayMetrics.density).toInt()
        }
        val import = Button(this).apply {
            text = "경로 파일 가져오기"
            setOnClickListener { openGpx.launch(ROUTE_PICKER_MIME_TYPES) }
        }
        val export = Button(this).apply {
            text = "기록 내보내기"
            setOnClickListener { startActivity(Intent(this@MainActivity, SessionExportActivity::class.java)) }
        }
        startButton = Button(this).apply {
            text = "안내 시작"
            setOnClickListener { ensurePermissionAndStart() }
        }
        pauseResumeButton = Button(this).apply {
            text = "안내 일시중지"
            setOnClickListener {
                if (NavigationPreferences.state(this@MainActivity) == NavigationPreferences.STATE_PAUSED) {
                    sendServiceAction(TrailForegroundService.ACTION_RESUME_GUIDANCE, "ui")
                } else {
                    sendServiceAction(TrailForegroundService.ACTION_PAUSE_GUIDANCE, "ui")
                }
            }
        }
        endButton = Button(this).apply {
            text = "안내 종료"
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("안내 종료")
                    .setMessage("안내를 종료하시겠습니까?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("종료") { _, _ ->
                        sendServiceAction(TrailForegroundService.ACTION_END_GUIDANCE, "ui")
                    }
                    .show()
            }
        }
        routeListAdapter = RouteListAdapter(this, savedRoutes, ::confirmRemoveSavedRoute)
        val list = ListView(this).apply {
            adapter = routeListAdapter
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setOnItemClickListener { _, _, position, _ -> selectSavedRoute(routeListAdapter.getItem(position)) }
            setOnItemLongClickListener { _, _, position, _ ->
                val route = routeListAdapter.getItem(position)
                confirmRemoveSavedRoute(route)
                true
            }
        }
        mapButton = Button(this).apply {
            text = "지도에서 보기"
            isEnabled = false
            setOnClickListener { openSelectedRouteInMap() }
        }
        val onRouteVoicePresets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val onRouteVoiceModeControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val intervalChoices = listOf(
            0L to "끄기",
            60L to "1분",
            180L to "3분",
            300L to "5분",
        )
        intervalButtons = intervalChoices.map { (intervalSeconds, label) ->
            Button(this).apply {
                text = label
                setOnClickListener {
                    onRouteVoiceIntervalSeconds = intervalSeconds
                    if (intervalSeconds == 0L) {
                        onRouteVoiceMode = NavigationPreferences.PeriodicVoiceMode.OFF
                    } else if (onRouteVoiceMode == NavigationPreferences.PeriodicVoiceMode.OFF) {
                        onRouteVoiceMode = NavigationPreferences.PeriodicVoiceMode.PROMPT
                    }
                    onRouteVoiceEnabled = intervalSeconds > 0L && onRouteVoiceMode != NavigationPreferences.PeriodicVoiceMode.OFF
                    NavigationPreferences.saveVoice(this@MainActivity, onRouteVoiceEnabled, intervalSeconds, onRouteVoiceMode)
                    renderVoiceSelections()
                    if (NavigationPreferences.state(this@MainActivity) != NavigationPreferences.STATE_IDLE) {
                        sendServiceAction(
                            TrailForegroundService.ACTION_UPDATE_ON_ROUTE_VOICE,
                            "ui",
                        )
                    }
                }
            }.also { button ->
                onRouteVoicePresets.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } to intervalSeconds
        }
        val modeChoices = listOf(
            NavigationPreferences.PeriodicVoiceMode.PROMPT,
            NavigationPreferences.PeriodicVoiceMode.TONE,
        )
        modeButtons = modeChoices.map { mode ->
            Button(this).apply {
                text = VoiceSelectionUi.modeLabel(mode)
                setOnClickListener {
                    if (onRouteVoiceIntervalSeconds > 0L) {
                        onRouteVoiceMode = mode
                        onRouteVoiceEnabled = true
                        NavigationPreferences.saveVoice(this@MainActivity, onRouteVoiceEnabled, onRouteVoiceIntervalSeconds, mode)
                    }
                    renderVoiceSelections()
                    if (NavigationPreferences.state(this@MainActivity) != NavigationPreferences.STATE_IDLE) {
                        sendServiceAction(TrailForegroundService.ACTION_UPDATE_ON_ROUTE_VOICE, "ui")
                    }
                }
            }.also { button ->
                onRouteVoiceModeControls.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } to mode
        }
        root.addView(header)
        root.addView(routeRibbon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (250 * resources.displayMetrics.density).toInt()))
        root.addView(import)
        root.addView(export)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(mapButton)
        root.addView(onRouteVoicePresets)
        root.addView(onRouteVoiceModeControls)
        navigationState = TextView(this).apply {
            text = "안내 대기 중"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(navigationState)
        root.addView(startButton)
        root.addView(pauseResumeButton)
        root.addView(endButton)
        setContentView(root)
        val horizontalPadding = dp(24f)
        val bottomPadding = dp(24f)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // The top inset is read from the current window rather than guessed
            // from a fixed status-bar height, so cutouts and foldable displays
            // receive the same safe placement.
            view.setPadding(horizontalPadding, safeInsets.top, horizontalPadding, bottomPadding + safeInsets.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        restoreUiState(savedInstanceState)
        restoreRouteCatalog()
        renderVoiceSelections()
        applyServiceState(NavigationPreferences.state(this))
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
        gpsIndicator.setTextColor(
            if (quality == "약함") 0xffb71c1c.toInt() else 0xff1b5e20.toInt(),
        )
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TrailForegroundService.ACTION_ROUTE_RIBBON_UPDATE).apply {
            addAction(TrailForegroundService.ACTION_ROUTE_PREPARATION_UPDATE)
            addAction(TrailForegroundService.ACTION_GPS_SIGNAL_UPDATE)
            addAction(TrailForegroundService.ACTION_SERVICE_STATE_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ribbonReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ribbonReceiver, filter)
        }
        sendServiceAction(TrailForegroundService.ACTION_QUERY_SERVICE_STATE, "ui")
    }

    override fun onStop() {
        unregisterReceiver(ribbonReceiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_ROUTE_URI, selectedRoute?.toString())
        outState.putString(KEY_SELECTED_ROUTE_SUMMARY, selectedRouteSummary)
        outState.putString(KEY_STATUS, status.text.toString())
        outState.putBoolean(KEY_ON_ROUTE_VOICE_ENABLED, onRouteVoiceEnabled)
        outState.putLong(KEY_ON_ROUTE_VOICE_INTERVAL_SECONDS, onRouteVoiceIntervalSeconds)
        outState.putString(KEY_ON_ROUTE_VOICE_MODE, onRouteVoiceMode.name)
        super.onSaveInstanceState(outState)
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            selectedRoute = savedInstanceState.getString(KEY_SELECTED_ROUTE_URI)?.let(Uri::parse)
            selectedRouteSummary = savedInstanceState.getString(KEY_SELECTED_ROUTE_SUMMARY)
            onRouteVoiceEnabled = savedInstanceState.getBoolean(KEY_ON_ROUTE_VOICE_ENABLED, false)
            onRouteVoiceIntervalSeconds = savedInstanceState.getLong(KEY_ON_ROUTE_VOICE_INTERVAL_SECONDS, 0L)
            onRouteVoiceMode = NavigationPreferences.PeriodicVoiceMode.fromWire(
                savedInstanceState.getString(KEY_ON_ROUTE_VOICE_MODE),
            )
            savedInstanceState.getString(KEY_STATUS)?.let { status.text = it }
        }
        val storedVoice = NavigationPreferences.voice(this)
        onRouteVoiceEnabled = storedVoice.enabled
        onRouteVoiceIntervalSeconds = storedVoice.intervalSeconds
        onRouteVoiceMode = storedVoice.mode
    }

    private fun restoreRouteCatalog() {
        savedRoutes.clear()
        savedRoutes.addAll(RouteCatalog.load(this))
        selectedSavedRoute = null
        routeListAdapter.selectedUri = null
        routeListAdapter.notifyDataSetChanged()
        val lastUri = RouteCatalog.lastSelectedUri(this)
        val restored = savedRoutes.firstOrNull { it.uri == lastUri }
            ?: selectedRoute?.let { uri -> savedRoutes.firstOrNull { it.uri == uri.toString() } }
        if (restored != null) selectSavedRoute(restored)
    }

    private fun selectSavedRoute(route: SavedRoute) {
        selectedSavedRoute = route
        selectedRoute = Uri.parse(route.uri)
        selectedRouteSummary = RouteCatalog.details(route)
        routeListAdapter.selectedUri = route.uri
        routeListAdapter.notifyDataSetChanged()
        RouteCatalog.save(this, savedRoutes, route.uri)
        val readable = RouteCatalog.isReadable(this, route)
        mapButton.isEnabled = readable
        if (readable) {
            status.text = "${route.displayName} 선택됨"
        } else {
            status.text = "이 경로의 파일 권한이 없습니다. 다시 가져오기 필요"
        }
    }

    private fun confirmRemoveSavedRoute(route: SavedRoute) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("목록에서 제거")
            .setMessage("${route.displayName}을(를) 목록에서 제거하시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("제거") { _, _ -> removeSavedRoute(route) }
            .show()
    }

    private fun removeSavedRoute(route: SavedRoute) {
        val remaining = savedRoutes.filterNot { it.uri == route.uri }
        savedRoutes.clear()
        savedRoutes.addAll(remaining)
        val replacement = remaining.maxByOrNull { it.addedAt }
        RouteCatalog.save(this, savedRoutes, replacement?.uri)
        if (selectedSavedRoute?.uri == route.uri) {
            routeListAdapter.selectedUri = replacement?.uri
        }
        routeListAdapter.notifyDataSetChanged()
        if (selectedSavedRoute?.uri == route.uri) {
            selectedSavedRoute = replacement
            selectedRoute = replacement?.let { Uri.parse(it.uri) }
            selectedRouteSummary = replacement?.let(RouteCatalog::details)
            mapButton.isEnabled = replacement?.let { RouteCatalog.isReadable(this, it) } == true
            status.text = if (replacement == null) "경로를 가져오세요" else "${replacement.displayName} 선택됨"
        }
    }

    private fun openSelectedRouteInMap() {
        val route = selectedSavedRoute ?: return
        val uri = selectedRoute ?: return
        if (!RouteCatalog.isReadable(this, route)) {
            status.text = "이 경로의 파일 권한이 없습니다. 다시 가져오기 필요"
            return
        }
        try {
            if (launchMapIntent(uri, isSend = false)) {
                return
            }
            val cachedUri = copyRouteToMapCache(route, uri)
            if (cachedUri != null) {
                if (launchMapIntent(cachedUri, isSend = false)) {
                    return
                }
                if (launchMapIntent(cachedUri, isSend = true)) {
                    return
                }
            }
            showMapUnavailable()
        } catch (_: android.content.ActivityNotFoundException) {
            showMapUnavailable()
        } catch (_: SecurityException) {
            showMapUnavailable()
        }
    }

    private fun launchMapIntent(uri: Uri, isSend: Boolean): Boolean {
        val exactType = "application/gpx+xml"
        val exactIntent = if (isSend) mapSendIntent(uri, exactType) else mapViewIntent(uri, exactType)
        val exactMatches = queryResolvedApps(exactIntent)
        val actualType = contentResolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val genericIntent = if (isSend) mapSendIntent(uri, actualType) else mapViewIntent(uri, actualType)
        val genericMatches = queryResolvedApps(genericIntent).filter(::looksLikeMapApp)
        val plan = chooseMapLaunchPlan(exactMatches, genericMatches)
        val launchType = if (exactMatches.isNotEmpty()) exactType else actualType
        val launchIntent = { packageName: String ->
            if (isSend) mapSendIntent(uri, launchType, packageName) else mapViewIntent(uri, launchType, packageName)
        }
        return when (plan) {
            MapLaunchPlan.None -> false
            is MapLaunchPlan.Direct -> {
                startActivity(launchIntent(plan.packageName))
                true
            }
            is MapLaunchPlan.Chooser -> {
                val primary = launchIntent(plan.primaryPackage)
                val alternatives = plan.alternativePackages.map(::launchIntent)
                startActivity(
                    Intent.createChooser(primary, "지도 앱 선택").apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, alternatives.toTypedArray())
                    },
                )
                true
            }
        }
    }

    private fun mapViewIntent(uri: Uri, type: String, packageName: String? = null): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = uri
        this.type = type
        packageName?.let(::setPackage)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri("", uri)
    }

    private fun mapSendIntent(uri: Uri, type: String, packageName: String? = null): Intent = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        packageName?.let(::setPackage)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri("", uri)
    }

    private fun queryResolvedApps(intent: Intent): List<ResolvedApp> = packageManager
        .queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            ResolvedApp(packageName, info.loadLabel(packageManager)?.toString().orEmpty())
        }

    private fun showMapUnavailable() {
        val message = "GPX를 열 수 있는 지도 앱이 없습니다"
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun copyRouteToMapCache(route: SavedRoute, uri: Uri): Uri? {
        return try {
            // Keep the provider surface limited to cache/exports, shared with
            // the session ZIP exporter. The raw SAF URI is always attempted first.
            val shared = java.io.File(cacheDir, "exports/map").apply { mkdirs() }
            val safeName = route.displayName.substringAfterLast('/').ifBlank { "route.gpx" }
            val target = java.io.File(shared, safeName)
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", target)
        } catch (_: Exception) {
            null
        }
    }

    private fun renderVoiceSelections() {
        intervalButtons.forEach { (button, intervalSeconds) ->
            val selected = VoiceSelectionUi.intervalSelected(onRouteVoiceIntervalSeconds, intervalSeconds)
            applyButtonSelection(button, selected, button.text.toString().removePrefix("✓ "))
        }
        modeButtons.forEach { (button, mode) ->
            val selected = VoiceSelectionUi.modeSelected(onRouteVoiceEnabled, onRouteVoiceMode, mode)
            applyButtonSelection(button, selected, VoiceSelectionUi.modeLabel(mode))
        }
    }

    private fun applyButtonSelection(button: Button, selected: Boolean, label: String) {
        button.text = if (selected) "✓ $label" else label
        button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        button.setBackgroundColor(if (selected) 0xff2962ff.toInt() else Color.TRANSPARENT)
        button.setTextColor(if (selected) Color.WHITE else Color.DKGRAY)
        button.contentDescription = if (selected) "$label 선택됨" else label
    }

    private fun applyServiceState(state: String) {
        when (state) {
            NavigationPreferences.STATE_RUNNING -> {
                navigationState.text = "안내 중"
                startButton.isEnabled = false
                pauseResumeButton.visibility = android.view.View.VISIBLE
                pauseResumeButton.text = "안내 일시중지"
                endButton.visibility = android.view.View.VISIBLE
            }
            NavigationPreferences.STATE_PAUSED -> {
                navigationState.text = "안내 일시중지 중"
                startButton.isEnabled = false
                pauseResumeButton.visibility = android.view.View.VISIBLE
                pauseResumeButton.text = "안내 재개"
                endButton.visibility = android.view.View.VISIBLE
            }
            else -> {
                navigationState.text = "안내 대기 중"
                startButton.isEnabled = true
                pauseResumeButton.visibility = android.view.View.GONE
                endButton.visibility = android.view.View.GONE
            }
        }
        val storedVoice = NavigationPreferences.voice(this)
        onRouteVoiceEnabled = storedVoice.enabled
        onRouteVoiceIntervalSeconds = storedVoice.intervalSeconds
        onRouteVoiceMode = storedVoice.mode
        renderVoiceSelections()
    }

    private fun sendServiceAction(action: String, source: String) {
        startService(
            Intent(this, TrailForegroundService::class.java)
                .setAction(action)
                .putExtra(TrailForegroundService.EXTRA_ACTION_SOURCE, source),
        )
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
        val savedRoute = selectedSavedRoute
        if (savedRoute == null || !RouteCatalog.isReadable(this, savedRoute)) {
            status.text = "이 경로의 파일 권한이 없습니다. 다시 가져오기 필요"
            mapButton.isEnabled = false
            return
        }
        routeRibbon.updatePreparation(RoutePreparationStage.PREPARING)
        val voice = NavigationPreferences.voice(this)
        val intent = Intent(this, TrailForegroundService::class.java).putExtra(
            TrailForegroundService.EXTRA_ROUTE_URI,
            route.toString(),
        ).putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_ENABLED, voice.enabled)
            .putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, voice.intervalSeconds)
            .putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_MODE, voice.mode.name)
        ContextCompat.startForegroundService(this, intent)
        status.text = "안내 서비스를 시작했습니다"
    }

    companion object {
        private const val KEY_SELECTED_ROUTE_URI = "selected_route_uri"
        private const val KEY_SELECTED_ROUTE_SUMMARY = "selected_route_summary"
        private const val KEY_STATUS = "status"
        private const val KEY_ON_ROUTE_VOICE_ENABLED = "on_route_voice_enabled"
        private const val KEY_ON_ROUTE_VOICE_INTERVAL_SECONDS = "on_route_voice_interval_seconds"
        private const val KEY_ON_ROUTE_VOICE_MODE = "on_route_voice_mode"
        // Drive and local document providers frequently expose GPX as
        // application/octet-stream or omit a MIME type. The content is still
        // validated as GPX after selection, so the picker can safely be broad.
        private val ROUTE_PICKER_MIME_TYPES = arrayOf("*/*")
    }
}
