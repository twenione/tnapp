package com.trailnav.app

import java.util.ArrayDeque

internal const val TURN_NOW_UNPASSED_CAP_MILLIS = 10_000L

internal data class VoiceQueueItem(
    val id: String,
    val text: String,
    val priority: VoicePriority,
    val protected: Boolean,
    val source: String,
    val turnIndex: Int?,
    val enqueuedAtMillis: Long,
) {
    init {
        require(source in VOICE_SOURCES) { "unknown voice source: $source" }
        require(priority != VoicePriority.TIME_CRITICAL || turnIndex != null) {
            "TIME_CRITICAL utterances must identify their turn"
        }
    }

    companion object {
        val VOICE_SOURCES = setOf("guidance", "recovery", "ondemand", "gps", "system")
    }
}

internal data class VoiceDropped(val item: VoiceQueueItem, val reason: String)

internal data class VoiceEnqueueResult(
    val dropped: List<VoiceDropped> = emptyList(),
    val stopActive: VoiceQueueItem? = null,
)

internal data class VoiceStartResult(
    val started: VoiceQueueItem? = null,
    val dropped: List<VoiceDropped> = emptyList(),
)

internal data class VoiceQueueShutdown(
    val active: VoiceQueueItem?,
    val pending: List<VoiceDropped>,
)

/** App-owned FIFO with the only priority preemption used by TURN_NOW. */
internal class VoiceQueuePolicy {
    private val waiting = ArrayDeque<VoiceQueueItem>()
    private val passedTurnIndexes = mutableSetOf<Int>()
    private var active: VoiceQueueItem? = null
    private var stopRequestedFor: String? = null

    val activeItem: VoiceQueueItem? get() = active
    val pendingItems: List<VoiceQueueItem> get() = waiting.toList()
    val passedTurns: Set<Int> get() = passedTurnIndexes.toSet()

    fun enqueue(item: VoiceQueueItem): VoiceEnqueueResult {
        val dropped = mutableListOf<VoiceDropped>()
        var stop: VoiceQueueItem? = null
        if (item.priority == VoicePriority.TIME_CRITICAL) {
            val retained = ArrayDeque<VoiceQueueItem>()
            while (waiting.isNotEmpty()) {
                val pending = waiting.removeFirst()
                if (isDiscardableByTurn(pending)) dropped += VoiceDropped(pending, "flushed")
                else retained.addLast(pending)
            }
            waiting.addAll(retained)
            val current = active
            if (current != null && !isNonInterruptible(current) && stopRequestedFor != current.id) {
                stopRequestedFor = current.id
                stop = current
            }
            waiting.addFirst(item)
        } else {
            waiting.addLast(item)
        }
        return VoiceEnqueueResult(dropped = dropped, stopActive = stop)
    }

    /** Drops passed or stale queued TURN_NOW entries and remembers passed indexes monotonically. */
    fun refreshPendingTurns(
        nowMillis: Long,
        locationStale: Boolean,
        hasPassed: (Int) -> Boolean,
    ): List<VoiceDropped> {
        val dropped = mutableListOf<VoiceDropped>()
        val retained = ArrayDeque<VoiceQueueItem>()
        while (waiting.isNotEmpty()) {
            val item = waiting.removeFirst()
            val reason = dropReason(item, nowMillis, locationStale, hasPassed)
            if (reason == null) retained.addLast(item) else dropped += VoiceDropped(item, reason)
        }
        waiting.addAll(retained)
        return dropped
    }

    /** Selects one next utterance, checking TURN_NOW again immediately before playback. */
    fun beginNext(
        nowMillis: Long,
        locationStale: Boolean,
        hasPassed: (Int) -> Boolean,
    ): VoiceStartResult {
        val dropped = refreshPendingTurns(nowMillis, locationStale, hasPassed)
        if (active != null) return VoiceStartResult(dropped = dropped)
        val next = waiting.pollFirst() ?: return VoiceStartResult(dropped = dropped)
        active = next
        stopRequestedFor = null
        return VoiceStartResult(started = next, dropped = dropped)
    }

    fun finishActive(id: String): VoiceQueueItem? {
        val current = active ?: return null
        if (current.id != id) return null
        active = null
        stopRequestedFor = null
        return current
    }

    /** Initialization failure completes every accepted item with an error. */
    fun failAll(): List<VoiceQueueItem> {
        val failed = buildList {
            active?.let(::add)
            while (waiting.isNotEmpty()) add(waiting.removeFirst())
        }
        active = null
        stopRequestedFor = null
        return failed
    }

    /** Session shutdown terminates the active utterance and flushes all unstarted work. */
    fun shutdown(): VoiceQueueShutdown {
        val wasActive = active
        active = null
        stopRequestedFor = null
        val pending = buildList {
            while (waiting.isNotEmpty()) add(VoiceDropped(waiting.removeFirst(), "flushed"))
        }
        return VoiceQueueShutdown(wasActive, pending)
    }

    private fun dropReason(
        item: VoiceQueueItem,
        nowMillis: Long,
        locationStale: Boolean,
        hasPassed: (Int) -> Boolean,
    ): String? {
        if (item.priority != VoicePriority.TIME_CRITICAL) return null
        val turnIndex = item.turnIndex ?: return null
        if (turnIndex in passedTurnIndexes) return "passed"
        if (hasPassed(turnIndex)) {
            passedTurnIndexes += turnIndex
            return "passed"
        }
        val elapsed = nowMillis - item.enqueuedAtMillis
        return if (nowMillis >= item.enqueuedAtMillis && elapsed >= TURN_NOW_UNPASSED_CAP_MILLIS && locationStale) {
            "stale-10s"
        } else {
            null
        }
    }

    private fun isNonInterruptible(item: VoiceQueueItem): Boolean =
        item.priority == VoicePriority.STATE_TRANSITION || item.protected

    private fun isDiscardableByTurn(item: VoiceQueueItem): Boolean =
        item.priority != VoicePriority.STATE_TRANSITION && !item.protected
}
