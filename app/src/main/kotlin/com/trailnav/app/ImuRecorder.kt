package com.trailnav.app

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Writes the opt-in experimental accelerometer stream without touching events.ndjson. */
class ImuRecorder(file: File) : AutoCloseable {
    private var writer: BufferedWriter? = null
    private var closed = false
    private var failed = false
    private var failureReported = false
    private var pendingFailure: IOException? = null
    private var lastFlushElapsedMillis: Long? = null
    private var lastMarkElapsedMillis: Long? = null

    var onWriteFailure: ((IOException) -> Unit)? = null
        set(value) {
            field = value
            val pending = pendingFailure
            if (value != null && pending != null) {
                pendingFailure = null
                notifyWriteFailure(value, pending)
            }
        }

    init {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create IMU directory: " + parent.path)
            }
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8))
            if (appendLine(HEADER)) flushWriter()
        } catch (error: IOException) {
            recordWriteFailure(error)
        }
    }

    fun onSample(elapsedMillis: Long, sensorTimestampNanos: Long, x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val line = """{"kind":"acc","t_ms":$elapsedMillis,"ts_ns":$sensorTimestampNanos,"x":$x,"y":$y,"z":$z}"""
        if (!appendLine(line)) return

        val lastFlush = lastFlushElapsedMillis
        if (lastFlush == null) {
            lastFlushElapsedMillis = elapsedMillis
        } else if (elapsedMillis >= lastFlush && elapsedMillis - lastFlush >= FLUSH_INTERVAL_MILLIS) {
            if (flushWriter()) lastFlushElapsedMillis = elapsedMillis
        }
    }

    /** Returns true only when the mark was written and made durable. */
    fun mark(elapsedMillis: Long): Boolean {
        if (closed || failed) return false
        val previous = lastMarkElapsedMillis
        if (previous != null && (elapsedMillis < previous || elapsedMillis - previous < MARK_DEBOUNCE_MILLIS)) {
            return false
        }
        if (!appendLine("""{"kind":"mark","t_ms":$elapsedMillis}""")) return false
        lastMarkElapsedMillis = elapsedMillis
        if (!flushWriter()) return false
        lastFlushElapsedMillis = elapsedMillis
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        val active = writer ?: return
        writer = null
        try {
            active.flush()
            active.close()
        } catch (error: IOException) {
            recordWriteFailure(error, closeWriter = false)
        }
    }

    private fun appendLine(line: String): Boolean {
        if (closed || failed) return false
        val active = writer ?: return false
        return try {
            active.write(line)
            active.newLine()
            true
        } catch (error: IOException) {
            recordWriteFailure(error)
            false
        }
    }

    private fun flushWriter(): Boolean {
        if (closed || failed) return false
        val active = writer ?: return false
        return try {
            active.flush()
            true
        } catch (error: IOException) {
            recordWriteFailure(error)
            false
        }
    }

    private fun recordWriteFailure(error: IOException, closeWriter: Boolean = true) {
        failed = true
        val active = writer
        writer = null
        if (closeWriter && active != null) {
            runCatching { active.close() }
        }
        if (failureReported) return
        failureReported = true
        val callback = onWriteFailure
        if (callback == null) pendingFailure = error else notifyWriteFailure(callback, error)
    }

    private fun notifyWriteFailure(callback: (IOException) -> Unit, error: IOException) {
        runCatching { callback(error) }
    }

    companion object {
        private const val MARK_DEBOUNCE_MILLIS = 1_000L
        private const val FLUSH_INTERVAL_MILLIS = 5_000L
        private const val HEADER =
            """{"kind":"header","sensor":"accelerometer","delay":"SENSOR_DELAY_GAME","schema":"imu-experimental-0"}"""
    }
}
