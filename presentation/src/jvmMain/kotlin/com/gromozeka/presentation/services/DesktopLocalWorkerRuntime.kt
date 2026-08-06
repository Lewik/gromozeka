package com.gromozeka.presentation.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal interface DesktopLocalWorkerRuntime : AutoCloseable {
    val deviceDisplayName: String
    val workerIdSuffix: String
    val workerConfig: Path
    val computerUsePermissionsSupported: Boolean

    suspend fun isEnabled(): Boolean
    suspend fun isRunning(): Boolean
    suspend fun enable()
    suspend fun disable()
    suspend fun start()
    suspend fun stop()
    suspend fun enroll(arguments: List<String>, enrollmentToken: String)
    suspend fun readComputerUsePermissions(): LocalWorkerPermissions?
    suspend fun requestComputerUsePermissions()
    fun requireStableInstallationPath()
    fun hostName(): String?

    override fun close() = Unit
}

internal fun createDesktopLocalWorkerRuntime(
    userHome: Path,
    osName: String,
    environment: Map<String, String>,
): DesktopLocalWorkerRuntime? = when {
    osName.contains("mac", ignoreCase = true) -> MacOsLocalWorkerRuntime(userHome, environment)
    osName.startsWith("Windows", ignoreCase = true) -> WindowsLocalWorkerRuntime(userHome, environment)
    else -> null
}

internal abstract class BaseDesktopLocalWorkerRuntime(
    protected val userHome: Path,
    protected val environment: Map<String, String>,
) : DesktopLocalWorkerRuntime {
    protected val workerHome: Path = userHome.resolve(".gromozeka")
    protected val localWorkerHome: Path = workerHome.resolve("local-worker")
    final override val workerConfig: Path = localWorkerHome.resolve("worker.yaml")

    protected suspend fun runCommand(
        command: List<String>,
        requiredFile: Path,
        description: String,
        requireSuccess: Boolean = true,
        enrollmentToken: String? = null,
        additionalEnvironment: Map<String, String> = emptyMap(),
    ): DesktopCommandResult = withContext(Dispatchers.IO) {
        require(Files.isRegularFile(requiredFile)) { "Bundled Local Worker executable is missing: $requiredFile" }
        val outputFile = Files.createTempFile("gromozeka-local-worker-", ".log")
        try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
            processBuilder.environment()[GROMOZEKA_HOME_ENVIRONMENT_VARIABLE] = workerHome.toString()
            processBuilder.environment()[GROMOZEKA_WORKER_CONFIG_ENVIRONMENT_VARIABLE] = workerConfig.toString()
            enrollmentToken?.let {
                processBuilder.environment()[WORKER_ENROLLMENT_TOKEN_ENVIRONMENT_VARIABLE] = it
            }
            processBuilder.environment().putAll(additionalEnvironment)
            val process = processBuilder.start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                error("$description timed out")
            }
            val result = DesktopCommandResult(process.exitValue(), Files.readString(outputFile).trim())
            if (requireSuccess && result.exitCode != 0) {
                error(result.output.ifBlank { "$description failed" })
            }
            result
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    protected fun bundleRoot(): Path {
        val explicit = System.getProperty(LOCAL_WORKER_BUNDLE_ROOT_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
        val packaged = applicationResourcesDirectory()?.resolve("local-worker")
        return explicit ?: packaged
            ?: error("Local Worker resources are available only in a packaged app or the configured development run")
    }

    protected fun applicationResourcesDirectory(): Path? =
        System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)

    protected fun isPackagedApplication(): Boolean = applicationResourcesDirectory() != null

    protected fun developmentWorkerCommand(arguments: List<String>, includeConfig: Boolean): List<String> {
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
        )
        return buildList {
            add(javaExecutable.toString())
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(WORKER_MAIN_CLASS)
            if (includeConfig) {
                add("--spring.config.additional-location=optional:file:$workerConfig")
            }
            addAll(arguments)
        }
    }

    protected companion object {
        const val WORKER_MAIN_CLASS = "com.gromozeka.worker.GromozekaWorkerMainKt"
        const val GROMOZEKA_HOME_ENVIRONMENT_VARIABLE = "GROMOZEKA_HOME"
        const val GROMOZEKA_WORKER_CONFIG_ENVIRONMENT_VARIABLE = "GROMOZEKA_WORKER_CONFIG"
        const val WORKER_ENROLLMENT_TOKEN_ENVIRONMENT_VARIABLE = "GROMOZEKA_WORKER_ENROLLMENT_TOKEN"
        const val LOCAL_WORKER_BUNDLE_ROOT_PROPERTY = "gromozeka.local-worker.bundle-root"
        const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
        const val COMMAND_TIMEOUT_SECONDS = 30L
    }
}

internal data class DesktopCommandResult(val exitCode: Int, val output: String)
