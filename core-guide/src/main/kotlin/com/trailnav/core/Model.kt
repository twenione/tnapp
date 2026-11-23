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

enum class Compass8 { N, NE, E, SE, S, SW, W, NW }

enum class ProgressDirection { FORWARD, REVERSE, STATIONARY, UNKNOWN }

/** Structured explanation written verbatim to the guide stream by callers. */
data class Reason(
    val rule: String,
    val thresholds: Map<String, Double> = emptyMap(),
    val alternativesConsidered: List<String> = emptyList(),
    val details: Map<String, String> = emptyMap()
)

sealed class Guidance {
    data class OffRoute(val distance: Double, val direction: String, val returnHint: Boolean = false) : Guidance()
    object ReturnHint : Guidance()
    data class ReturnGuide(val backtrackMeters: Int?, val straightMeters: Int, val compass: Compass8) : Guidance()
    data class Approach(val distanceMeters: Double, val bearingDegrees: Double) : Guidance()
    data class WaitingStart(val distanceMeters: Double) : Guidance()
    data class TurnAhead(val distance: Double, val side: Side, val turnIndex: Int) : Guidance()
    data class TurnNow(val side: Side, val turnIndex: Int) : Guidance()
    data class TurnaroundAhead(val distance: Double) : Guidance()
    object Turnaround : Guidance()
    object RoundTripStart : Guidance()
    data class Milestone(val distanceMeters: Double) : Guidance()
    data class Elapsed(val hours: Int) : Guidance()
    data class Remaining(val thresholdMeters: Double, val remainingMeters: Double, val returnLeg: Boolean = false) : Guidance()
    data class Slope(val kind: SlopeKind, val deltaMeters: Double) : Guidance()
    data class Elevation(val elevationMeters: Double) : Guidance()
    data class Waypoint(val index: Int, val name: String, val distanceMeters: Double) : Guidance()
    data class Sunset(val minutesRemaining: Int?, val afterSunset: Boolean = false) : Guidance()
    data class Sunrise(val minutesRemaining: Int) : Guidance()
    data class Status(
        val message: String,
        val distance: Double? = null,
        val direction: String? = null
    ) : Guidance()
    object Arrived : Guidance()
    object ArrivedAtStart : Guidance()
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
    val offRouteEnterDistMeters: Double = 25.0,
    val offRouteEnterDwellSeconds: Double = 20.0,
    val offRouteExitDistMeters: Double = 15.0,
    val offRouteExitDwellSeconds: Double = 10.0,
    val returnGuideTriggerMeters: Double = 200.0,
    val reannounceIntervalSeconds: Double = 60.0,
    val arriveRadiusMeters: Double = 25.0,
    val arriveProgressFraction: Double = 0.95,
    val arrivalWalkMaxSpeedMps: Double = 4.0,
    val arrivalJumpToleranceMeters: Double = 40.0,
    val arrivalAdvanceFraction: Double = 0.8,
    val arrivalMinAdvanceMeters: Double = 50.0,
    val minimumPointSpacingMeters: Double = 1.0,
    val maximumPointGapMeters: Double = 50.0,
    val douglasPeuckerEpsilonMeters: Double = 5.0,
    val turnLookbackMeters: Double = 30.0,
    val turnLookaheadMeters: Double = 30.0,
    val turnAngleThresholdDegrees: Double = 45.0,
    val turnAheadDistanceMeters: Double = 60.0,
    val turnNowDistanceMeters: Double = 10.0,
    val turnMergeGapMeters: Double = 60.0,
    val turnOnRouteMaxOffsetMeters: Double = 15.0,
    val eventMinIntervalSeconds: Double = 60.0,
    val milestoneIntervalMeters: Double = 1000.0,
    val remainingAnnounceMeters: List<Double> = listOf(2000.0, 1000.0, 500.0),
    val elapsedAnnounceIntervalSeconds: Double = 3600.0,
    val peakAnnounceLeadMeters: Double = 100.0,
    val waypointAnnounceLeadMeters: Double = 200.0,
    val sunsetAnnounceMinutes: List<Int> = listOf(60, 30),
    val sunriseAnnounceMinutes: List<Int> = listOf(30, 10),
    val elevationBoundaryMeters: Double = 100.0,
    val elevationHysteresisMeters: Double = 10.0,
    val milestoneEnabled: Boolean = false,
    val elapsedEnabled: Boolean = false,
    val remainingEnabled: Boolean = false,
    val slopeEnabled: Boolean = false,
    val elevationEnabled: Boolean = false,
    val waypointEnabled: Boolean = false,
    val sunsetEnabled: Boolean = true,
    val sunriseEnabled: Boolean = false,
    /** Minimum prominence required to confirm a route peak for E4. */
    val peakProminenceMeters: Double = 30.0,
    /** Route distance within which a waypoint suppresses a duplicate peak announcement. */
    val peakWaypointDedupeMeters: Double = 150.0,
    /** Maximum perpendicular distance for an eligible waypoint. */
    val waypointNearRouteMeters: Double = 50.0,
    /** Maximum sanitized waypoint name length. */
    val waypointNameMaxLength: Int = 80,
    /** A residual above this value marks an elevation profile unstable. */
    val elevationSpikeThresholdMeters: Double = 10.0,
    val elevationGridMeters: Double = 5.0,
    val elevationMedianHalfWindowMeters: Double = 10.0,
    val elevationMeanHalfWindowMeters: Double = 5.0,
    val spatialGridSizeMeters: Double = 100.0,
    val taggingOffRouteDistanceMeters: Double = 30.0,
    val taggingOffRouteDwellSeconds: Double = 60.0,
    val minimumSessionSecondsBeforeArrival: Double = 10.0,
    val etaWindowSeconds: Double = 600.0,
    val etaMinWindowMeters: Double = 300.0,
    val etaMinMovingSpeedMps: Double = 0.3,
    val etaCorrectionMin: Double = 0.5,
    val etaCorrectionMax: Double = 2.0,
    val etaSlopeWindowMeters: Double = 50.0,
    val etaMinSegmentSpeedMps: Double = 0.15,
    val etaMaxTotalSeconds: Double = 86_400.0,
    val etaMinAverageSpeedMps: Double = 0.10,
    val elevationSpikeMaxRatio: Double = 0.02,
    /** Walked route distance required before the start trajectory is matched. */
    val startTrajectoryMeters: Double = 30.0,
    /** Maximum RMS-fit error gap treated as an equivalent trajectory interpretation. */
    val startTrajectoryMatchToleranceMeters: Double = 1.0,
    /** Minimum route-progress separation used to identify overlapping route portions. */
    val overlapMinRouteGapMeters: Double = 100.0,
    /** Maximum spatial separation considered an overlapping route portion. */
    val overlapNearMeters: Double = 15.0,
    /** Minimum location-update gap before displacement is considered for trajectory rematching. */
    val positionGapRematchSeconds: Double = 30.0,
    /** Maximum plausible straight-line walking speed during a location-update gap. */
    val gapMaxWalkSpeedMps: Double = 2.0,
    /** Reverse distance required before switching the route interpretation. */
    val directionSwitchDistanceMeters: Double = 150.0,
    /** Opt-in engine-only round-trip routing. */
    val roundTripEnabled: Boolean = false,
    /** Endpoint gap at or below this distance identifies a loop route. */
    val roundTripLoopMaxGapMeters: Double = 100.0,
    /** First accepted frame at or beyond this route distance starts a quiet waiting phase. */
    val waitingEnterMeters: Double = 2_000.0,
    /** Waiting ends on the first accepted frame strictly inside this route distance. */
    val waitingExitMeters: Double = 500.0,
    /** Location provider interval requested while the engine is waiting for the route. */
    val waitingLocationIntervalMillis: Long = 5_000L,
    val routeQualityGapWarnMeters: Double = 100.0,
    val routeQualityShortSegmentMeters: Double = 6.0,
) {
    init {
        require(accuracyRejectMeters >= 0.0)
        require(stationarySpeedMps >= 0.0)
        require(searchWindowMeters > 0.0)
        require(offRouteExitDistMeters <= offRouteEnterDistMeters)
        require(returnGuideTriggerMeters >= offRouteEnterDistMeters)
        require(arriveProgressFraction in 0.0..1.0)
        require(arrivalWalkMaxSpeedMps > 0.0)
        require(arrivalJumpToleranceMeters >= 0.0)
        require(arrivalAdvanceFraction > 0.0 && arrivalAdvanceFraction <= 1.0)
        require(arrivalMinAdvanceMeters >= 0.0)
        require(minimumPointSpacingMeters >= 0.0)
        require(maximumPointGapMeters > 0.0)
        require(douglasPeuckerEpsilonMeters >= 0.0)
        require(spatialGridSizeMeters > 0.0)
        require(minimumSessionSecondsBeforeArrival >= 0.0)
        require(turnAheadDistanceMeters > 0.0)
        require(turnNowDistanceMeters > 0.0)
        require(turnNowDistanceMeters < turnAheadDistanceMeters)
        require(turnMergeGapMeters >= 0.0)
        require(turnOnRouteMaxOffsetMeters > 0.0)
        require(eventMinIntervalSeconds >= 0.0)
        require(milestoneIntervalMeters > 0.0)
        require(remainingAnnounceMeters.all { it > 0.0 })
        require(elapsedAnnounceIntervalSeconds > 0.0)
        require(peakAnnounceLeadMeters >= 0.0)
        require(waypointAnnounceLeadMeters >= 0.0)
        require(sunsetAnnounceMinutes.all { it > 0 })
        require(sunriseAnnounceMinutes.all { it > 0 })
        require(elevationBoundaryMeters > 0.0)
        require(elevationHysteresisMeters >= 0.0)
        require(2.0 * elevationHysteresisMeters < elevationBoundaryMeters)
        require(peakProminenceMeters > 0.0)
        require(peakWaypointDedupeMeters >= 0.0)
        require(waypointNearRouteMeters >= 0.0)
        require(waypointNameMaxLength > 0)
        require(elevationSpikeThresholdMeters > 0.0)
        require(elevationGridMeters > 0.0)
        require(elevationMedianHalfWindowMeters > 0.0)
        require(elevationMeanHalfWindowMeters > 0.0)
        require(elevationSpikeMaxRatio in 0.0..1.0)
        require(startTrajectoryMeters > 0.0)
        require(startTrajectoryMatchToleranceMeters >= 0.0)
        require(overlapMinRouteGapMeters > 0.0)
        require(overlapNearMeters >= 0.0)
        require(positionGapRematchSeconds > 0.0)
        require(gapMaxWalkSpeedMps > 0.0)
        require(directionSwitchDistanceMeters > 0.0)
        require(roundTripLoopMaxGapMeters >= 0.0)
        require(waitingEnterMeters > waitingExitMeters)
        require(waitingExitMeters > offRouteExitDistMeters)
        require(waitingLocationIntervalMillis >= 1_000L)
        require(etaWindowSeconds > 0.0)
        require(etaMinWindowMeters >= 0.0)
        require(etaMinMovingSpeedMps >= 0.0)
        require(etaCorrectionMin > 0.0)
        require(etaCorrectionMax >= etaCorrectionMin)
        require(etaSlopeWindowMeters > 0.0)
        require(etaMinSegmentSpeedMps > 0.0)
        require(routeQualityGapWarnMeters > 0.0)
        require(routeQualityShortSegmentMeters > 0.0)
        require(etaMaxTotalSeconds > 0.0)
        require(etaMinAverageSpeedMps > 0.0)
    }

    companion object {
        /** Parameters whose values must change the Phase 1 guide output. */
        val sensitivityRequired: List<String> = listOf(
            "offRouteEnterDistMeters",
            "offRouteEnterDwellSeconds",
            "offRouteExitDistMeters",
            "offRouteExitDwellSeconds",
            "returnGuideTriggerMeters",
            "reannounceIntervalSeconds",
            "turnAheadDistanceMeters",
            "turnNowDistanceMeters",
            "turnOnRouteMaxOffsetMeters",
            "startTrajectoryMeters",
            "startTrajectoryMatchToleranceMeters",
            "positionGapRematchSeconds",
            "gapMaxWalkSpeedMps",
            "directionSwitchDistanceMeters",
        )

        /** Explicit exclusions required by D-029 for non-sensitivity fields. */
        val sensitivityExcluded: Map<String, String> = mapOf(
            "accuracyRejectMeters" to "input filter threshold; covered by matching tests",
            "roundTripEnabled" to "opt-in route mode; default false and covered by round-trip route tests",
            "roundTripLoopMaxGapMeters" to "route eligibility parameter; covered by loop-ignore and active-round-trip tests",
            "waitingEnterMeters" to "waiting entry threshold; covered by WaitingStateTest entry-boundary and one-time decision tests",
            "waitingExitMeters" to "waiting exit threshold; covered by WaitingStateTest exit-boundary and same-frame approach tests",
            "waitingLocationIntervalMillis" to "app location-provider cadence while waiting; covered by location interval policy tests",
            "stationarySpeedMps" to "movement classification; covered by matching tests",
            "searchWindowMeters" to "matching search bound; covered by out-and-back test",
            "arriveRadiusMeters" to "fixed Phase 1 arrival criterion; covered by arrival tests",
            "arriveProgressFraction" to "fixed Phase 1 arrival criterion; covered by arrival tests",
            "arrivalWalkMaxSpeedMps" to "covered by ArrivalAdvanceTest.arrivalWalkMaxSpeedChangesOutcome",
            "arrivalJumpToleranceMeters" to "covered by ArrivalAdvanceTest.arrivalJumpToleranceChangesOutcome",
            "arrivalAdvanceFraction" to "covered by ArrivalAdvanceTest.arrivalAdvanceFractionChangesOutcome",
            "arrivalMinAdvanceMeters" to "covered by ArrivalAdvanceTest.arrivalMinAdvanceMetersChangesOutcome",
            "overlapMinRouteGapMeters" to "overlap ranges are computed but no longer read by the guide after TASK-113 (isProgressInOverlap has no caller)",
            "overlapNearMeters" to "overlap ranges are computed but no longer read by the guide after TASK-113 (isProgressInOverlap has no caller)",
            "minimumPointSpacingMeters" to "route preprocessing criterion",
            "maximumPointGapMeters" to "route warning criterion",
            "douglasPeuckerEpsilonMeters" to "route preprocessing criterion",
            "turnLookbackMeters" to "turn extraction criterion for Phase 3",
            "turnLookaheadMeters" to "turn extraction criterion for Phase 3",
            "turnAngleThresholdDegrees" to "fixed Phase 1 turn extraction criterion",
            "turnMergeGapMeters" to "covered by TurnGuidanceTest.mergeGapConfigChangesSuppression",
            "peakProminenceMeters" to "covered by ElevationRouteTest.peakRequiresConfiguredProminence",
            "peakWaypointDedupeMeters" to "covered by ElevationRouteTest.peakWithinDedupeDistanceOfAWaypointIsOmitted",
            "waypointNearRouteMeters" to "covered by ElevationRouteTest.waypointNearRouteFilter",
            "waypointNameMaxLength" to "covered by ElevationRouteTest.waypointNameIsSanitized",
            "elevationSpikeThresholdMeters" to "covered by ElevationRouteTest.spikeIsUnstable",
            "elevationSpikeMaxRatio" to "covered by ElevationRouteTest.spikeRatioBoundaryAndConfigControl",
            "elevationGridMeters" to "covered by ElevationSmoothingTest.gridSpacingControlsSampleCount",
            "elevationMedianHalfWindowMeters" to "covered by ElevationSmoothingTest.medianWindowControlsSpikeRemoval",
            "elevationMeanHalfWindowMeters" to "covered by ElevationSmoothingTest.meanWindowControlsSmoothing",
            "eventMinIntervalSeconds" to "covered by DynamicGuidanceTest.minimumEventInterval",
            "milestoneIntervalMeters" to "covered by DynamicGuidanceTest.milestoneThreshold",
            "remainingAnnounceMeters" to "covered by DynamicGuidanceTest.remainingThresholds",
            "elapsedAnnounceIntervalSeconds" to "covered by DynamicGuidanceTest.elapsedThreshold",
            "peakAnnounceLeadMeters" to "covered by DynamicGuidanceTest.peakEventFiresWithinLeadDistanceAndConsumesOnce",
            "waypointAnnounceLeadMeters" to "covered by DynamicGuidanceTest.waypointWindow",
            "sunsetAnnounceMinutes" to "covered by DynamicGuidanceTest.sunsetThresholds",
            "sunriseAnnounceMinutes" to "covered by SunriseGuidanceTest.announceMinutesConfigChangesCrossings",
            "elevationBoundaryMeters" to "covered by ElevationBoundaryTest.boundaryConfigChangesCrossings",
            "elevationHysteresisMeters" to "covered by ElevationBoundaryTest.hysteresisConfigChangesChatter",
            "milestoneEnabled" to "covered by DynamicGuidanceTest.toggleMilestoneOnAndOff",
            "elapsedEnabled" to "covered by DynamicGuidanceTest.toggleElapsedOnAndOff",
            "remainingEnabled" to "covered by DynamicGuidanceTest.toggleRemainingOnAndOff",
            "slopeEnabled" to "covered by DynamicGuidanceTest.toggleSlopeOnAndOff",
            "elevationEnabled" to "covered by ElevationBoundaryTest.toggleOffSilentButAdvancesBand",
            "waypointEnabled" to "covered by DynamicGuidanceTest.toggleWaypointOnAndOff",
            "sunsetEnabled" to "covered by DynamicGuidanceTest.toggleSunsetOnAndOff",
            "sunriseEnabled" to "covered by SunriseGuidanceTest.toggleOffSilentButConsumes",
            "spatialGridSizeMeters" to "route index implementation parameter",
            "taggingOffRouteDistanceMeters" to "Phase 0 tagging parameter; not consumed by guide",
            "taggingOffRouteDwellSeconds" to "Phase 0 tagging parameter; not consumed by guide",
            "minimumSessionSecondsBeforeArrival" to "startup arrival guard; covered by the immediate-arrival regression test",
            "etaWindowSeconds" to "covered by RouteEtaTest.windowExpiry",
            "etaMinWindowMeters" to "covered by RouteEtaTest.correctionActivationThreshold",
            "etaMinMovingSpeedMps" to "covered by RouteEtaTest.stoppedSegmentsDoNotAffectCorrection",
            "etaCorrectionMin" to "covered by RouteEtaTest.correctionClampsToConfiguredBounds",
            "etaCorrectionMax" to "covered by RouteEtaTest.correctionClampsToConfiguredBounds",
            "etaSlopeWindowMeters" to "covered by RouteEtaTest.slopeWindowControlsSlopeAveraging",
            "etaMinSegmentSpeedMps" to "covered by RouteEtaTest.segmentSpeedFloorBoundsPredictedSeconds",
            "routeQualityGapWarnMeters" to "covered by RouteQualityTest.gapWarnThresholdControlsCount",
            "routeQualityShortSegmentMeters" to "covered by RouteQualityTest.shortSegmentThresholdControlsRatio",
            "etaMaxTotalSeconds" to "covered by RouteEtaTest.totalAboveCapIsUnavailable",
            "etaMinAverageSpeedMps" to "covered by RouteEtaTest.averageSpeedBelowMinimumIsUnavailable"
        )

        fun sensitivityFieldNames(): Set<String> =
            sensitivityRequired.toSet() + sensitivityExcluded.keys
    }
}

