package com.trailnav.app

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.xml.sax.InputSource

/** Result of choosing the route direction for the current navigation session. */
data class RouteOrientationResult(
    val gpxXml: String,
    val reversed: Boolean,
    val firstEndpointDistanceMeters: Double?,
    val lastEndpointDistanceMeters: Double?,
    val reason: String,
)

/**
 * App-layer GPX preprocessor.  The core engine continues to receive a route
 * whose first point is the session start and whose last point is the target.
 */
object RouteOrientation {
    /** Do not flip when the endpoint advantage is within normal GPS noise. */
    const val DISTANCE_TIE_TOLERANCE_METERS = 5.0

    fun orient(gpxXml: String, currentLocation: TrailLocation?): RouteOrientationResult {
        if (currentLocation == null) {
            return RouteOrientationResult(
                gpxXml = gpxXml,
                reversed = false,
                firstEndpointDistanceMeters = null,
                lastEndpointDistanceMeters = null,
                reason = "last-location-unavailable",
            )
        }
        val points = parsePoints(gpxXml)
        if (points.size < 2) {
            return RouteOrientationResult(
                gpxXml = gpxXml,
                reversed = false,
                firstEndpointDistanceMeters = null,
                lastEndpointDistanceMeters = null,
                reason = "endpoints-unavailable",
            )
        }
        val firstDistance = distanceMeters(currentLocation.latitude, currentLocation.longitude, points.first())
        val lastDistance = distanceMeters(currentLocation.latitude, currentLocation.longitude, points.last())
        return when {
            lastDistance + DISTANCE_TIE_TOLERANCE_METERS < firstDistance -> RouteOrientationResult(
                gpxXml = buildReversedGpx(points),
                reversed = true,
                firstEndpointDistanceMeters = firstDistance,
                lastEndpointDistanceMeters = lastDistance,
                reason = "last-endpoint-closer",
            )
            firstDistance + DISTANCE_TIE_TOLERANCE_METERS < lastDistance -> RouteOrientationResult(
                gpxXml = gpxXml,
                reversed = false,
                firstEndpointDistanceMeters = firstDistance,
                lastEndpointDistanceMeters = lastDistance,
                reason = "first-endpoint-closer",
            )
            else -> RouteOrientationResult(
                gpxXml = gpxXml,
                reversed = false,
                firstEndpointDistanceMeters = firstDistance,
                lastEndpointDistanceMeters = lastDistance,
                reason = "endpoint-distance-ambiguous",
            )
        }
    }

    private fun parsePoints(gpxXml: String): List<GpxPoint> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setXIncludeAware(false) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(gpxXml)))
        val trackPoints = document.getElementsByTagName("trkpt")
        val routePoints = if (trackPoints.length > 0) trackPoints else document.getElementsByTagName("rtept")
        buildList {
            for (index in 0 until routePoints.length) {
                val node = routePoints.item(index)
                val lat = node.attributes?.getNamedItem("lat")?.nodeValue?.toDoubleOrNull()
                val lon = node.attributes?.getNamedItem("lon")?.nodeValue?.toDoubleOrNull()
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                    add(GpxPoint(lat, lon))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun buildReversedGpx(points: List<GpxPoint>): String = buildString {
        append("<gpx version=\"1.1\" creator=\"TrailNav\"><trk><trkseg>")
        points.asReversed().forEach { point ->
            append("<trkpt lat=\"").append(point.latitude).append("\" lon=\"").append(point.longitude).append("\"/>")
        }
        append("</trkseg></trk></gpx>")
    }

    private fun distanceMeters(latitude: Double, longitude: Double, endpoint: GpxPoint): Double {
        val earthRadius = 6_371_008.8
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(endpoint.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(endpoint.longitude - longitude)
        val haversine = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        return earthRadius * 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
    }

    private data class GpxPoint(val latitude: Double, val longitude: Double)
}
