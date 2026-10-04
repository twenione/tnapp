package com.trailnav.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImuRecorderTest {
    @Test
    fun writesHeaderFirstAndSamplesWithStableJsonKeyOrder() {
        val directory = Files.createTempDirectory("imu-recorder-test").toFile()
        try {
            val file = File(directory, "imu.ndjson")
            val recorder = ImuRecorder(file)
            recorder.onSample(1_234L, 5_678_901L, 1.25f, -2.5f, 0.0f)
            recorder.close()

            val lines = file.readLines(Charsets.UTF_8)
            assertEquals(
                """{"kind":"header","sensor":"accelerometer","delay":"SENSOR_DELAY_GAME","schema":"imu-experimental-0"}""",
                lines.first(),
            )
            assertEquals(
                """{"kind":"acc","t_ms":1234,"ts_ns":5678901,"x":1.25,"y":-2.5,"z":0.0}""",
                lines[1],
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun markDebounceRejectsDuplicatesBeforeOneSecondAndAcceptsInclusiveBoundary() {
        val directory = Files.createTempDirectory("imu-recorder-test").toFile()
        try {
            val file = File(directory, "imu.ndjson")
            val recorder = ImuRecorder(file)

            assertTrue(recorder.mark(0L))
            val afterFirstMark = file.readLines(Charsets.UTF_8)
            assertFalse(recorder.mark(999L))
            assertEquals(afterFirstMark, file.readLines(Charsets.UTF_8))
            assertTrue(recorder.mark(1_000L))
            assertEquals(3, file.readLines(Charsets.UTF_8).size)
            recorder.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeMakesLaterSamplesNoOps() {
        val directory = Files.createTempDirectory("imu-recorder-test").toFile()
        try {
            val file = File(directory, "imu.ndjson")
            val recorder = ImuRecorder(file)
            recorder.onSample(10L, 20L, 1f, 2f, 3f)
            recorder.close()
            val before = file.readLines(Charsets.UTF_8)

            recorder.onSample(11L, 21L, 4f, 5f, 6f)

            assertEquals(before, file.readLines(Charsets.UTF_8))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun writeFailureIsReportedOnceAndDoesNotEscapeSampleCalls() {
        val directory = Files.createTempDirectory("imu-recorder-test").toFile()
        try {
            val blocker = File(directory, "not-a-directory")
            blocker.writeText("file")
            var failureCount = 0
            val recorder = ImuRecorder(File(blocker, "imu.ndjson"))
            recorder.onWriteFailure = { failureCount++ }

            recorder.onSample(1L, 2L, 1f, 2f, 3f)
            recorder.onSample(2L, 3L, 4f, 5f, 6f)

            assertEquals(1, failureCount)
            assertFalse(recorder.mark(0L))
            recorder.close()
        } finally {
            directory.deleteRecursively()
        }
    }
}
