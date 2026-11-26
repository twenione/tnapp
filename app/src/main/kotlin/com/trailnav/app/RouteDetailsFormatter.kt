package com.trailnav.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal sealed interface RouteDetailValue {
    data class Resource(val resourceId: Int, val arguments: List<Any> = emptyList()) : RouteDetailValue
    data class Literal(val text: String) : RouteDetailValue
}

internal data class RouteDetailRow(val labelResourceId: Int, val value: RouteDetailValue)

internal data class RouteDetailsPresentation(
    val title: String,
    val rows: List<RouteDetailRow>,
    val warnings: List<RouteDetailValue.Resource>,
)

internal object RouteDetailsFormatter {
    fun present(route: SavedRoute, timeZone: TimeZone = TimeZone.getDefault()): RouteDetailsPresentation {
        val importedDate = route.addedAt.takeIf { it > 0L }?.let { timestamp ->
            SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply { this.timeZone = timeZone }
                .format(Date(timestamp))
        }
        val shapeResource = when (route.closed) {
            false -> R.string.route_detail_shape_one_way
            true -> R.string.route_detail_shape_circular
            null -> R.string.route_detail_unknown
        }
        val elevationResource = when (route.elevationReason) {
            "ok" -> R.string.route_detail_elevation_full
            "absent" -> R.string.route_detail_elevation_location_only
            "partial" -> R.string.route_detail_elevation_partial
            "unstable" -> R.string.route_detail_elevation_unstable
            else -> R.string.route_detail_unknown_with_reimport
        }
        val waypointValue = when {
            route.waypointCount == null -> resource(R.string.route_detail_unknown)
            route.waypointCount == 0 -> resource(R.string.route_detail_waypoints_none)
            route.waypointCount > 0 -> resource(R.string.route_detail_waypoints_count, route.waypointCount)
            else -> resource(R.string.route_detail_unknown)
        }
        val dateValue = importedDate?.let { resource(R.string.route_detail_plain_value, it) }
            ?: resource(R.string.route_detail_unknown)
        val originalNameValue = route.originalFileName?.let(RouteDetailValue::Literal)
            ?: resource(R.string.route_detail_unknown)
        val warnings = buildList {
            route.wideGapCount?.takeIf { it > 0 }?.let { count ->
                add(resource(R.string.route_detail_warning_wide_gaps, count))
            }
            if (route.lengthMeters < RouteImportWarning.SHORT_ROUTE_WARNING_METERS) {
                add(resource(R.string.route_detail_warning_short_route))
            }
        }
        return RouteDetailsPresentation(
            title = route.displayName,
            rows = listOf(
                RouteDetailRow(
                    R.string.route_detail_label_length,
                    resource(R.string.route_detail_length, String.format(Locale.US, "%.2f", route.lengthMeters / 1_000.0)),
                ),
                RouteDetailRow(R.string.route_detail_label_shape, resource(shapeResource)),
                RouteDetailRow(R.string.route_detail_label_elevation, resource(elevationResource)),
                RouteDetailRow(R.string.route_detail_label_waypoints, waypointValue),
                RouteDetailRow(R.string.route_detail_label_imported_date, dateValue),
                RouteDetailRow(R.string.route_detail_label_original_name, originalNameValue),
            ),
            warnings = warnings,
        )
    }

    private fun resource(resourceId: Int, vararg arguments: Any): RouteDetailValue.Resource =
        RouteDetailValue.Resource(resourceId, arguments.toList())
}
