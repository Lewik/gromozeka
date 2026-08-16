package com.gromozeka.shared.logging

import kotlin.time.Clock

enum class GromozekaLogLevel(val priority: Int) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
}

data class GromozekaLogEvent(
    val timestamp: String,
    val level: GromozekaLogLevel,
    val loggerName: String,
    val message: String,
    val stackTrace: String?,
) {
    fun format(): String = buildString {
        append(timestamp)
        append(" [")
        append(level.name)
        append("] ")
        append(loggerName)
        append(" - ")
        append(message)
        stackTrace?.let {
            append('\n')
            append(it)
        }
    }
}

fun interface GromozekaLogSink {
    fun write(event: GromozekaLogEvent)
}

object GromozekaLogging {
    private val lock = GromozekaLogLock()
    private var sink: GromozekaLogSink = GromozekaLogSink { event -> println(event.format()) }
    private var minimumLevel: GromozekaLogLevel = GromozekaLogLevel.INFO

    fun configure(
        sink: GromozekaLogSink,
        minimumLevel: GromozekaLogLevel = GromozekaLogLevel.INFO,
    ) {
        lock.withLock {
            this.sink = sink
            this.minimumLevel = minimumLevel
        }
    }

    fun logger(name: String): GromozekaLogger = GromozekaLogger(name)

    fun logger(owner: Any): GromozekaLogger =
        GromozekaLogger(owner::class.simpleName ?: "Logger")

    internal fun emit(
        level: GromozekaLogLevel,
        loggerName: String,
        error: Throwable?,
        message: () -> String,
    ) {
        val configuredSink = lock.withLock {
            sink.takeIf { level.priority >= minimumLevel.priority }
        } ?: return
        val event = GromozekaLogEvent(
            timestamp = Clock.System.now().toString(),
            level = level,
            loggerName = loggerName,
            message = redactSensitiveLogData(message()).take(MAX_LOG_VALUE_LENGTH),
            stackTrace = error?.stackTraceToString()?.let(::redactSensitiveLogData)?.take(MAX_LOG_VALUE_LENGTH),
        )
        runCatching { configuredSink.write(event) }
            .onFailure { println(event.format()) }
    }
}

class CompositeGromozekaLogSink(
    private vararg val sinks: GromozekaLogSink,
) : GromozekaLogSink {
    override fun write(event: GromozekaLogEvent) {
        sinks.forEach { sink -> sink.write(event) }
    }
}

class BoundedFileLogSink internal constructor(
    private val path: String,
    private val maxFileBytes: Long,
    private val archiveCount: Int,
    private val fileSystem: GromozekaLogFileSystem,
) : GromozekaLogSink {
    private val lock = GromozekaLogLock()

    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(archiveCount >= 0) { "archiveCount must not be negative" }
        fileSystem.createParentDirectories(path)
    }

    override fun write(event: GromozekaLogEvent) {
        val line = event.format() + "\n"
        val lineBytes = line.encodeToByteArray().size.toLong()
        lock.withLock {
            if (fileSystem.size(path) + lineBytes > maxFileBytes) rotate()
            fileSystem.append(path, line)
        }
    }

    fun clear() {
        lock.withLock {
            fileSystem.delete(path)
            (1..archiveCount).forEach { archive -> fileSystem.delete(archivePath(archive)) }
        }
    }

    private fun rotate() {
        if (archiveCount == 0) {
            fileSystem.delete(path)
            return
        }
        fileSystem.delete(archivePath(archiveCount))
        for (archive in archiveCount - 1 downTo 1) {
            fileSystem.move(archivePath(archive), archivePath(archive + 1))
        }
        fileSystem.move(path, archivePath(1))
    }

    private fun archivePath(index: Int): String = "$path.$index"
}

internal interface GromozekaLogFileSystem {
    fun createParentDirectories(path: String)
    fun size(path: String): Long
    fun append(path: String, value: String)
    fun delete(path: String)
    fun move(source: String, target: String)
}

internal expect class GromozekaLogLock() {
    fun <T> withLock(block: () -> T): T
}

class GromozekaLogger internal constructor(
    private val name: String,
) {
    fun trace(message: String) = trace { message }
    fun trace(message: () -> String) = log(GromozekaLogLevel.TRACE, null, message)

    fun debug(message: String) = debug { message }
    fun debug(message: () -> String) = log(GromozekaLogLevel.DEBUG, null, message)

    fun info(message: String) = info { message }
    fun info(message: () -> String) = log(GromozekaLogLevel.INFO, null, message)

    fun warn(message: String) = warn { message }
    fun warn(message: () -> String) = log(GromozekaLogLevel.WARN, null, message)
    fun warn(error: Throwable, message: String) = warn(error) { message }
    fun warn(error: Throwable, message: () -> String) = log(GromozekaLogLevel.WARN, error, message)

    fun error(message: String) = error { message }
    fun error(message: () -> String) = log(GromozekaLogLevel.ERROR, null, message)
    fun error(error: Throwable, message: String) = error(error) { message }
    fun error(error: Throwable, message: () -> String) = log(GromozekaLogLevel.ERROR, error, message)

    private fun log(
        level: GromozekaLogLevel,
        error: Throwable?,
        message: () -> String,
    ) = GromozekaLogging.emit(level, name, error, message)
}

internal fun redactSensitiveLogData(value: String): String =
    sensitiveLogPatterns.fold(value) { result, pattern ->
        pattern.regex.replace(result, pattern.replacement)
    }

private data class SensitiveLogPattern(
    val regex: Regex,
    val replacement: String,
)

private val sensitiveLogPatterns = listOf(
    SensitiveLogPattern(
        regex = Regex("(?i)(bearer\\s+)[a-z0-9._~+/=-]+"),
        replacement = "$1[REDACTED]",
    ),
    SensitiveLogPattern(
        regex = Regex("(?i)((?:password|token|secret|api[_-]?key)\\s*[=:]\\s*)[^\\s,;&]+"),
        replacement = "$1[REDACTED]",
    ),
    SensitiveLogPattern(
        regex = Regex("\\bsk-[a-zA-Z0-9_-]{12,}\\b"),
        replacement = "sk-[REDACTED]",
    ),
    SensitiveLogPattern(
        regex = Regex("\\beyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\b"),
        replacement = "[REDACTED_JWT]",
    ),
)

private const val MAX_LOG_VALUE_LENGTH = 64 * 1024
