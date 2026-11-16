package com.trailnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StartTrajectoryMatcherTest {
    @Test
    fun matcherDoesNotResolveBeforeConfiguredTrajectoryDistanceIsObserved() {
        val config = GuideConfig()
        val route = RouteModel.fromGpx(routeXml(listOf(0.0 to 0.0, 1_000.0 to 0.0)), config)
        val shortTrack = listOf(
            StartTrajectorySample(
                point = pointAt(route, 400.0),
                timestampMillis = 0L,
                walkedMeters = 0.0,
                accuracyMeters = 1.0,
            ),
            StartTrajectorySample(
                point = pointAt(route, 405.0),
                timestampMillis = 1_000L,
                walkedMeters = 5.0,
                accuracyMeters = 1.0,
            ),
        )

        assertTrue(shortTrack.last().walkedMeters - shortTrack.first().walkedMeters < config.startTrajectoryMeters)
        assertEquals(null, StartTrajectoryMatcher.match(route, shortTrack, config))
    }

    @Test
    fun exactOverlapUsesPositiveFitWidthBeforeApplyingForwardTieBreak() {
        val config = GuideConfig()
        val route = RouteModel.fromGpx(
            routeXml(
                listOf(
                    0.0 to 0.0,
                    0.0 to 203.17,
                    211.39 to 203.17,
                    251.66 to 317.43,
                    -37.2 to 399.83,
                    0.0 to 203.17,
                    0.0 to 0.0,
                ),
            ),
            config,
        )
        val outboundProgress = 150.789
        val reverseStemProgress = route.totalLengthMeters - outboundProgress
        val samples = (0..30).map { step ->
            StartTrajectorySample(
                point = pointAt(route, reverseStemProgress + step),
                timestampMillis = step * 1_000L,
                walkedMeters = step.toDouble(),
                accuracyMeters = 0.0,
            )
        }
        val selected = assertNotNull(StartTrajectoryMatcher.match(route, samples, config))
        val forwardError = assertNotNull(
            StartTrajectoryMatcher.rootMeanSquareError(route, samples, reverseStemProgress, ProgressDirection.FORWARD, config),
        )
        val pairedReverseError = assertNotNull(
            StartTrajectoryMatcher.rootMeanSquareError(route, samples, outboundProgress, ProgressDirection.REVERSE, config),
        )
        val fitGap = abs(forwardError - pairedReverseError)
        assertEquals(ProgressDirection.FORWARD, selected.direction)
        assertTrue(selected.startProgressMeters > route.totalLengthMeters / 2.0)
        assertTrue(config.startTrajectoryMatchToleranceMeters > 0.0)
        assertTrue(fitGap > 0.0, "the exact-overlap input must expose floating-point fit ordering")
        assertTrue(fitGap <= config.startTrajectoryMatchToleranceMeters)
        val zeroWidth = StartTrajectoryMatcher.match(
            route,
            samples,
            config.copy(startTrajectoryMatchToleranceMeters = 0.0),
        )
        assertTrue(
            zeroWidth?.direction != ProgressDirection.FORWARD || zeroWidth.startProgressMeters <= route.totalLengthMeters / 2.0,
            "M-10 negative control survived: tolerance=0 still selected the forward paired interpretation",
        )
        println("START-TRAJECTORY-TIE p=$outboundProgress forwardError=$forwardError pairedReverseError=$pairedReverseError fitGap=$fitGap selected=${selected.direction}@${selected.startProgressMeters} width=${config.startTrajectoryMatchToleranceMeters} zeroWidth=$zeroWidth")
    }

    private fun pointAt(route: RouteModel, distance: Double): GeoPoint {
        val progress = distance.coerceIn(0.0, route.totalLengthMeters)
        val segment = route.cumulativeMeters.indexOfLast { it <= progress }
            .coerceIn(0, route.cumulativeMeters.lastIndex - 1)
        val span = route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment]
        val fraction = if (span <= 0.0) 0.0 else (progress - route.cumulativeMeters[segment]) / span
        val start = route.points[segment]
        val end = route.points[segment + 1]
        val east = start.east + (end.east - start.east) * fraction
        val north = start.north + (end.north - start.north) * fraction
        val origin = route.sourcePoints.first()
        val degrees = 180.0 / PI
        val originLatitude = Math.toRadians(origin.lat)
        return GeoPoint(
            origin.lat + north / 6_371_008.8 * degrees,
            origin.lon + east / (6_371_008.8 * kotlin.math.cos(originLatitude)) * degrees,
        )
    }

    private fun routeXml(points: List<Pair<Double, Double>>): String = buildString {
        append("<gpx><trk><trkseg>")
        points.forEach { (east, north) ->
            val latitude = 10.0 + north / 6_371_008.8 * 180.0 / PI
            val longitude = 20.0 + east / (6_371_008.8 * kotlin.math.cos(Math.toRadians(10.0))) * 180.0 / PI
            append("<trkpt lat=\"").append(latitude).append("\" lon=\"").append(longitude).append("\"/>")
        }
        append("</trkseg></trk></gpx>")
    }
}
