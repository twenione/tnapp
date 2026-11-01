package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StartPhaseExpectedFailurePolicyTest {
    @Test
    fun listedAssertionFailureIsObservedAndAccepted() {
        val observed = mutableListOf<String>()
        applyExpectedFailurePolicy(
            test = "listed-counterexample",
            releaseCondition = "remove after the invariant passes",
            listed = true,
            onObserved = observed::add,
        ) {
            throw AssertionError("counterexample")
        }
        assertEquals(listOf("counterexample"), observed)
    }

    @Test
    fun listedPassingBodyFailsWithReleaseCondition() {
        val failure = assertFailsWith<AssertionError> {
            applyExpectedFailurePolicy(
                test = "listed-counterexample",
                releaseCondition = "remove after the invariant passes",
                listed = true,
            ) { }
        }
        assertTrue(failure.message.orEmpty().contains("remove after the invariant passes"))
    }

    @Test
    fun unlistedAssertionFailureRemainsAFailure() {
        val failure = assertFailsWith<AssertionError> {
            applyExpectedFailurePolicy(
                test = "unlisted-counterexample",
                releaseCondition = "not applicable",
                listed = false,
            ) { throw AssertionError("unlisted assertion") }
        }
        assertEquals("unlisted assertion", failure.message)
    }

    @Test
    fun listedNullPointerExceptionIsNotTreatedAsExpectedFailure() {
        val failure = assertFailsWith<NullPointerException> {
            applyExpectedFailurePolicy(
                test = "listed-counterexample",
                releaseCondition = "remove after the invariant passes",
                listed = true,
            ) { throw NullPointerException("not an assertion") }
        }
        assertEquals("not an assertion", failure.message)
    }

    @Test
    fun missingSourceOrRuntimeCallFailsConsistencyAndEmptyListPasses() {
        assertFailsWith<AssertionError> {
            assertExpectedFailureSourceConsistency(setOf("not-in-source"), emptySet())
        }
        assertFailsWith<AssertionError> {
            assertExpectedFailureRuntimeConsistency(setOf("not-called"), emptySet())
        }
        assertExpectedFailureSourceConsistency(emptySet(), emptySet())
        assertExpectedFailureRuntimeConsistency(emptySet(), emptySet())
    }
}
