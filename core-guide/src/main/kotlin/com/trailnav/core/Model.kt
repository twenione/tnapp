package com.trailnav.core

/** A single location sample supplied by the host application. */
data class SensorFrame(
    /** Epoch milliseconds; a timestamp earlier than the previous sample contributes zero elapsed time. */
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float?,
    val gpsBearing: Float?
)

enum class Side { LEFT, RIGHT }

enum class ProgressDirection { FORWARD, REVERSE, STATIONARY, UNKNOWN }

/** Structured explanation written verbatim to the guide stream by callers. */
data class Reason(
    val rule: String,
    val thresholds: Map<String, Double> = emptyMap(),
    val alternativesConsidered: List<String> = emptyList(),
    val details: Map<String, String> = emptyMap()
)

sealed class Guidance {
    data class OffRoute(val distance: Double, val direction: String) : Guidance()
    data class TurnAhead(val distance: Double, val side: Side) : Guidance()
    data class TurnNow(val side: Side) : Guidance()
    data class Status(
        val message: String,
        val distance: Double? = null,
        val direction: String? = null
    ) : Guidance()
    object Arrived : Guidance()
}

/** The result of one pure guide evaluation. */
data class GuideResult(
    val guidance: Guidance?,
    val nextState: GuideState,
    val reason: Reason
)

/**
 * All tunable engine parameters live here.  Physical constants used by the
 * coordinate projection are fixed properties of the earth, rather than guide
 * policy.  The sensitivity lists make the D-029 completeness check explicit.
 */
data class GuideConfig(
    val accuracyRejectMeters: Double = 50.0,
    val stationarySpeedMps: Double = 0.3,
    val searchWindowMeters: Double = 200.0,
    val emaAlpha: Double = 0.5,
    val reverseWarningDwellSeconds: Double = 60.0,
    val offRouteEnterDistMeters: Double = 25.0,
    val offRouteEnterDwellSeconds: Double = 20.0,
    val offRouteExitDistMeters: Double = 15.0,
    val offRouteExitDwellSeconds: Double = 10.0,
    val reannounceIntervalSeconds: Double = 60.0,
    val arriveRadiusMeters: Double = 25.0,
    val arriveProgressFraction: Double = 0.95,
    val minimumPointSpacingMeters: Double = 1.0,
    val maximumPointGapMeters: Double = 50.0,
    val douglasPeuckerEpsilonMeters: Double = 5.0,
    val turnLookbackMeters: Double = 30.0,
    val turnLookaheadMeters: Double = 30.0,
    val turnAngleThresholdDegrees: Double = 45.0,
    val turnAheadDistanceMeters: Double = 60.0,
    val turnNowDistanceMeters: Double = 15.0,
    val turnOnRouteMaxOffsetMeters: Double = 15.0,
    /** Maximum route look-ahead used while extracting deterministic E4 slope segments. */
    val slopeLookaheadMeters: Double = 200.0,
    /** Minimum smoothed elevation delta for an E4 segment. */
    val slopeMinDeltaMeters: Double = 20.0,
    /** Hysteresis used to close a slope segment after the trend flattens. */
    val slopeHysteresisMeters: Double = 5.0,
    /** Maximum perpendicular distance for an eligible waypoint. */
    val waypointNearRouteMeters: Double = 50.0,
    /** Maximum sanitized waypoint name length. */
    val waypointNameMaxLength: Int = 80,
    /** A residual above this value marks an elevation profile unstable. */
    val elevationSpikeThresholdMeters: Double = 10.0,
    val spatialGridSizeMeters: Double = 100.0,
    val taggingOffRouteDistanceMeters: Double = 30.0,
    val taggingOffRouteDwellSeconds: Double = 60.0,
    val minimumSessionSecondsBeforeArrival: Double = 10.0
) {
    init {
        require(accuracyRejectMeters >= 0.0)
        require(stationarySpeedMps >= 0.0)
        require(searchWindowMeters > 0.0)
        require(emaAlpha in 0.0..1.0)
        require(offRouteExitDistMeters <= offRouteEnterDistMeters)
        require(arriveProgressFraction in 0.0..1.0)
        require(minimumPointSpacingMeters >= 0.0)
        require(maximumPointGapMeters > 0.0)
        require(douglasPeuckerEpsilonMeters >= 0.0)
        require(spatialGridSizeMeters > 0.0)
        require(minimumSessionSecondsBeforeArrival >= 0.0)
        require(turnAheadDistanceMeters > 0.0)
        require(turnNowDistanceMeters > 0.0)
        require(turnNowDistanceMeters < turnAheadDistanceMeters)
        require(turnOnRouteMaxOffsetMeters > 0.0)
        require(slopeLookaheadMeters > 0.0)
        require(slopeMinDeltaMeters > 0.0)
        require(slopeHysteresisMeters >= 0.0)
        require(waypointNearRouteMeters >= 0.0)
        require(waypointNameMaxLength > 0)
        require(elevationSpikeThresholdMeters > 0.0)
    }

    companion object {
        /** Parameters whose values must change the Phase 1 guide output. */
        val sensitivityRequired: List<String> = listOf(
            "offRouteEnterDistMeters",
            "offRouteEnterDwellSeconds",
            "offRouteExitDistMeters",
            "offRouteExitDwellSeconds",
            "reannounceIntervalSeconds",
            "turnAheadDistanceMeters",
            "turnNowDistanceMeters",
            "turnOnRouteMaxOffsetMeters"
        )

        /** Explicit exclusions required by D-029 for non-sensitivity fields. */
        val sensitivityExcluded: Map<String, String> = mapOf(
            "accuracyRejectMeters" to "input filter threshold; covered by matching tests",
            "stationarySpeedMps" to "movement classification; covered by matching tests",
            "searchWindowMeters" to "matching search bound; covered by out-and-back test",
            "emaAlpha" to "progress smoothing; covered by direction test",
            "reverseWarningDwellSeconds" to "reverse warning timing; covered by matching tests",
            "arriveRadiusMeters" to "fixed Phase 1 arrival criterion; covered by arrival tests",
            "arriveProgressFraction" to "fixed Phase 1 arrival criterion; covered by arrival tests",
            "minimumPointSpacingMeters" to "route preprocessing criterion",
            "maximumPointGapMeters" to "route warning criterion",
            "douglasPeuckerEpsilonMeters" to "route preprocessing criterion",
            "turnLookbackMeters" to "turn extraction criterion for Phase 3",
            "turnLookaheadMeters" to "turn extraction criterion for Phase 3",
            "turnAngleThresholdDegrees" to "fixed Phase 1 turn extraction criterion",
            "slopeLookaheadMeters" to "covered by ElevationRouteTest.slopeProfileUsesLookahead",
            "slopeMinDeltaMeters" to "covered by ElevationRouteTest.slopeProfileUsesThreshold",
            "slopeHysteresisMeters" to "covered by ElevationRouteTest.slopeProfileUsesHysteresis",
            "waypointNearRouteMeters" to "covered by ElevationRouteTest.waypointNearRouteFilter",
            "waypointNameMaxLength" to "covered by ElevationRouteTest.waypointNameIsSanitized",
            "elevationSpikeThresholdMeters" to "covered by ElevationRouteTest.spikeIsUnstable",
            "spatialGridSizeMeters" to "route index implementation parameter",
            "taggingOffRouteDistanceMeters" to "Phase 0 tagging parameter; not consumed by guide",
            "taggingOffRouteDwellSeconds" to "Phase 0 tagging parameter; not consumed by guide",
            "minimumSessionSecondsBeforeArrival" to "startup arrival guard; covered by the immediate-arrival regression test"
        )

        fun sensitivityFieldNames(): Set<String> =
            sensitivityRequired.toSet() + sensitivityExcluded.keys
    }
}

