package com.trailnav.app

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteDetailsFormatterTest {
    @Test
    fun detailRowsUseExpectedResourcesAndLocaleFormats() {
        val presentation = RouteDetailsFormatter.present(
            SavedRoute(
                uri = "content://route",
                displayName = "Trail",
                sha256 = "abc",
                lengthMeters = 8_990.0,
                addedAt = 86_400_000L,
                elevationReason = "ok",
                waypointCount = 3,
                originalFileName = "original.gpx",
                closed = false,
            ),
            TimeZone.getTimeZone("UTC"),
        )

        assertEquals("Trail", presentation.title)
        assertEquals(
            listOf(
                RouteDetailRow(R.string.route_detail_label_length, RouteDetailValue.Resource(R.string.route_detail_length, listOf("8.99"))),
                RouteDetailRow(R.string.route_detail_label_shape, RouteDetailValue.Resource(R.string.route_detail_shape_one_way)),
                RouteDetailRow(R.string.route_detail_label_elevation, RouteDetailValue.Resource(R.string.route_detail_elevation_full)),
                RouteDetailRow(R.string.route_detail_label_waypoints, RouteDetailValue.Resource(R.string.route_detail_waypoints_count, listOf(3))),
                RouteDetailRow(R.string.route_detail_label_imported_date, RouteDetailValue.Resource(R.string.route_detail_plain_value, listOf("1970-01-02"))),
                RouteDetailRow(R.string.route_detail_label_original_name, RouteDetailValue.Literal("original.gpx")),
            ),
            presentation.rows,
        )
        assertEquals("8.99 km", TestStrings.text("route_detail_length", "8.99"))
        assertEquals("편도 경로", TestStrings.text("route_detail_shape_one_way"))
        assertEquals("위치·고도", TestStrings.text("route_detail_elevation_full"))
        assertEquals("3개", TestStrings.text("route_detail_waypoints_count", 3))
        assertEquals("1970-01-02", TestStrings.text("route_detail_plain_value", "1970-01-02"))
    }

    @Test
    fun shapeAndElevationStatesMapToTheSpecifiedResources() {
        val expectedShapes = listOf(
            false to R.string.route_detail_shape_one_way,
            true to R.string.route_detail_shape_circular,
            null to R.string.route_detail_unknown,
        )
        expectedShapes.forEach { (closed, expectedResource) ->
            val row = RouteDetailsFormatter.present(route(closed = closed)).rows[1]
            assertEquals(expectedResource, (row.value as RouteDetailValue.Resource).resourceId)
        }

        val expectedElevation = listOf(
            "ok" to R.string.route_detail_elevation_full,
            "absent" to R.string.route_detail_elevation_location_only,
            "partial" to R.string.route_detail_elevation_partial,
            "unstable" to R.string.route_detail_elevation_unstable,
            "unknown" to R.string.route_detail_unknown_with_reimport,
            null to R.string.route_detail_unknown_with_reimport,
        )
        expectedElevation.forEach { (reason, expectedResource) ->
            val row = RouteDetailsFormatter.present(route(elevationReason = reason)).rows[2]
            assertEquals(expectedResource, (row.value as RouteDetailValue.Resource).resourceId)
        }
        assertEquals("순환 경로", TestStrings.text("route_detail_shape_circular"))
        assertEquals("알 수 없음", TestStrings.text("route_detail_unknown"))
        assertEquals("위치만", TestStrings.text("route_detail_elevation_location_only"))
        assertEquals("위치·고도 (일부 없음)", TestStrings.text("route_detail_elevation_partial"))
        assertEquals("위치·고도 (고도는 쓰지 않음)", TestStrings.text("route_detail_elevation_unstable"))
        assertEquals("알 수 없음 (다시 가져오기)", TestStrings.text("route_detail_unknown_with_reimport"))
    }

    @Test
    fun waypointDateAndOriginalFileNameUnknownStatesAreMapped() {
        val route = route(waypointCount = null, addedAt = 0L, originalFileName = null)
        val rows = RouteDetailsFormatter.present(route, TimeZone.getTimeZone("UTC")).rows

        assertEquals(R.string.route_detail_unknown, (rows[3].value as RouteDetailValue.Resource).resourceId)
        assertEquals(R.string.route_detail_unknown, (rows[4].value as RouteDetailValue.Resource).resourceId)
        assertEquals(R.string.route_detail_unknown, (rows[5].value as RouteDetailValue.Resource).resourceId)
        assertEquals(
            R.string.route_detail_waypoints_none,
            (RouteDetailsFormatter.present(route(waypointCount = 0)).rows[3].value as RouteDetailValue.Resource).resourceId,
        )
        assertEquals("없음", TestStrings.text("route_detail_waypoints_none"))
    }

    @Test
    fun warningsAppearOnlyForWideGapsOrShortRoutes() {
        assertEquals(emptyList(), RouteDetailsFormatter.present(route(lengthMeters = 100.0, wideGapCount = 0)).warnings)

        val gapWarning = RouteDetailsFormatter.present(route(lengthMeters = 1_000.0, wideGapCount = 1)).warnings.single()
        assertEquals(R.string.route_detail_warning_wide_gaps, gapWarning.resourceId)
        assertEquals(listOf(1), gapWarning.arguments)
        assertEquals(
            "점 사이가 100m 넘게 벌어진 곳 1곳 — 그 구간은 꺾임 안내가 덜 정확할 수 있어요",
            TestStrings.text("route_detail_warning_wide_gaps", 1),
        )

        val shortWarning = RouteDetailsFormatter.present(route(lengthMeters = 89.0, wideGapCount = 0)).warnings.single()
        assertEquals(R.string.route_detail_warning_short_route, shortWarning.resourceId)
        assertEquals(
            "경로가 짧아서 도착 안내가 나오지 않을 수 있어요",
            TestStrings.text("route_detail_warning_short_route"),
        )

        val both = RouteDetailsFormatter.present(route(lengthMeters = 89.0, wideGapCount = 2)).warnings
        assertEquals(
            listOf(R.string.route_detail_warning_wide_gaps, R.string.route_detail_warning_short_route),
            both.map { it.resourceId },
        )
    }

    private fun route(
        lengthMeters: Double = 1_000.0,
        wideGapCount: Int? = 0,
        waypointCount: Int? = 1,
        addedAt: Long = 1L,
        elevationReason: String? = "ok",
        originalFileName: String? = "route.gpx",
        closed: Boolean? = false,
    ) = SavedRoute(
        uri = "content://route",
        displayName = "Route",
        sha256 = "abc",
        lengthMeters = lengthMeters,
        addedAt = addedAt,
        elevationReason = elevationReason,
        waypointCount = waypointCount,
        wideGapCount = wideGapCount,
        originalFileName = originalFileName,
        closed = closed,
    )
}
