package com.trailnav.app

import android.content.Context
import android.net.Uri

/** Persistent metadata for a user-selected GPX route. */
internal data class SavedRoute(
    val uri: String,
    val displayName: String,
    val sha256: String,
    val lengthMeters: Double,
    val addedAt: Long,
    val elevationReason: String? = null,
    val waypointCount: Int? = null,
    val wideGapCount: Int? = null,
    val originalFileName: String? = null,
    val closed: Boolean? = null,
)

internal object RouteCatalog {
    private const val PREFS = "trailnav.routes"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST_SELECTED = "last_selected_uri"

    fun load(context: Context): MutableList<SavedRoute> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).mapNotNull { index ->
            decodeStoredRoute(
                mapOf(
                    "uri" to prefs.getString(key(index, "uri"), null),
                    "name" to prefs.getString(key(index, "name"), "GPX 경로"),
                    "sha256" to prefs.getString(key(index, "sha256"), ""),
                    "length" to prefs.getString(key(index, "length"), "0"),
                    "addedAt" to prefs.getLong(key(index, "addedAt"), 0L),
                    "elevReason" to prefs.getString(key(index, "elevReason"), null),
                    "wptCount" to prefs.getString(key(index, "wptCount"), null),
                    "gap100" to prefs.getString(key(index, "gap100"), null),
                    "origName" to prefs.getString(key(index, "origName"), null),
                    "closed" to if (prefs.contains(key(index, "closed"))) {
                        prefs.getBoolean(key(index, "closed"), false)
                    } else {
                        null
                    },
                ),
            )
        }.toMutableList()
    }

    fun save(context: Context, routes: List<SavedRoute>, lastSelectedUri: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        editor.putInt(KEY_COUNT, routes.size)
        routes.forEachIndexed { index, route ->
            encodeStoredRoute(route).forEach { (field, value) ->
                when (value) {
                    is String -> editor.putString(key(index, field), value)
                    is Long -> editor.putLong(key(index, field), value)
                    is Boolean -> editor.putBoolean(key(index, field), value)
                }
            }
        }
        if (lastSelectedUri != null) editor.putString(KEY_LAST_SELECTED, lastSelectedUri)
        editor.apply()
    }

    fun lastSelectedUri(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_SELECTED, null)

    fun remove(context: Context, route: SavedRoute, routes: List<SavedRoute>, replacement: SavedRoute?) {
        save(context, routes.filterNot { it.uri == route.uri }, replacement?.uri)
    }

    fun upsert(routes: MutableList<SavedRoute>, incoming: SavedRoute): SavedRoute {
        val duplicateIndex = routes.indexOfFirst { it.sha256 == incoming.sha256 }
        if (duplicateIndex < 0) {
            routes.add(incoming)
            return incoming
        }
        val existing = routes[duplicateIndex]
        val refreshed = existing.copy(
            uri = incoming.uri,
            lengthMeters = incoming.lengthMeters,
            elevationReason = incoming.elevationReason,
            waypointCount = incoming.waypointCount,
            wideGapCount = incoming.wideGapCount,
            originalFileName = existing.originalFileName ?: incoming.originalFileName,
            closed = existing.closed ?: incoming.closed,
        )
        routes[duplicateIndex] = refreshed
        return refreshed
    }

    fun isReadable(context: Context, route: SavedRoute): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(route.uri))?.use { } != null
    } catch (_: Exception) {
        false
    }

    fun rename(routes: List<SavedRoute>, uri: String, newName: String): SavedRoute? {
        val normalizedName = RouteNameRules.trimAndLimit(newName)
        if (normalizedName.isEmpty()) return null
        val route = routes.firstOrNull { it.uri == uri } ?: return null
        return route.copy(displayName = normalizedName)
    }

    internal fun encodeStoredRoute(route: SavedRoute): Map<String, Any> = buildMap {
        put("uri", route.uri)
        put("name", route.displayName)
        put("sha256", route.sha256)
        put("length", route.lengthMeters.toString())
        put("addedAt", route.addedAt)
        route.elevationReason?.let { put("elevReason", it) }
        route.waypointCount?.let { put("wptCount", it.toString()) }
        route.wideGapCount?.let { put("gap100", it.toString()) }
        route.originalFileName?.let { put("origName", it) }
        route.closed?.let { put("closed", it) }
    }

    internal fun decodeStoredRoute(fields: Map<String, Any?>): SavedRoute? {
        val uri = fields["uri"] as? String ?: return null
        return SavedRoute(
            uri = uri,
            displayName = fields["name"] as? String ?: "GPX 경로",
            sha256 = fields["sha256"] as? String ?: "",
            lengthMeters = (fields["length"] as? String)?.toDoubleOrNull() ?: 0.0,
            addedAt = fields["addedAt"] as? Long ?: 0L,
            elevationReason = fields["elevReason"] as? String,
            waypointCount = (fields["wptCount"] as? String)?.toIntOrNull(),
            wideGapCount = (fields["gap100"] as? String)?.toIntOrNull(),
            originalFileName = fields["origName"] as? String,
            closed = fields["closed"] as? Boolean,
        )
    }

    private fun key(index: Int, field: String): String = "route_${index}_$field"
}
