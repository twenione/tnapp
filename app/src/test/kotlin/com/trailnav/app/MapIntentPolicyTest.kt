package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapIntentPolicyTest {
    private val untyped = mapIntentShapes("text/xml")[0]
    private val exact = mapIntentShapes("text/xml")[1]
    private val actual = mapIntentShapes("text/xml")[2]

    @Test
    fun discoveryShapesIncludeUntypedExactAndActualMimeFallback() {
        assertEquals(
            listOf(null, "application/gpx+xml", "text/xml"),
            mapIntentShapes("text/xml").map { it.mimeType },
        )
        assertEquals(
            listOf(null, "application/gpx+xml", "application/octet-stream"),
            mapIntentShapes(null).map { it.mimeType },
        )
    }

    @Test
    fun mapLabelsAndPackagesAreRecognized() {
        assertTrue(looksLikeMapApp(ResolvedApp("com.nhn.android.nmap", "네이버지도")))
        assertTrue(looksLikeMapApp(ResolvedApp("com.example.navigation", "Google Maps")))
        assertTrue(looksLikeMapApp(ResolvedApp("com.example.trail", "Trail helper")))
        assertTrue(looksLikeMapApp(ResolvedApp("com.example.sangilsam", "산길샘")))
        assertFalse(looksLikeMapApp(ResolvedApp("com.google.android.gm", "Gmail")))
        assertFalse(looksLikeMapApp(ResolvedApp("com.kakao.talk", "카카오톡")))
        assertFalse(looksLikeMapApp(ResolvedApp("com.example.files", "파일 관리자")))
    }

    @Test
    fun unionKeepsAppsFoundByDifferentShapesAndUris() {
        val result = mergeMapCandidates(
            listOf(
                MapObservation(MapUriKind.ORIGINAL, untyped, listOf(ResolvedApp("com.kakao.map", "카카오지도"))),
                MapObservation(MapUriKind.ORIGINAL, exact, listOf(ResolvedApp("com.naver.map", "네이버지도"))),
                MapObservation(MapUriKind.COPY, actual, listOf(ResolvedApp("com.sangilsam", "산길샘"))),
            ),
        )
        assertEquals(
            listOf("산길샘", "네이버지도", "카카오지도").sortedWith(String.CASE_INSENSITIVE_ORDER),
            result.candidates.map { it.label },
        )
    }

    @Test
    fun combinationObservationsAreRetainedInPriorityOrderAndDeduplicated() {
        val app = ResolvedApp("com.naver.map", "네이버지도")
        val result = mergeMapCandidates(
            listOf(
                MapObservation(MapUriKind.COPY, actual, listOf(app)),
                MapObservation(MapUriKind.ORIGINAL, exact, listOf(app)),
                MapObservation(MapUriKind.ORIGINAL, untyped, listOf(app)),
                MapObservation(MapUriKind.ORIGINAL, untyped, listOf(app)),
            ),
        )
        val combos = result.candidates.single().combos
        assertEquals(
            listOf(
                MapCandidateCombo(MapUriKind.ORIGINAL, untyped),
                MapCandidateCombo(MapUriKind.ORIGINAL, exact),
                MapCandidateCombo(MapUriKind.COPY, actual),
            ),
            combos,
        )
    }

    @Test
    fun nonMapAppsAreExcludedOnce() {
        val result = mergeMapCandidates(
            listOf(
                MapObservation(
                    MapUriKind.ORIGINAL,
                    untyped,
                    listOf(
                        ResolvedApp("com.sangilsam", "산길샘"),
                        ResolvedApp("com.cx.file", "CX파일탐색기"),
                        ResolvedApp("com.deepseek", "DeepSeek"),
                        ResolvedApp("com.mifitness", "MiFitness"),
                        ResolvedApp("com.deepseek", "DeepSeek"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("산길샘"), result.candidates.map { it.label })
        assertEquals(listOf("CX파일탐색기", "DeepSeek", "MiFitness"), result.excludedLabels)
    }

    @Test
    fun labelsSortStablyRegardlessOfObservationOrder() {
        val apps = listOf(
            ResolvedApp("com.kakao.map", "카카오지도"),
            ResolvedApp("com.naver.map", "네이버지도"),
            ResolvedApp("com.sangilsam", "산길샘"),
        )
        val first = mergeMapCandidates(listOf(MapObservation(MapUriKind.ORIGINAL, untyped, apps)))
        val second = mergeMapCandidates(listOf(MapObservation(MapUriKind.ORIGINAL, untyped, apps.reversed())))
        assertEquals(first.candidates, second.candidates)
    }

    @Test
    fun zeroCandidatesDoNotRequireChoiceButOneCandidateDoes() {
        assertFalse(requiresUserChoice(emptyList()))
        assertTrue(
            requiresUserChoice(
                listOf(MapCandidate("com.naver.map", "네이버지도", emptyList())),
            ),
        )
    }

    @Test
    fun choiceLabelsHaveOtherAppsEntryAtTheEnd() {
        val candidates = listOf(
            MapCandidate("com.naver.map", "네이버지도", emptyList()),
            MapCandidate("com.kakao.map", "카카오지도", emptyList()),
        )
        assertEquals(listOf("네이버지도", "카카오지도", "다른 앱으로 열기…"), mapChoiceLabels(candidates))
    }

    @Test
    fun choiceIndexMapsFirstCandidateAndLastOtherAppsAndRejectsOutOfRange() {
        val first = MapCandidate("com.naver.map", "네이버지도", emptyList())
        val second = MapCandidate("com.kakao.map", "카카오지도", emptyList())
        val candidates = listOf(first, second)

        assertEquals(MapChoiceSelection.Candidate(first), mapChoiceAt(candidates, 0))
        assertEquals(MapChoiceSelection.OtherApps, mapChoiceAt(candidates, candidates.size))
        assertEquals(null, mapChoiceAt(candidates, -1))
        assertEquals(null, mapChoiceAt(candidates, candidates.size + 1))
    }

    @Test
    fun candidateAttemptsStopOnFirstSuccessAndContinueAfterFailures() {
        val candidate = MapCandidate(
            "com.naver.map",
            "네이버지도",
            listOf(
                MapCandidateCombo(MapUriKind.ORIGINAL, untyped),
                MapCandidateCombo(MapUriKind.ORIGINAL, exact),
                MapCandidateCombo(MapUriKind.COPY, actual),
            ),
        )
        val calls = mutableListOf<MapCandidateCombo>()
        val outcome = runCandidateAttempts(candidate) { combo ->
            calls += combo
            if (combo == MapCandidateCombo(MapUriKind.ORIGINAL, exact)) {
                CandidateAttemptResult.SUCCESS
            } else {
                CandidateAttemptResult.NOT_FOUND
            }
        }
        assertEquals(candidate.combos.take(2), calls)
        assertEquals(candidate.combos[1], outcome.successCombo)
        assertEquals(2, outcome.attempts)
        assertEquals(1, outcome.notFound)
    }

    @Test
    fun candidateAttemptsReportAllFailuresIncludingSecurity() {
        val candidate = MapCandidate(
            "com.naver.map",
            "네이버지도",
            listOf(MapCandidateCombo(MapUriKind.ORIGINAL, untyped), MapCandidateCombo(MapUriKind.COPY, actual)),
        )
        val outcome = runCandidateAttempts(candidate) { CandidateAttemptResult.SECURITY_REJECTED }
        assertEquals(2, outcome.attempts)
        assertEquals(2, outcome.securityRejected)
        assertEquals("권한이 거부되었습니다", outcome.lastReason)
    }
}
