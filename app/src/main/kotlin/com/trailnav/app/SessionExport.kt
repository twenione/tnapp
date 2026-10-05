package com.trailnav.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class ExportableSession(
    val directory: File,
    val sessionId: String,
    val startedAtMillis: Long,
    val durationSeconds: Long?,
    val sizeBytes: Long,
    val normalTermination: Boolean,
) {
    val folderName: String get() = directory.name
    /** Folder names are the durable session key; event timestamps are display data. */
    val selectionId: Long get() = directory.name.toLong()
}

internal object SessionExportFiles {
    const val MANIFEST_FILE = "manifest.json"
    const val EVENTS_FILE = "events.ndjson"
    const val IMU_FILE = "imu.ndjson"

    val requiredFiles = listOf(MANIFEST_FILE, EVENTS_FILE)
    val optionalFiles = listOf(IMU_FILE)
    val archiveFiles = requiredFiles + optionalFiles
}

internal object SessionExportCatalog {
    private val sessionIdPattern = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"")
    private val eventTimePattern = Regex("\"t\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?)")

    fun scan(context: Context): List<ExportableSession> {
        val activeSessionId = NavigationPreferences.activeSessionId(context)
        return File(context.filesDir, "sessions").listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.toLongOrNull() != null }
            .mapNotNull { directory ->
                val manifest = File(directory, "manifest.json")
                val events = File(directory, "events.ndjson")
                if (!manifest.isFile || !events.isFile) return@mapNotNull null
                val manifestText = runCatching { manifest.readText(StandardCharsets.UTF_8) }.getOrNull()
                    ?: return@mapNotNull null
                val sessionId = sessionIdPattern.find(manifestText)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                if (sessionId == activeSessionId) return@mapNotNull null
                val eventTimes = runCatching { eventTimes(events) }.getOrNull()
                    ?: return@mapNotNull null
                val startedAt = eventTimes.first
                    ?: directory.name.toLongOrNull()?.div(1_000.0)
                    ?: return@mapNotNull null
                val stoppedAt = eventTimes.second
                val folderStartMillis = directory.name.toLongOrNull()
                ExportableSession(
                    directory = directory,
                    sessionId = sessionId,
                    startedAtMillis = folderStartMillis ?: (startedAt * 1_000.0).toLong(),
                    durationSeconds = stoppedAt?.let { ((it - startedAt).coerceAtLeast(0.0)).toLong() },
                    sizeBytes = includedFileSizeBytes(directory),
                    normalTermination = stoppedAt != null,
                )
            }
            .sortedByDescending { it.startedAtMillis }
    }

    internal fun includedFileSizeBytes(directory: File): Long =
        SessionExportFiles.archiveFiles.sumOf { name ->
            File(directory, name).takeIf { it.isFile }?.length() ?: 0L
        }

    fun displayStartTime(session: ExportableSession): String = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm",
        Locale.KOREA,
    ).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(session.startedAtMillis))

    fun displayDuration(session: ExportableSession): String = session.durationSeconds?.let {
        "%d분 %02d초".format(Locale.KOREA, it / 60, it % 60)
    } ?: "—"

    fun displaySize(session: ExportableSession): String = when {
        session.sizeBytes >= 1_000_000L -> "%.1f MB".format(Locale.US, session.sizeBytes / 1_000_000.0)
        else -> "%.1f KB".format(Locale.US, session.sizeBytes / 1_000.0)
    }

    fun cleanupExports(context: Context) {
        File(context.cacheDir, "exports").listFiles().orEmpty().forEach { it.delete() }
    }

    /** Parse the compact event form used by the logger, including exponent notation. */
    internal fun eventTimeLine(line: String, kind: String): Double? =
        if (line.contains("\"kind\":\"$kind\"") || line.contains("\"kind\": \"$kind\"")) {
            eventTimePattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        } else null

    /** Read only the first start event and the last stop event; never materialize the stream. */
    private fun eventTimes(events: File): Pair<Double?, Double?> {
        var started: Double? = null
        var stopped: Double? = null
        events.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (started == null) started = eventTimeLine(line, "service.started")
                eventTimeLine(line, "service.stopped")?.let { stopped = it }
            }
        }
        return started to stopped
    }
}

