package com.trailnav.app

import com.trailnav.core.RouteModel
import com.trailnav.core.isClosedRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GpxImportTest {
    @Test
    fun trackNameWinsAndWhitespaceIsNormalized() {
        val xml = """
            <gpx>
              <metadata><name>Metadata name</name></metadata>
              <rte><name>Route name</name></rte>
              <trk><name>  Morning
                ridge   walk </name></trk>
            </gpx>
        """.trimIndent()

        assertEquals("Morning ridge walk", GpxImport.displayName(xml, "file.gpx", "fallback"))
    }

    @Test
    fun routeNameIsUsedWhenTrackNameIsMissingOrBlank() {
        val xml = "<gpx><trk><name>  </name></trk><rte><name>Route name</name></rte></gpx>"

        assertEquals("Route name", GpxImport.displayName(xml, "file.gpx", "fallback"))
    }

    @Test
    fun metadataNameIsUsedAfterTrackAndRouteNames() {
        val xml = "<gpx><trk/><rte/><metadata><name>Metadata name</name></metadata></gpx>"

        assertEquals("Metadata name", GpxImport.displayName(xml, null, "fallback"))
    }

    @Test
    fun fileNameFallbackRemovesOnlyTheLastExtension() {
        val xml = "<gpx/>"

        assertEquals("a.b", GpxImport.displayName(xml, "a.b.gpx", "fallback"))
        assertEquals("A", GpxImport.displayName(xml, "A.GPX", "fallback"))
        assertEquals("a", GpxImport.displayName(xml, "a.txt", "fallback"))
        assertEquals("a", GpxImport.displayName(xml, "a", "fallback"))
    }

    @Test
    fun fallbackResourceIsUsedWhenNamesAreUnavailable() {
        assertEquals(
            TestStrings.text("imported_route_fallback_name"),
            GpxImport.displayName("<gpx/>", null, TestStrings.text("imported_route_fallback_name")),
        )
    }

    @Test
    fun namesAreLimitedToSixtyUnicodeCodePoints() {
        val xml = "<gpx><trk><name>${"A".repeat(59)}😀B</name></trk></gpx>"

        val name = GpxImport.displayName(xml, null, "fallback")

        assertEquals("A".repeat(59) + "😀", name)
        assertEquals(60, RouteNameRules.codePointCount(name))
    }

    @Test
    fun importUsesGpxContentRatherThanFileExtension() {
        val xml = routeGpx("37.0", "37.01")

        val textNamed = GpxImport.importGpx(xml, "route.txt", null, "fallback")
        val gpxNamed = GpxImport.importGpx(xml, "route.gpx", null, "fallback")

        assertEquals(textNamed.route.totalLengthMeters, gpxNamed.route.totalLengthMeters)
        assertEquals("route", textNamed.displayName)
        assertEquals("route", gpxNamed.displayName)
        assertFails { GpxImport.importGpx("<gpx/>", "not-a-route.gpx", null, "fallback") }
    }

    @Test
    fun importedClosedValueMatchesEngineDefaultConfiguration() {
        val closedXml = routeGpx("37.0", "37.0004")
        val openXml = routeGpx("37.0", "37.009")

        val closed = GpxImport.importGpx(closedXml, "closed.gpx", null, "fallback")
        val open = GpxImport.importGpx(openXml, "open.gpx", null, "fallback")

        assertEquals(isClosedRoute(RouteModel.fromGpx(closedXml)), closed.closed)
        assertEquals(isClosedRoute(RouteModel.fromGpx(openXml)), open.closed)
        assertEquals(true, closed.closed)
        assertEquals(false, open.closed)
    }

    @Test
    fun doctypeRouteImportsUsingFileNameWhenNameParsingIsRejected() {
        val xml = "<!DOCTYPE gpx>${routeGpx("37.0", "37.01")}"

        val imported = GpxImport.importGpx(xml, "doctype-route.gpx", null, "fallback")

        assertEquals("doctype-route", imported.displayName)
        assertEquals(true, imported.route.totalLengthMeters > 0.0)
    }

    private fun routeGpx(firstLatitude: String, lastLatitude: String): String =
        """<gpx><trk><trkseg><trkpt lat="$firstLatitude" lon="127.0"/><trkpt lat="$lastLatitude" lon="127.0"/></trkseg></trk></gpx>"""
}
