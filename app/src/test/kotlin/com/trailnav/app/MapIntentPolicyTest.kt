package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MapIntentPolicyTest {
    @Test
    fun mapLabelsAndPackagesAreRecognized() {
        assertEquals(true, looksLikeMapApp(ResolvedApp("com.nhn.android.nmap", "네이버지도")))
        assertEquals(true, looksLikeMapApp(ResolvedApp("com.example.navigation", "Google Maps")))
        assertEquals(true, looksLikeMapApp(ResolvedApp("com.example.trail", "Trail helper")))
        assertEquals(false, looksLikeMapApp(ResolvedApp("com.google.android.gm", "Gmail")))
        assertEquals(false, looksLikeMapApp(ResolvedApp("com.kakao.talk", "카카오톡")))
        assertEquals(false, looksLikeMapApp(ResolvedApp("com.example.files", "파일 관리자")))
    }

    @Test
    fun noResolvedMapAppsProducesNone() {
        assertIs<MapLaunchPlan.None>(chooseMapLaunchPlan(emptyList(), emptyList()))
    }

    @Test
    fun exactTypeCandidatesTakePrecedenceOverGenericFallback() {
        assertEquals(
            MapLaunchPlan.Direct("com.example.exact"),
            chooseMapLaunchPlan(
                exactTypeMatches = listOf(ResolvedApp("com.example.exact", "GPX Viewer")),
                genericTypeMapLikeMatches = listOf(ResolvedApp("com.example.map", "지도 앱")),
            ),
        )
    }

    @Test
    fun genericFallbackCandidateLaunchesDirectly() {
        assertEquals(
            MapLaunchPlan.Direct("com.example.map"),
            chooseMapLaunchPlan(
                exactTypeMatches = emptyList(),
                genericTypeMapLikeMatches = listOf(ResolvedApp("com.example.map", "지도 앱")),
            ),
        )
    }

    @Test
    fun multipleResolvedMapAppsProduceRestrictedChooserAndRemoveDuplicates() {
        assertEquals(
            MapLaunchPlan.Chooser(
                "com.example.map",
                listOf("com.example.navi"),
            ),
            chooseMapLaunchPlan(
                exactTypeMatches = emptyList(),
                genericTypeMapLikeMatches = listOf(
                    ResolvedApp("com.example.map", "지도"),
                    ResolvedApp("com.example.navi", "Navi"),
                    ResolvedApp("com.example.map", "지도"),
                ),
            ),
        )
    }
}
