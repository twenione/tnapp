package com.trailnav.core

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.xml.sax.InputSource

/** A latitude/longitude pair from a GPX document. */
data class GeoPoint(val lat: Double, val lon: Double)

/** A local east/north point in metres, relative to the route origin. */
data class EnuPoint(val east: Double, val north: Double)

data class RouteWarning(
    val kind: String,
    val segmentIndex: Int,
    val distanceMeters: Double
)

data class TurnPoint(
    val s: Double,
    val side: Side,
    val angleDegrees: Double
)

/** Why the route elevation profile is or is not available to later event logic. */
data class ElevationUse(
    val used: Boolean,
    val reason: String,
) {
    init {
        require(reason in setOf("absent", "partial", "unstable", "ok"))
    }
}

/** A waypoint that is close enough to the route to be eligible for E6. */
data class Waypoint(
    val name: String,
    val point: GeoPoint,
    val s: Double,
    val distanceMeters: Double,
) {
    val projectedMeters: Double
        get() = s

    val distanceToRouteMeters: Double
        get() = distanceMeters
}

enum class SlopeKind { ASCENT, DESCENT }

/** A deterministic, precomputed E4 elevation segment. */
data class SlopeSegment(
    val startS: Double,
    val endS: Double,
    val deltaMeters: Double,
    val kind: SlopeKind,
) {
    val startMeters: Double
        get() = startS

    val endMeters: Double
        get() = endS

    val elevationDeltaMeters: Double
        get() = deltaMeters
}

/**
 * A read-only, preprocessed route.  [points] and [cumulativeMeters] are the
 * source route; [simplifiedPoints] is a separate Douglas-Peucker copy.
 */
data class RouteModel(
    val sourcePoints: List<GeoPoint>,
    val points: List<EnuPoint>,
    val cumulativeMeters: List<Double>,
    val simplifiedPoints: List<EnuPoint>,
    val simplifiedCumulativeMeters: List<Double>,
    val turns: List<TurnPoint>,
    val spatialIndex: SpatialIndex,
    val warnings: List<RouteWarning>,
    /** Elevation values paired with [points]; null is retained for a missing value. */
    val elevationMeters: List<Double?> = emptyList(),
    /** The deterministic profile used by both E4 slope extraction and E5 slots. */
    val smoothedElevationMeters: List<Double> = emptyList(),
    val elevationUse: ElevationUse = ElevationUse(false, "absent"),
    val slopeSegments: List<SlopeSegment> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
) {
    val totalLengthMeters: Double
        get() = cumulativeMeters.lastOrNull() ?: 0.0

    val isEmpty: Boolean
        get() = points.isEmpty()

    /** Convenience manifest fields; the structured value remains [elevationUse]. */
    val elevationUsed: Boolean
        get() = elevationUse.used

    val elevationReason: String
        get() = elevationUse.reason

    /** Alias used by consumers that call the paired profile an elevation profile. */
    val elevationProfile: List<Double?>
        get() = elevationMeters

    val elevationSamples: List<Double?>
        get() = elevationMeters

    /** Smoothed samples used for deterministic slope and slot calculations. */
    val smoothedElevationProfile: List<Double>
        get() = smoothedElevationMeters

    val e4Segments: List<SlopeSegment>
        get() = slopeSegments

    fun toEnu(point: GeoPoint): EnuPoint =
        if (sourcePoints.isEmpty()) EnuPoint(0.0, 0.0)
        else RouteMath.toEnu(sourcePoints.first(), point)

    companion object {
        fun empty(): RouteModel = RouteModel(
            sourcePoints = emptyList(),
            points = emptyList(),
            cumulativeMeters = emptyList(),
            simplifiedPoints = emptyList(),
            simplifiedCumulativeMeters = emptyList(),
            turns = emptyList(),
            spatialIndex = SpatialIndex.empty(),
            warnings = emptyList(),
            elevationMeters = emptyList(),
            smoothedElevationMeters = emptyList(),
            elevationUse = ElevationUse(false, "absent"),
            slopeSegments = emptyList(),
            waypoints = emptyList(),
        )

        fun fromGpx(xml: String, config: GuideConfig = GuideConfig()): RouteModel =
            GpxRouteParser.parse(xml, config)
    }
}

