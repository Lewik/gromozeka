package com.gromozeka.shared.logging

import android.content.Context
import android.util.Log
import java.io.File

object AndroidDiagnosticLogging {
    fun install(
        context: Context,
        fileName: String,
        minimumLevel: GromozekaLogLevel = GromozekaLogLevel.INFO,
    ): File {
        val logFile = File(context.filesDir, "diagnostics/$fileName")
        val fileSink = BoundedFileLogSink(
            path = logFile.absolutePath,
            maxFileBytes = 1024L * 1024,
            archiveCount = 2,
            fileSystem = AndroidGromozekaLogFileSystem,
        )
        GromozekaLogging.configure(
            sink = CompositeGromozekaLogSink(
                GromozekaLogSink { event ->
                    Log.println(event.level.androidPriority(), event.loggerName.take(23), event.format())
                },
                fileSink,
            ),
            minimumLevel = minimumLevel,
        )
        return logFile
    }
}

private object AndroidGromozekaLogFileSystem : GromozekaLogFileSystem {
    override fun createParentDirectories(path: String) {
        File(path).parentFile?.mkdirs()
    }

    override fun size(path: String): Long = File(path).takeIf(File::isFile)?.length() ?: 0L

    override fun append(path: String, value: String) {
        File(path).appendText(value)
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun move(source: String, target: String) {
        val sourceFile = File(source)
        if (!sourceFile.exists()) return
        val targetFile = File(target)
        targetFile.delete()
        sourceFile.renameTo(targetFile)
    }
}

private fun GromozekaLogLevel.androidPriority(): Int = when (this) {
    GromozekaLogLevel.TRACE -> Log.VERBOSE
    GromozekaLogLevel.DEBUG -> Log.DEBUG
    GromozekaLogLevel.INFO -> Log.INFO
    GromozekaLogLevel.WARN -> Log.WARN
    GromozekaLogLevel.ERROR -> Log.ERROR
}
