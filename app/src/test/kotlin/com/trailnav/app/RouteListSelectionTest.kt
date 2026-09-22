package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteListSelectionTest {
    private val first = SavedRoute("content://first", "first.gpx", "a", 100.0, 1L)
    private val second = SavedRoute("content://second", "second.gpx", "b", 200.0, 2L)

    @Test
    fun onlyMatchingUriIsSelected() {
        assertTrue(isSelectedRoute(first, first.uri))
        assertFalse(isSelectedRoute(second, first.uri))
        assertFalse(isSelectedRoute(first, null))
    }
}
