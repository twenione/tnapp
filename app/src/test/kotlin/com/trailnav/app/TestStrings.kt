package com.trailnav.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Reads the Android string table in JVM tests without depending on a device runtime. */
internal object TestStrings {
    private val valuesByName: Map<String, String> by lazy {
        val path = Path.of("src/main/res/values/strings.xml")
        require(Files.isRegularFile(path)) { "Android string table not found: $path" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
        val nodes = document.documentElement.getElementsByTagName("string")
        buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                put(element.getAttribute("name"), decodeAndroidEscapes(element.textContent))
            }
        }
    }

    fun text(name: String, vararg args: Any): String = format(
        valuesByName[name] ?: error("Unknown Android string resource: $name"),
        args,
    )

    private fun format(template: String, args: Array<out Any>): String =
        if (args.isEmpty()) template else String.format(Locale.KOREA, template, *args)

    private fun decodeAndroidEscapes(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '\\' && index + 1 < value.length) {
                append(
                    when (val escaped = value[index + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        '\\', '\'', '"', '@', '?' -> escaped
                        else -> escaped
                    },
                )
                index += 2
            } else {
                append(current)
                index += 1
            }
        }
    }
}
