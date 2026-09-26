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
    "산길",
)

internal fun looksLikeMapApp(app: ResolvedApp): Boolean =
    MAP_APP_LABEL_KEYWORDS.any { keyword ->
        app.label.contains(keyword, ignoreCase = true) || app.packageName.contains(keyword, ignoreCase = true)
    }

/** The URI source used while looking for an app that can open the GPX. */
internal enum class MapUriKind(val label: String, val priority: Int) {
    ORIGINAL("원본", 0),
    COPY("사본", 1),
}

/** The three ACTION_VIEW shapes used for candidate discovery. */
internal enum class MapIntentShapeKind(val priority: Int) {
    UNTYPED(0),
    EXACT(1),
    ACTUAL(2),
}

internal data class MapIntentShape(
    val kind: MapIntentShapeKind,
    val label: String,
    val mimeType: String?,
)

internal data class MapObservation(
    val uriKind: MapUriKind,
    val shape: MapIntentShape,
    val apps: List<ResolvedApp>,
)

internal data class MapCandidateCombo(
    val uriKind: MapUriKind,
    val shape: MapIntentShape,
)

internal data class MapCandidate(
    val packageName: String,
    val label: String,
    val combos: List<MapCandidateCombo>,
)

internal data class MapMergeResult(
    val candidates: List<MapCandidate>,
    val excludedLabels: List<String>,
)

/**
 * Returns a stable, ordered set of discovery shapes. Actual MIME is represented by
 * application/octet-stream when the provider does not report one.
 */
internal fun mapIntentShapes(actualType: String?): List<MapIntentShape> {
    val actual = actualType?.trim()?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
    return listOf(
        MapIntentShape(MapIntentShapeKind.UNTYPED, "무타입", null),
        MapIntentShape(MapIntentShapeKind.EXACT, "정확한 GPX", "application/gpx+xml"),
        MapIntentShape(MapIntentShapeKind.ACTUAL, "실제 타입", actual),
    )
}

private val mapCandidateComparator = Comparator<MapCandidate> { left, right ->
    val labelComparison = String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label)
    if (labelComparison != 0) labelComparison else left.packageName.compareTo(right.packageName)
}

private val mapComboComparator = compareBy<MapCandidateCombo> { it.uriKind.priority }
    .thenBy { it.shape.kind.priority }

/**
 * Merges every query result. A package is one candidate, while all successful
 * URI/shape observations remain available as ordered launch fallbacks.
 */
internal fun mergeMapCandidates(observations: List<MapObservation>): MapMergeResult {
    val candidatesByPackage = linkedMapOf<String, MutableList<Pair<ResolvedApp, MapCandidateCombo>>>()
    val excluded = linkedSetOf<String>()
    observations.forEach { observation ->
        val combo = MapCandidateCombo(observation.uriKind, observation.shape)
        observation.apps.forEach appLoop@ { app ->
            if (app.packageName.isBlank()) return@appLoop
            if (!looksLikeMapApp(app)) {
                app.label.takeIf { it.isNotBlank() }?.let(excluded::add)
                return@appLoop
            }
            candidatesByPackage.getOrPut(app.packageName) { mutableListOf() }.add(app to combo)
        }
    }

    val candidates = candidatesByPackage.map { (packageName, entries) ->
        val label = entries.asSequence()
            .map { it.first.label }
            .filter { it.isNotBlank() }
            .minWithOrNull(String.CASE_INSENSITIVE_ORDER)
            ?: packageName
        val combos = entries.asSequence()
            .map { it.second }
            .distinct()
            .sortedWith(mapComboComparator)
            .toList()
        MapCandidate(packageName, label, combos)
    }.sortedWith(mapCandidateComparator)

    return MapMergeResult(candidates, excluded.toList().sortedWith(String.CASE_INSENSITIVE_ORDER))
}

internal fun requiresUserChoice(candidates: List<MapCandidate>): Boolean = candidates.isNotEmpty()

internal fun mapChoiceLabels(candidates: List<MapCandidate>, otherAppsLabel: String = "다른 앱으로 열기…"): List<String> =
    candidates.map { it.label } + otherAppsLabel

internal sealed class MapChoiceSelection {
    data class Candidate(val candidate: MapCandidate) : MapChoiceSelection()
    data object OtherApps : MapChoiceSelection()
}

/** Maps the dialog row to its action, returning null for invalid row indexes. */
internal fun mapChoiceAt(candidates: List<MapCandidate>, index: Int): MapChoiceSelection? = when {
    index < 0 -> null
    index < candidates.size -> MapChoiceSelection.Candidate(candidates[index])
    index == candidates.size -> MapChoiceSelection.OtherApps
    else -> null
}

internal enum class CandidateAttemptResult { SUCCESS, NOT_FOUND, SECURITY_REJECTED }

internal data class CandidateLaunchOutcome(
    val successCombo: MapCandidateCombo?,
    val attempts: Int,
    val notFound: Int,
    val securityRejected: Int,
    val lastFailure: CandidateAttemptResult? = null,
) {
    val lastReason: String
        get() = when (lastFailure) {
            CandidateAttemptResult.SECURITY_REJECTED -> "권한이 거부되었습니다"
            CandidateAttemptResult.NOT_FOUND -> "활동을 찾지 못했습니다"
            null, CandidateAttemptResult.SUCCESS -> "알 수 없는 오류"
        }
}

internal fun runCandidateAttempts(
    candidate: MapCandidate,
    attempt: (MapCandidateCombo) -> CandidateAttemptResult,
): CandidateLaunchOutcome {
    var attempts = 0
    var notFound = 0
    var securityRejected = 0
    var lastFailure: CandidateAttemptResult? = null
    candidate.combos.forEach { combo ->
        attempts += 1
        when (attempt(combo)) {
            CandidateAttemptResult.SUCCESS ->
                return CandidateLaunchOutcome(combo, attempts, notFound, securityRejected, lastFailure)
            CandidateAttemptResult.NOT_FOUND -> {
                notFound += 1
                lastFailure = CandidateAttemptResult.NOT_FOUND
            }
            CandidateAttemptResult.SECURITY_REJECTED -> {
                securityRejected += 1
                lastFailure = CandidateAttemptResult.SECURITY_REJECTED
            }
        }
    }
    return CandidateLaunchOutcome(null, attempts, notFound, securityRejected, lastFailure)
}
