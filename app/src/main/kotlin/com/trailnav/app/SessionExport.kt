package com.trailnav.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
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
                    sizeBytes = manifest.length() + events.length(),
                    normalTermination = stoppedAt != null,
                )
            }
            .sortedByDescending { it.startedAtMillis }
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
        if (line.contains("\"kind\":\"$kind\"")) {
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
    private val filesInSession = listOf("manifest.json", "events.ndjson")

    fun build(sessions: List<ExportableSession>, output: File) {
        require(sessions.isNotEmpty()) { "at least one session is required" }
        output.parentFile?.mkdirs()
        val checksums = StringBuilder()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            sessions.sortedBy { it.folderName }.forEach { session ->
                filesInSession.forEach { name ->
                    val file = File(session.directory, name)
                    require(file.isFile) { "missing session file: ${file.path}" }
                    val bytes = file.readBytes()
                    val relativePath = "${session.folderName}/$name"
                    zip.putNextEntry(ZipEntry(relativePath))
                    zip.write(bytes)
                    zip.closeEntry()
                    checksums.append(sha256(bytes)).append(" *").append(relativePath).append('\n')
                }
            }
            zip.putNextEntry(ZipEntry("SHA256SUMS.txt"))
            zip.write(checksums.toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun zipFile(context: Context, sessions: List<ExportableSession>): File {
        SessionExportCatalog.cleanupExports(context)
        val name = "trailnav_sessions_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
        return File(context.cacheDir, "exports/$name").also { build(sessions, it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
