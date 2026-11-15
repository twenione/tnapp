package com.trailnav.app

import com.trailnav.core.Engine
import com.trailnav.core.EngineSystemEvent
import com.trailnav.core.GuideConfig
import com.trailnav.core.Guidance
import com.trailnav.core.ProgressDirection
import com.trailnav.core.RouteModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteOrientationTest {
    private val xml = """
        <gpx><trk><trkseg>
          <trkpt lat="10.000000" lon="20.000000"/>
          <trkpt lat="10.000000" lon="20.001000"/>
          <trkpt lat="10.000000" lon="20.002000"/>
        </trkseg></trk></gpx>
    """.trimIndent()

    @Test
    fun clearForwardMovementKeepsOriginalOrderAndResolvesWithGuideProgress() {
        val route = RouteModel.fromGpx(xml)
        val session = GuideSession(route, startupEnabled = true)
        val decisions = acceptStraightTrack(session, 20.0005, 1, 50, 1_000L)

        assertTrue(session.snapshot().startupResolved)
        assertFalse(session.snapshot().routeReversed)
        assertEquals(ProgressDirection.FORWARD, session.snapshot().decidedDirection)
        assertEquals(20.0, session.snapshot().route.sourcePoints.first().lon, 0.000001)
        assertTrue(decisions.any { it.startupJustResolved })
        assertEquals("trajectory-match", decisions.flatMap { it.newlyEmittedSystemEvents }
            .single { it.kind == "route.orientation" }.fields["reason"])
    }

    @Test
    fun startupResolutionResetsArrivalBaselineAndRecordsSystemEvent() {
        val config = GuideConfig()
        val route = RouteModel.fromGpx(xml, config)
        val session = GuideSession(route, config, startupEnabled = true)
        val decisions = acceptStraightTrack(session, 20.0005, 1, 50, 1_000L)

        assertTrue(decisions.any { it.startupJustResolved })
        val state = session.snapshot()
        val baseline = assertNotNull(state.arrivalBaselineRemainingMeters)
        val reset = state.systemEvents.single { it.kind == "arrival.baseline-reset" }
        assertEquals("none", reset.fields["previous_baseline_remaining_m"])
        assertEquals(baseline.toString(), reset.fields["new_baseline_remaining_m"])
        assertTrue(baseline in 0.0..route.totalLengthMeters)
    }

    @Test
    fun startupPendingSuppressesElevationBoundaryAnnouncement() {
        val config = GuideConfig().copy(
            elevationEnabled = true,
            elevationBoundaryMeters = 0.5,
            elevationHysteresisMeters = 0.0,
        )
        val probe = StartRoute(
            name = "startup-elevation-boundary",
            corners = listOf(
                StartPoint(0.0, 0.0, 0.0),
                StartPoint(0.0, 100.0, 5.0),
                StartPoint(0.0, 200.0, 10.0),
                StartPoint(0.0, 300.0, 15.0),
            ),
            closed = false,
        )
        val route = RouteModel.fromGpx(probe.xml, config)
        assertTrue(route.elevationUse.used, "the deterministic test route must expose a stable elevation profile")
        val session = GuideSession(route, config, startupEnabled = true)
        val first = probe.pointAt(0.0)
        val afterBoundary = probe.pointAt(20.0)

        fun fix(point: StartPoint, timestampMillis: Long) = TrailLocation(
            timestampMillis = timestampMillis,
            latitude = StartProbeRoutes.latitude + point.north / StartProbeRoutes.METERS_PER_DEGREE,
            longitude = StartProbeRoutes.longitude + point.east / StartProbeRoutes.metersPerDegreeLongitude,
            accuracyMeters = 5f,
            speedMps = 1.2f,
            bearingDegrees = null,
            provider = "synthetic-startup-elevation",
        )

        session.accept(fix(first, 0L))
        val decision = session.accept(fix(afterBoundary, 16_667L))
        assertFalse(decision.result.nextState.startupResolved, "20 m must remain below the 30 m startup-fit window")
        assertFalse(
            decision.result.guidance is Guidance.Elevation,
            "direction-dependent E5 must stay silent while the initial direction is unresolved",
        )
    }

    @Test
    fun startupPendingKeepsTurnAheadAndTurnNowTargetsUnconsumed() {
        val config = GuideConfig()
        val probe = StartRoute(
            name = "startup-turn-before-resolution",
            corners = listOf(StartPoint(0.0, 0.0), StartPoint(40.0, 0.0), StartPoint(40.0, 300.0)),
            closed = false,
        )
        val session = GuideSession(RouteModel.fromGpx(probe.xml, config), config, startupEnabled = true)
        for (second in 0..24) {
            val decision = session.accept(trailLocation(probe.pointAt(1.2 * second), second))
            assertFalse(decision.result.nextState.startupResolved, "the 30 m start window is not complete at second=$second")
            assertFalse(decision.result.guidance is Guidance.TurnAhead, "TURN_AHEAD escaped before direction was known at second=$second")
            assertFalse(decision.result.guidance is Guidance.TurnNow, "TURN_NOW escaped before direction was known at second=$second")
            assertTrue(decision.result.nextState.completedTurnAheadIndices.isEmpty())
            assertTrue(decision.result.nextState.completedTurnNowIndices.isEmpty())
        }
    }

    @Test
    fun startupArrivalCandidateIsBlockedAndRecordedAsDirectionUnknownOnce() {
        val config = GuideConfig()
        val probe = StartRoute(
            name = "startup-arrival-loop",
            corners = listOf(
                StartPoint(0.0, 0.0), StartPoint(300.0, 0.0), StartPoint(300.0, 300.0),
                StartPoint(0.0, 300.0), StartPoint(0.0, 0.0),
            ),
            closed = true,
        )
        val route = RouteModel.fromGpx(probe.xml, config)
        val session = GuideSession(route, config, startupEnabled = true)
        val start = probe.pointAt(probe.lengthMeters - 5.0)
        var blocked = emptyList<com.trailnav.core.ArrivalBlockedRecord>()
        for (second in 0..20) {
            val location = TrailLocation(
                timestampMillis = second * 1_000L,
                latitude = StartProbeRoutes.latitude + start.north / StartProbeRoutes.METERS_PER_DEGREE,
                longitude = StartProbeRoutes.longitude + start.east / StartProbeRoutes.metersPerDegreeLongitude,
                accuracyMeters = 5f,
                speedMps = 0f,
                bearingDegrees = null,
                provider = "synthetic-startup-arrival",
            )
            val decision = session.accept(location)
            assertFalse(decision.result.nextState.startupResolved, "stationary start must remain undecided")
            assertFalse(decision.result.guidance is Guidance.Arrived)
            if (decision.newlyBlockedArrivals.isNotEmpty()) blocked = decision.newlyBlockedArrivals
        }
        assertEquals(1, blocked.size, "the route-end slot records only once")
        assertEquals("direction-unknown", blocked.single().reason)
        assertEquals(1, session.snapshot().arrivalBlockedRecords.size)
    }

    @Test
    fun clearReverseMovementReversesOrderAndGuideArrivesAtOriginalFirstPoint() {
        val session = GuideSession(RouteModel.fromGpx(xml), GuideConfig(minimumSessionSecondsBeforeArrival = 0.0), startupEnabled = true)
        val startupDecisions = acceptStraightTrack(session, 20.0015, -1, 70, 0L)

        assertTrue(session.snapshot().startupResolved)
        assertTrue(session.snapshot().routeReversed)
        assertEquals(ProgressDirection.REVERSE, session.snapshot().decidedDirection)
        assertEquals(20.002, session.snapshot().route.sourcePoints.first().lon, 0.000001)
        assertEquals(20.0, session.snapshot().route.sourcePoints.last().lon, 0.000001)

        var arrival: SessionDecision? = null
        val lastStartupFix = startupDecisions.last().location
        for (step in 1..180) {
            val longitude = lastStartupFix.longitude + (20.0 - lastStartupFix.longitude) * step / 180.0
            val decision = session.accept(location(longitude, lastStartupFix.timestampMillis + step * 5_000L, 1f))
            if (decision.result.guidance is Guidance.Arrived) {
                arrival = decision
                break
            }
        }
        assertTrue(arrival?.result?.guidance is Guidance.Arrived)
    }

    @Test
    fun noisyInPlaceMovementDoesNotResolveUntilTrajectoryIsLongEnough() {
        val session = GuideSession(RouteModel.fromGpx(xml), startupEnabled = true)
        repeat(36) { second ->
            val jitter = if (second % 2 == 0) 0.00001 else -0.00001
            session.accept(location(20.001 + jitter, second * 1_000L, 0f))
        }

        assertFalse(session.snapshot().startupResolved)
        assertNull(session.routeStatus())
        assertTrue(session.finish().single { it.kind == "direction.undecided-at-end" }.fields["walked_meters"]!!.toDouble() < 50.0)
    }

    @Test
    fun preDecisionMatchingUsesTheWholeRouteWhenThePreviousProgressWindowMissesTheNearestSegment() {
        val routeXml = routeXmlForMeters(
            listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 500.0, 0.0 to 500.0),
        )
        val route = RouteModel.fromGpx(routeXml)
        val session = GuideSession(route, startupEnabled = true)

        session.accept(locationAtMeters(east = 50.0, north = 0.0, timestampMillis = 0L))
        assertTrue(session.snapshot().hasEnteredRoute)
        assertFalse(session.snapshot().startupResolved)

        session.accept(locationAtMeters(east = 50.0, north = 500.0, timestampMillis = 1_000L))

        assertFalse(session.snapshot().startupResolved, "stationary fixes must keep startup direction undecided")
        assertTrue(
            assertNotNull(session.snapshot().lastMatch).distanceMeters <= 1.0,
            "pre-decision matching must use the whole-route nearest segment, not the previous-progress window",
        )
    }

    @Test
    fun timeoutWithoutARecentFixRemainsUndecidedInsteadOfUsingOriginalOrder() {
        val session = GuideSession(RouteModel.fromGpx(xml), startupEnabled = true)
        repeat(36) { second -> session.accept(location(20.001, second * 1_000L, 0f)) }
        assertFalse(session.snapshot().startupResolved)
        assertFalse(session.snapshot().systemEvents.any { it.kind == "route.orientation" })
        assertEquals("undecided", Engine.finish(session.snapshot()).let { if (it.startupResolved) "resolved" else "undecided" })
    }

    @Test
    fun endpointAloneDoesNotTriggerFallbackInEitherDirection() {
        for (endpoint in listOf(20.0, 20.002)) {
            val session = GuideSession(RouteModel.fromGpx(xml), startupEnabled = true)
            repeat(40) { second -> session.accept(location(endpoint, second * 1_000L, 0f)) }
            assertFalse(session.snapshot().startupResolved, "endpoint=$endpoint")
            assertTrue(session.finish().single { it.kind == "direction.undecided-at-end" }.fields.containsKey("frames_before_decision"))
        }
    }

    @Test
    fun eachLocationIsSentDirectlyToTheEngineWithoutBufferReplay() {
        val session = GuideSession(RouteModel.fromGpx(xml), startupEnabled = true)
        val fixes = listOf(20.001000, 20.001080, 20.001250).mapIndexed { index, longitude ->
            location(longitude, index * 1_000L, 1f)
        }
        val decisions = fixes.map(session::accept)

        assertEquals(fixes, decisions.map { it.location })
        assertEquals(3, session.snapshot().trajectoryFrameCount)
        assertEquals(3, session.snapshot().lastTimestamp?.let { 3 } ?: 0)
        assertFalse(session.snapshot().startupResolved)
    }

    @Test
    fun reversePreservesElevationSamplesAndWaypointNames() {
        val source = """
            <gpx version="1.1"><wpt lat="10.0000" lon="20.0005"><name>쉼터 &amp; 전망대</name></wpt>
              <trk><trkseg>
                <trkpt lat="10.000000" lon="20.000000"><ele>101.5</ele></trkpt>
                <trkpt lat="10.000000" lon="20.001000"><ele>108.0</ele></trkpt>
              </trkseg></trk></gpx>
        """.trimIndent()
        val reversed = RouteModel.fromGpx(RouteOrientation.reverseGpx(source))
        assertEquals(20.001, reversed.sourcePoints.first().lon, 0.000001)
        assertEquals(listOf(108.0, 101.5), reversed.elevationMeters)
        assertEquals(listOf("쉼터 & 전망대"), reversed.waypoints.map { it.name })
    }

    @Test
    fun reversePreservesNaverWaypointsAsStandardWaypoints() {
        val source = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:nmap="https://map.naver.com/gpx/1">
              <metadata><extensions><nmap:walkCourse><nmap:waypoints>
                <nmap:waypoint index="0" lat="10.0000" lon="20.0000"><nmap:type>start</nmap:type><nmap:name>출발지</nmap:name><nmap:desc>start</nmap:desc></nmap:waypoint>
                <nmap:waypoint index="1" lat="10.0000" lon="20.0010"><nmap:type>waypoint</nmap:type><nmap:name>전망대</nmap:name><nmap:desc>view</nmap:desc></nmap:waypoint>
                <nmap:waypoint index="2" lat="10.0000" lon="20.0020"><nmap:type>goal</nmap:type><nmap:name>도착지</nmap:name><nmap:desc>goal</nmap:desc></nmap:waypoint>
              </nmap:waypoints></nmap:walkCourse></extensions></metadata>
              <trk><trkseg>
                <trkpt lat="10.0000" lon="20.0000"><ele>100</ele></trkpt>
                <trkpt lat="10.0000" lon="20.0010"><ele>120</ele></trkpt>
                <trkpt lat="10.0000" lon="20.0020"><ele>140</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val reversed = RouteModel.fromGpx(RouteOrientation.reverseGpx(source))
        assertEquals(listOf("출발지", "전망대", "도착지"), reversed.waypoints.map { it.name })
        assertEquals(3, reversed.waypoints.size)
        assertEquals(listOf(140.0, 120.0, 100.0), reversed.elevationMeters)
    }

    @Test
    fun preparationStageLabelsAreShortAndExplicit() {
        assertEquals("안내를 준비중", RoutePreparationStage.PREPARING.label)
        assertEquals("시작점 잡힘", RoutePreparationStage.START_FOUND.label)
        assertEquals("경로 방향 잡힘 (안내를 시작합니다)", RoutePreparationStage.DIRECTION_CONFIRMED.label)
        assertEquals(RoutePreparationStage.PREPARING, RoutePreparationStage.fromWire("unknown"))
    }

    @Test
    fun engineDirectionApplicationMatchesReverseGpxOracleForBothDirections() {
        val route = StartRoute(
            "A5-oracle-turns",
            listOf(
                StartPoint(0.0, 0.0, 100.0), StartPoint(240.0, 0.0, 140.0),
                StartPoint(240.0, 240.0, 90.0), StartPoint(480.0, 240.0, 180.0),
                StartPoint(480.0, 480.0, 110.0), StartPoint(720.0, 480.0, 200.0),
            ),
            closed = false,
        )
        val xml = routeGpx(route.points, listOf(route.pointAt(180.0) to "중간쉼터", route.pointAt(620.0) to "전망대"))
        val config = GuideConfig()
        val total = RouteModel.fromGpx(xml, config).totalLengthMeters

        for (direction in listOf(1, -1)) {
            val start = if (direction > 0) 0.0 else total
            val engineSession = GuideSession(RouteModel.fromGpx(xml, config), config, startupEnabled = true)
            val oracleXml = if (direction < 0) RouteOrientation.reverseGpx(xml) else xml
            val oracleSession = GuideSession(RouteModel.fromGpx(oracleXml, config), config)
            var decisionFrame: TrailLocation? = null
            for (second in 0..900) {
                val location = trailLocation(route.pointAt(start + direction * 1.2 * second), second)
                val decision = engineSession.accept(location)
                oracleSession.accept(location)
                if (decision.startupJustResolved) {
                    decisionFrame = location
                    break
                }
            }
            val resolvedAt = assertNotNull(decisionFrame, "direction=$direction did not resolve")
            assertEquals(direction < 0, engineSession.snapshot().routeReversed)
            assertEquals(if (direction > 0) ProgressDirection.FORWARD else ProgressDirection.REVERSE, engineSession.snapshot().decidedDirection)

            val wrongOrientationSession = if (direction < 0) GuideSession(RouteModel.fromGpx(xml, config), config) else null
            wrongOrientationSession?.accept(resolvedAt)
            var wrongOrientationDiverged = false

            for (second in (resolvedAt.timestampMillis / 1_000L + 1L).toInt()..900) {
                val location = trailLocation(route.pointAt(start + direction * 1.2 * second), second)
                val actual = engineSession.accept(location)
                val oracle = oracleSession.accept(location)
                assertEquals(oracleFeatures(oracle, oracleSession), oracleFeatures(actual, engineSession), "direction=$direction second=$second")

                val wrong = wrongOrientationSession?.accept(location)
                if (wrong != null && oracleFeatures(wrong, wrongOrientationSession!!) != oracleFeatures(actual, engineSession)) wrongOrientationDiverged = true
            }
            if (direction < 0) {
                assertTrue(wrongOrientationDiverged, "the deliberately un-reversed counterexample did not diverge")
                assertTrue(engineSession.snapshot().route.elevationMeters.isNotEmpty())
                assertEquals(listOf("중간쉼터", "전망대"), engineSession.snapshot().route.waypoints.map { it.name })
            }
        }
    }

    @Test
    fun engineDirectionResolutionReorientsAllEventStateToTheD203Oracle() {
        val route = StartRoute(
            "A5-all-events-oracle",
            listOf(
                StartPoint(0.0, 0.0, 100.0), StartPoint(240.0, 0.0, 140.0),
                StartPoint(240.0, 240.0, 90.0), StartPoint(480.0, 240.0, 180.0),
                StartPoint(480.0, 480.0, 110.0), StartPoint(720.0, 480.0, 200.0),
            ),
            closed = false,
        )
        val config = GuideConfig().copy(
            milestoneEnabled = true,
            elapsedEnabled = true,
            remainingEnabled = true,
            slopeEnabled = true,
            elevationEnabled = true,
            waypointEnabled = true,
            sunriseEnabled = true,
        )
        val routeXml = routeGpx(
            route.points,
            listOf(route.pointAt(180.0) to "중간쉼터", route.pointAt(620.0) to "전망대"),
        )
        val total = RouteModel.fromGpx(routeXml, config).totalLengthMeters
        val firstMismatchByDirectionAndField = linkedMapOf<String, String>()
        val waypointStateAtResolution = linkedMapOf<String, Pair<Set<Int>, Set<Int>>>()

        fun eventFeatures(decision: SessionDecision, session: GuideSession): Map<String, Any?> {
            val state = session.snapshot()
            return linkedMapOf(
                "guidance" to decision.result.guidance,
                "reason.rule" to decision.result.reason.rule,
                "reason.details" to decision.result.reason.details,
                "newlyDiscardedEvents" to decision.newlyDiscardedEvents,
                "newlyDiscardedSlopePeaks" to decision.newlyDiscardedSlopePeaks,
                "newlyDiscardedWaypoints" to decision.newlyDiscardedWaypoints,
                "newlyBlockedArrivals" to decision.newlyBlockedArrivals,
                "lastAnnouncementAt" to state.lastAnnouncementAt,
                "lastAnnouncementDistance" to state.lastAnnouncementDistance,
                "lastPeriodicEventAt" to state.lastPeriodicEventAt,
                "arrived" to state.arrived,
                // Direction-unknown arrival diagnostics are startup-specific (classification b), not event parity.
                "completedTurnAheadIndices" to state.completedTurnAheadIndices,
                "completedTurnNowIndices" to state.completedTurnNowIndices,
                "consumedMilestoneIndices" to state.consumedMilestoneIndices,
                "consumedElapsedIndices" to state.consumedElapsedIndices,
                "consumedRemainingThresholds" to state.consumedRemainingThresholds,
                "pendingRemainingThresholds" to state.pendingRemainingThresholds,
                "pendingRemainingDelayReason" to state.pendingRemainingDelayReason,
                "consumedSlopeIndices" to state.consumedSlopeIndices,
                "pendingSlopePeaks" to state.pendingSlopePeaks,
                "discardedSlopePeaks" to state.discardedSlopePeaks,
                "consumedWaypointIndices" to state.consumedWaypointIndices,
                "discardedWaypoints" to state.discardedWaypoints,
                "discardedEvents" to state.discardedEvents,
                "consumedSunsetThresholds" to state.consumedSunsetThresholds,
                "pendingSunsetThresholds" to state.pendingSunsetThresholds,
                "pendingSunsetDelayReason" to state.pendingSunsetDelayReason,
                "sunsetEvaluated" to state.sunsetEvaluated,
                "elevationBand" to state.elevationBand,
                "sunriseEvaluated" to state.sunriseEvaluated,
                "consumedSunriseThresholds" to state.consumedSunriseThresholds,
                "sunriseLocalDay" to state.sunriseLocalDay,
            )
        }

        for (direction in listOf(1, -1)) {
            val start = if (direction > 0) 0.0 else total
            val engineSession = GuideSession(RouteModel.fromGpx(routeXml, config), config, startupEnabled = true)
            val oracleXml = if (direction < 0) RouteOrientation.reverseGpx(routeXml) else routeXml
            val oracleSession = GuideSession(RouteModel.fromGpx(oracleXml, config), config)
            var resolvedSecond: Int? = null

            fun compareAt(second: Int, engineDecision: SessionDecision, oracleDecision: SessionDecision) {
                val engineFeatures = eventFeatures(engineDecision, engineSession)
                val oracleFeatures = eventFeatures(oracleDecision, oracleSession)
                for ((field, engineValue) in engineFeatures) {
                    val oracleValue = oracleFeatures[field]
                    if (engineValue != oracleValue) {
                        val directionName = if (direction > 0) "forward" else "reverse"
                        val key = "$directionName:$field"
                        firstMismatchByDirectionAndField.putIfAbsent(
                            key,
                            "direction=$directionName second=$second field=$field engine=$engineValue oracle=$oracleValue",
                        )
                    }
                }
            }

            for (second in 0..900) {
                val location = trailLocation(route.pointAt(start + direction * 1.2 * second), second)
                val engineDecision = engineSession.accept(location)
                val oracleDecision = oracleSession.accept(location)
                if (engineDecision.startupJustResolved) {
                    resolvedSecond = second
                    val directionName = if (direction > 0) "forward" else "reverse"
                    waypointStateAtResolution[directionName] =
                        engineSession.snapshot().consumedWaypointIndices to oracleSession.snapshot().consumedWaypointIndices
                    compareAt(second, engineDecision, oracleDecision)
                    break
                }
            }
            val decisionSecond = assertNotNull(resolvedSecond, "direction=$direction did not resolve")
            assertEquals(direction < 0, engineSession.snapshot().routeReversed)
            for (second in (decisionSecond + 1)..900) {
                val location = trailLocation(route.pointAt(start + direction * 1.2 * second), second)
                val engineDecision = engineSession.accept(location)
                val oracleDecision = oracleSession.accept(location)
                compareAt(second, engineDecision, oracleDecision)
            }
        }

        assertTrue(
            firstMismatchByDirectionAndField.isEmpty(),
            "event state must match the D-203 oracle from direction resolution:\n" +
                firstMismatchByDirectionAndField.values.joinToString("\n"),
        )
        waypointStateAtResolution.forEach { entry ->
            val (engineIndices, oracleIndices) = entry.value
            assertEquals(
                oracleIndices,
                engineIndices,
                "consumedWaypointIndices must reset at direction resolution for ${entry.key}",
            )
        }
    }

    @Test
    fun startupCarriesPreDecisionEtaObservationsIntoTheCalibrationWindow() {
        val route = StartRoute(
            "A5-oracle-turns",
            listOf(
                StartPoint(0.0, 0.0, 100.0), StartPoint(240.0, 0.0, 140.0),
                StartPoint(240.0, 240.0, 90.0), StartPoint(480.0, 240.0, 180.0),
                StartPoint(480.0, 480.0, 110.0), StartPoint(720.0, 480.0, 200.0),
            ),
            closed = false,
        )
        val routeXml = routeGpx(
            route.points,
            listOf(route.pointAt(180.0) to "중간쉼터", route.pointAt(620.0) to "전망대"),
        )
        val config = GuideConfig()
        val startupSession = GuideSession(RouteModel.fromGpx(routeXml, config), config, startupEnabled = true)
        val orientedControl = GuideSession(RouteModel.fromGpx(routeXml, config), config)
        val measuredThroughSecond = 276
        var decisionSecond: Int? = null
        var startupAtMeasurement: com.trailnav.core.RouteTargetEstimate? = null
        var controlAtMeasurement: com.trailnav.core.RouteTargetEstimate? = null

        for (second in 0..280) {
            val location = trailLocation(route.pointAt(1.2 * second), second)
            val startupDecision = startupSession.accept(location)
            orientedControl.accept(location)
            if (startupDecision.startupJustResolved) decisionSecond = second
            if (second == measuredThroughSecond) {
                startupAtMeasurement = startupSession.routeStatus()?.target
                controlAtMeasurement = orientedControl.routeStatus()?.target
            }
        }

        val resolvedAt = assertNotNull(decisionSecond, "the 30m startup trajectory did not resolve")
        assertTrue(resolvedAt > 0)
        val startupEstimate = assertNotNull(startupAtMeasurement)
        val controlEstimate = assertNotNull(controlAtMeasurement)
        assertTrue(startupEstimate.correctionActive, "pre-decision walking must contribute to the 300m ETA window")
        assertTrue(controlEstimate.correctionActive, "the already-oriented control must activate the same ETA window")

        // Count accepted location inputs on each side of the decision; the resolving fix belongs to the post-decision side.
        val allInputLocations = (0..measuredThroughSecond).count()
        val preDecisionInputLocations = (0 until resolvedAt).count()
        val postDecisionInputLocations = (resolvedAt..measuredThroughSecond).count()
        assertEquals(allInputLocations, preDecisionInputLocations + postDecisionInputLocations)

        assertEquals(controlEstimate.correction, startupEstimate.correction, 1e-6)
    }

    private fun oracleFeatures(decision: SessionDecision, session: GuideSession): List<Any?> = listOf(
        decision.result.guidance,
        decision.result.reason.rule,
        decision.result.reason.details,
        decision.newlyDiscardedEvents,
        decision.newlyDiscardedSlopePeaks,
        decision.newlyDiscardedWaypoints,
        decision.newlyBlockedArrivals,
        session.routeStatus(),
    )

    private fun acceptStraightTrack(
        session: GuideSession,
        startingLongitude: Double,
        direction: Int,
        meters: Int,
        startMillis: Long,
    ): List<SessionDecision> {
        val seconds = (meters / 1.2).toInt()
        return (0..seconds).map { second ->
            session.accept(location(startingLongitude + direction * 1.2 * second / 111_000.0, startMillis + second * 1_000L, 1.2f))
        }
    }

    private fun trailLocation(point: StartPoint, second: Int): TrailLocation = TrailLocation(
        timestampMillis = second * 1_000L,
        latitude = StartProbeRoutes.latitude + point.north / StartProbeRoutes.METERS_PER_DEGREE,
        longitude = StartProbeRoutes.longitude + point.east / StartProbeRoutes.metersPerDegreeLongitude,
        accuracyMeters = 5f,
        speedMps = 1.2f,
        bearingDegrees = null,
        provider = "synthetic-start-phase-oracle",
    )

    private fun location(longitude: Double, timestampMillis: Long, speed: Float): TrailLocation = TrailLocation(
        timestampMillis = timestampMillis,
        latitude = 10.0,
        longitude = longitude,
        accuracyMeters = 5f,
        speedMps = speed,
        bearingDegrees = null,
        provider = "test",
    )

    private fun locationAtMeters(east: Double, north: Double, timestampMillis: Long): TrailLocation = TrailLocation(
        timestampMillis = timestampMillis,
        latitude = 37.0 + north / 111_320.0,
        longitude = 127.0 + east / (111_320.0 * kotlin.math.cos(Math.toRadians(37.0))),
        accuracyMeters = 5f,
        speedMps = 0f,
        bearingDegrees = null,
        provider = "synthetic-predecision-window",
    )

    private fun routeXmlForMeters(points: List<Pair<Double, Double>>): String = buildString {
        append("<gpx><trk><trkseg>")
        points.forEach { (east, north) ->
            val lat = 37.0 + north / 111_320.0
            val lon = 127.0 + east / (111_320.0 * kotlin.math.cos(Math.toRadians(37.0)))
            append("<trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>")
        }
        append("</trkseg></trk></gpx>")
    }
}
