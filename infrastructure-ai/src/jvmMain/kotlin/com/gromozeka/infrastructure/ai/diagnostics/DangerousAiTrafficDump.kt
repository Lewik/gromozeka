package com.gromozeka.infrastructure.ai.diagnostics

import klog.KLoggers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

object DangerousAiTrafficDump {
    const val FLAG = "GROMOZEKA_DANGEROUS_AI_TRAFFIC_DUMP"
    private val log = KLoggers.logger(this)
    private val installedLoggers = mutableSetOf<String>()

    val enabled: Boolean
        get() = (System.getProperty(FLAG) ?: System.getenv(FLAG)).equals("true", ignoreCase = true)

    fun open(provider: String, transport: String): AiTrafficRecording? {
        if (!enabled) return null
        return try {
            val root = Path.of(System.getProperty("logging.file.path")
                ?: System.getenv("GROMOZEKA_LOG_DIR") ?: "logs", "ai-traffic")
            Files.createDirectories(root)
            val name = "${Instant.now().toEpochMilli()}-${UUID.randomUUID()}"
            val directory = root.resolve(name)
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectory(directory)
            }
            AiTrafficRecording(directory).also {
                it.event("opened", buildJsonObject { put("provider", provider); put("transport", transport) })
                log.warn("Dangerous AI traffic dump enabled: directory=$directory; payloads and credentials are unredacted")
            }
        } catch (error: Exception) {
            log.warn("Unable to open dangerous AI traffic dump: ${error.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    fun captureJavaLogger(name: String, provider: String) {
        if (!enabled || name in installedLoggers) return
        val recording = open(provider, "sdk-rpc") ?: return
        val logger = Logger.getLogger(name)
        logger.addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                if (!enabled) return
                recording.text("rpc", record.message.orEmpty())
                record.thrown?.let { recording.text("error", it.stackTraceToString()) }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }.apply { level = Level.ALL })
        logger.useParentHandlers = false
        logger.level = Level.ALL
        installedLoggers += name
    }
}

class AiTrafficRecording internal constructor(private val directory: Path) {
    private val log = KLoggers.logger(this)
    private val offsets = mutableMapOf<String, Long>()
    private val startedAt = System.nanoTime()
    private var failed = false
    private var sequence = 0L

    fun text(channel: String, text: String) = bytes(channel, text.toByteArray(Charsets.UTF_8))

    fun textFragment(channel: String, text: CharSequence) = bytes(channel, ByteArray(text.length * 2) { index ->
        val code = text[index / 2].code
        (if (index % 2 == 0) code ushr 8 else code).toByte()
    })

    fun bytes(channel: String, buffer: ByteBuffer) {
        val copy = buffer.duplicate()
        bytes(channel, ByteArray(copy.remaining()).also(copy::get))
    }

    @Synchronized
    fun bytes(channel: String, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (failed) return
        write {
            val position = offsets.getOrDefault(channel, 0L)
            append("$channel.bin", bytes, offset, length)
            offsets[channel] = position + length
            event("data", buildJsonObject {
                put("file", "$channel.bin"); put("offset", position); put("length", length)
            })
        }
    }

    @Synchronized
    fun event(type: String, details: JsonObject = JsonObject(emptyMap())) {
        if (failed) return
        write {
            val line = buildJsonObject {
                put("sequence", sequence++)
                put("at", Instant.now().toString())
                put("elapsedNanos", System.nanoTime() - startedAt)
                put("event", type)
                put("details", details)
            }.toString() + "\n"
            append("events.jsonl", line.toByteArray(Charsets.UTF_8))
        }
    }

    fun headers(event: String, headers: Map<String, List<String>>, details: Map<String, String> = emptyMap()) =
        event(event, buildJsonObject {
            details.forEach { (key, value) -> put(key, value) }
            put("headers", JsonObject(headers.mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }))
        })

    fun failure(error: Throwable) = text("error", error.stackTraceToString())

    fun input(channel: String, source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int = try {
            `in`.read().also { value ->
                if (value >= 0) bytes(channel, byteArrayOf(value.toByte())) else event("$channel.eof")
            }
        } catch (error: Exception) {
            failure(error)
            throw error
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
            `in`.read(buffer, offset, length).also { count ->
                if (count > 0) bytes(channel, buffer, offset, count) else if (count < 0) event("$channel.eof")
            }
        } catch (error: Exception) {
            failure(error)
            throw error
        }
        override fun skip(count: Long): Long {
            var remaining = count.coerceAtLeast(0)
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val size = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (size < 0) break
                remaining -= size
            }
            return count.coerceAtLeast(0) - remaining
        }
    }

    fun output(channel: String, destination: OutputStream): OutputStream = object : FilterOutputStream(destination) {
        override fun write(value: Int) { out.write(value); bytes(channel, byteArrayOf(value.toByte())) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytes(channel, buffer, offset, length)
        }
    }

    private fun append(name: String, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val path = directory.resolve(name)
        if (!Files.exists(path)) {
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else Files.newOutputStream(path, CREATE_NEW).close()
        }
        Files.newOutputStream(path, APPEND).use { it.write(bytes, offset, length) }
    }

    private inline fun write(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            failed = true
            log.warn("Dangerous AI traffic dump stopped after an I/O failure: ${error.javaClass.simpleName}")
        }
    }
}
