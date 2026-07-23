package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.RunningCommandProcess
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Service
class LocalCommandProcessRunner : CommandProcessRunner {
    private val host: LocalCommandHost by lazy(::currentLocalCommandHost)

    override fun start(spec: CommandProcessSpec): RunningCommandProcess {
        val workingDirectory = File(spec.workingDirectory)
        require(workingDirectory.isDirectory) {
            "Command working directory does not exist or is not a directory: ${workingDirectory.absolutePath}"
        }
        val outputFile = commandOutputFile(spec)
        val processTreeFile = processTreeFile(outputFile)
        val exitCodeFile = exitCodeFile(outputFile)
        var process: Process? = null
        try {
            process = host.launch(
                spec = spec,
                workingDirectory = workingDirectory,
                outputFile = outputFile,
                processTreeFile = processTreeFile,
                exitCodeFile = exitCodeFile,
            )
            val processHandle = process.toHandle()
            val startedAt = processHandle.info().startInstant().orElseThrow {
                IllegalStateException("OS did not expose start time for command process ${process.pid()}")
            }
            val processTreeId = host.resolveProcessTreeId(process, processTreeFile)
            return LocalRunningCommandProcess(
                process = process,
                processHandle = processHandle,
                startedAt = startedAt.toKotlinInstant(),
                processTree = host.processTree(processTreeId),
                outputArtifact = outputFile,
                exitCodeArtifact = exitCodeFile,
            )
        } catch (error: Throwable) {
            process?.toHandle()?.let { terminateStartupFailure(it, processTreeFile) }
            runCatching { deleteOutputArtifacts(outputFile.absolutePath) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun recover(spec: CommandProcessRecoverySpec): CommandProcessRecovery {
        val outputFile = runCatching { managedOutputFile(spec.outputFile) }
            .getOrElse { return CommandProcessRecovery.Unavailable(it.message ?: "Invalid command output artifact") }
        val exitCode = runCatching { readExitCode(outputFile) }
            .getOrElse { return CommandProcessRecovery.Unavailable(it.message ?: "Invalid command exit artifact") }
        if (exitCode != null) {
            return CommandProcessRecovery.Completed(exitCode)
        }
        val processId = spec.processId
            ?: return CommandProcessRecovery.Unavailable("Persisted command process id is missing")
        val processStartedAt = spec.processStartedAt
            ?: return CommandProcessRecovery.Unavailable("Persisted command process start time is missing")
        val processTreeId = spec.processTreeId
            ?.takeIf { it > 0 }
            ?: return CommandProcessRecovery.Unavailable("Persisted command process tree id is missing")
        val processHandle = ProcessHandle.of(processId).orElse(null)
            ?: return CommandProcessRecovery.Unavailable("Command process $processId is no longer running")
        val actualStartedAt = processHandle.info().startInstant().orElse(null)?.toKotlinInstant()
            ?: return CommandProcessRecovery.Unavailable("OS did not expose start time for command process $processId")
        if (actualStartedAt != processStartedAt) {
            return CommandProcessRecovery.Unavailable("Command process id $processId was reused by another process")
        }
        if (!processHandle.isAlive) {
            return readExitCode(outputFile)
                ?.let(CommandProcessRecovery::Completed)
                ?: CommandProcessRecovery.Unavailable("Command process $processId stopped without an exit artifact")
        }
        val processTree = runCatching { host.processTree(processTreeId) }
            .getOrElse {
                return CommandProcessRecovery.Unavailable(
                    it.message ?: "Command process tree $processTreeId cannot be recovered"
                )
            }
        val runningProcess = LocalRunningCommandProcess(
            process = null,
            processHandle = processHandle,
            startedAt = actualStartedAt,
            processTree = processTree,
            outputArtifact = outputFile,
            exitCodeArtifact = exitCodeFile(outputFile),
        )
        return if (outputFile.isFile) {
            CommandProcessRecovery.Running(runningProcess)
        } else {
            CommandProcessRecovery.UnrecoverableRunning(
                process = runningProcess,
                reason = "Command output artifact is missing: ${outputFile.absolutePath}",
            )
        }
    }

    override fun deleteOutputArtifacts(outputFile: String) {
        val outputArtifact = managedOutputFile(outputFile)
        outputArtifacts(outputArtifact).forEach { artifact ->
            check(!artifact.exists() || artifact.delete()) {
                "Cannot delete command output artifact: ${artifact.absolutePath}"
            }
        }
    }

    override fun garbageCollectOutputArtifacts(retainedOutputFiles: Set<String>) {
        val retained = retainedOutputFiles.mapNotNullTo(mutableSetOf()) { path ->
            runCatching { managedOutputFile(path).absolutePath }.getOrNull()
        }
        outputDirectory().listFiles().orEmpty()
            .mapNotNull(::outputFileForArtifact)
            .distinctBy { it.absolutePath }
            .filterNot { it.absolutePath in retained }
            .forEach { deleteOutputArtifacts(it.absolutePath) }
    }

    private fun terminateStartupFailure(processHandle: ProcessHandle, processTreeFile: File) {
        sequenceOf(processTreeFile, File("${processTreeFile.absolutePath}.tmp"))
            .mapNotNull(::readPositiveLong)
            .firstOrNull()
            ?.let { processTreeId ->
                runCatching { host.processTree(processTreeId).terminate(processHandle) }
            }
        forceTerminateHandleTree(processHandle)
    }

    private fun commandOutputFile(spec: CommandProcessSpec): File = File(
        outputDirectory(),
        "command-${System.currentTimeMillis()}-${spec.command.sha256Prefix()}-${spec.taskId.value.take(8)}.log",
    ).apply {
        check(createNewFile()) { "Command output artifact already exists: $absolutePath" }
    }

    private fun outputDirectory(): File = File(gromozekaHome(), OUTPUT_DIRECTORY_NAME)
        .absoluteFile
        .normalize()
        .apply {
            check(isDirectory || mkdirs()) { "Cannot create command output directory: $absolutePath" }
        }

    private fun managedOutputFile(path: String): File {
        val outputDirectory = outputDirectory().canonicalFile
        val outputFile = File(path).canonicalFile
        require(outputFile.parentFile == outputDirectory && OUTPUT_FILE_NAME.matches(outputFile.name)) {
            "Command output artifact is outside the managed output directory: ${outputFile.absolutePath}"
        }
        return outputFile
    }

    private fun outputFileForArtifact(artifact: File): File? {
        val match = OUTPUT_ARTIFACT_NAME.matchEntire(artifact.name) ?: return null
        return File(artifact.parentFile, match.groupValues[1]).canonicalFile
    }

    private fun outputArtifacts(outputFile: File): List<File> = listOf(
        outputFile,
        processTreeFile(outputFile),
        File("${processTreeFile(outputFile).absolutePath}.tmp"),
        exitCodeFile(outputFile),
        File("${exitCodeFile(outputFile).absolutePath}.tmp"),
        windowsCommandFile(outputFile),
        windowsWrapperFile(outputFile),
    )

    private fun readExitCode(outputFile: File): Int? {
        val artifact = exitCodeFile(outputFile)
        if (!artifact.isFile) return null
        return artifact.readText().trim().toIntOrNull()
            ?: error("Command exit artifact is invalid: ${artifact.absolutePath}")
    }

    private fun gromozekaHome(): File {
        val configuredHome = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")
        return configuredHome
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".gromozeka")
    }

    private fun String.sha256Prefix(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private class LocalRunningCommandProcess(
        private val process: Process?,
        private val processHandle: ProcessHandle,
        private val startedAt: Instant,
        private val processTree: LocalProcessTree,
        private val outputArtifact: File,
        private val exitCodeArtifact: File,
    ) : RunningCommandProcess {
        override val processId: Long
            get() = processHandle.pid()

        override val processStartedAt: Instant
            get() = startedAt

        override val processTreeId: Long
            get() = processTree.id

        override val outputFile: String
            get() = outputArtifact.absolutePath

        override fun isAlive(): Boolean = processHandle.isAlive

        override fun waitFor(timeoutMillis: Long): Boolean = when {
            process != null -> process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            !processHandle.isAlive -> true
            else -> {
                Thread.sleep(timeoutMillis)
                !processHandle.isAlive
            }
        }

        override fun exitCode(): Int {
            process?.let { return it.exitValue() }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_ARTIFACT_WAIT_MILLIS)
            while (System.nanoTime() < deadline) {
                exitCodeArtifact.takeIf(File::isFile)
                    ?.readText()
                    ?.trim()
                    ?.toIntOrNull()
                    ?.let { return it }
                Thread.sleep(STARTUP_POLL_MILLIS)
            }
            error("Exit code artifact is unavailable after reconnecting to process $processId")
        }

        override fun terminateTree() {
            processTree.terminate(processHandle)
        }
    }

    private companion object {
        const val OUTPUT_DIRECTORY_NAME = "tool-outputs"
        val OUTPUT_FILE_NAME = Regex("command-[A-Za-z0-9-]+\\.log")
        val OUTPUT_ARTIFACT_NAME = Regex(
            "(command-[A-Za-z0-9-]+\\.log)" +
                "(?:\\.(?:(?:tree|exit)(?:\\.tmp)?|(?:command|wrapper)\\.cmd))?"
        )
    }
}

internal interface LocalCommandHost {
    fun launch(
        spec: CommandProcessSpec,
        workingDirectory: File,
        outputFile: File,
        processTreeFile: File,
        exitCodeFile: File,
    ): Process

    fun resolveProcessTreeId(process: Process, processTreeFile: File): Long

    fun processTree(id: Long): LocalProcessTree
}

internal interface LocalProcessTree {
    val id: Long

    fun isAlive(processHandle: ProcessHandle): Boolean

    fun terminate(processHandle: ProcessHandle)
}

internal fun currentLocalCommandHost(
    osName: String = System.getProperty("os.name"),
): LocalCommandHost {
    val normalized = osName.lowercase()
    return when {
        normalized.contains("windows") -> WindowsLocalCommandHost()
        normalized.contains("mac") -> PosixLocalCommandHost.forMacOS()
        normalized.contains("linux") -> PosixLocalCommandHost.forLinux()
        else -> error("Managed command execution is not supported on $osName")
    }
}

internal class PosixLocalCommandHost private constructor(
    private val launcherMode: String,
    private val launcherExecutable: String?,
    private val killExecutable: File,
) : LocalCommandHost {
    override fun launch(
        spec: CommandProcessSpec,
        workingDirectory: File,
        outputFile: File,
        processTreeFile: File,
        exitCodeFile: File,
    ): Process = ProcessBuilder(
        POSIX_SHELL_PATH,
        "-c",
        POSIX_COMMAND_WRAPPER,
        "gromozeka-command",
        spec.command,
        processTreeFile.absolutePath,
        exitCodeFile.absolutePath,
        launcherMode,
        launcherExecutable.orEmpty(),
    )
        .directory(workingDirectory)
        .redirectErrorStream(true)
        .redirectOutput(outputFile)
        .start()

    override fun resolveProcessTreeId(process: Process, processTreeFile: File): Long =
        awaitPositiveLong(processTreeFile, STARTUP_HANDSHAKE_MILLIS)

    override fun processTree(id: Long): LocalProcessTree =
        PosixProcessTree(id, killExecutable)

    companion object {
        fun forMacOS(): PosixLocalCommandHost =
            PosixLocalCommandHost(
                launcherMode = "job-control",
                launcherExecutable = null,
                killExecutable = findExecutable(POSIX_KILL_EXECUTABLE_CANDIDATES, "POSIX kill"),
            )

        fun forLinux(): PosixLocalCommandHost =
            PosixLocalCommandHost(
                launcherMode = "setsid",
                launcherExecutable = findExecutable(SETSID_EXECUTABLE_CANDIDATES, "setsid").absolutePath,
                killExecutable = findExecutable(POSIX_KILL_EXECUTABLE_CANDIDATES, "POSIX kill"),
            )
    }
}

internal class WindowsLocalCommandHost(
    private val commandInterpreter: String = windowsCommandInterpreter(),
    private val taskkillExecutable: String = windowsTaskkillExecutable(),
) : LocalCommandHost {
    override fun launch(
        spec: CommandProcessSpec,
        workingDirectory: File,
        outputFile: File,
        processTreeFile: File,
        exitCodeFile: File,
    ): Process = prepareProcessBuilder(
        command = spec.command,
        workingDirectory = workingDirectory,
        outputFile = outputFile,
        exitCodeFile = exitCodeFile,
    ).start()

    internal fun prepareProcessBuilder(
        command: String,
        workingDirectory: File,
        outputFile: File,
        exitCodeFile: File,
    ): ProcessBuilder {
        val commandFile = windowsCommandFile(outputFile)
        val wrapperFile = windowsWrapperFile(outputFile)
        commandFile.writeText(command.toWindowsCommandFile(), StandardCharsets.UTF_8)
        wrapperFile.writeText(WINDOWS_COMMAND_WRAPPER, StandardCharsets.UTF_8)
        return ProcessBuilder(
            commandInterpreter,
            "/D",
            "/Q",
            "/V:OFF",
            "/C",
            wrapperFile.absolutePath,
        )
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .apply {
                environment()[WINDOWS_COMMAND_FILE_ENV] = commandFile.absolutePath
                environment()[WINDOWS_EXIT_FILE_ENV] = exitCodeFile.absolutePath
            }
    }

    override fun resolveProcessTreeId(process: Process, processTreeFile: File): Long =
        process.pid().also { processTreeId ->
            writePositiveLongAtomically(processTreeFile, processTreeId)
        }

    override fun processTree(id: Long): LocalProcessTree =
        WindowsProcessTree(id, taskkillExecutable)
}

private class PosixProcessTree(
    override val id: Long,
    private val killExecutable: File,
) : LocalProcessTree {
    override fun isAlive(processHandle: ProcessHandle): Boolean =
        processHandle.isAlive || processGroupIsAlive(killExecutable, id)

    override fun terminate(processHandle: ProcessHandle) {
        signalProcessGroup(killExecutable, id, "TERM")
        waitUntilStopped(processHandle, TERMINATION_GRACE_MILLIS)
        if (processGroupIsAlive(killExecutable, id)) {
            signalProcessGroup(killExecutable, id, "KILL")
        }
        waitUntilStopped(processHandle, FORCE_TERMINATION_GRACE_MILLIS)
        if (processHandle.isAlive) {
            processHandle.destroyForcibly()
        }
        waitUntilStopped(processHandle, FORCE_TERMINATION_GRACE_MILLIS)
        check(!isAlive(processHandle)) {
            "Failed to terminate command process tree $id"
        }
    }

    private fun waitUntilStopped(processHandle: ProcessHandle, timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (isAlive(processHandle) && System.nanoTime() < deadline) {
            Thread.sleep(TERMINATION_POLL_MILLIS)
        }
    }
}

private class WindowsProcessTree(
    override val id: Long,
    private val taskkillExecutable: String,
) : LocalProcessTree {
    override fun isAlive(processHandle: ProcessHandle): Boolean =
        processHandle.isAlive

    override fun terminate(processHandle: ProcessHandle) {
        require(processHandle.pid() == id) {
            "Windows command process tree $id does not match root process ${processHandle.pid()}"
        }
        val descendants = processHandle.descendants().toList()
        if (processHandle.isAlive) {
            runCatching { taskkill() }
        }
        waitUntilStopped(processHandle, descendants, FORCE_TERMINATION_GRACE_MILLIS)
        if (processHandle.isAlive || descendants.any(ProcessHandle::isAlive)) {
            descendants.asReversed().forEach { descendant ->
                if (descendant.isAlive) descendant.destroyForcibly()
            }
            if (processHandle.isAlive) processHandle.destroyForcibly()
        }
        waitUntilStopped(processHandle, descendants, FORCE_TERMINATION_GRACE_MILLIS)
        check(!processHandle.isAlive && descendants.none(ProcessHandle::isAlive)) {
            "Failed to terminate Windows command process tree $id"
        }
    }

    private fun taskkill(): Boolean =
        ProcessBuilder(taskkillExecutable, "/PID", id.toString(), "/T", "/F")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .let { process ->
                check(process.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "Timed out while terminating Windows command process tree $id"
                }
                process.exitValue() == 0
            }

    private fun waitUntilStopped(
        processHandle: ProcessHandle,
        descendants: List<ProcessHandle>,
        timeoutMillis: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (
            (processHandle.isAlive || descendants.any(ProcessHandle::isAlive)) &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(TERMINATION_POLL_MILLIS)
        }
    }
}

internal fun String.toWindowsCommandFile(): String {
    val normalized = replace("\r\n", "\n").replace('\r', '\n')
    val terminated = if (normalized.endsWith('\n')) normalized else "$normalized\n"
    return terminated.replace("\n", "\r\n")
}

private fun processTreeFile(outputFile: File): File = File("${outputFile.absolutePath}.tree")

private fun exitCodeFile(outputFile: File): File = File("${outputFile.absolutePath}.exit")

private fun windowsCommandFile(outputFile: File): File = File("${outputFile.absolutePath}.command.cmd")

private fun windowsWrapperFile(outputFile: File): File = File("${outputFile.absolutePath}.wrapper.cmd")

private fun awaitPositiveLong(file: File, timeoutMillis: Long): Long {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        readPositiveLong(file)?.let { return it }
        Thread.sleep(STARTUP_POLL_MILLIS)
    }
    error("Command process-tree handshake timed out: ${file.absolutePath}")
}

private fun readPositiveLong(file: File): Long? =
    file.takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

private fun writePositiveLongAtomically(file: File, value: Long) {
    require(value > 0) { "Command process tree id must be positive" }
    val temporaryFile = File("${file.absolutePath}.tmp")
    temporaryFile.writeText("$value\n", StandardCharsets.UTF_8)
    Files.move(
        temporaryFile.toPath(),
        file.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun findExecutable(candidates: List<String>, description: String): File =
    candidates
        .map(::File)
        .firstOrNull { it.isFile && it.canExecute() }
        ?: error("$description executable was not found")

private fun windowsCommandInterpreter(): String =
    System.getenv("ComSpec")
        ?.takeIf(String::isNotBlank)
        ?: "cmd.exe"

private fun windowsTaskkillExecutable(): String =
    System.getenv("SystemRoot")
        ?.takeIf(String::isNotBlank)
        ?.let { File(it, "System32/taskkill.exe") }
        ?.takeIf(File::isFile)
        ?.absolutePath
        ?: "taskkill.exe"

private fun signalProcessGroup(killExecutable: File, processTreeId: Long, signal: String): Boolean =
    ProcessBuilder(killExecutable.absolutePath, "-$signal", "-$processTreeId")
        .redirectErrorStream(true)
        .start()
        .let { process ->
            check(process.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Timed out while sending SIG$signal to process group $processTreeId"
            }
            process.exitValue() == 0
        }

private fun processGroupIsAlive(killExecutable: File, processTreeId: Long): Boolean =
    signalProcessGroup(killExecutable, processTreeId, "0")

private fun forceTerminateHandleTree(processHandle: ProcessHandle) {
    val descendants = processHandle.descendants().toList()
    descendants.asReversed().forEach { descendant ->
        if (descendant.isAlive) descendant.destroyForcibly()
    }
    if (processHandle.isAlive) processHandle.destroyForcibly()
}

private fun java.time.Instant.toKotlinInstant(): Instant =
    Instant.fromEpochMilliseconds(toEpochMilli())

private const val POSIX_SHELL_PATH = "/bin/sh"
private const val STARTUP_HANDSHAKE_MILLIS = 2_000L
private const val STARTUP_POLL_MILLIS = 10L
private const val EXIT_ARTIFACT_WAIT_MILLIS = 2_000L
private const val TERMINATION_GRACE_MILLIS = 1_000L
private const val FORCE_TERMINATION_GRACE_MILLIS = 1_000L
private const val TERMINATION_POLL_MILLIS = 25L
private const val TASKKILL_TIMEOUT_SECONDS = 5L
private const val WINDOWS_COMMAND_FILE_ENV = "GROMOZEKA_COMMAND_FILE"
private const val WINDOWS_EXIT_FILE_ENV = "GROMOZEKA_EXIT_FILE"
private val POSIX_KILL_EXECUTABLE_CANDIDATES = listOf("/bin/kill", "/usr/bin/kill")
private val SETSID_EXECUTABLE_CANDIDATES = listOf("/usr/bin/setsid", "/bin/setsid")
private val POSIX_COMMAND_WRAPPER = """
    case "${'$'}4" in
        job-control)
            set -m || exit 125
            /bin/sh -c "${'$'}1" &
            command_pid=${'$'}!
            set +m
            ;;
        setsid)
            "${'$'}5" /bin/sh -c "${'$'}1" &
            command_pid=${'$'}!
            ;;
        *)
            exit 125
            ;;
    esac
    printf '%s\n' "${'$'}command_pid" > "${'$'}2.tmp"
    /bin/mv "${'$'}2.tmp" "${'$'}2"
    wait "${'$'}command_pid" 2>/dev/null
    exit_code=${'$'}?
    printf '%s\n' "${'$'}exit_code" > "${'$'}3.tmp"
    /bin/mv "${'$'}3.tmp" "${'$'}3"
    exit "${'$'}exit_code"
""".trimIndent()
private val WINDOWS_COMMAND_WRAPPER = """
    @echo off
    setlocal DisableDelayedExpansion
    chcp 65001 >NUL
    "%ComSpec%" /D /Q /V:OFF /C call "%GROMOZEKA_COMMAND_FILE%"
    set "GROMOZEKA_COMMAND_EXIT_CODE=%ERRORLEVEL%"
    > "%GROMOZEKA_EXIT_FILE%.tmp" echo %GROMOZEKA_COMMAND_EXIT_CODE%
    move /Y "%GROMOZEKA_EXIT_FILE%.tmp" "%GROMOZEKA_EXIT_FILE%" >NUL || exit /B 125
    del /Q "%GROMOZEKA_COMMAND_FILE%" >NUL 2>&1
    del /Q "%~f0" >NUL 2>&1
    exit /B %GROMOZEKA_COMMAND_EXIT_CODE%
""".trimIndent().replace("\n", "\r\n") + "\r\n"