/** An E4 peak retained until it can be announced or passed. */
data class PendingSlopePeak(
    val index: Int,
    val peakS: Double,
    val delayReason: String,
)

/** A retained E4 peak that was passed before it could be announced. */
data class DiscardedSlopePeak(
    val index: Int,
    val peakS: Double,
    val passedProjectedMeters: Double,
    val delayReason: String,
    val reason: String = "passed",
)

/** A consumed E1/E2/E3/E8 event target that was not announced. */
data class DiscardedEvent(
    val event: String,
    val threshold: String,
    val reason: String,
    val progressMeters: Double,
)

/** An E6 waypoint consumed without an announcement. */
data class DiscardedWaypoint(
    val index: Int,
    val waypointS: Double,
    val distanceMeters: Double,
    val reason: String,
)

/** A route-end candidate blocked because the session has not earned enough forward progress. */
data class ArrivalBlockedRecord(
    val creditedMeters: Double,
    val requiredMeters: Double,
    val baselineRemainingMeters: Double?,
    val projectedMeters: Double,
    val reason: String,
)

data class MatchResult(
    val distanceMeters: Double,
    val projectedMeters: Double,
    val segmentIndex: Int,
    val projectedPoint: EnuPoint,
    val direction: ProgressDirection = ProgressDirection.UNKNOWN
)

