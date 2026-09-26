package com.trailnav.app

import com.trailnav.core.GuideResult
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Structured event sink so the Android service can be exercised without Android in JVM tests. */
interface SessionEventSink : Closeable {
    fun appendEnvelope(location: TrailLocation, eventStream: String, eventType: String)
    fun appendLocation(location: TrailLocation): Long
    fun appendGuide(location: TrailLocation, result: GuideResult, spokenText: String?, sourceSeq: Long, trigger: String? = null)
    fun appendSystem(kind: String, details: Map<String, String> = emptyMap())
    fun appendError(kind: String, message: String)
}

/**
 * Append-only JSONL logger for the adopted v0 session streams.  It writes to
 * the app's private files directory and never uploads raw location events.
 */
class JsonlSessionLogger(
    private val directory: File,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val codeHash: String,
    private val configHash: String,
    private val eventSettings: Map<String, Boolean>,
    private val routeHash: String,
    private val appVersion: String,
    private val routeElevationUsed: Boolean = false,
    private val routeElevationReason: String = "absent",
    private val routeWaypointCount: Int = 0,
) : SessionEventSink {
    private val eventsFile = File(directory, "events.ndjson")
    private val writer: BufferedWriter
    private var sequence = 0L

    init {
        require(UUID_REGEX.matches(sessionId)) { "sessionId must be a UUID" }
        require(eventSettings.keys == GuideEvent.values().map { it.id }.toSet()) {
            "eventSettings must contain exactly E1 through E8"
        }
        directory.mkdirs()
        sequence = nextSequence(eventsFile)
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(eventsFile, true), StandardCharsets.UTF_8))
        if (!File(directory, "manifest.json").exists()) writeManifest()
    }

    override fun appendEnvelope(location: TrailLocation, eventStream: String, eventType: String) {
        append(
            stream = "envelope",
            timestamp = location.timestampMillis / 1000.0,
            fields = linkedMapOf(
                "event_stream" to eventStream,
                "event_type" to eventType,
            ),
        )
    }

    private fun writeManifest() {
        val eventsJson = GuideEvent.values().joinToString(separator = ",", prefix = "{", postfix = "}") { event ->
            "\"${event.id}\":${eventSettings.getValue(event.id)}"
        }
        File(directory, "manifest.json").writeText(
            """{
              "session_id":"${escape(sessionId)}",
              "schema_version":"0.1.0-draft",
              "started_at_wall":"${java.time.Instant.now()}",
              "app":{"version":"${escape(appVersion)}","code_hash":"${escape(codeHash)}"},
              "engine":{"config":{"implementation":"core-guide","config_hash":"${escape(configHash)}","events":$eventsJson},"rng_seed":0},
              "route":{"gpx_hash":"${escape(routeHash)}","elevation_used":$routeElevationUsed,"elevation_reason":"${escape(routeElevationReason)}","waypoint_count":$routeWaypointCount},
              "clock":{"monotonic_source":"location-frame-timestamp","timestamp_unit":"seconds","t_order":"per-stream","seq_order":"append"},
              "privacy":{"upload_default":false}
            }""".trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
    }

    override fun appendLocation(location: TrailLocation): Long = append(
            stream = "loc",
            timestamp = location.timestampMillis / 1000.0,
            fields = linkedMapOf(
                "lat" to location.latitude,
                "lon" to location.longitude,
                "accuracy" to location.accuracyMeters,
                "speed_mps" to location.speedMps,
                "bearing_deg" to location.bearingDegrees,
                "provider" to location.provider,
            ),
        )

    override fun appendGuide(location: TrailLocation, result: GuideResult, spokenText: String?, sourceSeq: Long, trigger: String?) {
        val fields = linkedMapOf<String, Any?>(
            "src_seq" to sourceSeq,
            "inputs" to linkedMapOf(
                "timestamp" to location.timestampMillis,
                "lat" to location.latitude,
                "lon" to location.longitude,
                "accuracy" to location.accuracyMeters,
                "speed_mps" to location.speedMps,
                "bearing_deg" to location.bearingDegrees,
            ),
            "decision" to guidanceName(result),
            "output_text" to (spokenText ?: ""),
            "reason" to linkedMapOf(
                "rule" to result.reason.rule,
                "thresholds" to result.reason.thresholds,
                "alternatives_considered" to result.reason.alternativesConsidered,
                "details" to result.reason.details,
            ),
            "state_hash" to "sha256:${sha256(result.nextState.toString())}",
        )
        if (trigger != null) fields["trigger"] = trigger
        append(
            stream = "guide",
            timestamp = location.timestampMillis / 1000.0,
            fields = fields,
        )
    }

    override fun appendSystem(kind: String, details: Map<String, String>) {
        append(
            "sys",
            System.currentTimeMillis() / 1000.0,
            linkedMapOf(
                "kind" to kind,
                "battery_pct" to details["battery_pct"]?.toDoubleOrNull(),
                "details" to details,
            ),
        )
    }

    override fun appendError(kind: String, message: String) {
        append("err", System.currentTimeMillis() / 1000.0, linkedMapOf("kind" to kind, "message" to message))
    }

    private fun append(stream: String, timestamp: Double, fields: Map<String, Any?>): Long {
        val eventSeq = sequence++
        val event = linkedMapOf<String, Any?>("seq" to eventSeq, "t" to timestamp, "stream" to stream)
        event.putAll(fields)
        writer.append(toJson(event)).append('\n')
        writer.flush()
        return eventSeq
    }

    override fun close() {
        writer.flush()
        writer.close()
    }

    companion object {
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

        fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun nextSequence(eventsFile: File): Long {
            if (!eventsFile.isFile) return 0L
            val pattern = Regex("\\\"seq\\\"\\s*:\\s*(\\d+)")
            return eventsFile.useLines { lines ->
                lines.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull()?.plus(1) ?: 0L
            }
        }

        private fun guidanceName(result: GuideResult): String = when (result.guidance) {
            null -> "CONTINUE"
            is com.trailnav.core.Guidance.OffRoute -> "OFF_ROUTE"
            is com.trailnav.core.Guidance.TurnAhead -> "TURN_AHEAD"
            is com.trailnav.core.Guidance.TurnNow -> "TURN_NOW"
            is com.trailnav.core.Guidance.Milestone -> "MILESTONE"
            is com.trailnav.core.Guidance.Elapsed -> "ELAPSED"
            is com.trailnav.core.Guidance.Remaining -> "REMAINING"
            is com.trailnav.core.Guidance.Slope -> "SLOPE"
            is com.trailnav.core.Guidance.Elevation -> "ELEVATION"
            is com.trailnav.core.Guidance.Waypoint -> "WAYPOINT"
            is com.trailnav.core.Guidance.Sunset -> "SUNSET"
            is com.trailnav.core.Guidance.Sunrise -> "SUNRISE"
            is com.trailnav.core.Guidance.Status -> "STATUS"
            com.trailnav.core.Guidance.Arrived -> "ARRIVED"
        }

        private fun toJson(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"${escape(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { "\"${escape(it.key.toString())}\":${toJson(it.value)}" }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
            else -> "\"${escape(value.toString())}\""
        }

        private fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
