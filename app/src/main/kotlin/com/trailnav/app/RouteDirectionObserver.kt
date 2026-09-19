package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel

enum class RoutePreparationStage(val wireName: String, val label: String) {
    PREPARING("preparing", "안내를 준비중"),
    START_FOUND("start-found", "시작점 잡힘"),
    DIRECTION_CONFIRMED("direction-confirmed", "경로 방향 잡힘 (안내를 시작합니다)");

    companion object {
        fun fromWire(value: String?): RoutePreparationStage =
            entries.firstOrNull { it.wireName == value } ?: PREPARING
    }
}

enum class RouteDirectionObservationOutcome {
    WAITING_FOR_MATCH,
    START_FOUND,
    FORWARD,
    REVERSE,
    FALLBACK_TIMEOUT,
    FALLBACK_NO_NET_DISPLACEMENT,
}

data class RouteDirectionObservation(
    val outcome: RouteDirectionObservationOutcome,
    val direction: ProgressDirection? = null,
    val netDisplacementMeters: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
) {
    val resolved: Boolean
        get() = outcome == RouteDirectionObservationOutcome.FORWARD ||
            outcome == RouteDirectionObservationOutcome.REVERSE ||
            outcome == RouteDirectionObservationOutcome.FALLBACK_TIMEOUT ||
            outcome == RouteDirectionObservationOutcome.FALLBACK_NO_NET_DISPLACEMENT
}

/**
 * Observes recent-match progress without changing the pure guide engine.
 * The first matched projection is the reference; only net movement from it
 * can resolve direction, so back-and-forth GPS noise does not accumulate.
 */
class RouteDirectionObserver(
    route: RouteModel,
    private val config: GuideConfig = GuideConfig(),
    private val directionThresholdMeters: Double = 15.0,
    private val timeoutMillis: Long = 30_000L,
    startupAtMillis: Long? = null,
) {
    private val session = GuideSession(route, config)
    private var startedAtMillis: Long? = startupAtMillis
    private var firstProjectionMeters: Double? = null
    private var lastProjectionMeters: Double? = null
    private var resolvedObservation: RouteDirectionObservation? = null

    fun observe(location: TrailLocation, observedAtMillis: Long = location.timestampMillis): RouteDirectionObservation {
        resolvedObservation?.let { return it }
        if (startedAtMillis == null) startedAtMillis = observedAtMillis
        val elapsedSeconds = elapsedSeconds(observedAtMillis)
        val decision = session.accept(location)
        val projection = decision.result.nextState.lastMatch?.projectedMeters
            ?: return RouteDirectionObservation(
                outcome = RouteDirectionObservationOutcome.WAITING_FOR_MATCH,
                elapsedSeconds = elapsedSeconds,
            )
        lastProjectionMeters = projection
        val first = firstProjectionMeters
        if (first == null) {
            firstProjectionMeters = projection
            return RouteDirectionObservation(
                outcome = RouteDirectionObservationOutcome.START_FOUND,
                elapsedSeconds = elapsedSeconds,
            )
        }
        val netDisplacement = projection - first
        when {
            netDisplacement >= directionThresholdMeters -> return resolve(
                RouteDirectionObservation(
                    outcome = RouteDirectionObservationOutcome.FORWARD,
                    direction = ProgressDirection.FORWARD,
                    netDisplacementMeters = netDisplacement,
                    elapsedSeconds = elapsedSeconds,
                ),
            )
            netDisplacement <= -directionThresholdMeters -> return resolve(
                RouteDirectionObservation(
                    outcome = RouteDirectionObservationOutcome.REVERSE,
                    direction = ProgressDirection.REVERSE,
                    netDisplacementMeters = netDisplacement,
                    elapsedSeconds = elapsedSeconds,
                ),
            )
            elapsedMillis(observedAtMillis) >= timeoutMillis -> return resolve(
                RouteDirectionObservation(
                    outcome = RouteDirectionObservationOutcome.FALLBACK_NO_NET_DISPLACEMENT,
                    netDisplacementMeters = netDisplacement,
                    elapsedSeconds = elapsedSeconds,
                ),
            )
            else -> return RouteDirectionObservation(
                outcome = RouteDirectionObservationOutcome.START_FOUND,
                netDisplacementMeters = netDisplacement,
                elapsedSeconds = elapsedSeconds,
            )
        }
    }

    /** Resolve when the service timeout fires, including when no new fix arrived. */
    fun timeout(observedAtMillis: Long): RouteDirectionObservation {
        resolvedObservation?.let { return it }
        return resolve(
            RouteDirectionObservation(
                outcome = RouteDirectionObservationOutcome.FALLBACK_TIMEOUT,
                netDisplacementMeters = netDisplacementMeters(),
                elapsedSeconds = elapsedSeconds(observedAtMillis),
            ),
        )
    }

    private fun resolve(observation: RouteDirectionObservation): RouteDirectionObservation {
        resolvedObservation = observation
        return observation
    }

    private fun netDisplacementMeters(): Double {
        val first = firstProjectionMeters ?: return 0.0
        return (lastProjectionMeters ?: first) - first
    }

    private fun elapsedMillis(now: Long): Long = (now - (startedAtMillis ?: now)).coerceAtLeast(0L)

    private fun elapsedSeconds(now: Long): Double = elapsedMillis(now) / 1_000.0
}