/** One accepted on-route observation used by the pure start-trajectory matcher. */
data class StartTrajectorySample(
    val point: GeoPoint,
    val timestampMillis: Long,
    val walkedMeters: Double,
    val accuracyMeters: Double,
)

/** A deterministic engine diagnostic for the host to append to the sys stream. */
data class EngineSystemEvent(
    val kind: String,
    val fields: Map<String, String>,
)

/** A retained position on the current off-route return trajectory. */
data class ReturnTrajectoryPoint(
    val location: GeoPoint,
    val timestampMillis: Long,
)

/** Immutable state carried from one frame to the next. */
data class GuideState(
    val route: RouteModel,
    /** Direct engine callers may start after an already-oriented route; app and replay use [starting]. */
    val startupEnabled: Boolean = false,
    val startupResolved: Boolean = true,
    val startupHasMatch: Boolean = false,
    val trajectorySamples: List<StartTrajectorySample> = emptyList(),
    val trajectoryWalkedMeters: Double = 0.0,
    val trajectoryMetersConfig: Double = 50.0,
    val trajectoryFrameCount: Int = 0,
    val trajectoryStartTimestampMillis: Long? = null,
    val lastTrajectoryTimestampMillis: Long? = null,
    val lastTrajectoryPoint: GeoPoint? = null,
    val lastTrajectorySpeedMps: Double? = null,
    val lastTrajectoryAccuracyMeters: Double? = null,
    val directionDecidedAtMillis: Long? = null,
    /** Direction inferred on the imported route before the engine normalizes the route. */
    val decidedDirection: ProgressDirection? = null,
    val routeReversed: Boolean = false,
    val systemEvents: List<EngineSystemEvent> = emptyList(),
    val hasEnteredRoute: Boolean = false,
    /** True after the first accuracy-accepted route-distance decision for this session. */
    val waitingDecisionEvaluated: Boolean = false,
    /** True between the one-time far-start decision and the first near-route frame. */
    val waitingForRoute: Boolean = false,
    val waitingStartedAtMillis: Long? = null,
    val lastMatch: MatchResult? = null,
    val firstMatchProgressMeters: Double? = null,
    val lastTimestamp: Long? = null,
    val sessionStartTimestamp: Long? = null,
    val direction: ProgressDirection = ProgressDirection.UNKNOWN,
    val stationary: Boolean = false,
    val candidateOffRouteSince: Long? = null,
    val candidateOffRouteRecoverySince: Long? = null,
    val offRouteSince: Long? = null,
    val exitCandidateSince: Long? = null,
    val offRoute: Boolean = false,
    val lastAnnouncementAt: Long? = null,
    val lastAnnouncementDistance: Double? = null,
    val returnButtonActive: Boolean = false,
    val returnAvailableEmitted: Boolean = false,
    val returnHintPending: Boolean = false,
    val returnHintConsumed: Boolean = false,
    val returnAnchor: ReturnTrajectoryPoint? = null,
    val returnTrajectory: List<ReturnTrajectoryPoint> = emptyList(),
    val returnTrajectoryTruncated: Boolean = false,
    val returnTrajectoryUnavailableReason: String? = null,
    val returnCurrentLocation: GeoPoint? = null,
    val arrived: Boolean = false,
    val completedTurnAheadIndices: Set<Int> = emptySet(),
    val completedTurnNowIndices: Set<Int> = emptySet(),
    val consumedMilestoneIndices: Set<Int> = emptySet(),
    val consumedRemainingThresholds: Set<Double> = emptySet(),
    val pendingRemainingThresholds: Set<Double> = emptySet(),
    val pendingRemainingDelayReason: String? = null,
    val consumedElapsedIndices: Set<Int> = emptySet(),
    val consumedSlopeIndices: Set<Int> = emptySet(),
    val pendingSlopePeaks: Map<Int, PendingSlopePeak> = emptyMap(),
    val discardedSlopePeaks: List<DiscardedSlopePeak> = emptyList(),
    val consumedWaypointIndices: Set<Int> = emptySet(),
    val discardedWaypoints: List<DiscardedWaypoint> = emptyList(),
    val discardedEvents: List<DiscardedEvent> = emptyList(),
    val arrivalBaselineRemainingMeters: Double? = null,
    val advanceHighWaterMeters: Double? = null,
    val advanceCreditedMeters: Double = 0.0,
    /** E1 route distance walked since guidance start; rematching does not reset it (D-158). */
    val milestoneWalkedMeters: Double = 0.0,
    val arrivalBlockedRecords: List<ArrivalBlockedRecord> = emptyList(),
    val consumedSunsetThresholds: Set<Int> = emptySet(),
    val pendingSunsetThresholds: Set<Int> = emptySet(),
    val pendingSunsetDelayReason: String? = null,
    /** True after the first eligible E7 evaluation in this session. */
    val sunsetEvaluated: Boolean = false,
    val lastPeriodicEventAt: Long? = null,
    val elevationBand: Int? = null,
    val sunriseEvaluated: Boolean = false,
    val consumedSunriseThresholds: Set<Int> = emptySet(),
    val sunriseLocalDay: Long? = null,
    /** Rematching is retried after a long off-route stay or location-update gap. */
    val rematchPending: Boolean = false,
    val rematchPendingReason: String? = null,
    /** Directional fit distance accumulated while walking against the current route orientation. */
    val reverseWalkedMeters: Double = 0.0,
    /** True while progressing along the return half of an enabled joined route. */
    val roundTripOnReturnLeg: Boolean = false,
    /** The one-time round-trip startup announcement has been emitted. */
    val roundTripStartAnnounced: Boolean = false,
    /** An enabled loop route was intentionally left one-way. */
    val roundTripLoopIgnored: Boolean = false,
) {
    companion object {
        // Without startup direction resolution, GuideState.initial does not construct a round-trip route.
        fun initial(route: RouteModel): GuideState = GuideState(route)

        /** Start an un-oriented session. Direction and first match are resolved by [Engine]. */
        fun starting(route: RouteModel, config: GuideConfig = GuideConfig()): GuideState = GuideState(
            route = route,
            startupEnabled = true,
            startupResolved = false,
            trajectoryMetersConfig = config.startTrajectoryMeters,
        )
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
    val arrived: Boolean,
    val target: RouteTargetEstimate? = null,
    val closedRoute: Boolean = false,
    val roundTripReturnLeg: Boolean? = null,
)

/** Pure state report shared by on-demand voice and the route ribbon. */
fun routeStatus(state: GuideState, config: GuideConfig = GuideConfig()): RouteStatus? {
    if (state.startupEnabled && !state.startupResolved) return null
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
        arrived = state.arrived,
        closedRoute = isClosedRoute(state.route, config),
        roundTripReturnLeg = if (state.route.roundTripTurnaroundMeters == null) null else state.roundTripOnReturnLeg,
    )
}
