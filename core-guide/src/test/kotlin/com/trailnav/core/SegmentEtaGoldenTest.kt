package com.trailnav.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SegmentEtaGoldenTest {
    private data class PointValue(
        val index: Int,
        val distanceMeters: Double,
        val targetKind: String,
        val targetSource: String,
        val targetMeters: Double,
        val remainingMeters: Double,
        val remainingSeconds: Double,
    )

    private data class SegmentValue(
        val fromIndex: Int,
        val toIndex: Int,
        val distanceMeters: Double,
        val goalTransition: Boolean,
        val etaSeconds: Double?,
    )

    private data class GoldenPoint(
        val index: Int,
        val distanceMeters: Double,
        val targetKind: String,
        val targetSource: String,
        val targetMeters: Double,
        val remainingSeconds: Double,
    )

    private data class Measurement(
        val points: List<PointValue>,
        val segments: List<SegmentValue>,
    )

    private data class Golden(
        val fixture: String,
        val pointCount: Int,
        val segmentCount: Int,
        val relativeTolerance: Double,
        val points: List<GoldenPoint>,
        val segments: List<SegmentValue>,
    )

    private val fixtureRelativePath = "testdata/routes/eta_gap_steep_1/route.gpx"
    private val goldenRelativePath = "testdata/routes/eta_segment_eta_golden.json"
    private val mutationPointIndex = 5

    @Test
    fun sparseRouteSegmentEtasMatchGoldenAndPhysicalBounds() {
        val root = repositoryRoot()
        val config = GuideConfig()
        val golden = loadGolden(root)
        val route = RouteModel.fromGpx(Files.readString(root.resolve(golden.fixture)), config)

        assertTrue(route.elevationUse.used && route.elevationUse.reason == "ok")
        assertTrue(route.sourcePoints.size >= 4, "fixture must contain at least three route segments")

        val actual = measure(route, config)
        assertPhysicalBounds(actual, config)
        assertGoldenMatches(actual, golden)

        println(
            "SEGMENT_ETA_GOLDEN fixture=" + golden.fixture +
                " pointCount=" + actual.points.size +
                " segmentCount=" + actual.segments.size +
                " goalTransitions=" + actual.segments.count { it.goalTransition } +
                " relativeTolerance=" + golden.relativeTolerance
        )
    }

    @Test
    fun internalPlus10mMutationUsesSmoothingAndBreaksSegmentGolden() {
        val root = repositoryRoot()
        val config = GuideConfig()
        val golden = loadGolden(root)
        val gpx = Files.readString(root.resolve(golden.fixture))
        val baselineRoute = RouteModel.fromGpx(gpx, config)
        val baseline = measure(baselineRoute, config)
        assertTrue(baselineRoute.elevationUse.used && baselineRoute.elevationUse.reason == "ok")
        assertTrue(
            baselineRoute.elevationRepair.spikePoints == 0,
            "baseline fixture must use smoothing without spike repair: " + baselineRoute.elevationRepair.spikePoints,
        )
        assertGoldenMatches(baseline, golden)

        assertTrue(mutationPointIndex > 0 && mutationPointIndex < baselineRoute.sourcePoints.lastIndex)
        val plus10Route = RouteModel.fromGpx(mutateElevation(gpx, mutationPointIndex, 10.0), config)
        assertTrue(
            plus10Route.elevationUse.used && plus10Route.elevationUse.reason == "ok",
            "+10m mutation left the intended smoothing path: " + plus10Route.elevationUse,
        )
        assertTrue(
            plus10Route.elevationRepair.spikePoints == 0,
            "+10m mutation was classified as a spike, not the smoothing path: " +
                plus10Route.elevationRepair.spikePoints,
        )

        val plus10 = measure(plus10Route, config)
        assertPhysicalBounds(plus10, config)
        val mutationFailure = runCatching { assertGoldenMatches(plus10, golden) }.exceptionOrNull()
        assertNotNull(mutationFailure, "+10m mutation unexpectedly matched the per-segment ETA golden")
        val failureMessage = mutationFailure.message.orEmpty()
        assertTrue(
            failureMessage.contains("segment 4->5"),
            "+10m mismatch must identify a changed interval and expected/actual ETA: " + failureMessage,
        )
        val changedSegments = differingSegments(plus10, golden)
        assertTrue(changedSegments.isNotEmpty(), "+10m mutation changed no segment ETA")
        println(
            "SEGMENT_ETA_PLUS10 fixture=" + golden.fixture +
                " pointIndex=" + mutationPointIndex +
                " elevationUse=" + plus10Route.elevationUse +
                " spikePoints=" + plus10Route.elevationRepair.spikePoints +
                " changedSegments=" + changedSegments.joinToString(",")
        )
        println("SEGMENT_ETA_PLUS10_FAILURE " + failureMessage)

        val plus50Route = RouteModel.fromGpx(mutateElevation(gpx, mutationPointIndex, 50.0), config)
        val plus50 = measure(plus50Route, config)
        println(
            "SEGMENT_ETA_PLUS50 fixture=" + golden.fixture +
                " pointIndex=" + mutationPointIndex +
                " elevationUse=" + plus50Route.elevationUse +
                " spikePoints=" + plus50Route.elevationRepair.spikePoints +
                " differsFromGolden=" + differingSegments(plus50, golden).joinToString(",")
        )
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private fun loadGolden(root: Path): Golden {
        val text = Files.readString(root.resolve(goldenRelativePath))
        val version = numberField(text, "schema_version").toInt()
        check(version == 1) { "segment ETA golden schema_version must be 1" }
        val fixture = stringField(text, "fixture")
        check(fixture == fixtureRelativePath) { "unexpected segment fixture: " + fixture }
        check(stringField(text, "config") == "GuideConfig() defaults") { "golden config must use defaults" }

        val number = """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"""
        val pointPattern = Regex(
            """\{"index":(\d+),"distance_m":($number),"target_kind":"([^"]+)","target_source":"([^"]+)","target_m":($number),"remaining_seconds":($number|null)\}"""
        )
        val points = pointPattern.findAll(text).map { match ->
            GoldenPoint(
                index = match.groupValues[1].toInt(),
                distanceMeters = match.groupValues[2].toDouble(),
                targetKind = match.groupValues[3],
                targetSource = match.groupValues[4],
                targetMeters = match.groupValues[5].toDouble(),
                remainingSeconds = match.groupValues[6].toDouble(),
            )
        }.toList()
        val segmentPattern = Regex(
            """\{"from_index":(\d+),"to_index":(\d+),"distance_m":($number),"goal_transition":(true|false),"eta_seconds":($number|null)\}"""
        )
        val segments = segmentPattern.findAll(text).map { match ->
            SegmentValue(
                fromIndex = match.groupValues[1].toInt(),
                toIndex = match.groupValues[2].toInt(),
                distanceMeters = match.groupValues[3].toDouble(),
                goalTransition = match.groupValues[4].toBoolean(),
                etaSeconds = match.groupValues[5].takeUnless { it == "null" }?.toDouble(),
            )
        }.toList()
        return Golden(
            fixture = fixture,
            pointCount = numberField(text, "point_count").toInt(),
            segmentCount = numberField(text, "segment_count").toInt(),
            relativeTolerance = numberField(text, "relative_tolerance"),
            points = points,
            segments = segments,
        )
    }

    private fun stringField(text: String, name: String): String {
        val pattern = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]*)\"")
        return requireNotNull(pattern.find(text)).groupValues[1]
    }

    private fun numberField(text: String, name: String): Double {
        val number = """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"""
        val pattern = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(" + number + ")")
        return requireNotNull(pattern.find(text)).groupValues[1].toDouble()
    }

    private fun measure(route: RouteModel, config: GuideConfig): Measurement {
        val tracker = RouteEtaTracker(route, config)
        val points = route.cumulativeMeters.mapIndexed { index, position ->
            val target = assertNotNull(
                tracker.estimate(position, ProgressDirection.FORWARD, arrived = false),
                "ETA target missing at point " + index,
            )
            val seconds = assertNotNull(
                target.remainingSeconds,
                "ETA unavailable at point " + index + "; reason=" + target.etaUnavailableReason,
            )
            PointValue(
                index = index,
                distanceMeters = position,
                targetKind = target.kind.name,
                targetSource = target.source,
                targetMeters = position + target.remainingMeters,
                remainingMeters = target.remainingMeters,
                remainingSeconds = seconds,
            )
        }
        val segments = points.zipWithNext().mapIndexed { index, pair ->
            val left = pair.first
            val right = pair.second
            val sameTarget = left.targetKind == right.targetKind &&
                left.targetSource == right.targetSource &&
                abs(left.targetMeters - right.targetMeters) <= 1e-6
            SegmentValue(
                fromIndex = index,
                toIndex = index + 1,
                distanceMeters = right.distanceMeters - left.distanceMeters,
                goalTransition = !sameTarget,
                etaSeconds = if (sameTarget) left.remainingSeconds - right.remainingSeconds else null,
            )
        }
        return Measurement(points, segments)
    }

    private fun assertGoldenMatches(actual: Measurement, golden: Golden) {
        val mismatches = mutableListOf<String>()
        if (actual.points.size != golden.pointCount || golden.points.size != golden.pointCount) {
            mismatches += "point count expected=" + golden.pointCount + " actual=" + actual.points.size
        }
        if (actual.segments.size != golden.segmentCount || golden.segments.size != golden.segmentCount) {
            mismatches += "segment count expected=" + golden.segmentCount + " actual=" + actual.segments.size
        }

        actual.points.zip(golden.points).forEach { (actualPoint, expectedPoint) ->
            if (actualPoint.index != expectedPoint.index) {
                mismatches += "point index expected=" + expectedPoint.index + " actual=" + actualPoint.index
            }
            if (actualPoint.targetKind != expectedPoint.targetKind || actualPoint.targetSource != expectedPoint.targetSource) {
                mismatches += "point " + actualPoint.index + " target expected=" +
                    expectedPoint.targetKind + "/" + expectedPoint.targetSource + " actual=" +
                    actualPoint.targetKind + "/" + actualPoint.targetSource
            }
            compareNumber(
                mismatches,
                "point " + actualPoint.index + " distance_m",
                expectedPoint.distanceMeters,
                actualPoint.distanceMeters,
                golden.relativeTolerance,
            )
            compareNumber(
                mismatches,
                "point " + actualPoint.index + " target_m",
                expectedPoint.targetMeters,
                actualPoint.targetMeters,
                golden.relativeTolerance,
            )
            compareNumber(
                mismatches,
                "point " + actualPoint.index + " remainingSeconds",
                expectedPoint.remainingSeconds,
                actualPoint.remainingSeconds,
                golden.relativeTolerance,
            )
        }

        actual.segments.zip(golden.segments).forEach { (actualSegment, expectedSegment) ->
            val label = "segment " + actualSegment.fromIndex + "->" + actualSegment.toIndex
            if (actualSegment.fromIndex != expectedSegment.fromIndex ||
                actualSegment.toIndex != expectedSegment.toIndex
            ) {
                mismatches += label + " index mismatch"
            }
            if (actualSegment.goalTransition != expectedSegment.goalTransition) {
                mismatches += label + " goalTransition expected=" + expectedSegment.goalTransition +
                    " actual=" + actualSegment.goalTransition
            }
            compareNumber(
                mismatches,
                label + " distance_m",
                expectedSegment.distanceMeters,
                actualSegment.distanceMeters,
                golden.relativeTolerance,
            )
            if (expectedSegment.etaSeconds == null || actualSegment.etaSeconds == null) {
                if (expectedSegment.etaSeconds != actualSegment.etaSeconds) {
                    mismatches += label + " ETA goal-transition state expected=" +
                        expectedSegment.etaSeconds + " actual=" + actualSegment.etaSeconds
                }
            } else {
                compareNumber(
                    mismatches,
                    label + " ETA",
                    expectedSegment.etaSeconds,
                    actualSegment.etaSeconds,
                    golden.relativeTolerance,
                )
            }
        }
        check(mismatches.isEmpty()) {
            "segment ETA golden mismatch:\n" + mismatches.joinToString("\n")
        }
    }

    private fun compareNumber(
        mismatches: MutableList<String>,
        label: String,
        expected: Double,
        actual: Double,
        tolerance: Double,
    ) {
        val within = if (expected == 0.0) {
            actual == 0.0
        } else {
            abs(actual - expected) / abs(expected) <= tolerance
        }
        if (!within) {
            mismatches += label + " expected=" + expected + " actual=" + actual +
                " delta=" + (actual - expected) +
                " relativeError=" + (if (expected == 0.0) "undefined" else abs(actual - expected) / abs(expected))
        }
    }

    private fun differingSegments(measurement: Measurement, golden: Golden): List<Int> {
        val result = mutableListOf<Int>()
        measurement.segments.zip(golden.segments).forEach { (actual, expected) ->
            val actualEta = actual.etaSeconds
            val expectedEta = expected.etaSeconds
            if (actual.goalTransition != expected.goalTransition ||
                (actualEta == null) != (expectedEta == null) ||
                (actualEta != null && expectedEta != null &&
                    (expectedEta == 0.0 || abs(actualEta - expectedEta) / abs(expectedEta) > golden.relativeTolerance))
            ) {
                result += actual.fromIndex
            }
        }
        return result
    }

    private fun assertPhysicalBounds(actual: Measurement, config: GuideConfig) {
        val maximumSpeed = (0..20_000).maxOf { sample ->
            toblerSpeedMetersPerSecond(-1.0 + sample * 1e-4)
        }
        for (point in actual.points) {
            val minimumSeconds = point.remainingMeters / maximumSpeed
            val maximumSeconds = point.remainingMeters / config.etaMinSegmentSpeedMps
            assertTrue(
                point.remainingSeconds + 1e-6 >= minimumSeconds &&
                    point.remainingSeconds <= maximumSeconds + 1e-6,
                "point " + point.index + " violates ETA physical bounds: ETA=" + point.remainingSeconds +
                    " range=[" + minimumSeconds + "," + maximumSeconds + "] vMax=" + maximumSpeed,
            )
        }
        for (segment in actual.segments.filterNot { it.goalTransition }) {
            val seconds = assertNotNull(segment.etaSeconds, "same-target segment ETA missing")
            val minimumSeconds = segment.distanceMeters / maximumSpeed
            val maximumSeconds = segment.distanceMeters / config.etaMinSegmentSpeedMps
            assertTrue(
                seconds + 1e-6 >= minimumSeconds && seconds <= maximumSeconds + 1e-6,
                "segment " + segment.fromIndex + "->" + segment.toIndex +
                    " violates ETA physical bounds: ETA=" + seconds +
                    " range=[" + minimumSeconds + "," + maximumSeconds + "] vMax=" + maximumSpeed,
            )
        }
    }

    private fun mutateElevation(xml: String, pointIndex: Int, deltaMeters: Double): String {
        val elevations = Regex("<ele>([^<]+)</ele>").findAll(xml).toList()
        require(pointIndex in elevations.indices) { "mutation point index is outside the GPX" }
        val match = elevations[pointIndex]
        val range = requireNotNull(match.groups[1]).range
        return xml.replaceRange(range, (match.groupValues[1].toDouble() + deltaMeters).toString())
    }
}
