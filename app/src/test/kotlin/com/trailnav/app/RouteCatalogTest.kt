package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteCatalogTest {
    @Test
    fun sameHashPreservesUserNameAndExistingExtendedMetadata() {
        val existing = SavedRoute(
            uri = "content://old",
            displayName = "Custom trail",
            sha256 = "abc123",
            lengthMeters = 1_000.0,
            addedAt = 10L,
            elevationReason = "absent",
            waypointCount = 2,
            wideGapCount = 1,
            originalFileName = "source-old.gpx",
            closed = false,
        )
        val routes = mutableListOf(existing)

        val refreshed = RouteCatalog.upsert(
            routes,
            SavedRoute(
                uri = "content://new",
                displayName = "new-file.gpx",
                sha256 = "abc123",
                lengthMeters = 1_100.0,
                addedAt = 20L,
                elevationReason = "ok",
                waypointCount = 4,
                wideGapCount = 3,
                originalFileName = "source-new.gpx",
                closed = true,
            ),
        )

        assertEquals(1, routes.size)
        assertEquals("content://new", refreshed.uri)
        assertEquals("Custom trail", refreshed.displayName)
        assertEquals(1_100.0, refreshed.lengthMeters)
        assertEquals(10L, refreshed.addedAt)
        assertEquals("ok", refreshed.elevationReason)
        assertEquals(4, refreshed.waypointCount)
        assertEquals(3, refreshed.wideGapCount)
        assertEquals("source-old.gpx", refreshed.originalFileName)
        assertEquals(false, refreshed.closed)
        assertEquals(refreshed, routes.single())
    }

    @Test
    fun sameHashFillsMissingExtendedMetadata() {
        val routes = mutableListOf(SavedRoute("content://old", "old.gpx", "abc123", 1_000.0, 10L))

        val refreshed = RouteCatalog.upsert(
            routes,
            SavedRoute(
                uri = "content://new",
                displayName = "new.gpx",
                sha256 = "abc123",
                lengthMeters = 1_100.0,
                addedAt = 20L,
                originalFileName = "new.gpx",
                closed = true,
            ),
        )

        assertEquals("old.gpx", refreshed.displayName)
        assertEquals("new.gpx", refreshed.originalFileName)
        assertEquals(true, refreshed.closed)
        assertEquals(10L, refreshed.addedAt)
    }

    @Test
    fun extendedFieldsRoundTripAndLegacyFieldsDefaultToNull() {
        val original = SavedRoute(
            "content://route", "trail.gpx", "abc123", 1_000.0, 10L,
            "ok", 2, 3, "trail.gpx", false,
        )
        val stored = RouteCatalog.encodeStoredRoute(original)

        assertEquals(original, RouteCatalog.decodeStoredRoute(stored))
        assertEquals(null, RouteCatalog.decodeStoredRoute(stored - "gap100")?.wideGapCount)

        val legacy = RouteCatalog.decodeStoredRoute(stored - "origName" - "closed")
        assertEquals(null, legacy?.originalFileName)
        assertEquals(null, legacy?.closed)
        assertEquals(original.copy(originalFileName = null, closed = null), legacy)
    }

    @Test
    fun newHashAppendsWithoutDuplicate() {
        val routes = mutableListOf<SavedRoute>()
        val selected = RouteCatalog.upsert(
            routes,
            SavedRoute("content://route", "trail.gpx", "def456", 8_990.0, 30L),
        )

        assertEquals(selected, routes.single())
    }

    @Test
    fun renameTrimsAndOnlyChangesTheDisplayName() {
        val original = SavedRoute(
            "content://route", "trail.gpx", "def456", 8_990.0, 30L,
            "ok", 4, 2, "source.gpx", true,
        )
        val otherRoute = original.copy(uri = "content://other", displayName = "New route")

        val renamed = RouteCatalog.rename(listOf(original, otherRoute), original.uri, "  New route  ")

        assertEquals(original.copy(displayName = "New route"), renamed)
    }

    @Test
    fun renameRejectsBlankOrMissingRouteAndLimitsUnicodeCodePoints() {
        val route = SavedRoute("content://route", "trail.gpx", "abc", 100.0, 1L)
        assertNull(RouteCatalog.rename(listOf(route), route.uri, "   "))
        assertNull(RouteCatalog.rename(listOf(route), "content://missing", "Valid"))

        val tooLong = "A".repeat(59) + "😀" + "B"
        val renamed = RouteCatalog.rename(listOf(route), route.uri, tooLong)
        assertEquals("A".repeat(59) + "😀", renamed?.displayName)
        assertEquals(60, renamed?.displayName?.let(RouteNameRules::codePointCount))
    }
}
