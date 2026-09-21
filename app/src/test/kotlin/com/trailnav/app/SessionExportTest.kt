package com.trailnav.app

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionExportTest {
    @Test
    fun zipPreservesBothStreamsAndWritesVerifiableGnuChecksums() {
        val root = Files.createTempDirectory("trailnav-export-test").toFile()
        try {
            val sessionDir = File(root, "1700000000000").apply { mkdirs() }
            val manifest = "{\"session_id\":\"00000000-0000-4000-8000-000000000001\"}\n"
            val events = "{\"seq\":1, \"t\":1.700000000123E9, \"stream\":\"sys\", \"kind\":\"service.started\"}\n" +
                "{\"seq\":2, \"t\":1.700000756123E9, \"stream\":\"sys\", \"kind\":\"service.stopped\"}\n"
            File(sessionDir, "manifest.json").writeText(manifest, StandardCharsets.UTF_8)
            File(sessionDir, "events.ndjson").writeText(events, StandardCharsets.UTF_8)
            val session = ExportableSession(sessionDir, "00000000-0000-4000-8000-000000000001", 1L, 1L, (manifest.length + events.length).toLong(), true)
            val zip = File(root, "out.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            assertEquals(1.700000000123E9, SessionExportCatalog.eventTimeLine(events.lineSequence().first(), "service.started"))
            assertEquals(1700000000000L, session.selectionId)

            ZipFile(zip).use { archive ->
                assertEquals(manifest, archive.getInputStream(archive.getEntry("1700000000000/manifest.json")).bufferedReader().readText())
                assertEquals(events, archive.getInputStream(archive.getEntry("1700000000000/events.ndjson")).bufferedReader().readText())
                val sums = archive.getInputStream(archive.getEntry("SHA256SUMS.txt")).bufferedReader().readLines()
                assertEquals(2, sums.size)
                sums.forEach { line ->
                    val parts = line.split(" *", limit = 2)
                    assertEquals(2, parts.size)
                    val bytes = archive.getInputStream(archive.getEntry(parts[1])).readBytes()
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    assertEquals(parts[0], digest)
                }
            }
            assertTrue(zip.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun folderNamesRemainIndependentSelectionKeys() {
        val root = Files.createTempDirectory("trailnav-export-ids").toFile()
        try {
            val first = ExportableSession(File(root, "1700000060000"), "first", 1L, null, 0L, false)
            val second = ExportableSession(File(root, "1700000120000"), "second", 1L, null, 0L, false)
            assertTrue(first.selectionId != second.selectionId)
        } finally {
            root.deleteRecursively()
        }
    }
}