data class MatchResult(
    val distanceMeters: Double,
    val projectedMeters: Double,
    val segmentIndex: Int,
    val projectedPoint: EnuPoint,
    val direction: ProgressDirection = ProgressDirection.UNKNOWN
)

/** Immutable state carried from one frame to the next. */
data class GuideState(
    val route: RouteModel,
    val lastMatch: MatchResult? = null,
    val lastTimestamp: Long? = null,
    val sessionStartTimestamp: Long? = null,
    val emaDeltaMeters: Double = 0.0,
    val direction: ProgressDirection = ProgressDirection.UNKNOWN,
    val stationary: Boolean = false,
    val candidateOffRouteSince: Long? = null,
    val candidateOffRouteRecoverySince: Long? = null,
    val offRouteSince: Long? = null,
    val exitCandidateSince: Long? = null,
    val offRoute: Boolean = false,
    val lastAnnouncementAt: Long? = null,
    val lastAnnouncementDistance: Double? = null,
    val reverseSince: Long? = null,
    val arrived: Boolean = false,
    val completedTurnAheadIndices: Set<Int> = emptySet(),
    val completedTurnNowIndices: Set<Int> = emptySet()
) {
    companion object {
        fun initial(route: RouteModel): GuideState = GuideState(route)
    }
}

data class NextTurn(
    val index: Int,
    val side: Side,
    val distanceMeters: Double,
    val angleDegrees: Double
)

data class RouteStatus(
    val onRoute: Boolean,
    val offRouteDistanceMeters: Double?,
    val direction: ProgressDirection,
    val remainingMeters: Double,
    val nextTurn: NextTurn?,
    val arrived: Boolean
)

/** Pure state report shared by on-demand voice and the route ribbon. */
fun routeStatus(state: GuideState, config: GuideConfig = GuideConfig()): RouteStatus? {
    val match = state.lastMatch ?: return null
    val nextTurn = if (state.direction == ProgressDirection.REVERSE) {
        null
    } else {
        state.route.turns.asSequence()
            .withIndex()
            .firstOrNull { (_, turn) -> turn.s > match.projectedMeters }
            ?.let { (index, turn) ->
                NextTurn(
                    index = index,
                    side = turn.side,
                    distanceMeters = turn.s - match.projectedMeters,
                    angleDegrees = turn.angleDegrees
                )
            }
    }
    return RouteStatus(
        onRoute = !state.offRoute,
        offRouteDistanceMeters = if (state.offRoute) match.distanceMeters else null,
        direction = state.direction,
        remainingMeters = (state.route.totalLengthMeters - match.projectedMeters).coerceAtLeast(0.0),
        nextTurn = nextTurn,
        arrived = state.arrived
    )
}
