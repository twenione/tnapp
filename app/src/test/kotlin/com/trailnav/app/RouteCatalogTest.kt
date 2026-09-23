package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteCatalogTest {
    @Test
    fun sameHashReplacesUriWithoutAddingDuplicate() {
        val routes = mutableListOf(
            SavedRoute("content://old", "old.gpx", "abc123", 1_000.0, 10L),
        )
        val selected = RouteCatalog.upsert(
            routes,
            SavedRoute("content://new", "new.gpx", "abc123", 1_100.0, 20L, "ok", 4),
        )

        assertEquals(1, routes.size)
        assertEquals("content://new", selected.uri)
        assertEquals("new.gpx", routes.single().displayName)
        assertEquals(10L, routes.single().addedAt)
        assertEquals("ok", routes.single().elevationReason)
        assertEquals(4, routes.single().waypointCount)
    }

    @Test
    fun newHashAppendsAndMissingPermissionIsExplicit() {
        val routes = mutableListOf<SavedRoute>()
        val selected = RouteCatalog.upsert(
            routes,
            SavedRoute("content://route", "trail.gpx", "def456", 8_990.0, 30L),
        )

        assertEquals(selected, routes.single())
        assertEquals("8.99 km · sha256:def456", RouteCatalog.detailLine(selected, readable = true))
        assertEquals(
            "8.99 km · sha256:def456 · 다시 가져오기 필요",
            RouteCatalog.detailLine(selected, readable = false),
        )
    }

    @Test
    fun infoLevelLabelCoversElevationStatesAndWaypointSuffix() {
        assertEquals("정보 확인 필요(다시 가져오기)", RouteCatalog.infoLevelLabel(null, null))
        assertEquals("위치정보만", RouteCatalog.infoLevelLabel("absent", 0))
        assertEquals("고도 일부 누락", RouteCatalog.infoLevelLabel("partial", 3))
        assertEquals("고도 불안정(미사용)", RouteCatalog.infoLevelLabel("unstable", null))
        assertEquals("위치+고도", RouteCatalog.infoLevelLabel("ok", 0))
    }

    @Test
    fun infoLevelLabelAddsWaypointCountOnlyWhenKnownAndPositive() {
        assertEquals("위치+고도 · 지점 2개", RouteCatalog.infoLevelLabel("ok", 2))
        assertEquals("위치+고도", RouteCatalog.infoLevelLabel("ok", 0))
        assertEquals("위치+고도", RouteCatalog.infoLevelLabel("ok", null))
        assertEquals("정보 확인 필요(다시 가져오기)", RouteCatalog.infoLevelLabel(null, 4))
    }
}
