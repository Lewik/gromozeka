package com.gromozeka.shared.logging

import java.io.File

enum class JvmLogComponent(private val relativeDirectory: String?) {
    SERVER(null),
    WORKER("workers"),
    CLIENT(null),
    ;

    internal fun directoryBelow(baseDirectory: String): String =
        relativeDirectory?.let { File(baseDirectory, it).path } ?: baseDirectory
}

object JvmLogDirectoryResolver {
    fun configure(
        args: Array<String>,
        mode: String?,
        component: JvmLogComponent,
    ): String {
        val path = resolve(
            args = args.toList(),
            mode = mode,
            component = component,
            systemProperties = System.getProperties().stringPropertyNames()
                .associateWith(System::getProperty),
            environment = System.getenv(),
            userHome = System.getProperty("user.home"),
            operatingSystem = System.getProperty("os.name"),
        )
        System.setProperty("logging.file.path", path)
        return path
    }

    internal fun resolve(
        args: List<String>,
        mode: String?,
        component: JvmLogComponent,
        systemProperties: Map<String, String>,
        environment: Map<String, String>,
        userHome: String,
        operatingSystem: String,
    ): String {
        argumentValue(args, "logging.file.path")?.let { return it }
        argumentValue(args, "gromozeka.log.dir")?.let { return component.directoryBelow(it) }
        systemProperties["logging.file.path"]?.takeIf(String::isNotBlank)?.let { return it }
        systemProperties["gromozeka.log.dir"]?.takeIf(String::isNotBlank)?.let {
            return component.directoryBelow(it)
        }
        environment["LOGGING_FILE_PATH"]?.takeIf(String::isNotBlank)?.let { return it }
        environment["GROMOZEKA_LOG_DIR"]?.takeIf(String::isNotBlank)?.let {
            return component.directoryBelow(it)
        }

        val customHome = systemProperties["GROMOZEKA_HOME"]
            ?: environment["GROMOZEKA_HOME"]
        return when (mode?.lowercase()) {
            "dev", "development" -> component.directoryBelow("logs")
            "test", "e2e" -> component.directoryBelow(customHome?.let { "$it/logs" } ?: "build/test-data/logs")
            null, "prod", "production" -> component.directoryBelow(
                when {
                    operatingSystem.contains("mac", ignoreCase = true) -> "$userHome/Library/Logs/Gromozeka"
                    operatingSystem.contains("windows", ignoreCase = true) ->
                        "$userHome/AppData/Local/Gromozeka/logs"
                    else -> "$userHome/.local/share/Gromozeka/logs"
                }
            )
            else -> error("Unsupported GROMOZEKA_MODE=$mode")
        }
    }

    private fun argumentValue(args: List<String>, name: String): String? {
        val prefix = "--$name="
        args.firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val index = args.indexOf("--$name")
        return args.getOrNull(index + 1)?.takeIf(String::isNotBlank)
    }
}

object JvmClientDiagnosticLogging {
    fun install(args: Array<String> = emptyArray()): File {
        val mode = System.getProperty("GROMOZEKA_MODE") ?: System.getenv("GROMOZEKA_MODE")
        val directory = JvmLogDirectoryResolver.configure(args, mode, JvmLogComponent.CLIENT)
        val logFile = File(directory, "client.log")
        val fileSink = BoundedFileLogSink(
            path = logFile.absolutePath,
            maxFileBytes = 5L * 1024 * 1024,
            archiveCount = 2,
            fileSystem = JvmGromozekaLogFileSystem,
        )
        GromozekaLogging.configure(
            sink = CompositeGromozekaLogSink(
                GromozekaLogSink { event -> println(event.format()) },
                fileSink,
            ),
            minimumLevel = configuredLogLevel(mode),
        )
        return logFile
    }
}

private object JvmGromozekaLogFileSystem : GromozekaLogFileSystem {
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

private fun configuredLogLevel(mode: String?): GromozekaLogLevel {
    val configured = System.getProperty("gromozeka.log.level")
        ?: System.getenv("GROMOZEKA_LOG_LEVEL")
    if (configured != null) {
        return GromozekaLogLevel.entries.firstOrNull { it.name.equals(configured, ignoreCase = true) }
            ?: error("Unsupported GROMOZEKA_LOG_LEVEL=$configured")
    }
    return if (mode.equals("dev", ignoreCase = true) || mode.equals("development", ignoreCase = true)) {
        GromozekaLogLevel.DEBUG
    } else {
        GromozekaLogLevel.INFO
    }
}
