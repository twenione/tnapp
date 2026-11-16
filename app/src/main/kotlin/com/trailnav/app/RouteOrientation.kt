package com.trailnav.app

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Document

/** Test oracle for the pure engine's route reversal semantics. */
object RouteOrientation {
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
        val standardNodes = document.getElementsByTagName("wpt")
        buildList {
            for (index in 0 until standardNodes.length) {
                val node = standardNodes.item(index)
                parseWaypoint(node)?.let(::add)
            }
            val naverNodes = document.getElementsByTagNameNS(NAVER_GPX_NAMESPACE, "waypoint")
            for (index in 0 until naverNodes.length) {
                val node = naverNodes.item(index)
                parseWaypoint(node)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun parseWaypoint(node: org.w3c.dom.Node): GpxWaypoint? {
        val lat = node.attributes?.getNamedItem("lat")?.nodeValue?.toDoubleOrNull()
        val lon = node.attributes?.getNamedItem("lon")?.nodeValue?.toDoubleOrNull()
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        val children = node.childNodes
        val name = (0 until children.length).asSequence()
            .map { children.item(it) }
            .firstOrNull { it.localName == "name" || it.nodeName == "name" }
            ?.textContent?.trim().orEmpty()
        return GpxWaypoint(lat, lon, name)
    }

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

    private const val NAVER_GPX_NAMESPACE = "https://map.naver.com/gpx/1"
}
