package com.trailnav.core

import kotlin.math.sqrt

/** Result of fitting the collected GPS trajectory to the imported route. */
internal data class StartTrajectoryMatch(
    val direction: ProgressDirection,
    val startProgressMeters: Double,
    val currentProgressMeters: Double,
    val rootMeanSquareErrorMeters: Double,
    val minimumRootMeanSquareErrorMeters: Double,
    val netDisplacementMeters: Double,
)

/** Fits the whole startup trajectory against every route segment in both directions. */
internal object StartTrajectoryMatcher {
    fun match(
        route: RouteModel,
        samples: List<StartTrajectorySample>,
        config: GuideConfig,
    ): StartTrajectoryMatch? {
        if (route.points.size < 2 || samples.size < 2) return null
        val walkedOrigin = samples.first().walkedMeters
        val walkedTotal = samples.last().walkedMeters - walkedOrigin
        if (walkedTotal < config.startTrajectoryMeters) return null

        val candidates = startCandidates(route, samples.first().point, config)
        if (candidates.isEmpty()) return null
        val closed = isClosed(route, config)
        val rawFits = buildList {
            for (start in candidates) {
                for (direction in listOf(ProgressDirection.FORWARD, ProgressDirection.REVERSE)) {
                    var squaredError = 0.0
                    var invalid = false
                    for (sample in samples) {
                        val walked = sample.walkedMeters - walkedOrigin
                        val predictedProgress = progressAt(
                            route,
                            start,
                            if (direction == ProgressDirection.FORWARD) walked else -walked,
                            closed,
                        )
                        if (predictedProgress == null) {
                            invalid = true
                            break
                        }
                        val predictedPoint = pointAt(route, predictedProgress)
                        val observedPoint = route.toEnu(sample.point)
                        val residual = RouteMath.distance(observedPoint, predictedPoint)
                        squaredError += residual * residual
                    }
                    if (!invalid) {
                        val currentProgress = progressAt(
                            route,
                            start,
                            if (direction == ProgressDirection.FORWARD) walkedTotal else -walkedTotal,
                            closed,
                        ) ?: continue
                        add(
                            StartTrajectoryMatch(
                                direction = direction,
                                startProgressMeters = start,
                                currentProgressMeters = currentProgress,
                                rootMeanSquareErrorMeters = sqrt(squaredError / samples.size),
                                minimumRootMeanSquareErrorMeters = Double.NaN,
                                netDisplacementMeters = if (direction == ProgressDirection.FORWARD) walkedTotal else -walkedTotal,
                            ),
                        )
                    }
                }
            }
        }
        val bestError = rawFits.minOfOrNull { it.rootMeanSquareErrorMeters } ?: return null
        val fits = rawFits.map { it.copy(minimumRootMeanSquareErrorMeters = bestError) }
        val floatingPointAllowance = Math.ulp(maxOf(1.0, bestError)) * 8.0
        return fits.asSequence()
            .filter { it.rootMeanSquareErrorMeters - bestError <= config.startTrajectoryMatchToleranceMeters + floatingPointAllowance }
            .minWithOrNull(
                compareBy<StartTrajectoryMatch> { directionTieOrder(it.direction) }
                    .thenBy { it.startProgressMeters }
                    .thenBy { it.rootMeanSquareErrorMeters },
            )
            ?.takeIf { it.rootMeanSquareErrorMeters.isFinite() }
    }

    private fun startCandidates(route: RouteModel, firstPoint: GeoPoint, config: GuideConfig): List<Double> {
        val point = route.toEnu(firstPoint)
        val projections = (0 until route.points.lastIndex).mapNotNull { index ->
            val projection = RouteMath.segmentProjection(point, route.points[index], route.points[index + 1])
            val startMeters = route.cumulativeMeters[index]
            val span = route.cumulativeMeters[index + 1] - startMeters
            (startMeters + span * projection.fraction) to projection.distanceMeters
        }
        val minimumDistance = projections.minOfOrNull { it.second } ?: return emptyList()
        return projections
            .asSequence()
            .filter { (_, distance) -> distance <= minimumDistance + config.offRouteExitDistMeters }
            .map { it.first }
            .sorted()
            .fold(mutableListOf()) { unique, progress ->
                if (unique.isEmpty() || progress - unique.last() >= config.minimumPointSpacingMeters) {
                    unique += progress
                }
                unique
            }
    }

    private fun progressAt(route: RouteModel, start: Double, delta: Double, closed: Boolean): Double? {
        val total = route.totalLengthMeters
        if (total <= 0.0) return null
        val progress = start + delta
        if (closed) {
            val wrapped = ((progress % total) + total) % total
            return wrapped
        }
        return progress.takeIf { it in 0.0..total }
    }

    fun rootMeanSquareError(
        route: RouteModel,
        samples: List<StartTrajectorySample>,
        startProgressMeters: Double,
        direction: ProgressDirection,
        config: GuideConfig,
    ): Double? {
        if (route.points.size < 2 || samples.size < 2) return null
        val walkedOrigin = samples.first().walkedMeters
        val closed = isClosed(route, config)
        var squaredError = 0.0
        for (sample in samples) {
            val walked = sample.walkedMeters - walkedOrigin
            val predictedProgress = progressAt(
                route,
                startProgressMeters,
                if (direction == ProgressDirection.FORWARD) walked else -walked,
                closed,
            ) ?: return null
            val predictedPoint = pointAt(route, predictedProgress)
            val observedPoint = route.toEnu(sample.point)
            val residual = RouteMath.distance(observedPoint, predictedPoint)
            squaredError += residual * residual
        }
        return sqrt(squaredError / samples.size)
    }

    private fun pointAt(route: RouteModel, progressMeters: Double): EnuPoint {
        val cumulative = route.cumulativeMeters
        val found = cumulative.binarySearch(progressMeters)
        val index = if (found >= 0) found.coerceAtMost(cumulative.lastIndex - 1)
        else (-found - 2).coerceIn(0, cumulative.lastIndex - 1)
        val span = cumulative[index + 1] - cumulative[index]
        val fraction = if (span <= 0.0) 0.0 else (progressMeters - cumulative[index]) / span
        val start = route.points[index]
        val end = route.points[index + 1]
        return EnuPoint(
            east = start.east + (end.east - start.east) * fraction,
            north = start.north + (end.north - start.north) * fraction,
        )
    }

    private fun isClosed(route: RouteModel, config: GuideConfig): Boolean =
        route.points.size >= 2 &&
            RouteMath.distance(route.points.first(), route.points.last()) <= config.minimumPointSpacingMeters

    private fun directionTieOrder(direction: ProgressDirection): Int =
        if (direction == ProgressDirection.FORWARD) 0 else 1
}
