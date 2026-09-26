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
            } else if (uri == "cache" && shape.mimeType == null) {
                FallbackAttemptResult.SECURITY_REJECTED
            } else {
                FallbackAttemptResult.NOT_FOUND
            }
        }
        assertEquals(5, outcome.attempts)
        assertEquals(3, outcome.notFound)
        assertEquals(1, outcome.securityRejected)
        assertEquals("application/gpx+xml", outcome.successShape?.mimeType)
        assertEquals(5, calls.size)
        assertTrue(calls.last().startsWith("original:"))
    }

    @Test
    fun diagnosticContainsCandidatesExclusionsQueriesAndFailureCounts() {
        val diagnostic = mapFallbackDiagnostic(
            MapQueryCounts(
                originalUntypedRaw = 4,
                originalUntypedFiltered = 1,
                originalExactRaw = 3,
                originalExactFiltered = 0,
                originalActualRaw = 2,
                originalActualFiltered = 1,
                copyUntypedRaw = 5,
                copyUntypedFiltered = 2,
                copyExactRaw = 4,
                copyExactFiltered = 1,
                copyActualRaw = 3,
                copyActualFiltered = 1,
            ),
            FallbackOutcome(null, 6, 4, 2),
            MapCandidateDiagnostics(
                candidates = listOf("네이버지도", "카카오지도"),
                excludedLabels = listOf("CX파일탐색기", "DeepSeek"),
            ),
        )
        assertTrue(diagnostic.contains("후보 2개: 네이버지도, 카카오지도"))
        assertTrue(diagnostic.contains("제외: CX파일탐색기, DeepSeek"))
        assertTrue(diagnostic.contains("원본[무타입 4/1, 정확 3/0, 실제 2/1]"))
        assertTrue(diagnostic.contains("사본[무타입 5/2, 정확 4/1, 실제 3/1]"))
        assertTrue(diagnostic.contains("fallback 6회(미설치 4, 보안거부 2)"))
        assertNull(FallbackOutcome(null, 0, 0, 0).successShape)
    }
}
