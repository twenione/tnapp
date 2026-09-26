package com.trailnav.app

/** Android-free shapes and accounting for the last-resort GPX handoff. */
internal data class MapFallbackShape(
    val label: String,
    val mimeType: String?,
)

internal fun fallbackIntentShapes(actualType: String?): List<MapFallbackShape> {
    val result = mutableListOf(MapFallbackShape("타입 없음", null))
    fun add(label: String, type: String?) {
        if (!type.isNullOrBlank() && result.none { it.mimeType == type }) {
            result += MapFallbackShape(label, type)
        }
    }
    add("정확한 GPX", "application/gpx+xml")
    add("실제 타입", actualType?.takeIf { it.isNotBlank() })
    add("옥텟 스트림", "application/octet-stream")
    return result
}

internal enum class FallbackAttemptResult { SUCCESS, NOT_FOUND, SECURITY_REJECTED }

internal data class FallbackOutcome(
    val successShape: MapFallbackShape?,
    val attempts: Int,
    val notFound: Int,
    val securityRejected: Int,
)

internal fun runFallbackAttempts(
    uriKeys: List<String>,
    shapes: List<MapFallbackShape>,
    attempt: (uriKey: String, shape: MapFallbackShape) -> FallbackAttemptResult,
): FallbackOutcome {
    var attempts = 0
    var notFound = 0
    var securityRejected = 0
    uriKeys.forEach { uriKey ->
        shapes.forEach { shape ->
            attempts += 1
            when (attempt(uriKey, shape)) {
                FallbackAttemptResult.SUCCESS -> return FallbackOutcome(shape, attempts, notFound, securityRejected)
                FallbackAttemptResult.NOT_FOUND -> notFound += 1
                FallbackAttemptResult.SECURITY_REJECTED -> securityRejected += 1
            }
        }
    }
    return FallbackOutcome(null, attempts, notFound, securityRejected)
}

internal data class MapQueryCounts(
    val originalUntypedRaw: Int = 0,
    val originalUntypedFiltered: Int = 0,
    val originalExactRaw: Int = 0,
    val originalExactFiltered: Int = 0,
    val originalActualRaw: Int = 0,
    val originalActualFiltered: Int = 0,
    val copyUntypedRaw: Int = 0,
    val copyUntypedFiltered: Int = 0,
    val copyExactRaw: Int = 0,
    val copyExactFiltered: Int = 0,
    val copyActualRaw: Int = 0,
    val copyActualFiltered: Int = 0,
) {
    /** Compatibility aliases for callers that only recorded the original URI. */
    val untypedRaw: Int get() = originalUntypedRaw
    val untypedFiltered: Int get() = originalUntypedFiltered
    val exactRaw: Int get() = originalExactRaw
    val exactFiltered: Int get() = originalExactFiltered
    val actualRaw: Int get() = originalActualRaw
    val actualFiltered: Int get() = originalActualFiltered

    /** Six-argument compatibility constructor for the previous diagnostic tests. */
    constructor(
        untypedRaw: Int,
        untypedFiltered: Int,
        exactRaw: Int,
        exactFiltered: Int,
        actualRaw: Int,
        actualFiltered: Int,
    ) : this(
        originalUntypedRaw = untypedRaw,
        originalUntypedFiltered = untypedFiltered,
        originalExactRaw = exactRaw,
        originalExactFiltered = exactFiltered,
        originalActualRaw = actualRaw,
        originalActualFiltered = actualFiltered,
    )
}

internal data class MapCandidateDiagnostics(
    val candidates: List<String> = emptyList(),
    val excludedLabels: List<String> = emptyList(),
)

internal fun mapFallbackDiagnostic(
    counts: MapQueryCounts,
    outcome: FallbackOutcome,
    candidates: MapCandidateDiagnostics = MapCandidateDiagnostics(),
): String {
    val excluded = candidates.excludedLabels.distinct().take(8)
    val candidateLabels = candidates.candidates.joinToString(", ").ifBlank { "없음" }
    val excludedLabels = excluded.joinToString(", ").ifBlank { "없음" }
    return "후보 ${candidates.candidates.size}개: $candidateLabels / 제외: $excludedLabels / " +
        "원본[무타입 ${counts.originalUntypedRaw}/${counts.originalUntypedFiltered}, " +
        "정확 ${counts.originalExactRaw}/${counts.originalExactFiltered}, " +
        "실제 ${counts.originalActualRaw}/${counts.originalActualFiltered}] " +
        "사본[무타입 ${counts.copyUntypedRaw}/${counts.copyUntypedFiltered}, " +
        "정확 ${counts.copyExactRaw}/${counts.copyExactFiltered}, " +
        "실제 ${counts.copyActualRaw}/${counts.copyActualFiltered}] / " +
        "fallback ${outcome.attempts}회(미설치 ${outcome.notFound}, 보안거부 ${outcome.securityRejected})"
}
