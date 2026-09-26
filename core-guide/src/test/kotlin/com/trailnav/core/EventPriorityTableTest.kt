package com.trailnav.core

import java.time.Instant
import kotlin.test.Test
import kotlin.math.roundToLong

class EventPriorityTableTest {
    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()

    private val seoulRoute = RouteModel.fromGpx(
        "<gpx><trk><trkseg><trkpt lat=\"37.5665\" lon=\"126.9780\"/>" +
            "<trkpt lat=\"37.6065\" lon=\"126.9780\"/></trkseg></trk></gpx>"
    )

    private fun sunrise(): Double = sunriseEpochSeconds(epoch("2026-09-20T19:00:00Z"), 37.5665, 126.9780)!!

    private fun elevationRoute(startLat: Double, endLat: Double, waypointLat: Double? = null): RouteModel {
        val waypoint = waypointLat?.let { "<wpt lat=\"$it\" lon=\"126.9780\"><name>View</name></wpt>" } ?: ""
        return RouteModel.fromGpx(
            "<gpx>$waypoint<trk><trkseg>" + (0..200 step 10).map { elevation ->
                val fraction = elevation / 200.0
                val latitude = startLat + (endLat - startLat) * fraction
                "<trkpt lat=\"$latitude\" lon=\"126.9780\"><ele>$elevation</ele></trkpt>"
            }.joinToString("") + "</trkseg></trk></gpx>"
        )
    }

    @Test
    fun orderIsSunsetRemainingSunriseSlopeWaypointElevationMilestoneElapsed() {
        val priorities = listOf(
            EventPriority.SUNSET,
            EventPriority.REMAINING,
            EventPriority.SUNRISE,
            EventPriority.SLOPE,
            EventPriority.WAYPOINT,
            EventPriority.ELEVATION,
            EventPriority.MILESTONE,
            EventPriority.ELAPSED,
        )
        check(priorities.zipWithNext().all { (higher, lower) -> higher > lower }) {
            "priority order was $priorities"
        }
    }

    @Test
    fun remainingConsumesSameFrameSunrise() {
        val config = GuideConfig(
            remainingEnabled = true,
            remainingAnnounceMeters = listOf(seoulRoute.totalLengthMeters - 100.0),
            sunriseEnabled = true,
            sunriseAnnounceMinutes = listOf(30, 10),
            sunsetEnabled = false,
            eventMinIntervalSeconds = 0.0,
        )
        val startTime = ((sunrise() - 31.0 * 60.0) * 1000.0).roundToLong()
        val first = guide(GuideState.initial(seoulRoute), SensorFrame(startTime, 37.5665, 126.9780, 5f, 1f, null), config)
        val crossing = guide(
            first.nextState,
            SensorFrame(startTime + 120_000L, 37.5685, 126.9780, 5f, 1f, null),
            config,
        )
        check(crossing.guidance is Guidance.Remaining)
        check(crossing.reason.details["event"] == "E3")
        check(30 in crossing.nextState.consumedSunriseThresholds)
        val later = guide(
            crossing.nextState,
            SensorFrame(((sunrise() - 28.0 * 60.0) * 1000.0).roundToLong(), 37.5685, 126.9780, 5f, 1f, null),
            config,
        )
        check(later.guidance !is Guidance.Sunrise)
    }

    private fun longSlopeRoute(): RouteModel {
        val elevations = List(7) { 0 } + listOf(0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40)
        return RouteModel.fromGpx(
            "<gpx><trk><trkseg>" + elevations.mapIndexed { index, elevation ->
                "<trkpt lat=\"${37.5665 + index * 0.0005}\" lon=\"126.9780\"><ele>$elevation</ele></trkpt>"
            }.joinToString("") + "</trkseg></trk></gpx>",
            GuideConfig(slopeLookaheadMeters = 800.0),
        )
    }

    @Test
    fun sunriseConsumesSameFrameSlope() {
        val route = longSlopeRoute()
        val segment = route.slopeSegments.firstOrNull() ?: error("fixture must contain a slope segment")
        val config = GuideConfig(
            slopeEnabled = true,
            slopeAnnounceLeadMeters = 100.0,
            slopeLookaheadMeters = 800.0,
            sunriseEnabled = true,
            sunriseAnnounceMinutes = listOf(30, 10),
            sunsetEnabled = false,
            eventMinIntervalSeconds = 0.0,
        )
        val startTimestamp = ((sunrise() - 33.0 * 60.0) * 1000.0).roundToLong()
        val startLat = 37.5665 + (segment.startS - 300.0) / 111_195.0
        val first = guide(GuideState.initial(route), SensorFrame(startTimestamp, startLat, 126.9780, 5f, 1f, null), config)
        check(first.guidance !is Guidance.Slope)
        val beforeCrossingLat = 37.5665 + (segment.startS - 150.0) / 111_195.0
        val beforeCrossing = guide(
            first.nextState,
            SensorFrame(startTimestamp + 120_000L, beforeCrossingLat, 126.9780, 5f, 1f, null),
            config,
        )
        check(beforeCrossing.guidance !is Guidance.Slope)
        val crossingLat = 37.5665 + (segment.startS - 50.0) / 111_195.0
        val crossing = guide(
            beforeCrossing.nextState,
            SensorFrame(startTimestamp + 240_000L, crossingLat, 126.9780, 5f, 1f, null),
            config,
        )
        check(crossing.guidance is Guidance.Sunrise)
        check(0 in crossing.nextState.consumedSlopeIndices)
        check(crossing.reason.details["event"] == "E8")
    }

