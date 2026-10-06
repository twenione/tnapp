package com.trailnav.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceQueuePolicyTest {
    private fun item(
        id: String,
        priority: VoicePriority = VoicePriority.NORMAL,
        protected: Boolean = priority == VoicePriority.STATE_TRANSITION,
        turnIndex: Int? = if (priority == VoicePriority.TIME_CRITICAL) 0 else null,
        enqueuedAtMillis: Long = 0L,
        source: String = "system",
    ) = VoiceQueueItem(id, id, priority, protected, source, turnIndex, enqueuedAtMillis)

    private fun noTurnPassed(@Suppress("UNUSED_PARAMETER") index: Int) = false

    @Test
    fun stateTransitionWaitingItemSurvivesNormalAndTimeCriticalArrivals() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("state", VoicePriority.STATE_TRANSITION))
        queue.enqueue(item("normal"))
        val result = queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL))
        assertEquals(listOf("normal"), result.dropped.map { it.item.id })
        assertEquals(listOf("turn", "state"), queue.pendingItems.map { it.id })
    }

    @Test
    fun timeCriticalDropsOnlyUnprotectedWaitingItemsAndGoesToFront() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("active-state", VoicePriority.STATE_TRANSITION))
        queue.beginNext(0L, false, ::noTurnPassed)
        queue.enqueue(item("old-turn", VoicePriority.TIME_CRITICAL, turnIndex = 1))
        queue.enqueue(item("normal"))
        queue.enqueue(item("remaining", protected = true, source = "guidance"))
        queue.enqueue(item("sunset", protected = true, source = "guidance"))

        val result = queue.enqueue(item("new-turn", VoicePriority.TIME_CRITICAL, turnIndex = 2))

        assertEquals(listOf("old-turn", "normal"), result.dropped.map { it.item.id })
        assertTrue(result.dropped.all { it.reason == "flushed" })
        assertEquals(listOf("new-turn", "remaining", "sunset"), queue.pendingItems.map { it.id })
    }

    @Test
    fun playingStateTransitionIsNeverInterruptedByTurnNow() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("arrival", VoicePriority.STATE_TRANSITION))
        val active = queue.beginNext(0L, false, ::noTurnPassed).started
        val result = queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL))
        assertEquals("arrival", active?.id)
        assertNull(result.stopActive)
        assertEquals("arrival", queue.activeItem?.id)
    }

    @Test
    fun playingUnprotectedNormalIsStoppedByTurnNow() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("normal"))
        queue.beginNext(0L, false, ::noTurnPassed)
        val result = queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL))
        assertEquals("normal", result.stopActive?.id)
        assertEquals("normal", queue.activeItem?.id)
        assertEquals("normal", queue.finishActive("normal")?.id)
        assertEquals("turn", queue.beginNext(1L, false, ::noTurnPassed).started?.id)
    }

    @Test
    fun playingProtectedSunsetIsNotStoppedByTurnNow() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("sunset", protected = true, source = "guidance"))
        queue.beginNext(0L, false, ::noTurnPassed)
        val result = queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL))
        assertNull(result.stopActive)
        assertEquals("sunset", queue.activeItem?.id)
        assertEquals("turn", queue.pendingItems.single().id)
    }

    @Test
    fun normalArrivalPreservesExistingFifoQueue() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("first"))
        queue.enqueue(item("second", source = "ondemand"))
        val result = queue.enqueue(item("third"))
        assertTrue(result.dropped.isEmpty())
        assertEquals(listOf("first", "second", "third"), queue.pendingItems.map { it.id })
    }

    @Test
    fun passedPendingTurnIsDropped() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL, turnIndex = 4))
        val dropped = queue.refreshPendingTurns(1L, false) { it == 4 }
        assertEquals(listOf("passed"), dropped.map { it.reason })
        assertEquals(listOf(4), queue.passedTurns.toList())
    }

    @Test
    fun tenSecondCapRequiresStaleLocationAndKeepsEntryBeforeDeadline() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("turn-9999", VoicePriority.TIME_CRITICAL, turnIndex = 5, enqueuedAtMillis = 100L))
        assertTrue(queue.refreshPendingTurns(10_099L, true, ::noTurnPassed).isEmpty())
        queue.enqueue(item("turn-10000", VoicePriority.TIME_CRITICAL, turnIndex = 6, enqueuedAtMillis = 100L))
        val dropped = queue.refreshPendingTurns(10_100L, true, ::noTurnPassed)
        assertEquals(listOf("turn-10000"), dropped.map { it.item.id })
        assertEquals(listOf("stale-10s"), dropped.map { it.reason })
    }

    @Test
    fun tenSecondCapDoesNotDropWhenLocationIsFresh() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL, enqueuedAtMillis = 0L))
        assertTrue(queue.refreshPendingTurns(10_000L, false, ::noTurnPassed).isEmpty())
        assertEquals(listOf("turn"), queue.pendingItems.map { it.id })
    }

    @Test
    fun turnIsCheckedAgainImmediatelyBeforePlayback() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL, turnIndex = 7))
        val result = queue.beginNext(1L, false) { it == 7 }
        assertNull(result.started)
        assertEquals(listOf("passed"), result.dropped.map { it.reason })
    }

    @Test
    fun passedTurnIndexNeverBecomesUnpassedAgain() {
        val queue = VoiceQueuePolicy()
        queue.enqueue(item("first", VoicePriority.TIME_CRITICAL, turnIndex = 8))
        queue.refreshPendingTurns(1L, false) { true }
        queue.enqueue(item("second", VoicePriority.TIME_CRITICAL, turnIndex = 8))
        val result = queue.beginNext(2L, false, ::noTurnPassed)
        assertNull(result.started)
        assertEquals("passed", result.dropped.single().reason)
    }

    @Test
    fun normalQueueHasNoConfiguredSizeCap() {
        val queue = VoiceQueuePolicy()
        repeat(50) { queue.enqueue(item("normal-$it")) }
        assertEquals(50, queue.pendingItems.size)
    }

    @Test
    fun everyAcceptedIdCanReachExactlyOneTerminalOutcome() {
        val queue = VoiceQueuePolicy()
        val accepted = mutableListOf<String>()
        val terminalCounts = mutableMapOf<String, Int>()
        fun recordDropped(dropped: List<VoiceDropped>) {
            dropped.forEach { terminalCounts.merge(it.item.id, 1, Int::plus) }
        }
        fun add(item: VoiceQueueItem) {
            accepted += item.id
            val result = queue.enqueue(item)
            recordDropped(result.dropped)
        }
        add(item("normal-active"))
        assertEquals("normal-active", queue.beginNext(0L, false, ::noTurnPassed).started?.id)
        add(item("normal-flushed"))
        val turnEnqueue = queue.enqueue(item("turn", VoicePriority.TIME_CRITICAL))
        accepted += "turn"
        recordDropped(turnEnqueue.dropped)
        assertEquals("normal-active", turnEnqueue.stopActive?.id)
        queue.finishActive("normal-active")?.let { terminalCounts.merge(it.id, 1, Int::plus) }
        assertEquals("turn", queue.beginNext(1L, false, ::noTurnPassed).started?.id)
        add(item("state-after-turn", VoicePriority.STATE_TRANSITION))
        queue.finishActive("turn")?.let { terminalCounts.merge(it.id, 1, Int::plus) }
        assertEquals("state-after-turn", queue.beginNext(2L, false, ::noTurnPassed).started?.id)
        val shutdown = queue.shutdown()
        shutdown.active?.let { terminalCounts.merge(it.id, 1, Int::plus) }
        recordDropped(shutdown.pending)
        assertEquals(accepted.toSet(), terminalCounts.keys)
        assertTrue(terminalCounts.values.all { it == 1 })
    }
}
