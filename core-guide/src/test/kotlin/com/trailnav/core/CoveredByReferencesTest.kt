package com.trailnav.core

import kotlin.test.Test

/** Parses documented test references independently of the reflection adapter. */
internal fun coveredByReferenceViolations(
    exclusions: Map<String, String>,
    testMethods: (String) -> Set<String>?,
): List<String> {
    val pattern = Regex("covered by ([A-Za-z][A-Za-z0-9_]*)\\.([A-Za-z][A-Za-z0-9_]*)")
    return exclusions.mapNotNull { (field, note) ->
        val reference = pattern.find(note) ?: return@mapNotNull null
        val className = reference.groupValues[1]
        val methodName = reference.groupValues[2]
        if (methodName in (testMethods(className) ?: emptySet())) null
        else "$field -> $className.$methodName"
    }
}

class CoveredByReferencesTest {
    private fun reflectedTestMethods(className: String): Set<String>? = runCatching {
        Class.forName("com.trailnav.core.$className")
            .declaredMethods
            .map { it.name }
            .toSet()
    }.getOrNull()

    @Test
    fun everyCoveredByReferenceNamesAnExistingTestMethod() {
        val invalid = coveredByReferenceViolations(GuideConfig.sensitivityExcluded, ::reflectedTestMethods)
        check(invalid.isEmpty()) { "stale covered by references: ${invalid.joinToString()}" }
    }

    @Test
    fun fakeReferenceIsRejectedByTheChecker() {
        val withFakeReference = GuideConfig.sensitivityExcluded +
            ("testOnlyFakeReference" to "covered by SunriseGuidanceTest.noSuchTestMethod")
        val invalid = coveredByReferenceViolations(withFakeReference, ::reflectedTestMethods)
        check(invalid == listOf("testOnlyFakeReference -> SunriseGuidanceTest.noSuchTestMethod")) {
            "checker did not report the deliberately missing method: $invalid"
        }
    }
}
