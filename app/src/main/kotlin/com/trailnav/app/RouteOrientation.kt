package com.trailnav.app

import com.trailnav.core.ProgressDirection
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Document

/** Result of applying an observed travel direction to the imported GPX. */
data class RouteOrientationResult(
    val gpxXml: String,
    val reversed: Boolean,
    val reason: String,
    val observedNetDisplacementMeters: Double,
    val observationElapsedSeconds: Double,
)

/** App-layer GPX preprocessor; :core-guide remains unchanged. */
object RouteOrientation {
    fun orient(
        gpxXml: String,
        direction: ProgressDirection?,
        reason: String,
        observedNetDisplacementMeters: Double,
        observationElapsedSeconds: Double,
        fallbackLocation: TrailLocation? = null,
    ): RouteOrientationResult {
        val fallbackDecision = if (direction == null && fallbackLocation != null) {
            endpointFallbackDecision(gpxXml, fallbackLocation)
        } else {
            null
        }
        val reversed = direction == ProgressDirection.REVERSE || fallbackDecision?.reversed == true
        return RouteOrientationResult(
            gpxXml = if (reversed) reverseGpx(gpxXml) else gpxXml,
            reversed = reversed,
            reason = fallbackDecision?.reason ?: reason,
            observedNetDisplacementMeters = observedNetDisplacementMeters,
            observationElapsedSeconds = observationElapsedSeconds,
        )
    }

    private fun endpointFallbackDecision(gpxXml: String, location: TrailLocation): EndpointFallback? {
        val points = parsePoints(gpxXml)
        if (points.size < 2) return null
        val startDistance = distanceMeters(location.latitude, location.longitude, points.first())
        val endDistance = distanceMeters(location.latitude, location.longitude, points.last())
        val difference = startDistance - endDistance
        return when {
            difference > ENDPOINT_TIE_METERS -> EndpointFallback(
                reversed = true,
                reason = "fallback-endpoint-distance",
            )
            difference < -ENDPOINT_TIE_METERS -> EndpointFallback(
                reversed = false,
                reason = "fallback-endpoint-distance",
            )
            else -> EndpointFallback(
                reversed = false,
                reason = "fallback-endpoint-ambiguous",
            )
        }
    }

    private fun distanceMeters(latitude: Double, longitude: Double, point: GpxPoint): Double {
        val dLat = Math.toRadians(point.latitude - latitude)
        val dLon = Math.toRadians(point.longitude - longitude)
        val meanLat = Math.toRadians((point.latitude + latitude) / 2.0)
        val north = dLat * EARTH_RADIUS_METERS
        val east = dLon * kotlin.math.cos(meanLat) * EARTH_RADIUS_METERS
        return kotlin.math.sqrt(north * north + east * east)
    }

    /** Reverse track or route points while preserving the imported path geometry. */
    fun reverseGpx(gpxXml: String): String {
        val document = parseDocument(gpxXml) ?: return gpxXml
        val points = parsePoints(document)
        if (points.size < 2) return gpxXml
        val waypoints = parseWaypoints(document)
        return buildString {
            append("<gpx version=\"1.1\" creator=\"TrailNav\"><trk><trkseg>")
            points.asReversed().forEach { point ->
                append("<trkpt lat=\"").append(point.latitude)
                    .append("\" lon=\"").append(point.longitude).append("\">")
                point.elevationMeters?.let { append("<ele>").append(it).append("</ele>") }
                append("</trkpt>")
            }
            append("</trkseg></trk>")
            waypoints.forEach { waypoint ->
                append("<wpt lat=\"").append(waypoint.latitude)
                    .append("\" lon=\"").append(waypoint.longitude).append("\">")
                append("<name>").append(xmlEscape(waypoint.name)).append("</name></wpt>")
            }
            append("</gpx>")
        }
    }

    private fun parsePoints(gpxXml: String): List<GpxPoint> = parseDocument(gpxXml)?.let(::parsePoints).orEmpty()

    private fun parsePoints(document: Document): List<GpxPoint> = runCatching {
        val trackPoints = document.getElementsByTagName("trkpt")
        val routePoints = if (trackPoints.length > 0) trackPoints else document.getElementsByTagName("rtept")
        buildList {
            for (index in 0 until routePoints.length) {
                val node = routePoints.item(index)
                val lat = node.attributes?.getNamedItem("lat")?.nodeValue?.toDoubleOrNull()
                val lon = node.attributes?.getNamedItem("lon")?.nodeValue?.toDoubleOrNull()
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                    val elevation = node.childNodes.let { children ->
                        (0 until children.length).asSequence()
                            .map { children.item(it) }
                            .firstOrNull { it.localName == "ele" || it.nodeName == "ele" }
                            ?.textContent?.trim()?.toDoubleOrNull()
                    }
                    add(GpxPoint(lat, lon, elevation))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun parseWaypoints(document: Document): List<GpxWaypoint> = runCatching {
        val nodes = document.getElementsByTagName("wpt")
        buildList {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val lat = node.attributes?.getNamedItem("lat")?.nodeValue?.toDoubleOrNull()
                val lon = node.attributes?.getNamedItem("lon")?.nodeValue?.toDoubleOrNull()
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                    val children = node.childNodes
                    val name = (0 until children.length).asSequence()
                        .map { children.item(it) }
                        .firstOrNull { it.localName == "name" || it.nodeName == "name" }
                        ?.textContent?.trim().orEmpty()
                    add(GpxWaypoint(lat, lon, name))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun parseDocument(gpxXml: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setXIncludeAware(false) }
            isExpandEntityReferences = false
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(gpxXml)))
    }.getOrNull()

    private data class GpxPoint(val latitude: Double, val longitude: Double, val elevationMeters: Double? = null)
    private data class GpxWaypoint(val latitude: Double, val longitude: Double, val name: String)

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class EndpointFallback(val reversed: Boolean, val reason: String)

    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val ENDPOINT_TIE_METERS = 1.0
}
