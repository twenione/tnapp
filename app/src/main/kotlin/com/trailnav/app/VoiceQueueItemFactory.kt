package com.trailnav.app

import com.trailnav.core.Guidance

/** Pure conversion from a voice request to the item consumed by VoiceQueuePolicy. */
internal fun voiceQueueItemFor(
    id: String,
    text: String,
    source: String,
    enqueuedAtMillis: Long,
    priority: VoicePriority = VoicePriority.NORMAL,
    protected: Boolean = false,
    turnIndex: Int? = null,
    guidance: Guidance? = null,
    reasonRule: String? = null,
    recovery: Boolean = false,
    onDemand: Boolean = false,
): VoiceQueueItem {
    val resolvedPriority = when {
        guidance == null -> priority
        onDemand -> onDemandVoicePriority(guidance)
        else -> priorityFor(guidance, reasonRule, recovery)
    }
    val resolvedProtection = guidance?.let { isProtected(it, recovery, reasonRule) } ?: protected
    val resolvedTurnIndex = if (guidance != null) (guidance as? Guidance.TurnNow)?.turnIndex else turnIndex

    return VoiceQueueItem(
        id = id,
        text = text,
        priority = resolvedPriority,
        protected = resolvedProtection,
        source = source,
        turnIndex = resolvedTurnIndex,
        enqueuedAtMillis = enqueuedAtMillis,
    )
}
