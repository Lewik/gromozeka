package com.gromozeka.shared.logging

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.STDERR_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind
import platform.posix.stderr
import platform.posix.tmpfile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosDiagnosticLoggingTest {
    @Test
    fun writesClientInitializationMessageToSystemAndFileLogs() {
        // This informational message triggered the native NSLog crash in #173.
        assertLoggedToBothSinks("GromozekaWsClient", listOf("Protocol encoding=CBOR"))
    }

    @Test
    fun preservesUnicodeAndLiteralFormatSpecifiersInBothLogs() {
        assertLoggedToBothSinks(
            "NativeLogRegression",
            listOf(
                "Plain diagnostic text",
                "Кириллица: Привет, мир!",
                "עברית: שלום עולם",
                "Emoji: 🚀 🧑🏽‍💻 😀",
                "Literal formats: 100% %@ %s %d %n %1\$@ %%",
            ),
        )
    }

    private fun assertLoggedToBothSinks(loggerName: String, messages: List<String>) {
        val fileName = "ios-diagnostic-test-${NSUUID().UUIDString}.log"
        val path = IosDiagnosticLogging.install(fileName)
        try {
            val log = GromozekaLogging.logger(loggerName)
            // Exercise the real NSLog bridge, not a replacement/mock system sink.
            val systemOutput = captureStandardError {
                messages.forEach { log.info(it) }
            }
            val file = assertNotNull(fopen(path, "r"), "The file sink must run after NSLog")
            val fileOutput = try {
                readUtf8(file)
            } finally {
                fclose(file)
            }
            val expectedEvents = messages.map { "[INFO] $loggerName - $it" }
            expectedEvents.forEach { event -> assertContains(systemOutput, event) }
            assertEquals(
                expectedEvents,
                fileOutput.lineSequence().filter(String::isNotEmpty).map { it.substringAfter(' ') }.toList(),
            )
        } finally {
            GromozekaLogging.configure(GromozekaLogSink { }, GromozekaLogLevel.INFO)
            if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
                assertTrue(NSFileManager.defaultManager.removeItemAtPath(path, null))
            }
        }
    }

    private fun captureStandardError(block: () -> Unit): String {
        val capture = assertNotNull(tmpfile())
        try {
            val original = dup(STDERR_FILENO)
            assertTrue(original >= 0)
            try {
                assertEquals(0, fflush(stderr))
                assertTrue(dup2(fileno(capture), STDERR_FILENO) >= 0)
                try {
                    block()
                } finally {
                    fflush(stderr)
                    assertTrue(dup2(original, STDERR_FILENO) >= 0)
                }
                return readUtf8(capture)
            } finally {
                close(original)
            }
        } finally {
            fclose(capture)
        }
    }

    private fun readUtf8(file: CPointer<FILE>): String {
        assertEquals(0, fseek(file, 0, SEEK_END))
        val size = ftell(file)
        assertTrue(size >= 0 && size <= Int.MAX_VALUE)
        rewind(file)
        if (size == 0L) return ""
        val bytes = ByteArray(size.toInt())
        bytes.usePinned {
            assertEquals(size.toULong(), fread(it.addressOf(0), 1.convert(), bytes.size.convert(), file))
        }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
}
