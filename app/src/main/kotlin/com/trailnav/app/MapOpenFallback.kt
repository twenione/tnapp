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
    val untypedRaw: Int = 0,
    val untypedFiltered: Int = 0,
    val exactRaw: Int = 0,
    val exactFiltered: Int = 0,
    val actualRaw: Int = 0,
    val actualFiltered: Int = 0,
)

internal fun mapFallbackDiagnostic(counts: MapQueryCounts, outcome: FallbackOutcome): String =
    "지도 앱 조회 무타입 ${counts.untypedRaw}/${counts.untypedFiltered}, " +
        "정확 ${counts.exactRaw}/${counts.exactFiltered}, " +
        "실제 ${counts.actualRaw}/${counts.actualFiltered}; " +
        "fallback ${outcome.attempts}회(미설치 ${outcome.notFound}, 보안거부 ${outcome.securityRejected})"
