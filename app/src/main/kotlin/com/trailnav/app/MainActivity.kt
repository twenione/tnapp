package com.trailnav.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Minimal route list/import screen for Phase 2; maps and turn previews are later phases. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var routeListAdapter: RouteListAdapter
    private val savedRoutes = mutableListOf<SavedRoute>()
    private lateinit var startButton: Button
    private lateinit var voiceControls: OnRouteVoiceControls
    private var selectedRoute: Uri? = null
    private var selectedSavedRoute: SavedRoute? = null
    private var pendingGuidePreparation = false
    private var guideLaunchRequested = false
    private var startFlowInProgress = false
    private var startFlowAwaitingExternalResult = false
    private var startFlowAwaitingSettingsCheck = false
    private var startModeDialog: AlertDialog? = null

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != TrailForegroundService.ACTION_SERVICE_STATE_UPDATE) return
            val state = intent.getStringExtra(TrailForegroundService.EXTRA_SERVICE_STATE)
                ?: NavigationPreferences.STATE_IDLE
            if (screenFor(state) == AppScreen.GUIDE) {
                launchGuideActivity()
            } else {
                guideLaunchRequested = false
                refreshStartButtonEnabled()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startFlowAwaitingExternalResult = false
            startFlowInProgress = true
            checkLocationSettings()
        } else {
            finishStartFlow(getString(R.string.main_location_permission_needed))
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        startFlowAwaitingExternalResult = false
        startFlowInProgress = true
        if (result.resultCode == Activity.RESULT_OK) {
            chooseStartMode()
        } else {
            finishStartFlow(getString(R.string.start_location_off))
        }
    }

    private val openGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            var importWarning: RouteImportWarning? = null
            val imported = contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val originalFileName = contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
                val importedGpx = GpxImport.importGpx(
                    content = bytes.toString(Charsets.UTF_8),
                    fileName = originalFileName,
                    fallbackFileName = uri.lastPathSegment,
                    fallbackName = getString(R.string.imported_route_fallback_name),
                )
                val route = importedGpx.route
                importWarning = RouteImportWarning.from(route.quality, route.totalLengthMeters)
                SavedRoute(
                    uri = uri.toString(),
                    displayName = importedGpx.displayName,
                    sha256 = hash,
                    lengthMeters = route.totalLengthMeters,
                    addedAt = System.currentTimeMillis(),
                    elevationReason = route.elevationReason,
                    waypointCount = route.waypoints.size,
                    wideGapCount = route.quality.gapOver100Count,
                    originalFileName = originalFileName,
                    closed = importedGpx.closed,
                )
            } ?: throw IllegalStateException(getString(R.string.main_gpx_read_failed))
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers do not offer persistable grants; the current session can still use the URI.
            }
            val selected = RouteCatalog.upsert(savedRoutes, imported)
            RouteCatalog.save(this, savedRoutes, selected.uri)
            selectSavedRoute(selected)
            routeListAdapter.notifyDataSetChanged()
            importWarning?.let { warning ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(warning.title)
                    .setMessage(warning.message)
                    .setPositiveButton(warning.button, null)
                    .show()
            }
        } catch (_: Exception) {
            // The wildcard picker can show provider files whose MIME type is
            // generic or unknown. Keep the picker broad, then reject invalid
            // content here without crashing or replacing the current route.
            status.text = getString(R.string.main_route_file_read_failed)
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
        status = TextView(this).apply { text = getString(R.string.main_import_route_prompt) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.main_home_title)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = getString(R.string.main_home_export)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_recent_history, 0, 0, 0)
            compoundDrawablePadding = dp(4f)
            minWidth = dp(48f)
            minHeight = dp(48f)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SessionExportActivity::class.java))
            }
        })
        header.addView(Button(this).apply {
            text = getString(R.string.main_home_settings)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_preferences, 0, 0, 0)
            compoundDrawablePadding = dp(4f)
            minWidth = dp(48f)
            minHeight = dp(48f)
            setOnClickListener { showEventSettingsDialog() }
        })
        val importButton = Button(this).apply {
            text = getString(R.string.main_import_gpx)
            minHeight = dp(48f)
            setOnClickListener { openGpx.launch(ROUTE_PICKER_MIME_TYPES) }
        }
        startButton = Button(this).apply {
            text = getString(R.string.main_start_guidance)
            minHeight = dp(60f)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xff1f6b45.toInt())
            setTextColor(Color.WHITE)
            isEnabled = false
            setOnClickListener { ensurePermissionAndStart() }
        }
        routeListAdapter = RouteListAdapter(this, savedRoutes).apply {
            onRouteClick = ::selectSavedRoute
            onMenuClick = { anchor, route -> showRouteMenu(anchor, route) }
        }
        val emptyState = TextView(this).apply {
            text = getString(R.string.main_import_route_prompt)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val list = ListView(this).apply {
            adapter = routeListAdapter
            dividerHeight = dp(6f)
            clipToPadding = false
            setPadding(0, dp(4f), 0, dp(4f))
            emptyView = emptyState
        }
        val listContainer = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(emptyState, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        voiceControls = OnRouteVoiceControls(
            context = this,
            isGuidanceActive = { NavigationPreferences.state(this) != NavigationPreferences.STATE_IDLE },
            onActiveSelectionChanged = { sendServiceAction(TrailForegroundService.ACTION_UPDATE_ON_ROUTE_VOICE, "ui") },
        )
        root.addView(header)
        root.addView(status)
        root.addView(listContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(voiceControls.view)
        root.addView(importButton)
        root.addView(startButton)
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
        voiceControls.render()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TrailForegroundService.ACTION_SERVICE_STATE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceStateReceiver, filter)
        }
        if (screenFor(NavigationPreferences.state(this)) == AppScreen.GUIDE) launchGuideActivity()
        else refreshStartButtonEnabled()
        voiceControls.render()
        sendServiceAction(TrailForegroundService.ACTION_QUERY_SERVICE_STATE, "ui")
    }

    override fun onStop() {
        unregisterReceiver(serviceStateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        guideLaunchRequested = false
        if (startFlowInProgress &&
            !startFlowAwaitingExternalResult &&
            !startFlowAwaitingSettingsCheck &&
            startModeDialog == null
        ) {
            finishStartFlow()
        }
        refreshStartButtonEnabled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_ROUTE_URI, selectedRoute?.toString())
        outState.putString(KEY_STATUS, status.text.toString())
        outState.putBoolean(KEY_PENDING_GUIDE_PREPARATION, pendingGuidePreparation)
        super.onSaveInstanceState(outState)
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            selectedRoute = savedInstanceState.getString(KEY_SELECTED_ROUTE_URI)?.let(Uri::parse)
            savedInstanceState.getString(KEY_STATUS)?.let { status.text = it }
            pendingGuidePreparation = savedInstanceState.getBoolean(KEY_PENDING_GUIDE_PREPARATION, false)
        }
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
        else refreshStartButtonEnabled()
    }

    private fun selectSavedRoute(route: SavedRoute) {
        selectedSavedRoute = route
        selectedRoute = Uri.parse(route.uri)
        routeListAdapter.selectedUri = route.uri
        routeListAdapter.notifyDataSetChanged()
        RouteCatalog.save(this, savedRoutes, route.uri)
        if (!RouteCatalog.isReadable(this, route)) {
            status.text = getString(R.string.saved_route_permission_missing)
        } else {
            status.text = ""
        }
        refreshStartButtonEnabled()
    }

    private fun confirmRemoveSavedRoute(route: SavedRoute) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.saved_route_remove_title))
            .setMessage(getString(R.string.saved_route_remove_confirmation, route.displayName))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_remove)) { _, _ -> removeSavedRoute(route) }
            .show()
    }

    private fun removeSavedRoute(route: SavedRoute) {
        val removedSelectedRoute = selectedSavedRoute?.uri == route.uri
        val remaining = savedRoutes.filterNot { it.uri == route.uri }
        savedRoutes.clear()
        savedRoutes.addAll(remaining)
        val replacement = if (removedSelectedRoute) remaining.maxByOrNull { it.addedAt } else selectedSavedRoute
        RouteCatalog.save(this, savedRoutes, replacement?.uri)
        if (removedSelectedRoute) {
            routeListAdapter.selectedUri = replacement?.uri
        }
        routeListAdapter.notifyDataSetChanged()
        if (removedSelectedRoute) {
            selectedSavedRoute = replacement
            selectedRoute = replacement?.let { Uri.parse(it.uri) }
            when {
                replacement == null -> status.text = getString(R.string.main_import_route_prompt)
                !RouteCatalog.isReadable(this, replacement) -> status.text = getString(R.string.saved_route_permission_missing)
            }
        }
        refreshStartButtonEnabled()
    }

    private fun refreshStartButtonEnabled() {
        startButton.isEnabled = selectedSavedRoute != null &&
            NavigationPreferences.state(this) == NavigationPreferences.STATE_IDLE &&
            !guideLaunchRequested &&
            !startFlowInProgress
    }

    private fun showRouteMenu(anchor: View, route: SavedRoute) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(ROUTE_MENU_GROUP_ACTIONS, ROUTE_MENU_MAP, 0, getString(R.string.main_view_map))
        popup.menu.add(ROUTE_MENU_GROUP_ACTIONS, ROUTE_MENU_DETAILS, 1, getString(R.string.route_menu_details))
        popup.menu.add(ROUTE_MENU_GROUP_ACTIONS, ROUTE_MENU_RENAME, 2, getString(R.string.route_rename))
        popup.menu.add(ROUTE_MENU_GROUP_DELETE, ROUTE_MENU_DELETE, 3, getString(R.string.route_menu_delete))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) popup.menu.setGroupDividerEnabled(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ROUTE_MENU_MAP -> openRouteInMap(route)
                ROUTE_MENU_DETAILS -> showRouteDetails(route)
                ROUTE_MENU_RENAME -> showRouteRenameDialog(route)
                ROUTE_MENU_DELETE -> confirmRemoveSavedRoute(route)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun showRouteRenameDialog(route: SavedRoute) {
        val input = EditText(this).apply {
            setSingleLine(true)
            filters = arrayOf(RouteNameLengthFilter(RouteNameRules.MAX_LENGTH))
            setText(route.displayName)
            setSelection(text.length)
        }
        val inputContainer = FrameLayout(this).apply {
            val horizontal = dp(24f)
            val vertical = dp(8f)
            setPadding(horizontal, vertical, horizontal, 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.route_rename))
            .setView(inputContainer)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_save), null)
            .create()
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            saveButton.minimumHeight = dp(48f)
            saveButton.isEnabled = input.text.toString().trim().isNotEmpty()
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) {
                    saveButton.isEnabled = !value.isNullOrBlank()
                }
            })
            saveButton.setOnClickListener {
                val renamed = RouteCatalog.rename(savedRoutes, route.uri, input.text.toString()) ?: return@setOnClickListener
                val index = savedRoutes.indexOfFirst { it.uri == route.uri }
                if (index < 0) return@setOnClickListener
                savedRoutes[index] = renamed
                if (selectedSavedRoute?.uri == route.uri) selectedSavedRoute = renamed
                RouteCatalog.save(this, savedRoutes, RouteCatalog.lastSelectedUri(this))
                routeListAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showRouteDetails(route: SavedRoute) {
        val currentRoute = routeWithClosedValue(route)
        val presentation = RouteDetailsFormatter.present(currentRoute)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        }
        presentation.rows.forEach { detailRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f), 0, dp(8f))
            }
            row.addView(TextView(this).apply {
                text = getString(detailRow.labelResourceId)
                textSize = 16f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = when (val value = detailRow.value) {
                    is RouteDetailValue.Resource -> getString(value.resourceId, *value.arguments.toTypedArray())
                    is RouteDetailValue.Literal -> value.text
                }
                textSize = 16f
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row)
        }
        if (presentation.warnings.isNotEmpty()) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(0xfffff7ed.toInt())
                }
                presentation.warnings.forEach { warning ->
                    addView(TextView(this@MainActivity).apply {
                        text = getString(warning.resourceId, *warning.arguments.toTypedArray())
                        textSize = 16f
                        setTextColor(0xffc2410c.toInt())
                    })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(presentation.title)
            .setView(scroll)
            .setNegativeButton(getString(R.string.route_detail_close), null)
            .setPositiveButton(getString(R.string.main_view_map)) { _, _ -> openRouteInMap(currentRoute) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).minimumHeight = dp(48f)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).minimumHeight = dp(48f)
            dialog.window?.apply {
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
    }

    private fun routeWithClosedValue(route: SavedRoute): SavedRoute {
        if (route.closed != null) return route
        val closed = runCatching {
            contentResolver.openInputStream(Uri.parse(route.uri))?.use { stream ->
                val xml = stream.readBytes().toString(Charsets.UTF_8)
                com.trailnav.core.isClosedRoute(com.trailnav.core.RouteModel.fromGpx(xml))
            }
        }.getOrNull() ?: return route
        val updated = route.copy(closed = closed)
        val index = savedRoutes.indexOfFirst { it.uri == route.uri }
        if (index >= 0) {
            savedRoutes[index] = updated
            if (selectedSavedRoute?.uri == route.uri) selectedSavedRoute = updated
            RouteCatalog.save(this, savedRoutes, RouteCatalog.lastSelectedUri(this))
            routeListAdapter.notifyDataSetChanged()
        }
        return updated
    }

    private data class MapUriSource(
        val kind: MapUriKind,
        val uri: Uri,
    )

    private data class MapDiscovery(
        val merge: MapMergeResult,
        val counts: MapQueryCounts,
        val sources: List<MapUriSource>,
    )

    private fun openRouteInMap(route: SavedRoute) {
        val uri = Uri.parse(route.uri)
        if (!RouteCatalog.isReadable(this, route)) {
            status.text = getString(R.string.saved_route_permission_missing)
            return
        }

        // Make the provider-backed copy before discovery. Some map apps only
        // advertise support for a filename with a .gpx extension.
        val cachedUri = copyRouteToMapCache(route, uri)
        val sources = buildList {
            add(MapUriSource(MapUriKind.ORIGINAL, uri))
            cachedUri?.let { copy ->
                if (copy.toString() != uri.toString()) add(MapUriSource(MapUriKind.COPY, copy))
            }
        }
        val discovery = discoverMapCandidates(sources)
        val diagnostics = MapCandidateDiagnostics(
            candidates = discovery.merge.candidates.map { it.label },
            excludedLabels = discovery.merge.excludedLabels,
        )
        val discoveryLine = mapFallbackDiagnostic(
            discovery.counts,
            FallbackOutcome(null, 0, 0, 0),
            diagnostics,
        )

        if (requiresUserChoice(discovery.merge.candidates)) {
            status.text = discoveryLine
            showMapChoiceDialog(discovery, diagnostics)
        } else {
            runMapFallback(discovery, diagnostics)
        }
    }

    private fun discoverMapCandidates(sources: List<MapUriSource>): MapDiscovery {
        val observations = mutableListOf<MapObservation>()
        var counts = MapQueryCounts()
        sources.forEach { source ->
            val actualType = runCatching { contentResolver.getType(source.uri) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
            mapIntentShapes(actualType).forEach { shape ->
                val rawApps = runCatching {
                    queryResolvedApps(mapViewIntent(source.uri, shape.mimeType))
                }.getOrDefault(emptyList())
                val filteredCount = rawApps.count(::looksLikeMapApp)
                counts = when (source.kind to shape.kind) {
                    MapUriKind.ORIGINAL to MapIntentShapeKind.UNTYPED -> counts.copy(
                        originalUntypedRaw = rawApps.size,
                        originalUntypedFiltered = filteredCount,
                    )
                    MapUriKind.ORIGINAL to MapIntentShapeKind.EXACT -> counts.copy(
                        originalExactRaw = rawApps.size,
                        originalExactFiltered = filteredCount,
                    )
                    MapUriKind.ORIGINAL to MapIntentShapeKind.ACTUAL -> counts.copy(
                        originalActualRaw = rawApps.size,
                        originalActualFiltered = filteredCount,
                    )
                    MapUriKind.COPY to MapIntentShapeKind.UNTYPED -> counts.copy(
                        copyUntypedRaw = rawApps.size,
                        copyUntypedFiltered = filteredCount,
                    )
                    MapUriKind.COPY to MapIntentShapeKind.EXACT -> counts.copy(
                        copyExactRaw = rawApps.size,
                        copyExactFiltered = filteredCount,
                    )
                    MapUriKind.COPY to MapIntentShapeKind.ACTUAL -> counts.copy(
                        copyActualRaw = rawApps.size,
                        copyActualFiltered = filteredCount,
                    )
                    else -> counts
                }
                observations += MapObservation(source.kind, shape, rawApps)
            }
        }
        return MapDiscovery(mergeMapCandidates(observations), counts, sources)
    }

    private fun showMapChoiceDialog(discovery: MapDiscovery, diagnostics: MapCandidateDiagnostics) {
        if (isFinishing || isDestroyed) return
        val candidates = discovery.merge.candidates
        val labels = mapChoiceLabels(candidates)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.map_app_picker_title))
            .setItems(labels.toTypedArray()) { _, which ->
                when (val selection = mapChoiceAt(candidates, which)) {
                    is MapChoiceSelection.Candidate -> launchMapCandidate(selection.candidate, discovery.sources)
                    MapChoiceSelection.OtherApps -> runMapFallback(discovery, diagnostics)
                    null -> Unit
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setCancelable(true)
            .show()
    }

    private fun launchMapCandidate(candidate: MapCandidate, sources: List<MapUriSource>) {
        val sourceByKind = sources.associateBy { it.kind }
        val outcome = runCandidateAttempts(candidate) { combo ->
            val source = sourceByKind[combo.uriKind]
                ?: return@runCandidateAttempts CandidateAttemptResult.NOT_FOUND
            try {
                startActivity(mapViewIntent(source.uri, combo.shape.mimeType, candidate.packageName))
                CandidateAttemptResult.SUCCESS
            } catch (_: android.content.ActivityNotFoundException) {
                CandidateAttemptResult.NOT_FOUND
            } catch (_: SecurityException) {
                CandidateAttemptResult.SECURITY_REJECTED
            }
        }
        val success = outcome.successCombo
        if (success != null) {
            status.text = getString(R.string.map_open_success, candidate.label, success.uriKind.label, success.shape.label)
        } else {
            val message = getString(R.string.map_open_failure, candidate.label, outcome.lastReason)
            status.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun runMapFallback(discovery: MapDiscovery, diagnostics: MapCandidateDiagnostics) {
        val fallbackSources = discovery.sources.sortedByDescending { it.kind.priority }
        val actualType = fallbackSources.firstOrNull()?.let { source ->
            runCatching { contentResolver.getType(source.uri) }.getOrNull()
        }
        val shapes = fallbackIntentShapes(actualType)
        val outcome = runFallbackAttempts(fallbackSources.map { it.uri.toString() }, shapes) { uriKey, shape ->
            val intent = mapViewIntent(Uri.parse(uriKey), shape.mimeType)
            try {
                startActivity(intent)
                FallbackAttemptResult.SUCCESS
            } catch (_: android.content.ActivityNotFoundException) {
                FallbackAttemptResult.NOT_FOUND
            } catch (_: SecurityException) {
                FallbackAttemptResult.SECURITY_REJECTED
            }
        }
        outcome.successShape?.let { shape ->
            status.text = getString(R.string.map_fallback_success, shape.label)
            return
        }
        showMapUnavailable(mapFallbackDiagnostic(discovery.counts, outcome, diagnostics))
    }

    private fun mapViewIntent(uri: Uri, type: String?, packageName: String? = null): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = uri
        if (type != null) this.type = type
        if (packageName != null) setPackage(packageName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri("", uri)
    }

    private fun queryResolvedApps(intent: Intent): List<ResolvedApp> = packageManager
        .queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            ResolvedApp(packageName, info.loadLabel(packageManager)?.toString().orEmpty())
        }

    private fun showMapUnavailable(diagnostic: String? = null) {
        val message = getString(R.string.map_app_unavailable)
        status.text = if (diagnostic.isNullOrBlank()) message else "$message\n$diagnostic"
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

    private fun showEventSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val currentSettings = NavigationPreferences.eventSettings(this)
        val checked = EventSettingsUi.checkedItems(currentSettings)
        val onDemandEnabled = CheckBox(this).apply {
            text = getString(R.string.shake_route_status)
            isChecked = NavigationPreferences.onDemandEnabled(this@MainActivity)
        }
        val responseModes = DistanceTimeMode.values()
        val responseModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            responseModes.forEach { mode ->
                val label = when (mode) {
                    DistanceTimeMode.Both -> getString(R.string.distance_and_time)
                    DistanceTimeMode.DistanceOnly -> getString(R.string.distance_only)
                    DistanceTimeMode.TimeOnly -> getString(R.string.time_only)
                }
                addView(RadioButton(this@MainActivity).apply {
                    id = mode.ordinal + 1
                    text = label
                })
            }
            check(NavigationPreferences.onDemandResponseMode(this@MainActivity).ordinal + 1)
        }
        val eventCheckBoxes = EventSettingsUi.labels { getString(it) }.mapIndexed { index, label ->
            CheckBox(this).apply {
                text = label
                isChecked = checked[index]
            }
        }
        val tapCounter = TapCounter(target = 7)
        var settingsDialog: androidx.appcompat.app.AlertDialog? = null
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).roundToInt(), 0, (20 * density).roundToInt(), 0)
            addView(onDemandEnabled)
            addView(responseModeGroup)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.event_settings_heading)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, (8 * density).roundToInt(), 0, (4 * density).roundToInt())
            })
            eventCheckBoxes.forEach { addView(it) }
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_about_version, BuildConfig.VERSION_NAME)
                setPadding((8 * density).roundToInt(), (12 * density).roundToInt(), (8 * density).roundToInt(), (8 * density).roundToInt())
                setOnClickListener {
                    if (tapCounter.tap()) {
                        startActivity(Intent(this@MainActivity, DeveloperActivity::class.java))
                        settingsDialog?.dismiss()
                    }
                }
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_guidance_settings))
            .setView(scroll)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setNeutralButton(getString(R.string.settings_reset_defaults), null)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                eventCheckBoxes.forEachIndexed { index, checkBox -> checked[index] = checkBox.isChecked }
                NavigationSettingsUi.resolveDialogSelection(
                    eventChecked = checked,
                    onDemandEnabled = onDemandEnabled.isChecked,
                    imuCollectEnabled = NavigationPreferences.imuCollectEnabled(this),
                    accepted = true,
                    onDemandResponseMode = responseModes.firstOrNull {
                        it.ordinal + 1 == responseModeGroup.checkedRadioButtonId
                    } ?: DistanceTimeMode.Both,
                )?.let { selection ->
                    NavigationPreferences.saveEventSettings(this, selection.eventSettings)
                    NavigationPreferences.saveOnDemandEnabled(this, selection.onDemandEnabled)
                    NavigationPreferences.saveImuCollectEnabled(this, selection.imuCollectEnabled)
                    NavigationPreferences.saveOnDemandResponseMode(this, selection.onDemandResponseMode)
                    status.text = getString(R.string.settings_saved)
                }
            }
            .setCancelable(true)
            .create()
        settingsDialog = dialog
        dialog.setOnDismissListener { tapCounter.reset() }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val defaults = NavigationSettingsUi.resetToDefaults(checked)
                onDemandEnabled.isChecked = defaults.onDemandEnabled
                responseModeGroup.check(defaults.onDemandResponseMode.ordinal + 1)
                eventCheckBoxes.forEachIndexed { index, checkBox -> checkBox.isChecked = checked[index] }
            }
        }
        dialog.show()
    }

    private fun sendServiceAction(action: String, source: String) {
        startService(
            Intent(this, TrailForegroundService::class.java)
                .setAction(action)
                .putExtra(TrailForegroundService.EXTRA_ACTION_SOURCE, source),
        )
    }

    private fun launchGuideActivity() {
        if (guideLaunchRequested || isFinishing || isDestroyed) return
        guideLaunchRequested = true
        startButton.isEnabled = false
        startActivity(Intent(this, GuideActivity::class.java).putExtra(
            GuideActivity.EXTRA_START_WITH_PREPARING,
            pendingGuidePreparation,
        ))
        pendingGuidePreparation = false
    }

    private fun ensurePermissionAndStart() {
        if (startFlowInProgress) return
        startFlowInProgress = true
        refreshStartButtonEnabled()
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (locationGranted && notificationGranted) {
            checkLocationSettings()
        } else {
            startFlowAwaitingExternalResult = true
            try {
                permissionLauncher.launch(
                    buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray()
                )
            } catch (_: Exception) {
                finishStartFlow()
            }
        }
    }

    private fun checkLocationSettings() {
        if (!canContinueStartFlow()) return
        startFlowAwaitingSettingsCheck = true
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()
        try {
            LocationServices.getSettingsClient(this)
                .checkLocationSettings(request)
                .addOnSuccessListener(this) {
                    startFlowAwaitingSettingsCheck = false
                    if (canContinueStartFlow()) handleLocationGate(settingsOk = true)
                }
                .addOnFailureListener(this) { exception ->
                    startFlowAwaitingSettingsCheck = false
                    if (canContinueStartFlow()) handleLocationSettingsFailure(exception)
                }
        } catch (exception: Exception) {
            startFlowAwaitingSettingsCheck = false
            if (canContinueStartFlow()) handleLocationSettingsFailure(exception)
        }
    }

    private fun handleLocationSettingsFailure(exception: Exception) {
        val resolution = (exception as? ResolvableApiException)
            ?.takeIf { it.statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED }
        handleLocationGate(settingsOk = false, resolution = resolution)
    }

    private fun handleLocationGate(settingsOk: Boolean, resolution: ResolvableApiException? = null) {
        when (
            LocationGate.decide(
                settingsOk = settingsOk,
                resolvable = resolution != null,
                serviceEnabled = isLocationServiceEnabled(),
            )
        ) {
            LocationGateAction.Proceed -> chooseStartMode()
            LocationGateAction.PromptUser -> requestLocationResolution(requireNotNull(resolution))
            LocationGateAction.Blocked -> finishStartFlow(getString(R.string.start_location_off))
        }
    }

    private fun requestLocationResolution(exception: ResolvableApiException) {
        if (!canContinueStartFlow()) return
        startFlowAwaitingExternalResult = true
        try {
            locationSettingsLauncher.launch(
                IntentSenderRequest.Builder(exception.resolution).build(),
            )
        } catch (_: Exception) {
            startFlowAwaitingExternalResult = false
            if (canContinueStartFlow()) handleLocationGate(settingsOk = false)
        }
    }

    private fun isLocationServiceEnabled(): Boolean = runCatching {
        getSystemService(LocationManager::class.java)?.let { manager ->
            LocationManagerCompat.isLocationEnabled(manager)
        } ?: false
    }.getOrDefault(false)

    private fun canContinueStartFlow(): Boolean = startFlowInProgress && !isFinishing && !isDestroyed

    private fun chooseStartMode() {
        if (!canContinueStartFlow()) return
        val route = selectedSavedRoute
        if (route == null) {
            startNavigation(roundTripEnabled = false)
            return
        }
        when (val action = StartModeGate.decide(routeWithClosedValue(route).closed)) {
            is StartModeAction.StartDirectly -> startNavigation(action.roundTrip)
            StartModeAction.AskUser -> showStartModeDialog()
        }
    }

    private fun showStartModeDialog() {
        if (!canContinueStartFlow() || isFinishing || isDestroyed) return
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(12f))
        }
        content.addView(Button(this).apply {
            text = getString(R.string.start_mode_one_way)
            minHeight = dp(60f)
            setOnClickListener {
                startNavigation(roundTripEnabled = false)
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60f)).apply {
            bottomMargin = dp(8f)
        })
        content.addView(Button(this).apply {
            text = getString(R.string.start_mode_round_trip)
            minHeight = dp(60f)
            setOnClickListener {
                startNavigation(roundTripEnabled = true)
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60f)).apply {
            bottomMargin = dp(8f)
        })
        content.addView(Button(this).apply {
            text = getString(R.string.common_cancel)
            minHeight = dp(48f)
            setOnClickListener { dialog.cancel() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))

        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.start_mode_title))
            .setView(content)
            .create()
        startModeDialog = dialog
        dialog.setOnCancelListener { finishStartFlow() }
        dialog.setOnDismissListener {
            if (startModeDialog === dialog) startModeDialog = null
            if (startFlowInProgress) finishStartFlow()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
        dialog.setCanceledOnTouchOutside(true)
    }

    private fun finishStartFlow(message: String? = null) {
        startFlowInProgress = false
        startFlowAwaitingExternalResult = false
        startFlowAwaitingSettingsCheck = false
        if (message != null) status.text = message
        refreshStartButtonEnabled()
    }

    private fun startNavigation(roundTripEnabled: Boolean) {
        val route = selectedRoute
        if (route == null) {
            finishStartFlow(getString(R.string.main_import_route_first))
            return
        }
        val savedRoute = selectedSavedRoute
        if (savedRoute == null || !RouteCatalog.isReadable(this, savedRoute)) {
            finishStartFlow(getString(R.string.saved_route_permission_missing))
            return
        }
        val voice = NavigationPreferences.voice(this)
        val intent = Intent(this, TrailForegroundService::class.java).putExtra(
            TrailForegroundService.EXTRA_ROUTE_URI,
            route.toString(),
        ).putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_ENABLED, voice.enabled)
            .putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_INTERVAL_SECONDS, voice.intervalSeconds)
            .putExtra(TrailForegroundService.EXTRA_ON_ROUTE_VOICE_MODE, voice.mode.name)
        pendingGuidePreparation = true
        startFlowInProgress = false
        startFlowAwaitingExternalResult = false
        startFlowAwaitingSettingsCheck = false
        NavigationPreferences.saveRoundTripEnabled(this, roundTripEnabled)
        ContextCompat.startForegroundService(this, intent)
        status.text = getString(R.string.guidance_service_started)
    }

    companion object {
        private const val KEY_SELECTED_ROUTE_URI = "selected_route_uri"
        private const val KEY_STATUS = "status"
        private const val KEY_PENDING_GUIDE_PREPARATION = "pending_guide_preparation"
        private const val ROUTE_MENU_GROUP_ACTIONS = 1
        private const val ROUTE_MENU_GROUP_DELETE = 2
        private const val ROUTE_MENU_MAP = 101
        private const val ROUTE_MENU_DETAILS = 102
        private const val ROUTE_MENU_RENAME = 103
        private const val ROUTE_MENU_DELETE = 104
        // Drive and local document providers frequently expose GPX as
        // application/octet-stream or omit a MIME type. The content is still
        // validated as GPX after selection, so the picker can safely be broad.
        private val ROUTE_PICKER_MIME_TYPES = arrayOf("*/*")
    }
}