    @Test
    fun waypointWinsElevationCollision() {
        val route = elevationRoute(10.0, 10.01, waypointLat = 10.006)
        val config = GuideConfig(
            elevationEnabled = true,
            elevationBoundaryMeters = 100.0,
            elevationHysteresisMeters = 10.0,
            waypointEnabled = true,
            waypointAnnounceLeadMeters = 100.0,
            sunsetEnabled = false,
            eventMinIntervalSeconds = 0.0,
        )
        var state = guide(GuideState.initial(route), SensorFrame(0L, 10.002, 126.9780, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(1_000L, 10.004, 126.9780, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(2_000L, 10.006, 126.9780, 5f, 1f, null), config)
        check(result.guidance is Guidance.Waypoint)
        check(result.reason.details["event"] == "E6")
        check(result.nextState.elevationBand == 1) { "E5 must be consumed even though E6 wins" }
    }

    @Test
    fun elevationWinsMilestoneCollision() {
        val route = elevationRoute(10.0, 10.01)
        val config = GuideConfig(
            elevationEnabled = true,
            elevationBoundaryMeters = 100.0,
            elevationHysteresisMeters = 10.0,
            milestoneEnabled = true,
            milestoneIntervalMeters = 500.0,
            sunsetEnabled = false,
            eventMinIntervalSeconds = 0.0,
        )
        var state = guide(GuideState.initial(route), SensorFrame(0L, 10.002, 126.9780, 5f, 1f, null), config).nextState
        state = guide(state, SensorFrame(1_000L, 10.004, 126.9780, 5f, 1f, null), config).nextState
        val result = guide(state, SensorFrame(2_000L, 10.006, 126.9780, 5f, 1f, null), config)
        check(result.guidance is Guidance.Elevation)
        check(result.reason.details["event"] == "E5")
        check(result.nextState.consumedMilestoneIndices.isNotEmpty())
    }

    @Test
    fun sunsetWinsElevationCollisionWithoutPendingDelay() {
        val route = elevationRoute(37.5665, 37.5765)
        val config = GuideConfig(
            elevationEnabled = true,
            elevationBoundaryMeters = 100.0,
            elevationHysteresisMeters = 10.0,
            sunsetEnabled = true,
            sunsetAnnounceMinutes = listOf(30),
            eventMinIntervalSeconds = 0.0,
        )
        var state = guide(
            GuideState.initial(route),
            SensorFrame(epoch("2026-09-21T08:50:00Z"), 37.5685, 126.9780, 5f, 1f, null),
            config,
        ).nextState
        state = guide(
            state,
            SensorFrame(epoch("2026-09-21T09:00:00Z"), 37.5705, 126.9780, 5f, 1f, null),
            config,
        ).nextState
        val result = guide(state, SensorFrame(epoch("2026-09-21T09:05:00Z"), 37.5725, 126.9780, 5f, 1f, null), config)
        check(result.guidance is Guidance.Sunset)
        check(result.reason.details["event"] == "E7")
        check(result.nextState.elevationBand == 1)
        check(result.nextState.pendingSunsetThresholds.isEmpty())
    }

    @Test
    fun sunsetWinsSunriseCollisionWithoutPendingDelay() {
        val route = seoulRoute
        val sunriseTime = sunrise()
        val disabledAtFirstFrame = GuideConfig(
            sunriseEnabled = true,
            sunriseAnnounceMinutes = listOf(30, 10),
            sunsetEnabled = false,
            sunsetAnnounceMinutes = listOf(1_000),
            eventMinIntervalSeconds = 0.0,
        )
        val firstTimestamp = ((sunriseTime - 31.0 * 60.0) * 1000.0).roundToLong()
        val first = guide(
            GuideState.initial(route),
            SensorFrame(firstTimestamp, 37.5665, 126.9780, 5f, 0f, null),
            disabledAtFirstFrame,
        )
        val enabledAtCrossing = disabledAtFirstFrame.copy(sunsetEnabled = true)
        val result = guide(
            first.nextState,
            SensorFrame(firstTimestamp + 120_000L, 37.5665, 126.9780, 5f, 0f, null),
            enabledAtCrossing,
        )
        check(result.guidance is Guidance.Sunset)
        check(result.reason.details["event"] == "E7")
        check(30 in result.nextState.consumedSunriseThresholds)
        check(result.nextState.pendingSunsetThresholds.isEmpty())
    }
}
