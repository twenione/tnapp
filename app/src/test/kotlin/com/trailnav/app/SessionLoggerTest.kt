package com.trailnav.app

import com.trailnav.core.GuideConfig
import com.trailnav.core.GuideState
import com.trailnav.core.Guidance
import com.trailnav.core.ElevationRepair
import com.trailnav.core.RouteModel
import com.trailnav.core.RouteQuality
import com.trailnav.core.guide
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionLoggerTest {
    @Test
    fun loggerAppendsAdoptedStreamsWithMonotonicSequence() {
        val directory = createTempDir(prefix = "tnapp-session-")
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
        )
        val sessionId = UUID.randomUUID().toString()
        JsonlSessionLogger(
            directory = directory,
            sessionId = sessionId,
            codeHash = "sha256:test",
            configHash = "sha256:config",
            eventSettings = EventSettings.defaults().toWireMap(),
            routeHash = "sha256:route",
            appVersion = "test",
            routeElevationUsed = true,
            routeElevationReason = "ok",
            routeWaypointCount = 2,
            routeElevationRepair = ElevationRepair(2, 2, 100, 16.123, listOf(123.4, 456.7)),
            routeElevationSpikeRatioCap = 0.02,
            routeElevationSpikeThresholdMeters = 10.0,
        ).use { logger ->
            val location = TrailLocation(1_000L, 10.0001, 20.0, 5f, 1f, null, "fake")
            val result = guide(GuideState.initial(route), location.toSensorFrame(), GuideConfig())
            logger.appendEnvelope(location, "loc", "location")
            val locationSeq = logger.appendLocation(location)
            logger.appendGuide(location, result, null, locationSeq)
            logger.appendGuide(location, result, "경로 위입니다", locationSeq, trigger = "on-demand")
            logger.appendSystem("service.started", mapOf("battery_pct" to "90"))
            logger.appendSystem(
                "events.config",
                EventSettings.defaults().toWireMap().mapValues { (_, enabled) -> enabled.toString() },
            )
            logger.appendError("test", "synthetic")
        }
        val manifest = File(directory, "manifest.json").readText()
        assertTrue(manifest.contains("\"upload_default\":false"))
        assertTrue(manifest.contains("\"elevation_used\":true"))
        assertTrue(manifest.contains("\"elevation_reason\":\"ok\""))
        assertTrue(manifest.contains("\"waypoint_count\":2"))
        assertTrue(manifest.contains("\"elevation_spike_points\":2"))
        assertTrue(manifest.contains("\"elevation_interpolated_points\":2"))
        assertTrue(manifest.contains("\"elevation_total_points\":100"))
        assertTrue(manifest.contains("\"elevation_spike_ratio\":0.0200"))
        assertTrue(manifest.contains("\"elevation_spike_ratio_cap\":0.02"))
        assertTrue(manifest.contains("\"elevation_max_residual_m\":16.1"))
        assertTrue(manifest.contains("\"elevation_spike_threshold_m\":10.0"))
        assertTrue(manifest.contains("\"elevation_spike_at_m\":\"123,457\""))
        assertTrue(manifest.contains("\"events\":{\"E1\":false,\"E2\":false,\"E3\":true,\"E4\":true,\"E5\":false,\"E6\":true,\"E7\":true,\"E8\":true}"))
        val events = File(directory, "events.ndjson").readLines().filter { it.isNotBlank() }
        assertEquals(7, events.size)
        assertTrue(events[0].contains("\"stream\":\"envelope\""))
        assertTrue(events[0].contains("\"event_stream\":\"loc\""))
        assertTrue(events[1].contains("\"stream\":\"loc\""))
        assertTrue(events[2].contains("\"stream\":\"guide\""))
        assertTrue(events[2].contains("\"src_seq\":1"))
        assertTrue(events[2].contains("\"rule\":\"matching.on-route\""))
        assertTrue(events[3].contains("\"stream\":\"guide\""))
        assertTrue(events[3].contains("\"trigger\":\"on-demand\""))
        assertTrue(events[4].contains("\"stream\":\"sys\""))
        assertTrue(events[5].contains("\"kind\":\"events.config\""))
        assertTrue(events[5].contains("\"E1\":\"false\""))
        assertTrue(events[5].contains("\"E8\":\"true\""))
        assertTrue(events[6].contains("\"stream\":\"err\""))
        val seqs = events.map { Regex("\"seq\":(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), seqs)
        directory.deleteRecursively()
    }

    @Test
    fun deferredSlopeDiscardIsReturnedOnceAndLoggedWithoutRawLocation() {
        val elevations = listOf(0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 80, 80, 80, 80, 70, 60, 50, 40, 30, 20, 20, 20)
        val track = elevations.mapIndexed { index, elevation ->
            "<trkpt lat=\"${10.0 + index * 0.00045}\" lon=\"20.0\"><ele>$elevation</ele></trkpt>"
        }.joinToString("")
        val route = RouteModel.fromGpx("<gpx><trk><trkseg>$track</trkseg></trk></gpx>")
        val peak = route.peaks.first()
        val config = GuideConfig(
            milestoneEnabled = true,
            milestoneIntervalMeters = 100.0,
            slopeEnabled = true,
            peakAnnounceLeadMeters = 100.0,
            eventMinIntervalSeconds = 60.0,
            minimumSessionSecondsBeforeArrival = 600.0,
            sunsetEnabled = false,
        )
        val session = GuideSession(route, config)
        fun location(progress: Double, timestamp: Long) = TrailLocation(
            timestamp, 10.0 + progress / 111_195.0, 20.0, 5f, 1f, null, "synthetic",
        )
        session.accept(location(0.0, 0L))
        for (step in 1..((peak.s - 50.0) / 50.0).toInt()) {
            val progress = step * 50.0
            session.accept(location(progress, step * 1_000L))
        }
        val pending = session.accept(location(peak.s - 50.0, 30_000L))
        assertTrue(pending.result.nextState.pendingSlopePeaks.keys == setOf(0), "secondPeriodic=${session.snapshot().lastPeriodicEventAt}, direction=${pending.result.nextState.direction}, progress=${pending.result.nextState.lastMatch?.projectedMeters}, offRoute=${pending.result.nextState.offRoute}, peaks=${route.peaks}, reason=${pending.result.reason}")
        assertTrue(pending.newlyDiscardedSlopePeaks.isEmpty())

        val passed = session.accept(location(peak.s + 20.0, 62_000L))
        val discard = passed.newlyDiscardedSlopePeaks.single()
        assertEquals(0, discard.index)
        assertEquals(peak.s, discard.peakS)
        assertEquals("event-min-interval", discard.delayReason)
        val later = session.accept(location(peak.s + 30.0, 63_000L))
        assertTrue(later.newlyDiscardedSlopePeaks.isEmpty())

        val directory = createTempDir(prefix = "tnapp-e4-discard-")
        JsonlSessionLogger(
            directory = directory,
            sessionId = UUID.randomUUID().toString(),
            codeHash = "sha256:test",
            configHash = "sha256:config",
            eventSettings = EventSettings.defaults().toWireMap(),
            routeHash = "sha256:route",
            appVersion = "test",
        ).use { logger ->
            logger.appendDiscardedSlopePeaks(passed.newlyDiscardedSlopePeaks)
            logger.appendDiscardedSlopePeaks(later.newlyDiscardedSlopePeaks)
        }
        val events = File(directory, "events.ndjson").readLines().filter { it.contains("\"kind\":\"event.discarded\"") }
        assertEquals(1, events.size)
        val event = events.single()
        assertTrue(event.contains("\"event\":\"E4\""))
        assertTrue(event.contains("\"peak_index\":\"0\""))
        assertTrue(event.contains("\"peak_s\":\"${peak.s}\""))
        assertTrue(event.contains("\"passed_projected_meters\":"))
        assertTrue(event.contains("\"delay_reason\":\"event-min-interval\""))
        assertTrue(event.contains("\"reason\":\"passed\""))
        assertTrue(!event.contains("\"lat\"") && !event.contains("\"lon\"") && !event.contains("\"timestamp\""))
        directory.deleteRecursively()
    }

    @Test
    fun discardedWaypointIsLoggedWithoutLocationOrName() {
        val captured = mutableListOf<Pair<String, Map<String, String>>>()
        val sink = object : SessionEventSink {
            override fun appendEnvelope(location: TrailLocation, eventStream: String, eventType: String) = Unit
            override fun appendLocation(location: TrailLocation): Long = 0L
            override fun appendGuide(location: TrailLocation, result: com.trailnav.core.GuideResult, spokenText: String?, sourceSeq: Long, trigger: String?) = Unit
            override fun appendSystem(kind: String, details: Map<String, String>) { captured += kind to details }
            override fun appendError(kind: String, message: String) = Unit
            override fun close() = Unit
        }

        sink.appendDiscardedEvents(
            emptyList(),
            listOf(com.trailnav.core.DiscardedWaypoint(2, 410.0, 35.0, "priority")),
        )

        assertEquals(1, captured.size)
        assertEquals("event.discarded", captured.single().first)
        assertEquals(
            mapOf(
                "event" to "E6",
                "waypoint_index" to "2",
                "waypoint_s" to "410.0",
                "distance_meters" to "35.0",
                "reason" to "priority",
            ),
            captured.single().second,
        )
        assertTrue(captured.single().second.keys.none { it in setOf("lat", "lon", "timestamp", "name") })
    }

    @Test
    fun blockedArrivalIsReturnedOnceAndLoggedWithoutLocationOrTime() {
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.0" lon="20.018263876598734"/></trkseg></trk></gpx>""",
        )
        val session = GuideSession(route, GuideConfig(minimumSessionSecondsBeforeArrival = 0.0))
        fun location(progress: Double, timestamp: Long, speed: Float) = TrailLocation(
            timestamp,
            10.0,
            20.0 + 0.018263876598734 * progress / 2_000.0,
            5f,
            speed,
            null,
            "synthetic",
        )

        session.accept(location(1_940.0, 0L, 0f))
        val blocked = session.accept(location(1_980.0, 10_000L, 2f))
        val repeated = session.accept(location(1_980.0, 11_000L, 0f))
        assertEquals(1, blocked.newlyBlockedArrivals.size)
        assertTrue(repeated.newlyBlockedArrivals.isEmpty())
        assertTrue(blocked.result.guidance != Guidance.Arrived)

        val captured = mutableListOf<Pair<String, Map<String, String>>>()
        val sink = object : SessionEventSink {
            override fun appendEnvelope(location: TrailLocation, eventStream: String, eventType: String) = Unit
            override fun appendLocation(location: TrailLocation): Long = 0L
            override fun appendGuide(location: TrailLocation, result: com.trailnav.core.GuideResult, spokenText: String?, sourceSeq: Long, trigger: String?) = Unit
            override fun appendSystem(kind: String, details: Map<String, String>) { captured += kind to details }
            override fun appendError(kind: String, message: String) = Unit
            override fun close() = Unit
        }
        sink.appendArrivalBlockedEvents(blocked.newlyBlockedArrivals)
        sink.appendArrivalBlockedEvents(repeated.newlyBlockedArrivals)
        assertEquals(1, captured.size)
        assertEquals("arrival.blocked", captured.single().first)
        assertEquals(
            setOf("creditedMeters", "requiredMeters", "baselineRemainingMeters", "projectedMeters"),
            captured.single().second.keys,
        )
        assertTrue(captured.single().second.keys.none { it in setOf("lat", "lon", "timestamp", "t", "name") })

        val failingSink = object : SessionEventSink by sink {
            override fun appendSystem(kind: String, details: Map<String, String>) { error("synthetic logger failure") }
        }
        failingSink.appendArrivalBlockedEvents(blocked.newlyBlockedArrivals)
        assertTrue(blocked.result.guidance != Guidance.Arrived)
    }

    @Test
    fun failedDiscardLogDoesNotPropagateIntoGuideCall() {
        val sink = object : SessionEventSink {
            override fun appendEnvelope(location: TrailLocation, eventStream: String, eventType: String) = Unit
            override fun appendLocation(location: TrailLocation): Long = 0L
            override fun appendGuide(location: TrailLocation, result: com.trailnav.core.GuideResult, spokenText: String?, sourceSeq: Long, trigger: String?) = Unit
            override fun appendSystem(kind: String, details: Map<String, String>) { error("synthetic logger failure") }
            override fun appendError(kind: String, message: String) = Unit
            override fun close() = Unit
        }
        sink.appendDiscardedEvents(
            listOf(com.trailnav.core.DiscardedSlopePeak(0, 510.0, 520.0, "event-min-interval", "disabled")),
            listOf(com.trailnav.core.DiscardedWaypoint(2, 410.0, 35.0, "priority")),
        )
        assertTrue(true)
    }

    @Test
    fun eventSelectionChangesSessionConfigHash() {
        val defaults = EventSettings.defaults()
        val allOff = GuideEvent.values().fold(defaults) { settings, event -> settings.withEnabled(event, false) }
        val enabledMilestone = allOff.withEnabled(GuideEvent.MILESTONE, true)
        val firstHash = JsonlSessionLogger.sha256(allOff.toGuideConfig().toString())
        val secondHash = JsonlSessionLogger.sha256(enabledMilestone.toGuideConfig().toString())

        assertNotEquals(firstHash, secondHash)
    }

    @Test
    fun guideSessionUsesCompiledCoreEngine() {
        val route = RouteModel.fromGpx(
            """<gpx><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
        )
        val session = GuideSession(route)
        val decision = session.accept(TrailLocation(0L, 10.0001, 20.0, 5f, 1f, null, "fake"))
        assertTrue(decision.result.reason.rule.isNotBlank())
    }

    @Test
    fun onDemandConfigAndSuppressionAreRecordedWithRequiredFields() {
        val directory = createTempDir(prefix = "tnapp-ondemand-")
        JsonlSessionLogger(
            directory = directory,
            sessionId = UUID.randomUUID().toString(),
            codeHash = "sha256:test",
            configHash = "sha256:config",
            eventSettings = EventSettings.defaults().toWireMap(),
            routeHash = "sha256:route",
            appVersion = "test",
        ).use { logger ->
            logger.appendSystem("ondemand.config", OnDemandConfig().toWireMap())
            logger.appendSystem(
                "ondemand.suppressed",
                ShakeCooldownSuppression(count = 3, remainingMillis = 270_000L).toWireMap(),
            )
        }

        val events = File(directory, "events.ndjson").readLines()
        assertTrue(events[0].contains("\"kind\":\"ondemand.config\""))
        assertTrue(events[0].contains("\"shake_cooldown_ms\":\"60000\""))
        assertTrue(events[1].contains("\"kind\":\"ondemand.suppressed\""))
        assertTrue(events[1].contains("\"source\":\"shake\""))
        assertTrue(events[1].contains("\"reason\":\"cooldown\""))
        assertTrue(events[1].contains("\"count\":\"3\""))
        assertTrue(events[1].contains("\"remaining_ms\":\"270000\""))
        directory.deleteRecursively()
    }

    @Test
    fun manifestRecordsRejectedAndAbsentElevationRepair() {
        val rejected = manifestFor(
            elevationUsed = false,
            elevationReason = "unstable",
            repair = ElevationRepair(3, 0, 100, 20.0, listOf(400.0, 410.0, 420.0)),
        )
        assertTrue(rejected.contains("\"elevation_spike_points\":3"))
        assertTrue(rejected.contains("\"elevation_interpolated_points\":0"))
        assertTrue(rejected.contains("\"elevation_spike_ratio\":0.0300"))
        assertTrue(rejected.contains("\"elevation_spike_at_m\":\"400,410,420\""))

        val absent = manifestFor(
            elevationUsed = false,
            elevationReason = "absent",
            repair = ElevationRepair(),
        )
        assertTrue(absent.contains("\"elevation_spike_points\":0"))
        assertTrue(absent.contains("\"elevation_interpolated_points\":0"))
        assertTrue(absent.contains("\"elevation_total_points\":0"))
        assertTrue(absent.contains("\"elevation_spike_ratio\":0.0000"))
        assertTrue(absent.contains("\"elevation_spike_at_m\":\"\""))
    }

    @Test
    fun manifestRouteBlockCarriesQualityKeys() {
        val quality = RouteQuality(
            pointCount = 4,
            meanSpacingMeters = 75.0,
            maxSpacingMeters = 120.0,
            shortSegmentRatio = 0.3333,
            gapOver50Count = 2,
            gapOver100Count = 1,
            smoothedMaxAbsSlope = 0.12345,
            etaMinSegmentSpeedMps = 0.4567,
            etaClampedSegmentCount = 1,
            etaClampedLengthRatio = 0.5000,
            smoothingMaxDeltaMeters = 6.7,
        )
        val manifest = manifestFor(
            elevationUsed = true,
            elevationReason = "ok",
            repair = ElevationRepair(),
            quality = quality,
        )
        val routeBlock = manifest.substringAfter("\"route\":{").substringBefore("},\n")
        val existingKeys = listOf(
            "gpx_hash", "elevation_used", "elevation_reason", "waypoint_count",
            "elevation_spike_points", "elevation_interpolated_points", "elevation_total_points",
            "elevation_spike_ratio", "elevation_spike_ratio_cap", "elevation_max_residual_m",
            "elevation_spike_threshold_m", "elevation_spike_at_m",
        )
        val qualityKeys = listOf(
            "quality_point_count", "quality_mean_spacing_m", "quality_max_spacing_m",
            "quality_short_segment_ratio", "quality_gap_over_50_count", "quality_gap_over_100_count",
            "quality_smoothed_max_abs_slope", "quality_eta_min_segment_speed_mps",
            "quality_eta_clamped_segment_count", "quality_eta_clamped_length_ratio",
            "quality_smoothing_max_delta_m",
        )
        val allKeys = existingKeys + qualityKeys
        val keyPositions = allKeys.map { routeBlock.indexOf("\"$it\":") }

        assertEquals(allKeys.size, keyPositions.count { it >= 0 })
        assertEquals(keyPositions.sorted(), keyPositions)
        assertTrue(routeBlock.contains("\"quality_mean_spacing_m\":75.0"))
        assertTrue(routeBlock.contains("\"quality_eta_min_segment_speed_mps\":0.4567"))
    }

    private fun manifestFor(
        elevationUsed: Boolean,
        elevationReason: String,
        repair: ElevationRepair,
        quality: RouteQuality = RouteQuality.EMPTY,
    ): String {
        val directory = createTempDir(prefix = "tnapp-elevation-repair-")
        JsonlSessionLogger(
            directory = directory,
            sessionId = UUID.randomUUID().toString(),
            codeHash = "sha256:test",
            configHash = "sha256:config",
            eventSettings = EventSettings.defaults().toWireMap(),
            routeHash = "sha256:route",
            appVersion = "test",
            routeElevationUsed = elevationUsed,
            routeElevationReason = elevationReason,
            routeElevationRepair = repair,
            routeElevationSpikeRatioCap = 0.02,
            routeElevationSpikeThresholdMeters = 10.0,
            routeQuality = quality,
        ).close()
        val manifest = File(directory, "manifest.json").readText()
        directory.deleteRecursively()
        return manifest
    }
}
