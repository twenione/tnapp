package com.trailnav.app

internal data class ResolvedApp(
    val packageName: String,
    val label: String,
)

internal val MAP_APP_LABEL_KEYWORDS = listOf(
    "지도",
    "맵",
    "map",
    "navi",
    "네비",
    "gps",
    "trail",
    "track",
)

internal fun looksLikeMapApp(app: ResolvedApp): Boolean =
    MAP_APP_LABEL_KEYWORDS.any { keyword ->
        app.label.contains(keyword, ignoreCase = true) || app.packageName.contains(keyword, ignoreCase = true)
    }

/** Pure result of resolving a GPX intent against the allowed map packages. */
internal sealed class MapLaunchPlan {
    data object None : MapLaunchPlan()
    data class Direct(val packageName: String) : MapLaunchPlan()
    data class Chooser(val primaryPackage: String, val alternativePackages: List<String>) : MapLaunchPlan()
}

internal fun chooseMapLaunchPlan(
    exactTypeMatches: List<ResolvedApp>,
    genericTypeMapLikeMatches: List<ResolvedApp>,
): MapLaunchPlan {
    val distinct = (exactTypeMatches.ifEmpty { genericTypeMapLikeMatches })
        .filter { it.packageName.isNotBlank() }
        .distinctBy { it.packageName }
    return when (distinct.size) {
        0 -> MapLaunchPlan.None
        1 -> MapLaunchPlan.Direct(distinct.single().packageName)
        else -> MapLaunchPlan.Chooser(
            distinct.first().packageName,
            distinct.drop(1).map { it.packageName },
        )
    }
}
