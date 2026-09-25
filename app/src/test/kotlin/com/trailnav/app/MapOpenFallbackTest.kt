package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapOpenFallbackTest {
    @Test
    fun fallbackShapesDeduplicateActualTypeInRequiredOrder() {
        assertEquals(
            listOf(null, "application/gpx+xml", "application/octet-stream"),
            fallbackIntentShapes("application/octet-stream").map { it.mimeType },
        )
        assertEquals(
            listOf(null, "application/gpx+xml", "text/xml", "application/octet-stream"),
            fallbackIntentShapes("text/xml").map { it.mimeType },
        )
        assertEquals(
            listOf(null, "application/gpx+xml", "application/octet-stream"),
            fallbackIntentShapes("application/gpx+xml").map { it.mimeType },
        )
        assertEquals(
            listOf(null, "application/gpx+xml", "application/octet-stream"),
            fallbackIntentShapes(null).map { it.mimeType },
        )
        assertEquals(
            listOf(null, "application/gpx+xml", "application/octet-stream"),
            fallbackIntentShapes("  ").map { it.mimeType },
        )
    }

    @Test
    fun attemptsContinueAfterFailuresAndStopAtFirstSuccess() {
        val calls = mutableListOf<String>()
        val shapes = fallbackIntentShapes(null)
        val outcome = runFallbackAttempts(listOf("cache", "original"), shapes) { uri, shape ->
            calls += "$uri:${shape.label}"
            if (uri == "original" && shape.mimeType == "application/gpx+xml") {
                FallbackAttemptResult.SUCCESS
            } else {
                FallbackAttemptResult.NOT_FOUND
            }
        }
        assertEquals(5, outcome.attempts)
        assertEquals(4, outcome.notFound)
        assertEquals(0, outcome.securityRejected)
        assertEquals("application/gpx+xml", outcome.successShape?.mimeType)
        assertEquals(5, calls.size)
        assertTrue(calls.last().startsWith("original:"))
    }

    @Test
    fun diagnosticContainsRawFilteredQueriesAndFailureCounts() {
        val diagnostic = mapFallbackDiagnostic(
            MapQueryCounts(4, 1, 3, 0, 2, 1),
            FallbackOutcome(null, 6, 4, 2),
        )
        assertTrue(diagnostic.contains("무타입 4/1"))
        assertTrue(diagnostic.contains("정확 3/0"))
        assertTrue(diagnostic.contains("실제 2/1"))
        assertTrue(diagnostic.contains("fallback 6회(미설치 4, 보안거부 2)"))
        assertNull(FallbackOutcome(null, 0, 0, 0).successShape)
    }
}
