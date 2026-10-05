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
                assertEquals(3, sums.size)
                assertTrue(sums.any { it.endsWith(" *EXPORT_INDEX.txt") })
                sums.forEach { line ->
                    val parts = line.split(" *", limit = 2)
                    assertEquals(2, parts.size)
                    val bytes = archive.getInputStream(archive.getEntry(parts[1])).readBytes()
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    assertEquals(parts[0], digest)
                }
            }
            assertTrue(zip.isFile)
            assertArchiveChecksums(zip)
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

    @Test
    fun optionalImuIsIncludedByteForByteAndIndexedAsPresent() {
        val root = Files.createTempDirectory("trailnav-export-imu").toFile()
        try {
            val events = imuConfigEvent(true)
            val imuBytes = ByteArray(300_000) { (it % 251).toByte() }
            val session = createSession(root, "1700000000000", events, imuBytes)
            val zip = File(root, "imu.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            ZipFile(zip).use { archive ->
                val entry = archive.getEntry("1700000000000/imu.ndjson")
                assertTrue(entry != null)
                val exportedImu = archive.getInputStream(entry).use { it.readBytes() }
                assertTrue(imuBytes.contentEquals(exportedImu))
                val index = readEntryText(archive, "EXPORT_INDEX.txt")
                assertTrue(index.contains("session=1700000000000 imu_config_enabled=true"))
                assertTrue(index.contains("file=imu.ndjson size=" + imuBytes.size + " included=yes reason=imu-optional"))
                assertTrue(index.contains("expected=imu.ndjson status=present"))
            }
            assertArchiveChecksums(zip)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enabledImuMissingProducesWarningAndDoesNotFailExport() {
        val root = Files.createTempDirectory("trailnav-export-imu-warning").toFile()
        try {
            val session = createSession(root, "session-enabled-missing", imuConfigEvent(true))
            val zip = File(root, "warning.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            ZipFile(zip).use { archive ->
                val index = readEntryText(archive, "EXPORT_INDEX.txt")
                val warning = readEntryText(archive, "EXPORT_WARNINGS.txt")
                assertTrue(index.contains("imu_config_enabled=true"))
                assertTrue(index.contains("expected=imu.ndjson status=absent"))
                assertTrue(warning.contains("session-enabled-missing"))
                assertTrue(warning.contains("imu.ndjson 기대했으나 없음"))
            }
            assertArchiveChecksums(zip)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun disabledImuMissingHasNoWarningAndIsNotExpected() {
        val root = Files.createTempDirectory("trailnav-export-imu-disabled").toFile()
        try {
            val session = createSession(root, "session-disabled", imuConfigEvent(false))
            val zip = File(root, "disabled.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            ZipFile(zip).use { archive ->
                assertTrue(readEntryText(archive, "EXPORT_INDEX.txt").contains("expected=imu.ndjson status=not-expected"))
                assertEquals(null, archive.getEntry("EXPORT_WARNINGS.txt"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacySessionWithoutImuConfigIsUnknownWithoutWarning() {
        val root = Files.createTempDirectory("trailnav-export-legacy").toFile()
        try {
            val session = createSession(root, "session-legacy", serviceStartedEvent())
            val zip = File(root, "legacy.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            ZipFile(zip).use { archive ->
                val index = readEntryText(archive, "EXPORT_INDEX.txt")
                assertTrue(index.contains("session=session-legacy imu_config_enabled=unknown"))
                assertTrue(index.contains("expected=imu.ndjson status=not-expected"))
                assertEquals(null, archive.getEntry("EXPORT_WARNINGS.txt"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun filesOutsideAllowlistAreIndexedButNotAddedToArchive() {
        val root = Files.createTempDirectory("trailnav-export-allowlist").toFile()
        try {
            val session = createSession(root, "session-notes", serviceStartedEvent())
            val notes = File(session.directory, "notes.txt").apply { writeText("owner note") }
            val zip = File(root, "allowlist.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            ZipFile(zip).use { archive ->
                val index = readEntryText(archive, "EXPORT_INDEX.txt")
                assertTrue(index.contains("file=notes.txt size=" + notes.length() + " included=no reason=not-in-allowlist"))
                assertEquals(null, archive.getEntry("session-notes/notes.txt"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun indexHasOneFileRowForEveryDirectSessionFile() {
        val root = Files.createTempDirectory("trailnav-export-index-files").toFile()
        try {
            val session = createSession(root, "session-files", imuConfigEvent(true), byteArrayOf(1, 2, 3))
            File(session.directory, "notes.txt").writeText("note")
            val expectedFileCount = session.directory.listFiles().orEmpty().count { it.isFile }
            val zip = File(root, "index-files.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            ZipFile(zip).use { archive ->
                val fileRows = readEntryText(archive, "EXPORT_INDEX.txt").lineSequence().count { it.startsWith("file=") }
                assertEquals(expectedFileCount, fileRows)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sessionIndexBlocksAreSortedByFolderName() {
        val root = Files.createTempDirectory("trailnav-export-order").toFile()
        try {
            val later = createSession(root, "b-session", serviceStartedEvent())
            val earlier = createSession(root, "a-session", serviceStartedEvent())
            val zip = File(root, "order.zip")
            SessionArchiveBuilder.build(listOf(later, earlier), zip)

            ZipFile(zip).use { archive ->
                val names = readEntryText(archive, "EXPORT_INDEX.txt")
                    .lineSequence()
                    .filter { it.startsWith("session=") }
                    .map { it.substringAfter("session=").substringBefore(' ') }
                    .toList()
                assertEquals(listOf("a-session", "b-session"), names)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun catalogSizeIncludesOnlyExistingAllowlistedFiles() {
        val root = Files.createTempDirectory("trailnav-export-size").toFile()
        try {
            val session = createSession(root, "session-size", serviceStartedEvent(), byteArrayOf(1, 2, 3, 4))
            File(session.directory, "notes.txt").writeText("ignored")
            val expected = SessionExportFiles.requiredFiles
                .plus(SessionExportFiles.optionalFiles)
                .sumOf { File(session.directory, it).takeIf { file -> file.isFile }?.length() ?: 0L }
            assertEquals(expected, SessionExportCatalog.includedFileSizeBytes(session.directory))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun allowlistNamesAreExplicitlyAsserted() {
        assertEquals(listOf("manifest.json", "events.ndjson"), SessionExportFiles.requiredFiles)
        assertEquals(listOf("imu.ndjson"), SessionExportFiles.optionalFiles)
    }

    @Test
    fun unreadableSessionDirectoryIsIndexedAndDoesNotStopExport() {
        val root = Files.createTempDirectory("trailnav-export-directory-error").toFile()
        try {
            val notADirectory = File(root, "session-unreadable").apply { writeText("not a directory") }
            val session = ExportableSession(notADirectory, "session-id", 1L, null, 0L, false)
            val zip = File(root, "directory-error.zip")
            SessionArchiveBuilder.build(listOf(session), zip)

            ZipFile(zip).use { archive ->
                val index = readEntryText(archive, "EXPORT_INDEX.txt")
                assertTrue(index.contains("session=session-unreadable error=IOException"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createSession(
        root: File,
        folderName: String,
        events: String,
        imuBytes: ByteArray? = null,
    ): ExportableSession {
        val directory = File(root, folderName).apply { mkdirs() }
        val manifest = "{\"session_id\":\"00000000-0000-4000-8000-000000000001\"}\n"
        File(directory, SessionExportFiles.MANIFEST_FILE).writeText(manifest, StandardCharsets.UTF_8)
        File(directory, SessionExportFiles.EVENTS_FILE).writeText(events, StandardCharsets.UTF_8)
        imuBytes?.let { File(directory, SessionExportFiles.IMU_FILE).writeBytes(it) }
        return ExportableSession(directory, "00000000-0000-4000-8000-000000000001", 1L, null, 0L, false)
    }

    private fun imuConfigEvent(enabled: Boolean): String =
        "{\"seq\":1,\"t\":1,\"stream\":\"sys\",\"kind\":\"imu.config\",\"details\":{\"enabled\":\"" +
            enabled + "\"}}\n"

    private fun serviceStartedEvent(): String =
        "{\"seq\":1,\"t\":1,\"stream\":\"sys\",\"kind\":\"service.started\"}\n"

    private fun readEntryText(archive: ZipFile, name: String): String {
        val entry = archive.getEntry(name) ?: error("Missing zip entry: " + name)
        return archive.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun assertArchiveChecksums(zip: File) {
        ZipFile(zip).use { archive ->
            val sums = readEntryText(archive, "SHA256SUMS.txt").lineSequence().filter { it.isNotBlank() }.toList()
            val verifiedPaths = mutableSetOf<String>()
            sums.forEach { line ->
                val separator = line.indexOf(" *")
                assertTrue(separator > 0, "Invalid checksum line: " + line)
                val expected = line.substring(0, separator)
                val path = line.substring(separator + 2)
                val entry = archive.getEntry(path) ?: error("Missing checksum target: " + path)
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                assertEquals(expected, actual)
                verifiedPaths += path
            }
            val expectedPaths = archive.entries().asSequence()
                .map { it.name }
                .filter { it != "SHA256SUMS.txt" }
                .toSet()
            assertEquals(expectedPaths, verifiedPaths)
        }
    }
}