/** Grid index over route segments.  It stores segment indices, not mutable route state. */
class SpatialIndex private constructor(
    private val cellSizeMeters: Double,
    private val cells: Map<GridCell, List<Int>>
) {
    data class GridCell(val east: Int, val north: Int)

    fun candidateSegments(point: EnuPoint, radiusMeters: Double): Set<Int> {
        if (cells.isEmpty()) return emptySet()
        val radius = max(0.0, radiusMeters)
        val minEast = floor((point.east - radius) / cellSizeMeters).toInt()
        val maxEast = floor((point.east + radius) / cellSizeMeters).toInt()
        val minNorth = floor((point.north - radius) / cellSizeMeters).toInt()
        val maxNorth = floor((point.north + radius) / cellSizeMeters).toInt()
        val result = linkedSetOf<Int>()
        for (east in minEast..maxEast) {
            for (north in minNorth..maxNorth) {
                result.addAll(cells[GridCell(east, north)].orEmpty())
            }
        }
        return result
    }

    companion object {
        fun empty(): SpatialIndex = SpatialIndex(100.0, emptyMap())

        fun build(points: List<EnuPoint>, cellSizeMeters: Double): SpatialIndex {
            if (points.size < 2) return SpatialIndex(cellSizeMeters, emptyMap())
            val mutable = linkedMapOf<GridCell, MutableList<Int>>()
            points.zipWithNext().forEachIndexed { index, pair ->
                val start = pair.first
                val end = pair.second
                val minEast = floor(min(start.east, end.east) / cellSizeMeters).toInt()
                val maxEast = floor(max(start.east, end.east) / cellSizeMeters).toInt()
                val minNorth = floor(min(start.north, end.north) / cellSizeMeters).toInt()
                val maxNorth = floor(max(start.north, end.north) / cellSizeMeters).toInt()
                for (east in minEast..maxEast) {
                    for (north in minNorth..maxNorth) {
                        mutable.getOrPut(GridCell(east, north)) { mutableListOf() }.add(index)
                    }
                }
            }
            return SpatialIndex(cellSizeMeters, mutable.mapValues { it.value.toList() })
        }
    }
}

internal object RouteMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun toEnu(origin: GeoPoint, point: GeoPoint): EnuPoint {
        val lat0 = Math.toRadians(origin.lat)
        val dLat = Math.toRadians(point.lat - origin.lat)
        val dLon = Math.toRadians(point.lon - origin.lon)
        return EnuPoint(
            east = dLon * cos(lat0) * EARTH_RADIUS_METERS,
            north = dLat * EARTH_RADIUS_METERS
        )
    }

    fun distance(a: EnuPoint, b: EnuPoint): Double {
        val east = b.east - a.east
        val north = b.north - a.north
        return sqrt(east * east + north * north)
    }

    fun segmentProjection(point: EnuPoint, start: EnuPoint, end: EnuPoint): SegmentProjection {
        val east = end.east - start.east
        val north = end.north - start.north
        val lengthSquared = east * east + north * north
        val t = if (lengthSquared <= 0.0) 0.0 else {
            ((point.east - start.east) * east + (point.north - start.north) * north) / lengthSquared
        }.coerceIn(0.0, 1.0)
        val projected = EnuPoint(start.east + east * t, start.north + north * t)
        return SegmentProjection(projected, t, distance(point, projected))
    }

    fun headingDegrees(start: EnuPoint, end: EnuPoint): Double {
        val east = end.east - start.east
        val north = end.north - start.north
        return normalizeBearing(Math.toDegrees(atan2(east, north)))
    }

    fun normalizeBearing(value: Double): Double {
        var result = value % 360.0
        if (result < 0.0) result += 360.0
        return result
    }

    fun signedAngle(delta: Double): Double {
        var result = (delta + 180.0) % 360.0
        if (result < 0.0) result += 360.0
        return result - 180.0
    }

    fun perpendicularDistance(point: EnuPoint, start: EnuPoint, end: EnuPoint): Double =
        segmentProjection(point, start, end).distanceMeters
}

