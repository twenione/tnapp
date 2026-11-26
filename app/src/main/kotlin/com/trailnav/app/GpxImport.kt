package com.trailnav.app

import com.trailnav.core.RouteModel
import com.trailnav.core.isClosedRoute
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal data class ImportedGpx(
    val route: RouteModel,
    val displayName: String,
    val closed: Boolean,
)

internal object GpxImport {
    fun importGpx(
        content: String,
        fileName: String?,
        fallbackFileName: String?,
        fallbackName: String,
    ): ImportedGpx {
        val route = RouteModel.fromGpx(content)
        return ImportedGpx(
            route = route,
            displayName = displayName(content, fileName?.takeIf { it.isNotBlank() } ?: fallbackFileName, fallbackName),
            closed = isClosedRoute(route),
        )
    }

    fun displayName(content: String, fileName: String?, fallbackName: String): String {
        val document = runCatching { parseNameDocument(content) }.getOrNull()
        val trackName = document?.let { firstNameInElements(it, "trk") }
        val routeName = document?.let { firstNameInElements(it, "rte") }
        val metadataName = document?.let { firstNameInElements(it, "metadata") }
        val fileBaseName = fileName
            ?.takeIf { it.isNotBlank() }
            ?.let(::removeLastExtension)
            ?.let(::normalizeCandidate)
        return listOfNotNull(trackName, routeName, metadataName, fileBaseName)
            .firstOrNull()
            ?.let { RouteNameRules.limitCodePoints(it, RouteNameRules.MAX_LENGTH) }
            ?: fallbackName
    }

    private fun parseNameDocument(content: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            runCatching { isXIncludeAware = false }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(content)))
    }

    private fun firstNameInElements(document: Document, elementName: String): String? {
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val parent = elements.item(index) as? Element ?: continue
            if (localName(parent) != elementName) continue
            var child = parent.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE && localName(child) == "name") {
                    normalizeCandidate(child.textContent)?.let { return it }
                }
                child = child.nextSibling
            }
        }
        return null
    }

    private fun localName(node: Node): String = node.localName ?: node.nodeName.substringAfter(':')

    private fun normalizeCandidate(value: String): String? {
        val normalized = buildString {
            var pendingSpace = false
            value.forEach { character ->
                if (character.isWhitespace()) {
                    pendingSpace = isNotEmpty()
                } else {
                    if (pendingSpace) append(' ')
                    append(character)
                    pendingSpace = false
                }
            }
        }
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun removeLastExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }
}
