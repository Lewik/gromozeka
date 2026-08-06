package com.gromozeka.presentation.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class WindowsLocalWorkerRuntime(
    userHome: Path,
    environment: Map<String, String>,
) : BaseDesktopLocalWorkerRuntime(userHome, environment) {
    override val deviceDisplayName: String = "PC"
    override val workerIdSuffix: String = "windows"
    override val computerUsePermissionsSupported: Boolean = false

    private val stateDirectory = localWorkerHome
    private val enabledMarker = stateDirectory.resolve("enabled")
    private val pidFile = stateDirectory.resolve("worker.pid")
    private val lockFile = stateDirectory.resolve("lifecycle.lock")
    private val logDirectory = workerHome.resolve("logs")
    private val standardOutput = logDirectory.resolve("worker-client.stdout.log")
    private val standardError = logDirectory.resolve("worker-client.stderr.log")

    override suspend fun isEnabled(): Boolean = Files.isRegularFile(enabledMarker)

    override suspend fun isRunning(): Boolean = managedProcess() != null

    override suspend fun enable() = withLifecycleLock {
        Files.writeString(
            enabledMarker,
            "enabled\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        try {
            startUnlocked()
        } catch (error: Throwable) {
            Files.deleteIfExists(enabledMarker)
            throw error
        }
    }

    override suspend fun disable() = withLifecycleLock {
        stopUnlocked()
        Files.deleteIfExists(enabledMarker)
        Unit
    }

    override suspend fun start() = withLifecycleLock {
        require(Files.isRegularFile(enabledMarker)) { "Local Worker is not enabled" }
        startUnlocked()
    }

    override suspend fun stop() = withLifecycleLock {
        stopUnlocked()
    }

    override suspend fun enroll(arguments: List<String>, enrollmentToken: String) {
        val command = workerCommand(arguments, includeConfig = false)
        runCommand(
            command = command,
            requiredFile = Path.of(command.first()),
            description = "Local Worker ${arguments.firstOrNull().orEmpty()}",
            enrollmentToken = enrollmentToken,
            additionalEnvironment = workerEnvironment(),
        )
    }

    override suspend fun readComputerUsePermissions(): LocalWorkerPermissions? = null

    override suspend fun requestComputerUsePermissions() = Unit

    override fun requireStableInstallationPath() = Unit

    override fun hostName(): String? =
        environment["COMPUTERNAME"] ?: environment["HOSTNAME"]

    private fun startUnlocked() {
        if (managedProcess() != null) return
        Files.deleteIfExists(pidFile)
        Files.createDirectories(logDirectory)
        val command = workerCommand(emptyList(), includeConfig = true)
        val processBuilder = ProcessBuilder(command)
            .directory(userHome.toFile())
            .redirectOutput(ProcessBuilder.Redirect.appendTo(standardOutput.toFile()))
            .redirectError(ProcessBuilder.Redirect.appendTo(standardError.toFile()))
        processBuilder.environment()[GROMOZEKA_HOME_ENVIRONMENT_VARIABLE] = workerHome.toString()
        processBuilder.environment()[GROMOZEKA_WORKER_CONFIG_ENVIRONMENT_VARIABLE] = workerConfig.toString()
        processBuilder.environment().putAll(workerEnvironment())
        val process = processBuilder.start()
        try {
            val startedAtMillis = process.toHandle().info().startInstant().orElse(null)?.toEpochMilli()
            Files.writeString(
                pidFile,
                buildString {
                    appendLine(process.pid())
                    startedAtMillis?.let { appendLine(it) }
                },
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            if (!process.waitFor(STARTUP_PROBE_MILLISECONDS, TimeUnit.MILLISECONDS)) return
            Files.deleteIfExists(pidFile)
            error(
                "Local Worker exited during startup with code ${process.exitValue()}. " +
                    "See $standardError"
            )
        } catch (error: Throwable) {
            if (process.isAlive) process.destroyForcibly()
            Files.deleteIfExists(pidFile)
            throw error
        }
    }

    private fun stopUnlocked() {
        val process = managedProcess() ?: run {
            Files.deleteIfExists(pidFile)
            return
        }
        val descendants = process.descendants().toList()
        try {
            process.destroy()
            try {
                process.onExit().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                process.destroyForcibly()
                runCatching { process.onExit().get(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.onExit().get(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
            descendants.filter { it.isAlive }.forEach { it.destroy() }
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (!process.isAlive) Files.deleteIfExists(pidFile)
        }
        check(!process.isAlive) { "Local Worker process ${process.pid()} could not be stopped" }
    }

    private fun managedProcess(): ProcessHandle? {
        val identity = readProcessIdentity() ?: return null
        val process = ProcessHandle.of(identity.pid).orElse(null)
        if (process == null || !process.isAlive || !identity.matches(process)) {
            Files.deleteIfExists(pidFile)
            return null
        }
        return process
    }

    private fun readProcessIdentity(): ManagedProcessIdentity? {
        val lines = runCatching { Files.readAllLines(pidFile) }.getOrNull() ?: return null
        val pid = lines.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        val startedAtMillis = lines.getOrNull(1)?.trim()?.toLongOrNull()
        return ManagedProcessIdentity(pid, startedAtMillis)
    }

    private fun ManagedProcessIdentity.matches(process: ProcessHandle): Boolean {
        val actualStartedAtMillis = process.info().startInstant().orElse(null)?.toEpochMilli()
        if (startedAtMillis != null && actualStartedAtMillis != null) {
            return startedAtMillis == actualStartedAtMillis
        }
        return isWorkerProcess(process)
    }

    private fun isWorkerProcess(process: ProcessHandle): Boolean {
        val info = process.info()
        val command = info.command().orElse("")
        if (command.endsWith("GromozekaWorker.exe", ignoreCase = true)) return true
        val commandLine = info.commandLine().orElse("")
        if (commandLine.contains(WORKER_MAIN_CLASS)) return true
        return info.arguments().orElse(emptyArray<String>()).any { it == WORKER_MAIN_CLASS }
    }

    private fun workerCommand(arguments: List<String>, includeConfig: Boolean): List<String> =
        if (isPackagedApplication()) {
            buildList {
                add(packagedWorkerLauncher().toString())
                if (includeConfig) {
                    add("--spring.config.additional-location=optional:file:$workerConfig")
                }
                addAll(arguments)
            }
        } else {
            developmentWorkerCommand(arguments, includeConfig)
        }

    private fun packagedWorkerLauncher(): Path {
        val resourcesDirectory = requireNotNull(applicationResourcesDirectory())
        val applicationRoot = requireNotNull(resourcesDirectory.parent?.parent) {
            "Cannot resolve the packaged application root from $resourcesDirectory"
        }
        val launcher = applicationRoot.resolve("GromozekaWorker.exe")
        require(Files.isRegularFile(launcher)) { "Bundled Local Worker executable is missing: $launcher" }
        return launcher
    }

    private fun workerEnvironment(): Map<String, String> {
        val bundleRoot = bundleRoot()
        return mapOf(
            "GROMOZEKA_MODE" to (environment["GROMOZEKA_MODE"] ?: "prod"),
            "GROMOZEKA_BROWSER_MCP_LAUNCHER" to bundleRoot.resolve("bin/gromozeka-browser-mcp.cmd").toString(),
            "GROMOZEKA_BROWSER_MCP_HOME" to bundleRoot.resolve("app/browser-mcp").toString(),
            "GROMOZEKA_RUNTIME_BOOTSTRAP" to bundleRoot.resolve("bin/runtime-bootstrap.ps1").toString(),
        )
    }

    private suspend fun <T> withLifecycleLock(block: () -> T): T = withContext(Dispatchers.IO) {
        Files.createDirectories(stateDirectory)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                block()
            }
        }
    }

    private companion object {
        const val STARTUP_PROBE_MILLISECONDS = 750L
        const val STOP_TIMEOUT_SECONDS = 10L
        const val FORCE_STOP_TIMEOUT_SECONDS = 2L
    }

    private data class ManagedProcessIdentity(
        val pid: Long,
        val startedAtMillis: Long?,
    )
}