internal data class SegmentProjection(
    val point: EnuPoint,
    val fraction: Double,
    val distanceMeters: Double
)

internal object GpxRouteParser {
    private const val NAVER_MAP_EXTENSION_NAMESPACE = "https://map.naver.com/gpx/1"

    fun parse(xml: String, config: GuideConfig): RouteModel {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val parsedPoints = mutableListOf<ParsedRoutePoint>()
        val elements = document.getElementsByTagName("trkpt")
        val routeElements = if (elements.length > 0) elements else document.getElementsByTagName("rtept")
        for (index in 0 until routeElements.length) {
            val node = routeElements.item(index)
            val lat = node.attributes?.getNamedItem("lat")?.nodeValue?.toDoubleOrNull()
            val lon = node.attributes?.getNamedItem("lon")?.nodeValue?.toDoubleOrNull()
            if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                parsedPoints.add(ParsedRoutePoint(GeoPoint(lat, lon), childDouble(node, "ele")))
            }
        }
        require(parsedPoints.isNotEmpty()) { "GPX contains no valid trkpt or rtept" }
        val waypoints = mutableListOf<ParsedWaypoint>()
        fun appendWaypoints(nodes: org.w3c.dom.NodeList) {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val lat = node.attributes?.getNamedItem("lat")?.nodeValue?.toDoubleOrNull()
                val lon = node.attributes?.getNamedItem("lon")?.nodeValue?.toDoubleOrNull()
                val name = sanitizeName(childText(node, "name"), config.waypointNameMaxLength)
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && name != null) {
                    waypoints.add(ParsedWaypoint(GeoPoint(lat, lon), name))
                }
            }
        }
        appendWaypoints(document.getElementsByTagName("wpt"))
        appendWaypoints(document.getElementsByTagNameNS(NAVER_MAP_EXTENSION_NAMESPACE, "waypoint"))
        return preprocess(parsedPoints, waypoints, config)
    }

    private fun preprocess(
        source: List<ParsedRoutePoint>,
        rawWaypoints: List<ParsedWaypoint>,
        config: GuideConfig,
    ): RouteModel {
        val filtered = mutableListOf<ParsedRoutePoint>()
        source.forEach { item ->
            if (filtered.isEmpty() || RouteMath.distance(
                    RouteMath.toEnu(filtered.last().point, item.point),
                    EnuPoint(0.0, 0.0),
                ) >= config.minimumPointSpacingMeters
            ) {
                filtered.add(item)
            }
        }
        val usable = if (filtered.size == 1) filtered + filtered else filtered
        val sourcePoints = usable.map { it.point }
        val origin = sourcePoints.first()
        val projected = sourcePoints.map { RouteMath.toEnu(origin, it) }
        val cumulative = cumulative(projected)
        val warnings = cumulative.zipWithNext().mapIndexedNotNull { index, pair ->
            val gap = pair.second - pair.first
            if (gap > config.maximumPointGapMeters) RouteWarning("point-gap", index, gap) else null
        }
        val simplifiedIndices = douglasPeuckerIndices(projected, config.douglasPeuckerEpsilonMeters)
        val simplified = simplifiedIndices.map { projected[it] }
        val simplifiedCumulative = cumulative(simplified)
        val turns = extractTurns(
            simplified,
            simplifiedCumulative,
            simplifiedIndices,
            cumulative,
            config,
        )
        val elevations = usable.map { it.elevationMeters }
        val elevation = classifyElevation(elevations, cumulative, config)
        val slopes = if (elevation.used) extractSlopeSegments(cumulative, elevation.smoothed, config) else emptyList()
        val eligibleWaypoints = rawWaypoints.mapNotNull { waypoint ->
            val waypointEnu = RouteMath.toEnu(origin, waypoint.point)
            val projection = projectToRoute(waypointEnu, projected, cumulative)
            if (projection.distanceMeters <= config.waypointNearRouteMeters) {
                Waypoint(waypoint.name, waypoint.point, projection.s, projection.distanceMeters)
            } else null
        }
        return RouteModel(
            sourcePoints = sourcePoints,
            points = projected,
            cumulativeMeters = cumulative,
            simplifiedPoints = simplified,
            simplifiedCumulativeMeters = simplifiedCumulative,
            turns = turns,
            spatialIndex = SpatialIndex.build(projected, config.spatialGridSizeMeters),
            warnings = warnings,
            elevationMeters = elevations,
            smoothedElevationMeters = elevation.smoothed,
            elevationUse = ElevationUse(elevation.used, elevation.reason),
            slopeSegments = slopes,
            waypoints = eligibleWaypoints,
        )
    }

    private data class ParsedRoutePoint(val point: GeoPoint, val elevationMeters: Double?)
    private data class ParsedWaypoint(val point: GeoPoint, val name: String)
    private data class ElevationClassification(
        val used: Boolean,
        val reason: String,
        val smoothed: List<Double>,
    )
    private data class RouteProjection(val s: Double, val distanceMeters: Double)

    private fun childText(node: org.w3c.dom.Node, tag: String): String? {
        val children = node.childNodes ?: return null
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeName.substringAfterLast(':') == tag) return child.textContent
        }
        return null
    }

    private fun childDouble(node: org.w3c.dom.Node, tag: String): Double? =
        childText(node, tag)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun sanitizeName(raw: String?, maxLength: Int): String? {
        val normalized = raw
            ?.replace(Regex("[\\p{Cntrl}]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(maxLength)
            ?.trim()
        return normalized?.takeIf { it.isNotEmpty() }
    }

    private fun classifyElevation(
        values: List<Double?>,
        cumulative: List<Double>,
        config: GuideConfig,
    ): ElevationClassification {
        if (values.isEmpty() || values.none { it != null }) {
            return ElevationClassification(false, "absent", emptyList())
        }
        if (values.any { it == null }) {
            return ElevationClassification(false, "partial", emptyList())
        }
        val raw = values.map { requireNotNull(it) }
        val median = raw.indices.map { index ->
            val from = max(0, index - 2)
            val to = min(raw.lastIndex, index + 2)
            raw.subList(from, to + 1).sorted()[((to - from) / 2)]
        }
        val smoothed = median.indices.map { index ->
            // Preserve profile endpoints so a 200 m ramp keeps its measured
            // total delta; only interior samples receive the moving average.
            if (index == 0 || index == median.lastIndex) raw[index]
            else {
                val from = max(0, index - 1)
                val to = min(median.lastIndex, index + 1)
                median.subList(from, to + 1).average()
            }
        }
        val residual = raw.indices.maxOfOrNull { index -> abs(raw[index] - median[index]) } ?: 0.0
        if (residual > config.elevationSpikeThresholdMeters) {
            return ElevationClassification(false, "unstable", smoothed)
        }
        return ElevationClassification(true, "ok", smoothed)
    }

    private fun extractSlopeSegments(
        cumulative: List<Double>,
        smoothed: List<Double>,
        config: GuideConfig,
    ): List<SlopeSegment> {
        if (smoothed.size < 2) return emptyList()
        data class Active(var start: Int, var end: Int, var sign: Int)
        val result = mutableListOf<SlopeSegment>()
        var active: Active? = null
        fun flush() {
            val item = active ?: return
            val delta = smoothed[item.end] - smoothed[item.start]
            if (abs(delta) >= config.slopeMinDeltaMeters) {
                result.add(
                    SlopeSegment(
                        startS = cumulative[item.start],
                        endS = cumulative[item.end],
                        deltaMeters = delta,
                        kind = if (delta > 0.0) SlopeKind.ASCENT else SlopeKind.DESCENT,
                    )
                )
            }
            active = null
        }
        for (index in 1 until smoothed.size) {
            val step = smoothed[index] - smoothed[index - 1]
            val sign = when {
                step > 0.0 -> 1
                step < 0.0 -> -1
                else -> 0
            }
            val current = active
            if (sign == 0) {
                if (current != null && abs(smoothed[index] - smoothed[current.start]) <= config.slopeHysteresisMeters) flush()
                continue
            }
            if (current == null) {
                active = Active(index - 1, index, sign)
            } else if (current.sign == sign &&
                cumulative[index] - cumulative[current.start] <= config.slopeLookaheadMeters + config.minimumPointSpacingMeters
            ) {
                current.end = index
            } else {
                flush()
                active = Active(index - 1, index, sign)
            }
        }
        flush()
        return result
    }

    private fun projectToRoute(point: EnuPoint, route: List<EnuPoint>, cumulative: List<Double>): RouteProjection {
        if (route.isEmpty()) return RouteProjection(0.0, Double.POSITIVE_INFINITY)
        if (route.size == 1) return RouteProjection(0.0, RouteMath.distance(point, route.first()))
        var best = RouteProjection(0.0, Double.POSITIVE_INFINITY)
        route.zipWithNext().forEachIndexed { index, pair ->
            val projection = RouteMath.segmentProjection(point, pair.first, pair.second)
            if (projection.distanceMeters < best.distanceMeters) {
                best = RouteProjection(
                    s = cumulative[index] + (cumulative[index + 1] - cumulative[index]) * projection.fraction,
                    distanceMeters = projection.distanceMeters,
                )
            }
        }
        return best
    }

    private fun cumulative(points: List<EnuPoint>): List<Double> {
        if (points.isEmpty()) return emptyList()
        val result = MutableList(points.size) { 0.0 }
        for (index in 1 until points.size) {
            result[index] = result[index - 1] + RouteMath.distance(points[index - 1], points[index])
        }
        return result
    }

    private fun douglasPeuckerIndices(points: List<EnuPoint>, epsilon: Double): List<Int> {
        if (points.size <= 2) return points.indices.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        fun simplify(first: Int, last: Int) {
            if (last <= first + 1) return
            var farthest = -1
            var maximum = epsilon
            for (index in first + 1 until last) {
                val distance = RouteMath.perpendicularDistance(points[index], points[first], points[last])
                if (distance > maximum) {
                    maximum = distance
                    farthest = index
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                simplify(first, farthest)
                simplify(farthest, last)
            }
        }
        simplify(0, points.lastIndex)
        val result = mutableListOf<Int>()
        for (index in keep.indices) if (keep[index]) result.add(index)
        return result
    }

    private fun extractTurns(
        points: List<EnuPoint>,
        cumulative: List<Double>,
        originalPointIndices: List<Int>,
        originalCumulative: List<Double>,
        config: GuideConfig
    ): List<TurnPoint> {
        if (points.size < 3) return emptyList()
        val total = cumulative.lastOrNull() ?: 0.0
        val result = mutableListOf<TurnPoint>()
        for (index in 1 until points.lastIndex) {
            val at = cumulative[index]
            if (at < config.turnLookbackMeters || total - at < config.turnLookaheadMeters) continue
            val before = pointAtDistance(points, cumulative, at - config.turnLookbackMeters)
            val after = pointAtDistance(points, cumulative, at + config.turnLookaheadMeters)
            val incoming = RouteMath.headingDegrees(before, points[index])
            val outgoing = RouteMath.headingDegrees(points[index], after)
            val signed = RouteMath.signedAngle(outgoing - incoming)
            if (abs(signed) >= config.turnAngleThresholdDegrees) {
                val positionOnOriginalAxis = originalCumulative[originalPointIndices[index]]
                result.add(
                    TurnPoint(
                        positionOnOriginalAxis,
                        if (signed > 0.0) Side.RIGHT else Side.LEFT,
                        abs(signed),
                    ),
                )
            }
        }
        return result
    }

    private fun pointAtDistance(points: List<EnuPoint>, cumulative: List<Double>, distance: Double): EnuPoint {
        if (distance <= 0.0) return points.first()
        val total = cumulative.last()
        if (distance >= total) return points.last()
        val index = cumulative.binarySearch(distance).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.lastIndex - 1)
        val span = cumulative[index + 1] - cumulative[index]
        val fraction = if (span <= 0.0) 0.0 else (distance - cumulative[index]) / span
        return EnuPoint(
            points[index].east + (points[index + 1].east - points[index].east) * fraction,
            points[index].north + (points[index + 1].north - points[index].north) * fraction
        )
    }
}
