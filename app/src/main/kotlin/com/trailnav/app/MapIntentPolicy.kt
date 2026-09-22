package com.trailnav.app

internal val MAP_APP_PACKAGES = listOf(
    "com.google.android.apps.maps",
    "com.nhn.android.nmap",
    "net.daum.android.map",
)

/** Pure result of resolving a GPX intent against the allowed map packages. */
internal sealed class MapLaunchPlan {
    data object None : MapLaunchPlan()
    data class Direct(val packageName: String) : MapLaunchPlan()
    data class Chooser(val primaryPackage: String, val alternativePackages: List<String>) : MapLaunchPlan()
}

internal fun chooseMapLaunchPlan(resolvedPackages: List<String>): MapLaunchPlan {
    val distinct = resolvedPackages.filter { it.isNotBlank() }.distinct()
    return when (distinct.size) {
        0 -> MapLaunchPlan.None
        1 -> MapLaunchPlan.Direct(distinct.single())
        else -> MapLaunchPlan.Chooser(distinct.first(), distinct.drop(1))
    }
}
