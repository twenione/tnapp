package com.trailnav.core

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test

/** Phase 3 C event threshold, gating, and consumption checks. */
class DynamicGuidanceTest {
    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun pointXml(index: Int): String =
        "<trkpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"/>"

    private val route = RouteModel.fromGpx(
        "<gpx><trk><trkseg>" +
            (0..12).joinToString("") { index -> "<trkpt lat=\"${10.0 + index * 0.003}\" lon=\"20.0\"/>" } +
            "</trkseg></trk></gpx>"
    )

    @Test
    fun individualEventDefaultsAreDisabledExceptSunset() {
        val config = GuideConfig()
        check(!config.milestoneEnabled)
        check(!config.elapsedEnabled)
        check(!config.remainingEnabled)
        check(!config.slopeEnabled)
        check(!config.elevationEnabled)
        check(!config.waypointEnabled)
        check(config.sunsetEnabled)
        check(!config.sunriseEnabled)
    }

    @Test
    fun milestoneThreshold() {
        val config = GuideConfig(milestoneEnabled = true, milestoneIntervalMeters = 200.0, eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Milestone)
        check(result.reason.rule == "event.milestone")
    }

    @Test
    fun remainingThresholds() {
        val config = GuideConfig(remainingEnabled = true, remainingAnnounceMeters = listOf(3000.0, 2800.0, 2400.0), eventMinIntervalSeconds = 0.0)
        var state = GuideState.initial(route)
        state = guide(state, SensorFrame(0L, 10.008, 20.0, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(1_000L, 10.0095, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Remaining)
    }

    @Test
    fun remainingIsDeferredUntilTheIntervalOpensAndUsesTheCurrentDistance() {
        val route = remainingEventRoute()
        val config = GuideConfig(
            milestoneEnabled = true,
            milestoneIntervalMeters = 100.0,
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(500.0),
            eventMinIntervalSeconds = 60.0,
            sunsetEnabled = false,
        )
        val primed = remainingEventState(route, remainingMeters = route.totalLengthMeters)
        val first = guide(primed, remainingEventFrame(route, route.totalLengthMeters - 120.0, 0L), config)
        check(first.guidance is Guidance.Milestone)

        val crossed = guide(first.nextState, remainingEventFrame(route, 499.0, 49_000L), config)
        check(crossed.guidance !is Guidance.Remaining)
        check(crossed.nextState.pendingRemainingThresholds == setOf(500.0))
        check(crossed.nextState.pendingRemainingDelayReason == "event-min-interval")

        val stillClosed = guide(crossed.nextState, remainingEventFrame(route, 400.0, 59_000L), config)
        check(stillClosed.guidance !is Guidance.Remaining)
        check(stillClosed.nextState.pendingRemainingThresholds == setOf(500.0))

        val opened = guide(stillClosed.nextState, remainingEventFrame(route, 400.0, 60_000L), config)
        val remaining = opened.guidance as? Guidance.Remaining
            ?: error("the retained E3 threshold should speak at the inclusive 60-second boundary")
        val expectedProgress = route.totalLengthMeters - 400.0
        val independentlyCalculatedRemaining = route.totalLengthMeters - expectedProgress
        check(remaining.thresholdMeters == 500.0)
        check(abs(remaining.remainingMeters - independentlyCalculatedRemaining) <= 1.0)
        check(remaining.remainingMeters != remaining.thresholdMeters)
        check(opened.reason.details["delayed"] == "true")
        check(opened.reason.details["delayReason"] == "event-min-interval")
        check(opened.nextState.pendingRemainingThresholds.isEmpty())
        check(opened.nextState.pendingRemainingDelayReason == null)

        val afterFlush = guide(opened.nextState, remainingEventFrame(route, 399.0, 61_000L), config)
        check(afterFlush.guidance !is Guidance.Remaining)
    }

    @Test
    fun pendingRemainingStaysSilentInReverseThenSpeaksAfterReturningForward() {
        val route = remainingEventRoute()
        val config = GuideConfig(
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(500.0),
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 60.0,
            sunsetEnabled = false,
        )
        val initial = remainingEventState(route, remainingMeters = 600.0, lastPeriodicEventAt = 0L)
        val pending = guide(initial, remainingEventFrame(route, 499.0, 49_000L), config)
        check(pending.guidance !is Guidance.Remaining)
        check(pending.nextState.direction == ProgressDirection.FORWARD)
        check(pending.nextState.pendingRemainingThresholds == setOf(500.0))

        val reverse = guide(pending.nextState, remainingEventFrame(route, 650.0, 60_000L), config)
        check(reverse.nextState.direction == ProgressDirection.REVERSE) {
            "the test must exercise a confirmed reverse frame, got ${reverse.nextState.direction}"
        }
        check(reverse.guidance !is Guidance.Remaining)
        check(reverse.nextState.pendingRemainingThresholds == setOf(500.0))

        val forwardAgain = guide(reverse.nextState, remainingEventFrame(route, 400.0, 61_000L), config)
        val remaining = forwardAgain.guidance as? Guidance.Remaining
            ?: error("the pending E3 threshold should speak after returning to forward movement")
        check(forwardAgain.nextState.direction == ProgressDirection.FORWARD)
        check(remaining.thresholdMeters == 500.0)
        check(remaining.remainingMeters in 399.0..401.0)
        check(forwardAgain.nextState.pendingRemainingThresholds.isEmpty())
    }

    @Test
    fun immediateRemainingKeepsItsExistingDetailsAndNearThresholdDistance() {
        val route = this.route
        val config = GuideConfig(
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(3_000.0),
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val state = remainingEventState(route, remainingMeters = 3_005.0)
        val result = guide(state, remainingEventFrame(route, 2_998.0, 1_000L), config)
        val remaining = result.guidance as? Guidance.Remaining ?: error("expected immediate E3")
        check(remaining.thresholdMeters == 3_000.0)
        check(remaining.remainingMeters in 2_995.0..3_000.0)
        check(result.reason.details.keys == setOf("event", "thresholdMeters", "remainingMeters"))
        check(result.reason.details["delayed"] == null)
        check(result.nextState.pendingRemainingThresholds.isEmpty())
    }

    @Test
    fun multipleDeferredRemainingThresholdsFlushOnceUsingTheNearestThreshold() {
        val route = this.route
        val config = GuideConfig(
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(2_000.0, 1_990.0),
            eventMinIntervalSeconds = 60.0,
            sunsetEnabled = false,
        )
        val state = remainingEventState(route, remainingMeters = 2_050.0, lastPeriodicEventAt = 0L)
        val crossed = guide(state, remainingEventFrame(route, 1_980.0, 49_000L), config)
        check(crossed.guidance !is Guidance.Remaining)
        check(crossed.nextState.pendingRemainingThresholds == setOf(2_000.0, 1_990.0)) {
            "pending=" + crossed.nextState.pendingRemainingThresholds +
                ", reason=" + crossed.nextState.pendingRemainingDelayReason +
                ", consumed=" + crossed.nextState.consumedRemainingThresholds +
                ", direction=" + crossed.nextState.direction +
                ", oldProgress=" + state.lastMatch?.projectedMeters +
                ", newProgress=" + crossed.nextState.lastMatch?.projectedMeters +
                ", guidance=" + crossed.guidance
        }

        val flushed = guide(crossed.nextState, remainingEventFrame(route, 1_980.0, 60_000L), config)
        val remaining = flushed.guidance as? Guidance.Remaining ?: error("expected one deferred E3")
        check(remaining.thresholdMeters == 1_990.0)
        check(remaining.remainingMeters in 1_979.0..1_981.0)
        check(flushed.nextState.pendingRemainingThresholds.isEmpty())
        check(guide(flushed.nextState, remainingEventFrame(route, 1_979.0, 61_000L), config).guidance !is Guidance.Remaining)
    }

    @Test
    fun offRouteRemainingIsRetainedUntilTheFirstOnRouteSpeakableFrame() {
        val route = remainingEventRoute()
        val config = GuideConfig(
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(500.0),
            eventMinIntervalSeconds = 0.0,
            offRouteEnterDistMeters = 20.0,
            offRouteExitDistMeters = 15.0,
            offRouteExitDwellSeconds = 0.0,
            sunsetEnabled = false,
        )
        val state = remainingEventState(route, remainingMeters = 600.0).copy(offRoute = true, offRouteSince = 0L)
        val offRoute = guide(state, remainingEventFrame(route, 499.0, 1_000L, lateralMeters = 30.0), config)
        check(offRoute.nextState.offRoute)
        check(offRoute.guidance !is Guidance.Remaining)
        check(offRoute.nextState.pendingRemainingThresholds == setOf(500.0))
        check(offRoute.nextState.pendingRemainingDelayReason == "off-route")

        val returned = guide(offRoute.nextState, remainingEventFrame(route, 400.0, 2_000L), config)
        val remaining = returned.guidance as? Guidance.Remaining ?: error("expected E3 after returning on-route")
        check(!returned.nextState.offRoute)
        check(remaining.remainingMeters in 399.0..401.0)
        check(returned.reason.details["delayed"] == "true")
        check(returned.reason.details["delayReason"] == "off-route")
    }

    @Test
    fun reverseRemainingCrossingIsConsumedButNeverRetained() {
        val route = remainingEventRoute()
        val config = GuideConfig(
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(500.0),
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val reverse = remainingEventState(
            route,
            remainingMeters = 600.0,
            direction = ProgressDirection.REVERSE,
        ).copy(emaDeltaMeters = -500.0, reverseSince = 0L)
        val result = guide(reverse, remainingEventFrame(route, 499.0, 1_000L), config)
        check(result.nextState.direction == ProgressDirection.REVERSE)
        check(result.guidance !is Guidance.Remaining)
        check(result.nextState.pendingRemainingThresholds.isEmpty())
        check(500.0 in result.nextState.consumedRemainingThresholds)
    }

    @Test
    fun disabledRemainingIsConsumedWithoutPendingState() {
        val route = remainingEventRoute()
        val config = GuideConfig(
            remainingEnabled = false,
            remainingAnnounceMeters = listOf(500.0),
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val state = remainingEventState(route, remainingMeters = 600.0)
        val result = guide(state, remainingEventFrame(route, 499.0, 1_000L), config)
        check(result.guidance !is Guidance.Remaining)
        check(result.nextState.pendingRemainingThresholds.isEmpty())
        check(result.nextState.pendingRemainingDelayReason == null)
        check(500.0 in result.nextState.consumedRemainingThresholds)
    }

    @Test
    fun pendingRemainingDoesNotDiscardPendingSunset() {
        val route = remainingEventRoute()
        val start = epoch("2026-09-21T09:00:00Z")
        val config = GuideConfig(
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(500.0),
            eventMinIntervalSeconds = 60.0,
            sunsetEnabled = true,
        )
        val state = remainingEventState(
            route,
            remainingMeters = 600.0,
            timestamp = start - 1_000L,
            lastPeriodicEventAt = start - 60_000L,
        ).copy(
            sunsetEvaluated = true,
            consumedSunsetThresholds = setOf(30),
            pendingSunsetThresholds = setOf(30),
            pendingSunsetDelayReason = "event-min-interval",
        )
        val e3 = guide(state, remainingEventFrame(route, 499.0, start), config)
        check(e3.guidance is Guidance.Remaining)
        check(e3.nextState.pendingRemainingThresholds.isEmpty())
        check(e3.nextState.pendingSunsetThresholds == setOf(30))

        val e7 = guide(e3.nextState, remainingEventFrame(route, 499.0, start + 60_000L), config)
        check(e7.guidance is Guidance.Sunset)
        check(e7.reason.details["event"] == "E7")
        check(e7.nextState.pendingSunsetThresholds.isEmpty())
    }

    @Test
    fun otherPeriodicEventsStillConsumeWithoutCreatingRemainingPendingState() {
        fun assertNoRemainingPending(result: GuideResult) {
            check(result.guidance !is Guidance.Remaining)
            check(result.nextState.pendingRemainingThresholds.isEmpty())
            check(result.nextState.pendingRemainingDelayReason == null)
        }

        val interval = 60.0
        val shortRoute = remainingEventRoute()
        val milestoneState = remainingEventState(shortRoute, remainingMeters = 412.0, lastPeriodicEventAt = 0L)
        val milestone = guide(
            milestoneState,
            remainingEventFrame(shortRoute, 312.0, 30_000L),
            GuideConfig(
                milestoneEnabled = true,
                milestoneIntervalMeters = 100.0,
                remainingEnabled = true,
                remainingAnnounceMeters = listOf(1.0),
                eventMinIntervalSeconds = interval,
                sunsetEnabled = false,
            ),
        )
        check(milestone.guidance !is Guidance.Milestone)
        check(milestone.nextState.consumedMilestoneIndices.isNotEmpty())
        assertNoRemainingPending(milestone)

        val elapsedState = remainingEventState(shortRoute, remainingMeters = 500.0, lastPeriodicEventAt = 0L)
            .copy(sessionStartTimestamp = 0L, lastTimestamp = 0L)
        val elapsed = guide(
            elapsedState,
            remainingEventFrame(shortRoute, 500.0, 30_000L),
            GuideConfig(
                elapsedEnabled = true,
                elapsedAnnounceIntervalSeconds = 10.0,
                remainingEnabled = true,
                remainingAnnounceMeters = listOf(1.0),
                eventMinIntervalSeconds = interval,
                sunsetEnabled = false,
            ),
        )
        check(elapsed.guidance !is Guidance.Elapsed)
        check(elapsed.nextState.consumedElapsedIndices.isNotEmpty())
        assertNoRemainingPending(elapsed)

        val slopeRoute = peakEventRoute()
        val peak = slopeRoute.peaks.first()
        val slopeState = peakStateAt(slopeRoute, peak.s - 100.0).copy(
            hasEnteredRoute = true,
            lastPeriodicEventAt = 0L,
        )
        val slope = guide(
            slopeState,
            frameAtProgress(peak.s - 50.0, 30_000L),
            GuideConfig(
                slopeEnabled = true,
                peakAnnounceLeadMeters = 75.0,
                remainingEnabled = true,
                remainingAnnounceMeters = listOf(1.0),
                eventMinIntervalSeconds = interval,
                sunsetEnabled = false,
            ),
        )
        check(slope.guidance !is Guidance.Slope)
        check(0 in slope.nextState.consumedSlopeIndices)
        assertNoRemainingPending(slope)

        val elevationRoute = peakEventRoute()
        val elevationPeak = elevationRoute.peaks.first()
        val elevationState = peakStateAt(elevationRoute, 0.0).copy(
            hasEnteredRoute = true,
            elevationBand = -1,
            lastPeriodicEventAt = 0L,
        )
        val elevation = guide(
            elevationState,
            frameAtProgress(0.0, 30_000L),
            GuideConfig(
                elevationEnabled = true,
                elevationBoundaryMeters = 50.0,
                elevationHysteresisMeters = 0.0,
                remainingEnabled = true,
                remainingAnnounceMeters = listOf(1.0),
                eventMinIntervalSeconds = interval,
                sunsetEnabled = false,
            ),
        )
        check(elevation.guidance !is Guidance.Elevation)
        check((elevation.nextState.elevationBand ?: -1) > -1)
        assertNoRemainingPending(elevation)

        val waypointRoute = RouteModel.fromGpx(
            "<gpx><wpt lat=\"10.005\" lon=\"20.0\"><name>View</name></wpt>" +
                "<trk><trkseg><trkpt lat=\"10.0\" lon=\"20.0\"/>" +
                "<trkpt lat=\"10.01\" lon=\"20.0\"/></trkseg></trk></gpx>"
        )
        val waypoint = waypointRoute.waypoints.single()
        val waypointState = remainingEventState(
            waypointRoute,
            remainingMeters = waypointRoute.totalLengthMeters - (waypoint.s - 200.0),
            lastPeriodicEventAt = 0L,
        )
        val waypointResult = guide(
            waypointState,
            remainingEventFrame(waypointRoute, waypointRoute.totalLengthMeters - (waypoint.s - 50.0), 30_000L),
            GuideConfig(
                waypointEnabled = true,
                waypointAnnounceLeadMeters = 100.0,
                remainingEnabled = true,
                remainingAnnounceMeters = listOf(1.0),
                eventMinIntervalSeconds = interval,
                sunsetEnabled = false,
            ),
        )
        check(waypointResult.guidance !is Guidance.Waypoint)
        check(0 in waypointResult.nextState.consumedWaypointIndices)
        assertNoRemainingPending(waypointResult)

        val sunriseRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/>" +
                "<trkpt lat=\"37.5765\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val sunriseTime = epoch("2026-09-20T19:00:00Z")
        val sunriseState = remainingEventState(
            sunriseRoute,
            remainingMeters = sunriseRoute.totalLengthMeters - 100.0,
            timestamp = sunriseTime - 1_000L,
            lastPeriodicEventAt = sunriseTime - 30_000L,
        ).copy(lastTimestamp = sunriseTime - 1_000L)
        val sunrise = guide(
            sunriseState,
            remainingEventFrame(sunriseRoute, sunriseRoute.totalLengthMeters - 100.0, sunriseTime),
            GuideConfig(
                sunriseEnabled = true,
                sunriseAnnounceMinutes = listOf(1_000),
                remainingEnabled = true,
                remainingAnnounceMeters = listOf(1.0),
                eventMinIntervalSeconds = interval,
                sunsetEnabled = false,
            ),
        )
        check(sunrise.guidance !is Guidance.Sunrise)
        check(1_000 in sunrise.nextState.consumedSunriseThresholds)
        assertNoRemainingPending(sunrise)
    }

    @Test
    fun minimumEventInterval() {
        val intervalRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg>" +
                "<trkpt lat=\"10.0\" lon=\"20.0\"/>" +
                "<trkpt lat=\"10.001\" lon=\"20.0\"/>" +
                "<trkpt lat=\"10.002\" lon=\"20.0\"/>" +
                "<trkpt lat=\"10.003\" lon=\"20.0\"/>" +
                "</trkseg></trk></gpx>"
        )
        val config = GuideConfig(milestoneEnabled = true, milestoneIntervalMeters = 100.0, eventMinIntervalSeconds = 60.0)
        var state = GuideState.initial(intervalRoute)
        state = guide(state, SensorFrame(0L, 10.0001, 20.0, 5f, 1f, null), config).nextState
        val first = guide(state, SensorFrame(1_000L, 10.0011, 20.0, 5f, 1f, null), config)
        check(first.guidance is Guidance.Milestone)
        val second = guide(first.nextState, SensorFrame(2_000L, 10.0019, 20.0, 5f, 1f, null), config)
        check(second.guidance !is Guidance.Milestone)
    }

    @Test
    fun toggleMilestoneOnAndOff() {
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                milestoneEnabled = enabled,
                milestoneIntervalMeters = 200.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), configured)
            return guide(first.nextState, SensorFrame(1_000L, 10.0022, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Milestone)
        check(enabled.reason.details["event"] == "E1")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Milestone)
        check(disabled.nextState.consumedMilestoneIndices.isNotEmpty())
    }
    @Test
    fun sunsetThresholds() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val atThirtyTwoMinutes = SensorFrame(epoch("2026-09-21T09:00:00Z"), 37.5665, 126.9780, 5f, 0f, null)
        val first = guide(GuideState.initial(sunsetRoute), atThirtyTwoMinutes)
        check(first.guidance is Guidance.Sunset)
        check(first.reason.rule == "event.sunset")
        check(first.reason.details["event"] == "E7")
        check(first.reason.details["startAnnouncement"] == "true")
        check(first.reason.details["thresholdMinutes"] == "60")
        check(60 in first.nextState.consumedSunsetThresholds && 30 !in first.nextState.consumedSunsetThresholds)

        val second = guide(first.nextState, atThirtyTwoMinutes.copy(timestamp = epoch("2026-09-21T09:10:00Z")))
        check(second.guidance is Guidance.Sunset)
        check(second.reason.details["thresholdMinutes"] == "30")
        val after = guide(second.nextState, atThirtyTwoMinutes.copy(timestamp = epoch("2026-09-21T09:40:00Z")))
        check(after.guidance is Guidance.Sunset)
        check((after.guidance as Guidance.Sunset).afterSunset)
    }

    @Test
    fun sunsetWorksWithPeriodicDisabledAndAccuracyRejected() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val result = guide(
            GuideState.initial(sunsetRoute),
            SensorFrame(epoch("2026-09-21T09:00:00Z"), 37.5665, 126.9780, 80f, 0f, null),
            GuideConfig(),
        )
        check(result.guidance is Guidance.Sunset)
        check(result.reason.rule == "input.accuracy-filter")
        check(result.reason.details["event"] == "E7")
    }

