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
)

internal object RouteCatalog {
    private const val PREFS = "trailnav.routes"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST_SELECTED = "last_selected_uri"

    fun load(context: Context): MutableList<SavedRoute> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).mapNotNull { index ->
            val uri = prefs.getString(key(index, "uri"), null) ?: return@mapNotNull null
            SavedRoute(
                uri = uri,
                displayName = prefs.getString(key(index, "name"), "GPX 경로") ?: "GPX 경로",
                sha256 = prefs.getString(key(index, "sha256"), "") ?: "",
                lengthMeters = prefs.getString(key(index, "length"), "0")?.toDoubleOrNull() ?: 0.0,
                addedAt = prefs.getLong(key(index, "addedAt"), 0L),
                elevationReason = prefs.getString(key(index, "elevReason"), null),
                waypointCount = prefs.getString(key(index, "wptCount"), null)?.toIntOrNull(),
            )
        }.toMutableList()
    }

    fun save(context: Context, routes: List<SavedRoute>, lastSelectedUri: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        editor.putInt(KEY_COUNT, routes.size)
        routes.forEachIndexed { index, route ->
            editor.putString(key(index, "uri"), route.uri)
            editor.putString(key(index, "name"), route.displayName)
            editor.putString(key(index, "sha256"), route.sha256)
            editor.putString(key(index, "length"), route.lengthMeters.toString())
            editor.putLong(key(index, "addedAt"), route.addedAt)
            if (route.elevationReason != null) editor.putString(key(index, "elevReason"), route.elevationReason)
            if (route.waypointCount != null) editor.putString(key(index, "wptCount"), route.waypointCount.toString())
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
            displayName = incoming.displayName,
            lengthMeters = incoming.lengthMeters,
            elevationReason = incoming.elevationReason,
            waypointCount = incoming.waypointCount,
        )
        routes[duplicateIndex] = refreshed
        return refreshed
    }

    fun isReadable(context: Context, route: SavedRoute): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(route.uri))?.use { } != null
    } catch (_: Exception) {
        false
    }

    fun details(route: SavedRoute): String = "%.2f km · sha256:%s".format(
        java.util.Locale.US,
        route.lengthMeters / 1_000.0,
        route.sha256.take(12),
    )

    fun detailLine(route: SavedRoute, readable: Boolean): String =
        if (readable) details(route) else "${details(route)} · 다시 가져오기 필요"

    fun infoLevelLabel(elevationReason: String?, waypointCount: Int?): String {
        val base = when (elevationReason) {
            null -> "정보 확인 필요(다시 가져오기)"
            "absent" -> "위치정보만"
            "partial" -> "고도 일부 누락"
            "unstable" -> "고도 불안정(미사용)"
            "ok" -> "위치+고도"
            else -> "정보 확인 필요(다시 가져오기)"
        }
        return if (elevationReason != null && waypointCount != null && waypointCount > 0) {
            "$base · 지점 ${waypointCount}개"
        } else {
            base
        }
    }

    private fun key(index: Int, field: String): String = "route_${index}_$field"
}