internal object SessionArchiveBuilder {
    private data class SessionFile(
        val name: String,
        val sizeBytes: Long,
        val included: Boolean,
        val reason: String,
    )

    private data class SessionIndex(
        val folderName: String,
        val imuConfigEnabled: Boolean?,
        val files: List<SessionFile>,
        val error: String? = null,
    )

    fun build(sessions: List<ExportableSession>, output: File) {
        require(sessions.isNotEmpty()) { "at least one session is required" }
        output.parentFile?.mkdirs()
        val stagingDirectory = requireNotNull(output.absoluteFile.parentFile)
        stagingDirectory.mkdirs()

        val warnings = StringBuilder()
        val checksums = StringBuilder()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val index = sessions
                .sortedBy { it.folderName }
                .map { session -> indexSession(session, stagingDirectory, zip, checksums, warnings) }
            val indexBytes = renderIndex(index, Instant.now()).toByteArray(StandardCharsets.UTF_8)
            val warningBytes = warnings.toString().takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)

            writeBytesEntry(zip, "EXPORT_INDEX.txt", indexBytes, checksums)
            if (warningBytes != null) {
                writeBytesEntry(zip, "EXPORT_WARNINGS.txt", warningBytes, checksums)
            }
            zip.putNextEntry(ZipEntry("SHA256SUMS.txt"))
            zip.write(checksums.toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun indexSession(
        session: ExportableSession,
        stagingDirectory: File,
        zip: ZipOutputStream,
        checksums: StringBuilder,
        warnings: StringBuilder,
    ): SessionIndex {
        val files = try {
            (session.directory.listFiles() ?: throw IOException("unable to list session directory"))
                .filter { it.isFile }
                .sortedBy { it.name }
        } catch (error: Exception) {
            return SessionIndex(
                folderName = session.folderName,
                imuConfigEnabled = null,
                files = emptyList(),
                error = error.javaClass.simpleName.ifBlank { "Exception" },
            )
        }
        SessionExportFiles.requiredFiles.forEach { requiredName ->
            if (files.none { it.name == requiredName }) {
                throw IllegalArgumentException("missing session file: " + File(session.directory, requiredName).path)
            }
        }

        var imuConfigEnabled: Boolean? = null
        val indexedFiles = mutableListOf<SessionFile>()
        files.forEach { file ->
            val name = file.name
            val reason = when {
                name in SessionExportFiles.requiredFiles -> "allowlist"
                name in SessionExportFiles.optionalFiles -> "imu-optional"
                else -> "not-in-allowlist"
            }
            if (reason == "not-in-allowlist") {
                indexedFiles += SessionFile(name, file.length(), included = false, reason = reason)
            } else {
                val parser = if (name == SessionExportFiles.EVENTS_FILE) ImuConfigParser() else null
                var stagedFile: File? = null
                var stageError: Exception? = null
                val staged = try {
                    val tempFile = File.createTempFile("tnx", ".stage", stagingDirectory)
                    stagedFile = tempFile
                    stageSessionFile(file, tempFile, parser).let { size -> tempFile to size }
                } catch (error: Exception) {
                    stageError = error
                    stagedFile?.delete()
                    null
                } finally {
                    if (parser != null) imuConfigEnabled = parser.enabled
                }
                if (staged == null) {
                    val error = requireNotNull(stageError)
                    indexedFiles += SessionFile(
                        name,
                        file.length(),
                        included = false,
                        reason = "unreadable:" + error.javaClass.simpleName.ifBlank { "Exception" },
                    )
                } else {
                    try {
                        val relativePath = session.folderName + "/" + name
                        val digest = writeFileEntry(zip, relativePath, staged.first)
                        appendChecksum(checksums, digest, relativePath)
                        indexedFiles += SessionFile(name, staged.second, included = true, reason = reason)
                    } finally {
                        staged.first.delete()
                    }
                }
            }
        }

        if (imuConfigEnabled == true && indexedFiles.none { it.name == SessionExportFiles.IMU_FILE }) {
            warnings.append(session.folderName)
                .append(": imu.ndjson 기대했으나 없음\n")
        }
        return SessionIndex(session.folderName, imuConfigEnabled, indexedFiles)
    }

    private fun stageSessionFile(source: File, staged: File, parser: ImuConfigParser?): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var sizeBytes = 0L
        BufferedInputStream(FileInputStream(source)).use { input ->
            staged.outputStream().buffered().use { output ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    parser?.accept(buffer, count)
                    sizeBytes += count
                }
            }
        }
        parser?.finish()
        return sizeBytes
    }

    private fun renderIndex(sessions: List<SessionIndex>, exportTime: Instant): String = buildString {
        append("EXPORT_INDEX format=1\n")
        append("export_utc=").append(exportTime).append('\n')
        sessions.forEach { session ->
            if (session.error != null) {
                append("session=").append(session.folderName)
                    .append(" error=").append(session.error).append('\n')
                return@forEach
            }
            append("session=").append(session.folderName)
                .append(" imu_config_enabled=")
                .append(session.imuConfigEnabled?.toString() ?: "unknown")
                .append('\n')
            session.files.forEach { file ->
                append("file=").append(file.name)
                    .append(" size=").append(file.sizeBytes)
                    .append(" included=").append(if (file.included) "yes" else "no")
                    .append(" reason=").append(file.reason).append('\n')
            }
            val imuStatus = when {
                session.imuConfigEnabled != true -> "not-expected"
                session.files.any { it.name == SessionExportFiles.IMU_FILE } -> "present"
                else -> "absent"
            }
            append("expected=imu.ndjson status=").append(imuStatus).append('\n')
        }
    }

    private fun writeFileEntry(zip: ZipOutputStream, path: String, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(path))
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                zip.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
        }
        zip.closeEntry()
        return digest.hexDigest()
    }

    private fun writeBytesEntry(
        zip: ZipOutputStream,
        path: String,
        bytes: ByteArray,
        checksums: StringBuilder,
    ) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
        appendChecksum(checksums, sha256(bytes), path)
    }

    private fun appendChecksum(checksums: StringBuilder, digest: String, path: String) {
        checksums.append(digest).append(" *").append(path).append('\n')
    }

    fun zipFile(context: Context, sessions: List<ExportableSession>): File {
        SessionExportCatalog.cleanupExports(context)
        val name = "trailnav_sessions_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
        return File(context.cacheDir, "exports/$name").also { build(sessions, it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").apply { update(bytes) }.hexDigest()

    private fun MessageDigest.hexDigest(): String =
        digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class ImuConfigParser {
        private val line = ByteArrayOutputStream()
        var enabled: Boolean? = null
            private set

        fun accept(bytes: ByteArray, length: Int) {
            var start = 0
            for (index in 0 until length) {
                if (bytes[index] == '\n'.code.toByte()) {
                    line.write(bytes, start, index - start)
                    consumeLine()
                    start = index + 1
                }
            }
            if (start < length) line.write(bytes, start, length - start)
        }

        fun finish() {
            if (line.size() > 0) consumeLine()
        }

        private fun consumeLine() {
            val bytes = line.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            val value = String(bytes, 0, length, StandardCharsets.UTF_8)
            if (IMU_CONFIG_KIND.containsMatchIn(value)) {
                val details = DETAILS_OBJECT.find(value)?.groupValues?.getOrNull(1)
                enabled = details?.let { ENABLED_VALUE.find(it)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull() }
            }
            line.reset()
        }

        private companion object {
            val IMU_CONFIG_KIND = Regex("\"kind\"\\s*:\\s*\"imu\\.config\"")
            val DETAILS_OBJECT = Regex("\"details\"\\s*:\\s*\\{([^}]*)\\}")
            val ENABLED_VALUE = Regex("\"enabled\"\\s*:\\s*\"(true|false)\"")
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
