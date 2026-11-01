package com.trailnav.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

private data class ExpectedFailureEntry(
    val test: String,
    val decision: String,
    val releaseCondition: String,
)

private object ExpectedFailureRegistry {
    val entries: Map<String, ExpectedFailureEntry> by lazy(::loadExpectedFailures)
    private val observed = ConcurrentHashMap.newKeySet<String>()

    fun markObserved(test: String) {
        observed += test
    }

    fun observedIds(): Set<String> = observed.toSet()
}

internal fun expectedFailureAware(test: String, body: () -> Unit) {
    val entry = ExpectedFailureRegistry.entries[test]
    if (entry == null) {
        body()
        return
    }
    ExpectedFailureRegistry.markObserved(test)
    applyExpectedFailurePolicy(test, entry.releaseCondition, listed = true, onObserved = { message ->
        println("EXPECTED-FAILURE $test observed: $message")
    }, body = body)
}

internal fun applyExpectedFailurePolicy(
    test: String,
    releaseCondition: String,
    listed: Boolean,
    onObserved: (String) -> Unit = {},
    body: () -> Unit,
) {
    if (!listed) {
        body()
        return
    }
    try {
        body()
    } catch (failure: AssertionError) {
        onObserved(failure.message.orEmpty())
        return
    }
    throw AssertionError(
        "D-170: 기대실패 시험이 통과했다 — 해제 조건 충족. 목록에서 빼야 병합된다: $releaseCondition",
    )
}

internal fun expectedFailureEntries(): Map<String, Pair<String, String>> =
    ExpectedFailureRegistry.entries.mapValues { (_, entry) -> entry.decision to entry.releaseCondition }

internal fun observedExpectedFailureIds(): Set<String> = ExpectedFailureRegistry.observedIds()

private fun loadExpectedFailures(): Map<String, ExpectedFailureEntry> {
    val root = repositoryRoot()
    val source = Files.newBufferedReader(root.resolve("tools/known_exceptions.json")).use { it.readText() }
    val arrayMatch = Regex("\"expected_failures\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        .find(source) ?: error("tools/known_exceptions.json has no expected_failures list")
    val objects = Regex("\\{([^{}]*)}").findAll(arrayMatch.groupValues[1])
    val entries = linkedMapOf<String, ExpectedFailureEntry>()
    for (match in objects) {
        val objectText = match.groupValues[1]
        val test = jsonString(objectText, "test") ?: error("expected_failures entry has no test string")
        val decision = jsonString(objectText, "decision") ?: error("expected_failures entry has no decision string")
        val release = jsonString(objectText, "release_condition")
            ?: error("expected_failures entry has no release_condition string")
        require(test.isNotBlank() && decision.isNotBlank() && release.isNotBlank()) {
            "expected_failures entries require non-empty test, decision, and release_condition"
        }
        require(entries.put(test, ExpectedFailureEntry(test, decision, release)) == null) {
            "duplicate expected_failures test id: $test"
        }
    }
    return entries
}

private fun jsonString(objectText: String, field: String): String? {
    val raw = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        .find(objectText)?.groupValues?.get(1) ?: return null
    return buildString {
        var index = 0
        while (index < raw.length) {
            val char = raw[index++]
            if (char != '\\' || index >= raw.length) {
                append(char)
                continue
            }
            when (val escaped = raw[index++]) {
                '"', '\\', '/' -> append(escaped)
                'b' -> append('\b')
                'f' -> append('\u000c')
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'u' -> {
                    require(index + 4 <= raw.length) { "invalid unicode escape in expected_failures" }
                    append(raw.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("invalid JSON escape in expected_failures")
            }
        }
    }
}

internal fun repositoryRoot(start: Path = Paths.get("").toAbsolutePath()): Path {
    var candidate: Path? = start.normalize()
    while (candidate != null) {
        if (Files.isRegularFile(candidate.resolve("tools/known_exceptions.json"))) return candidate
        candidate = candidate.parent
    }
    error("could not find repository root from the test working directory")
}
