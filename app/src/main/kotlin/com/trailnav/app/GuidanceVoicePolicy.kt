package com.trailnav.app

/** Voice categories that share the pause gate. Kept free of Android APIs for JVM tests. */
internal enum class VoiceKind { GUIDANCE, RECOVERY, GPS, ON_ROUTE, SUNSET }

internal data class VoiceDecision(
    val allowed: Boolean,
    val suppressionReason: String? = null,
)

internal object GuidanceVoicePolicy {
    fun decide(paused: Boolean, kind: VoiceKind): VoiceDecision =
        if (paused && kind != VoiceKind.SUNSET) VoiceDecision(allowed = false, suppressionReason = "paused")
        else VoiceDecision(allowed = true)
}

internal fun shouldSpeakVoice(ending: Boolean, paused: Boolean, kind: VoiceKind): Boolean =
    !ending && GuidanceVoicePolicy.decide(paused, kind).allowed

/** Tracks one five-minute off-route episode and emits one pause affordance. */
internal class GuidancePauseAvailability(
    private val dwellMillis: Long = 5 * 60 * 1_000L,
) {
    private var offRouteSince: Long? = null
    private var prompted = false

    fun onFrame(offRoute: Boolean, nowMillis: Long): Boolean {
        if (!offRoute) {
            reset()
            return false
        }
        val since = offRouteSince
        if (since == null || nowMillis < since) {
            offRouteSince = nowMillis
            prompted = false
            return false
        }
        if (!prompted && nowMillis - since >= dwellMillis) {
            prompted = true
            return true
        }
        return false
    }

    fun reset() {
        offRouteSince = null
        prompted = false
    }
}