    @Test
    fun sunsetAfterSunsetSpeaksOnce() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val first = guide(
            GuideState.initial(sunsetRoute),
            SensorFrame(epoch("2026-09-21T09:40:00Z"), 37.5665, 126.9780, 5f, 0f, null),
        )
        check(first.guidance is Guidance.Sunset)
        check((first.guidance as Guidance.Sunset).afterSunset)
        check(first.reason.details["afterSunset"] == "true")
        val second = guide(first.nextState, SensorFrame(epoch("2026-09-21T09:41:40Z"), 37.5665, 126.9780, 5f, 0f, null))
        check(second.guidance !is Guidance.Sunset)
    }

    @Test
    fun elapsedThreshold() {
        val config = GuideConfig(elapsedEnabled = true, eventMinIntervalSeconds = 0.0)
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config)
        val second = guide(first.nextState, SensorFrame(3_600_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(second.guidance is Guidance.Elapsed)
        check((second.guidance as Guidance.Elapsed).hours == 1)
    }

    private fun peakEventRoute(): RouteModel {
        val elevations = listOf(
            0, 0, 0, 10, 20, 30, 40, 50, 60, 70,
            80, 80, 80, 80, 80, 70, 60, 50, 40, 30, 20, 20, 20,
        )
        val track = elevations.mapIndexed { index, elevation ->
            "<trkpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
        }.joinToString("")
        return RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>").also {
            check(it.elevationUse.used && it.peaks.isNotEmpty())
        }
    }

    private fun peakStateAt(route: RouteModel, progressMeters: Double): GuideState {
        val segment = route.cumulativeMeters.indexOfLast { it <= progressMeters }
            .coerceIn(0, route.points.lastIndex - 1)
        return GuideState.initial(route).copy(
            lastMatch = MatchResult(0.0, progressMeters, segment, route.points[segment], ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
        )
    }

    private fun frameAtProgress(progressMeters: Double, timestamp: Long) =
        SensorFrame(timestamp, 10.0 + progressMeters / 111_195.0, 20.0, 5f, 1f, null)

    private fun remainingEventRoute(): RouteModel = RouteModel.fromGpx(
        "<gpx><trk><trkseg><trkpt lat=\"10.0\" lon=\"20.0\"/>" +
            "<trkpt lat=\"10.01\" lon=\"20.0\"/></trkseg></trk></gpx>"
    )

    private fun remainingEventState(
        route: RouteModel,
        remainingMeters: Double,
        timestamp: Long = 0L,
        lastPeriodicEventAt: Long? = null,
        direction: ProgressDirection = ProgressDirection.FORWARD,
    ): GuideState {
        val progress = (route.totalLengthMeters - remainingMeters).coerceIn(0.0, route.totalLengthMeters)
        val segment = route.cumulativeMeters.indexOfLast { it <= progress }.coerceIn(0, route.points.lastIndex - 1)
        return GuideState.initial(route).copy(
            hasEnteredRoute = true,
            lastMatch = MatchResult(0.0, progress, segment, route.points[segment], direction),
            lastTimestamp = timestamp,
            sessionStartTimestamp = timestamp,
            emaDeltaMeters = if (direction == ProgressDirection.REVERSE) -100.0 else 0.0,
            direction = direction,
            reverseSince = if (direction == ProgressDirection.REVERSE) timestamp else null,
            lastPeriodicEventAt = lastPeriodicEventAt,
        )
    }

    private fun remainingEventFrame(
        route: RouteModel,
        remainingMeters: Double,
        timestamp: Long,
        lateralMeters: Double = 0.0,
    ): SensorFrame {
        val progress = (route.totalLengthMeters - remainingMeters).coerceIn(0.0, route.totalLengthMeters)
        val latitude = 10.0 + progress / 111_195.0
        val longitude = 20.0 + lateralMeters / (111_195.0 * kotlin.math.cos(Math.toRadians(10.0)))
        return SensorFrame(timestamp, latitude, longitude, 5f, 5f, null)
    }

    @Test
    fun peakEventFiresWithinLeadDistanceAndConsumesOnce() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val targetProgress = peak.s - 75.0

        fun announce(leadMeters: Double): GuideResult {
            val config = GuideConfig(
                slopeEnabled = true,
                peakAnnounceLeadMeters = leadMeters,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            return guide(peakStateAt(route, targetProgress), frameAtProgress(targetProgress, 1_000L), config)
        }

        val withinDefaultLead = announce(100.0)
        val slope = withinDefaultLead.guidance as? Guidance.Slope
        check(slope != null)
        check(slope.kind == SlopeKind.ASCENT)
        check(slope.deltaMeters == peak.climbMeters)
        check(withinDefaultLead.reason.details["event"] == "E4")
        check(withinDefaultLead.reason.details["peakIndex"] == "0")
        check(withinDefaultLead.reason.details["peakS"] == peak.s.toString())
        check(withinDefaultLead.reason.details["elevationMeters"] == peak.elevationMeters.toString())
        check(withinDefaultLead.reason.details["climbMeters"] == peak.climbMeters.toString())

        val outsideShorterLead = announce(50.0)
        check(outsideShorterLead.guidance !is Guidance.Slope)

        val config = GuideConfig(slopeEnabled = true, peakAnnounceLeadMeters = 100.0, eventMinIntervalSeconds = 0.0, sunsetEnabled = false)
        val repeated = guide(
            withinDefaultLead.nextState,
            frameAtProgress(peak.s - 25.0, 2_000L),
            config,
        )
        check(repeated.guidance !is Guidance.Slope)
    }

    @Test
    fun waypointWindow() {
        val xml = "<gpx><wpt lat=\"10.001\" lon=\"20.0\"><name>View</name></wpt>" +
            "<trk><trkseg>${pointXml(0)}${pointXml(1)}${pointXml(2)}</trkseg></trk></gpx>"
        val routeWithWaypoint = RouteModel.fromGpx(xml)
        val config = GuideConfig(waypointEnabled = true, eventMinIntervalSeconds = 0.0, waypointAnnounceLeadMeters = 75.0)
        val primed = GuideState.initial(routeWithWaypoint).copy(
            lastMatch = MatchResult(0.0, 0.0, 0, routeWithWaypoint.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
        )
        val result = guide(primed, SensorFrame(1_000L, 10.00045, 20.0, 5f, 1f, null), config)
        check(result.guidance is Guidance.Waypoint)
    }

    @Test
    fun remainingBeatsSlopeWhenThresholdsCrossTogether() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val targetProgress = peak.s - 50.0
        val remainingThreshold = route.totalLengthMeters - targetProgress + 1.0
        val config = GuideConfig(
            remainingEnabled = true,
            slopeEnabled = true,
            eventMinIntervalSeconds = 0.0,
            remainingAnnounceMeters = listOf(remainingThreshold),
            peakAnnounceLeadMeters = 100.0,
            sunsetEnabled = false,
        )
        val previous = peakStateAt(route, targetProgress - 10.0)
        val result = guide(previous, frameAtProgress(targetProgress, 1_000L), config)
        check(result.guidance is Guidance.Remaining) {
            "guidance=" + result.guidance + " reason=" + result.reason + " remaining=" +
                (route.totalLengthMeters - result.nextState.lastMatch!!.projectedMeters) +
                " target=" + targetProgress + " threshold=" + remainingThreshold
        }
        check(result.reason.details["event"] == "E3")
        check(0 in result.nextState.consumedSlopeIndices) {
            "E4 is consumed even when the higher-priority E3 cue wins"
        }
    }

    @Test
    fun offRouteConsumesPeriodicThresholds() {
        val config = GuideConfig(
            milestoneEnabled = true,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
            milestoneIntervalMeters = 50.0,
            offRouteEnterDwellSeconds = 0.0,
            offRouteExitDwellSeconds = 0.0,
        )
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config)
        val off = guide(first.nextState, SensorFrame(1_000L, 10.002, 20.0004, 5f, 1f, null), config)
        check(off.nextState.offRoute)
        check(off.nextState.consumedMilestoneIndices.isNotEmpty())
        val recovered = guide(off.nextState, SensorFrame(2_000L, 10.002, 20.0, 5f, 1f, null), config)
        check(!recovered.nextState.offRoute)
        check(recovered.guidance !is Guidance.Milestone)
    }

    @Test
    fun offRouteGateDoesNotRecordPeriodicAnnouncement() {
        val config = GuideConfig(
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
            milestoneIntervalMeters = 50.0,
            offRouteEnterDwellSeconds = 0.0,
        )
        val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), config)
        val off = guide(first.nextState, SensorFrame(1_000L, 10.002, 20.0004, 5f, 1f, null), config)
        check(off.nextState.offRoute)
        check(off.nextState.lastPeriodicEventAt == null)
    }

    @Test
    fun reverseGateSuppressesSlopeAnnouncement() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val config = GuideConfig(
            slopeEnabled = true,
            peakAnnounceLeadMeters = 100.0,
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val reverseState = peakStateAt(route, peak.s + 20.0).copy(
            direction = ProgressDirection.REVERSE,
            emaDeltaMeters = -40.0,
            reverseSince = 0L,
        )
        val result = guide(reverseState, frameAtProgress(peak.s - 10.0, 1_000L), config)
        check(result.nextState.direction == ProgressDirection.REVERSE)
        check(result.guidance !is Guidance.Slope)
        check(0 in result.nextState.consumedSlopeIndices)
    }

    @Test
    fun milestoneConsumptionQueuePreventsRefire() {
        val config = GuideConfig(milestoneEnabled = true, sunsetEnabled = false, eventMinIntervalSeconds = 0.0, milestoneIntervalMeters = 100.0)
        val primed = GuideState.initial(route).copy(
            lastMatch = MatchResult(0.0, 0.0, 0, route.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            direction = ProgressDirection.FORWARD,
        )
        val first = guide(primed, SensorFrame(1_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(first.guidance is Guidance.Milestone)
        val back = guide(first.nextState, SensorFrame(2_000L, 10.0005, 20.0, 5f, 1f, null), config)
        val forward = guide(back.nextState, SensorFrame(3_000L, 10.0015, 20.0, 5f, 1f, null), config)
        check(forward.guidance !is Guidance.Milestone)
    }

    @Test
    fun peakThresholdDoesNotRefireAfterConsumption() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        val config = GuideConfig(slopeEnabled = true, peakAnnounceLeadMeters = 100.0, eventMinIntervalSeconds = 0.0, sunsetEnabled = false)
        val state = peakStateAt(route, peak.s - 50.0).copy(consumedSlopeIndices = setOf(0))
        val result = guide(state, frameAtProgress(peak.s - 25.0, 1_000L), config)
        check(result.guidance !is Guidance.Slope)
    }

    @Test
    fun eventPriorityKeepsRemainingAboveSlope() {
        check(EventPriority.REMAINING > EventPriority.SLOPE)
    }

    @Test
    fun toggleElapsedOnAndOff() {
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                elapsedEnabled = enabled,
                elapsedAnnounceIntervalSeconds = 60.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            val first = guide(GuideState.initial(route), SensorFrame(0L, 10.0005, 20.0, 5f, 1f, null), configured)
            return guide(first.nextState, SensorFrame(60_000L, 10.0005, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Elapsed)
        check(enabled.reason.details["event"] == "E2")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Elapsed)
        check(disabled.nextState.consumedElapsedIndices.isNotEmpty())
    }

    @Test
    fun toggleRemainingOnAndOff() {
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                remainingEnabled = enabled,
                remainingAnnounceMeters = listOf(3_000.0),
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            val first = guide(GuideState.initial(route), SensorFrame(0L, 10.008, 20.0, 5f, 1f, null), configured)
            return guide(first.nextState, SensorFrame(1_000L, 10.0095, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Remaining)
        check(enabled.reason.details["event"] == "E3")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Remaining)
        check(3_000.0 in disabled.nextState.consumedRemainingThresholds)
    }

    @Test
    fun toggleSlopeOnAndOff() {
        val route = peakEventRoute()
        val peak = route.peaks.first()
        fun run(enabled: Boolean): GuideResult {
            val config = GuideConfig(
                slopeEnabled = enabled,
                peakAnnounceLeadMeters = 75.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            return guide(peakStateAt(route, peak.s - 50.0), frameAtProgress(peak.s - 50.0, 1_000L), config)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Slope)
        check(enabled.reason.details["event"] == "E4")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Slope)
        check(0 in disabled.nextState.consumedSlopeIndices)
    }

    @Test
    fun toggleWaypointOnAndOff() {
        val waypointRoute = RouteModel.fromGpx(
            "<gpx><wpt lat=\"10.001\" lon=\"20.0\"><name>View</name></wpt><trk><trkseg>" +
                "<trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.002\" lon=\"20.0\"/>" +
                "</trkseg></trk></gpx>"
        )
        fun run(enabled: Boolean): GuideResult {
            val configured = GuideConfig(
                waypointEnabled = enabled,
                waypointAnnounceLeadMeters = 100.0,
                eventMinIntervalSeconds = 0.0,
                sunsetEnabled = false,
            )
            var state = guide(GuideState.initial(waypointRoute), SensorFrame(0L, 10.0, 20.0, 5f, 1f, null), configured).nextState
            return guide(state, SensorFrame(1_000L, 10.0006, 20.0, 5f, 1f, null), configured)
        }
        val enabled = run(true)
        check(enabled.guidance is Guidance.Waypoint)
        check(enabled.reason.details["event"] == "E6")
        val disabled = run(false)
        check(disabled.guidance !is Guidance.Waypoint)
        check(0 in disabled.nextState.consumedWaypointIndices)
    }

    @Test
    fun toggleSunsetOnAndOff() {
        val sunsetRoute = RouteModel.fromGpx(
            "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/><trkpt lat=\"37.5865\" lon=\"126.9780\"/></trkseg></trk></gpx>"
        )
        val frame = SensorFrame(epoch("2026-09-21T09:00:00Z"), 37.5665, 126.9780, 5f, 0f, null)
        val enabled = guide(GuideState.initial(sunsetRoute), frame, GuideConfig(sunsetEnabled = true))
        check(enabled.guidance is Guidance.Sunset)
        check(enabled.reason.details["event"] == "E7")
        val disabled = guide(GuideState.initial(sunsetRoute), frame, GuideConfig(sunsetEnabled = false))
        check(disabled.guidance !is Guidance.Sunset)
    }

    @Test
    fun reverseDirectionSuppressesMilestoneRemainingSlopeAndWaypoint() {
        val milestoneConfig = GuideConfig(
            milestoneEnabled = true,
            milestoneIntervalMeters = 100.0,
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(route.totalLengthMeters - 200.0),
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val oldProgress = 100.0
        val primed = GuideState.initial(route).copy(
            lastMatch = MatchResult(0.0, oldProgress, 0, route.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            emaDeltaMeters = -500.0,
            direction = ProgressDirection.REVERSE,
            reverseSince = 0L,
        )
        val distanceEvents = guide(
            primed,
            SensorFrame(1_000L, 10.00225, 20.0, 5f, 1f, null),
            milestoneConfig,
        )
        check(distanceEvents.nextState.direction == ProgressDirection.REVERSE)
        check(distanceEvents.guidance !is Guidance.Milestone)
        check(distanceEvents.guidance !is Guidance.Remaining)

        val slopeRoute = peakEventRoute()
        val slope = slopeRoute.peaks.first()
        val slopeConfig = GuideConfig(
            slopeEnabled = true,
            peakAnnounceLeadMeters = 75.0,
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val slopeState = GuideState.initial(slopeRoute).copy(
            lastMatch = MatchResult(0.0, slope.s - 100.0, 0, slopeRoute.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            emaDeltaMeters = -100.0,
            direction = ProgressDirection.REVERSE,
            reverseSince = 0L,
        )
        val slopeResult = guide(
            slopeState,
            SensorFrame(1_000L, 10.0 + (slope.s - 20.0) / 111_195.0, 20.0, 5f, 1f, null),
            slopeConfig,
        )
        check(slopeResult.nextState.direction == ProgressDirection.REVERSE)
        check(slopeResult.guidance !is Guidance.Slope)

        val waypointRoute = RouteModel.fromGpx(
            "<gpx><wpt lat=\"10.002\" lon=\"20.0\"><name>View</name></wpt><trk><trkseg>" +
                "<trkpt lat=\"10.0\" lon=\"20.0\"/><trkpt lat=\"10.004\" lon=\"20.0\"/>" +
                "</trkseg></trk></gpx>"
        )
        val waypoint = waypointRoute.waypoints.single()
        val waypointConfig = GuideConfig(
            waypointEnabled = true,
            waypointAnnounceLeadMeters = 100.0,
            reverseWarningDwellSeconds = 0.0,
            eventMinIntervalSeconds = 0.0,
            sunsetEnabled = false,
        )
        val waypointState = GuideState.initial(waypointRoute).copy(
            lastMatch = MatchResult(0.0, waypoint.s - 80.0, 0, waypointRoute.points.first(), ProgressDirection.FORWARD),
            lastTimestamp = 0L,
            sessionStartTimestamp = 0L,
            emaDeltaMeters = -100.0,
            direction = ProgressDirection.REVERSE,
            reverseSince = 0L,
        )
        val waypointResult = guide(
            waypointState,
            SensorFrame(1_000L, 10.0 + (waypoint.s - 20.0) / 111_195.0, 20.0, 5f, 1f, null),
            waypointConfig,
        )
        check(waypointResult.nextState.direction == ProgressDirection.REVERSE)
        check(waypointResult.guidance !is Guidance.Waypoint)
    }
}
