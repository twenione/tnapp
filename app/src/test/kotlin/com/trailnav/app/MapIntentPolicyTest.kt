package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MapIntentPolicyTest {
    @Test
    fun allowedMapPackageListIsNotEmpty() {
        assertEquals(true, MAP_APP_PACKAGES.isNotEmpty())
    }

    @Test
    fun noResolvedMapAppsProducesNone() {
        assertIs<MapLaunchPlan.None>(chooseMapLaunchPlan(emptyList()))
    }

    @Test
    fun oneResolvedMapAppLaunchesDirectly() {
        assertEquals(MapLaunchPlan.Direct("com.google.android.apps.maps"), chooseMapLaunchPlan(listOf("com.google.android.apps.maps")))
    }

    @Test
    fun multipleResolvedMapAppsProduceRestrictedChooser() {
        assertEquals(
            MapLaunchPlan.Chooser(
                "com.google.android.apps.maps",
                listOf("com.nhn.android.nmap"),
            ),
            chooseMapLaunchPlan(listOf("com.google.android.apps.maps", "com.nhn.android.nmap", "com.google.android.apps.maps")),
        )
    }
}
