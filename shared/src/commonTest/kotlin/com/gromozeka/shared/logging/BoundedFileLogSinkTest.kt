package com.gromozeka.shared.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoundedFileLogSinkTest {
    @Test
    fun rotatesAndDeletesOldestArchive() {
        val files = InMemoryLogFileSystem()
        val sink = BoundedFileLogSink(
            path = "logs/client.log",
            maxFileBytes = event("three").format().encodeToByteArray().size.toLong() + 1,
            archiveCount = 2,
            fileSystem = files,
        )

        sink.write(event("one"))
        sink.write(event("two"))
        sink.write(event("three"))
        sink.write(event("four"))

        assertEquals(event("four").format() + "\n", files["logs/client.log"])
        assertEquals(event("three").format() + "\n", files["logs/client.log.1"])
        assertEquals(event("two").format() + "\n", files["logs/client.log.2"])
        assertFalse(files.contains("logs/client.log.3"))
    }

    @Test
    fun clearsCurrentFileAndArchives() {
        val files = InMemoryLogFileSystem()
        val sink = BoundedFileLogSink(
            "client.log",
            event("one").format().encodeToByteArray().size.toLong() + 1,
            2,
            files,
        )
        sink.write(event("one"))
        sink.write(event("two"))

        sink.clear()

        assertFalse(files.contains("client.log"))
        assertFalse(files.contains("client.log.1"))
        assertFalse(files.contains("client.log.2"))
    }

    private fun event(message: String) = GromozekaLogEvent(
        timestamp = "",
        level = GromozekaLogLevel.INFO,
        loggerName = "",
        message = message,
        stackTrace = null,
    )
}

private class InMemoryLogFileSystem : GromozekaLogFileSystem {
    private val files = mutableMapOf<String, String>()

    operator fun get(path: String): String? = files[path]

    fun contains(path: String): Boolean = path in files

    override fun createParentDirectories(path: String) = Unit

    override fun size(path: String): Long = files[path]?.encodeToByteArray()?.size?.toLong() ?: 0L

    override fun append(path: String, value: String) {
        files[path] = files.orEmpty(path) + value
    }

    override fun delete(path: String) {
        files.remove(path)
    }

    override fun move(source: String, target: String) {
        files.remove(source)?.let { files[target] = it }
    }

    private fun Map<String, String>.orEmpty(path: String): String = get(path).orEmpty()
}
