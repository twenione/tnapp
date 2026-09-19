package com.trailnav.app

import com.trailnav.core.ProgressDirection
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

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
    ): RouteOrientationResult {
        val reversed = direction == ProgressDirection.REVERSE
        return RouteOrientationResult(
            gpxXml = if (reversed) reverseGpx(gpxXml) else gpxXml,
            reversed = reversed,
            reason = reason,
            observedNetDisplacementMeters = observedNetDisplacementMeters,
            observationElapsedSeconds = observationElapsedSeconds,
        )
    }

    /** Reverse track or route points while preserving the imported path geometry. */
    fun reverseGpx(gpxXml: String): String {
        val points = parsePoints(gpxXml)
        if (points.size < 2) return gpxXml
        return buildString {
            append("<gpx version=\"1.1\" creator=\"TrailNav\"><trk><trkseg>")
            points.asReversed().forEach { point ->
                append("<trkpt lat=\"").append(point.latitude)
                    .append("\" lon=\"").append(point.longitude).append("\"/>")
            }
            append("</trkseg></trk></gpx>")
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

    private data class GpxPoint(val latitude: Double, val longitude: Double)
}
