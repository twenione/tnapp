package com.trailnav.app

import com.trailnav.core.Guidance
import com.trailnav.core.Side
import com.trailnav.core.SlopeKind
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceQueueItemFactoryTest {
    @Test
    fun turnaroundGuidanceCanBeConvertedIntoQueueItem() {
        val item = voiceQueueItemFor(
            id = "turnaround",
            text = "반환점입니다.",
            source = "guidance",
            enqueuedAtMillis = 1_000L,
            guidance = Guidance.Turnaround,
            reasonRule = "turnaround.now",
        )

        assertEquals(VoicePriority.TIME_CRITICAL, item.priority)
        assertEquals(null, item.turnIndex)
    }

    @Test
    fun everyGuidanceSubclassCanBeQueuedForGuidanceAndOnDemand() {
        val guidanceKinds = Guidance::class.sealedSubclasses
        assertTrue(guidanceKinds.isNotEmpty())
        val examples = guidanceKinds.map(::exampleFor)
        assertEquals(guidanceKinds.size, examples.size)
        assertEquals(examples.size, examples.distinctBy { it::class }.size)

        examples.forEachIndexed { index, guidance ->
            val reasonRule = guidance.reasonRuleForTest()
            val guidanceItem = voiceQueueItemFor(
                id = "guidance-$index",
                text = "guidance-${guidance::class.simpleName}",
                source = "guidance",
                enqueuedAtMillis = index.toLong(),
                guidance = guidance,
                reasonRule = reasonRule,
            )
            assertEquals(priorityFor(guidance, reasonRule, recovery = false), guidanceItem.priority)

            val onDemandItem = voiceQueueItemFor(
                id = "on-demand-$index",
                text = "on-demand-${guidance::class.simpleName}",
                source = "ondemand",
                enqueuedAtMillis = index.toLong(),
                guidance = guidance,
                reasonRule = "on-demand.route-status",
                onDemand = true,
            )
            assertEquals(onDemandVoicePriority(guidance), onDemandItem.priority)
        }
    }

    @Test
    fun turnaroundRemainsImmediateAndTurnNowKeepsItsIndexedQueueEntry() {
        val queue = VoiceQueuePolicy()
        val active = voiceQueueItemFor(
            id = "active",
            text = "active",
            source = "guidance",
            enqueuedAtMillis = 0L,
            guidance = Guidance.Status("returning"),
            reasonRule = "direction.switched.turnaround",
        )
        queue.enqueue(active)
        assertEquals(active, queue.beginNext(0L, locationStale = false) { false }.started)

        val delayed = voiceQueueItemFor(
            id = "delayed",
            text = "delayed",
            source = "guidance",
            enqueuedAtMillis = 1L,
        )
        queue.enqueue(delayed)
        val turnaround = voiceQueueItemFor(
            id = "turnaround-now",
            text = "반환점입니다.",
            source = "guidance",
            enqueuedAtMillis = 2L,
            guidance = Guidance.Turnaround,
            reasonRule = "turnaround.now",
        )

        assertEquals(VoicePriority.TIME_CRITICAL, turnaround.priority)
        assertEquals(null, turnaround.turnIndex)
        val preemption = queue.enqueue(turnaround)
        assertEquals(active, preemption.stopActive)
        assertEquals(turnaround, queue.pendingItems.first())
        assertTrue(preemption.dropped.any { it.item == delayed })

        queue.finishActive(active.id)
        assertEquals(turnaround, queue.beginNext(3L, locationStale = false) { false }.started)

        val turnNow = voiceQueueItemFor(
            id = "turn-now",
            text = "지금 오른쪽으로 꺾입니다.",
            source = "guidance",
            enqueuedAtMillis = 4L,
            guidance = Guidance.TurnNow(Side.RIGHT, 7),
            reasonRule = "turn.now",
        )
        assertEquals(7, turnNow.turnIndex)
        assertEquals(VoicePriority.TIME_CRITICAL, turnNow.priority)
    }

    @Test
    fun failedVoiceCleanupRemovesOnlyItsOwnPendingItem() {
        val queue = VoiceQueuePolicy()
        val before = voiceQueueItemFor("before", "before", "guidance", 0L)
        val failed = voiceQueueItemFor("failed", "failed", "guidance", 1L)
        val after = voiceQueueItemFor("after", "after", "guidance", 2L)
        queue.enqueue(before)
        queue.enqueue(failed)
        queue.enqueue(after)

        assertEquals(failed, queue.removePending(failed.id))
        assertEquals(listOf(before, after), queue.pendingItems)
    }

    private fun exampleFor(kind: KClass<out Guidance>): Guidance {
        kind.objectInstance?.let { return it }
        val constructor = kind.primaryConstructor ?: error("No primary constructor for ${kind.qualifiedName}")
        val requiredArguments = constructor.parameters
            .filterNot { it.isOptional }
            .associateWith { parameter -> sampleArgument(parameter.type) }
        return constructor.callBy(requiredArguments)
    }

    private fun sampleArgument(type: KType): Any? {
        if (type.isMarkedNullable) return null
        return when (type.classifier) {
            String::class -> "sample"
            Double::class -> 1.0
            Int::class -> 1
            Boolean::class -> false
            Side::class -> Side.RIGHT
            SlopeKind::class -> SlopeKind.ASCENT
            else -> error("No sample value for sealed subtype constructor parameter $type")
        }
    }

    private fun Guidance.reasonRuleForTest(): String = when (this) {
        is Guidance.OffRoute -> "off-route.enter"
        is Guidance.Approach -> "approach.pre-entry"
        is Guidance.WaitingStart -> "waiting.enter"
        is Guidance.TurnAhead -> "turn.ahead"
        is Guidance.TurnNow -> "turn.now"
        is Guidance.TurnaroundAhead -> "turnaround.ahead"
        Guidance.Turnaround -> "turnaround.now"
        Guidance.RoundTripStart -> "route.round-trip.start"
        is Guidance.Milestone -> "event.milestone"
        is Guidance.Elapsed -> "event.elapsed"
        is Guidance.Remaining -> "event.remaining"
        is Guidance.Slope -> "event.slope"
        is Guidance.Elevation -> "event.elevation"
        is Guidance.Waypoint -> "event.waypoint"
        is Guidance.Sunset -> "event.sunset"
        is Guidance.Sunrise -> "event.sunrise"
        is Guidance.Status -> "on-demand.route-status"
        Guidance.Arrived -> "arrival.radius-and-progress"
        Guidance.ArrivedAtStart -> "arrival.radius-and-progress"
    }
}
