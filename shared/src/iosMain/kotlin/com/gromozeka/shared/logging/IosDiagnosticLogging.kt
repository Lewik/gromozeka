package com.gromozeka.shared.logging

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

@OptIn(ExperimentalForeignApi::class)
object IosDiagnosticLogging {
    fun install(
        fileName: String,
        minimumLevel: GromozekaLogLevel = GromozekaLogLevel.INFO,
    ): String {
        val logPath = "${NSHomeDirectory()}/Library/Logs/Gromozeka/$fileName"
        val fileSink = BoundedFileLogSink(
            path = logPath,
            maxFileBytes = 1024L * 1024,
            archiveCount = 2,
            fileSystem = IosGromozekaLogFileSystem,
        )
        GromozekaLogging.configure(
            sink = CompositeGromozekaLogSink(
                GromozekaLogSink { event -> NSLog("%@", event.format()) },
                fileSink,
            ),
            minimumLevel = minimumLevel,
        )
        return logPath
    }
}

@OptIn(ExperimentalForeignApi::class)
private object IosGromozekaLogFileSystem : GromozekaLogFileSystem {
    private val fileManager get() = NSFileManager.defaultManager

    override fun createParentDirectories(path: String) {
        val directory = path.substringBeforeLast('/', missingDelimiterValue = path)
        fileManager.createDirectoryAtPath(directory, true, null, null)
    }

    override fun size(path: String): Long =
        (fileManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    override fun append(path: String, value: String) {
        val file = fopen(path, "a") ?: error("Cannot open diagnostic log at $path")
        try {
            check(fputs(value, file) >= 0) { "Cannot write diagnostic log at $path" }
        } finally {
            fclose(file)
        }
    }

    override fun delete(path: String) {
        if (fileManager.fileExistsAtPath(path)) {
            fileManager.removeItemAtPath(path, null)
        }
    }

    override fun move(source: String, target: String) {
        if (!fileManager.fileExistsAtPath(source)) return
        delete(target)
        fileManager.moveItemAtPath(source, target, null)
    }
}
