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
            SavedRoute("content://new", "new.gpx", "abc123", 1_100.0, 20L),
        )

        assertEquals(1, routes.size)
        assertEquals("content://new", selected.uri)
        assertEquals("new.gpx", routes.single().displayName)
        assertEquals(10L, routes.single().addedAt)
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
}
