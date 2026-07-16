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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Service
class LocalCommandProcessRunner : CommandProcessRunner {
    override fun start(spec: CommandProcessSpec): RunningCommandProcess {
        requireSupportedPlatform()
        val workingDirectory = File(spec.workingDirectory)
        require(workingDirectory.isDirectory) {
            "Command working directory does not exist or is not a directory: ${workingDirectory.absolutePath}"
        }
        val outputFile = commandOutputFile(spec)
        val processGroupFile = processGroupFile(outputFile)
        val exitCodeFile = exitCodeFile(outputFile)
        val groupLauncher = processGroupLauncher()
        val process = ProcessBuilder(
            SHELL_PATH,
            "-c",
            COMMAND_WRAPPER,
            "gromozeka-command",
            spec.command,
            processGroupFile.absolutePath,
            exitCodeFile.absolutePath,
            groupLauncher.mode,
            groupLauncher.executable.orEmpty(),
        )
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .start()
        val processHandle = process.toHandle()
        try {
            val startedAt = processHandle.info().startInstant().orElseThrow {
                IllegalStateException("OS did not expose start time for command process ${process.pid()}")
            }
            val processGroupId = awaitPositiveLong(processGroupFile, STARTUP_HANDSHAKE_MILLIS)
            return LocalRunningCommandProcess(
                process = process,
                processHandle = processHandle,
                startedAt = startedAt.toKotlinInstant(),
                processGroupId = processGroupId,
                outputArtifact = outputFile,
                exitCodeArtifact = exitCodeFile,
                killExecutable = killExecutable(),
            )
        } catch (error: Throwable) {
            terminateStartupFailure(processHandle, processGroupFile)
            runCatching { deleteOutputArtifacts(outputFile.absolutePath) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun recover(spec: CommandProcessRecoverySpec): CommandProcessRecovery {
        requireSupportedPlatform()
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
        val processGroupId = spec.processGroupId
            ?.takeIf { it > 0 }
            ?: return CommandProcessRecovery.Unavailable("Persisted command process group id is missing")
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
        val runningProcess = LocalRunningCommandProcess(
            process = null,
            processHandle = processHandle,
            startedAt = actualStartedAt,
            processGroupId = processGroupId,
            outputArtifact = outputFile,
            exitCodeArtifact = exitCodeFile(outputFile),
            killExecutable = killExecutable(),
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

    private fun terminateStartupFailure(processHandle: ProcessHandle, processGroupFile: File) {
        val processGroupId = sequenceOf(processGroupFile, File("${processGroupFile.absolutePath}.tmp"))
            .mapNotNull { artifact -> artifact.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() }
            .firstOrNull { it > 0 }
        if (processGroupId != null) {
            runCatching { signalProcessGroup(killExecutable(), processGroupId, "KILL") }
        }
        processHandle.descendants().toList().asReversed().forEach { descendant ->
            if (descendant.isAlive) descendant.destroyForcibly()
        }
        if (processHandle.isAlive) processHandle.destroyForcibly()
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
        processGroupFile(outputFile),
        File("${processGroupFile(outputFile).absolutePath}.tmp"),
        exitCodeFile(outputFile),
        File("${exitCodeFile(outputFile).absolutePath}.tmp"),
    )

    private fun processGroupFile(outputFile: File): File = File("${outputFile.absolutePath}.pgid")

    private fun exitCodeFile(outputFile: File): File = File("${outputFile.absolutePath}.exit")

    private fun readExitCode(outputFile: File): Int? {
        val artifact = exitCodeFile(outputFile)
        if (!artifact.isFile) return null
        return artifact.readText().trim().toIntOrNull()
            ?: error("Command exit artifact is invalid: ${artifact.absolutePath}")
    }

    private fun awaitPositiveLong(file: File, timeoutMillis: Long): Long {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            file.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            Thread.sleep(STARTUP_POLL_MILLIS)
        }
        error("Command process-group handshake timed out: ${file.absolutePath}")
    }

    private fun gromozekaHome(): File {
        val configuredHome = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")
        return configuredHome
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".gromozeka")
    }

    private fun requireSupportedPlatform() {
        val osName = System.getProperty("os.name").lowercase()
        require(osName.contains("mac") || osName.contains("linux")) {
            "Managed command execution is supported only on macOS and Linux, current OS: $osName"
        }
    }

    private fun processGroupLauncher(): ProcessGroupLauncher {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("mac")) return ProcessGroupLauncher("job-control", null)
        val setsid = SETSID_EXECUTABLE_CANDIDATES
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
            ?: error("Linux managed command execution requires the setsid executable")
        return ProcessGroupLauncher("setsid", setsid.absolutePath)
    }

    private fun killExecutable(): File = KILL_EXECUTABLE_CANDIDATES
        .map(::File)
        .firstOrNull { it.isFile && it.canExecute() }
        ?: error("POSIX kill executable was not found")

    private fun String.sha256Prefix(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private class LocalRunningCommandProcess(
        private val process: Process?,
        private val processHandle: ProcessHandle,
        private val startedAt: Instant,
        override val processGroupId: Long,
        private val outputArtifact: File,
        private val exitCodeArtifact: File,
        private val killExecutable: File,
    ) : RunningCommandProcess {
        override val processId: Long
            get() = processHandle.pid()

        override val processStartedAt: Instant
            get() = startedAt

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
            signalProcessGroup(killExecutable, processGroupId, "TERM")
            waitUntilStopped(TERMINATION_GRACE_MILLIS)
            if (processGroupIsAlive(killExecutable, processGroupId)) {
                signalProcessGroup(killExecutable, processGroupId, "KILL")
            }
            waitUntilStopped(FORCE_TERMINATION_GRACE_MILLIS)
            if (processHandle.isAlive) {
                processHandle.destroyForcibly()
            }
            waitUntilStopped(FORCE_TERMINATION_GRACE_MILLIS)
            check(!processGroupIsAlive(killExecutable, processGroupId) && !processHandle.isAlive) {
                "Failed to terminate command process group $processGroupId"
            }
        }

        private fun waitUntilStopped(timeoutMillis: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while ((processHandle.isAlive || processGroupIsAlive(killExecutable, processGroupId)) &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(TERMINATION_POLL_MILLIS)
            }
        }
    }

    private data class ProcessGroupLauncher(
        val mode: String,
        val executable: String?,
    )

    private companion object {
        const val SHELL_PATH = "/bin/sh"
        const val OUTPUT_DIRECTORY_NAME = "tool-outputs"
        const val STARTUP_HANDSHAKE_MILLIS = 2_000L
        const val STARTUP_POLL_MILLIS = 10L
        const val EXIT_ARTIFACT_WAIT_MILLIS = 2_000L
        const val TERMINATION_GRACE_MILLIS = 1_000L
        const val FORCE_TERMINATION_GRACE_MILLIS = 1_000L
        const val TERMINATION_POLL_MILLIS = 25L
        val KILL_EXECUTABLE_CANDIDATES = listOf("/bin/kill", "/usr/bin/kill")
        val SETSID_EXECUTABLE_CANDIDATES = listOf("/usr/bin/setsid", "/bin/setsid")
        val OUTPUT_FILE_NAME = Regex("command-[A-Za-z0-9-]+\\.log")
        val OUTPUT_ARTIFACT_NAME = Regex("(command-[A-Za-z0-9-]+\\.log)(?:\\.(?:pgid|exit)(?:\\.tmp)?)?")
        val COMMAND_WRAPPER = """
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
    }
}

private fun signalProcessGroup(killExecutable: File, processGroupId: Long, signal: String): Boolean =
    ProcessBuilder(killExecutable.absolutePath, "-$signal", "-$processGroupId")
        .redirectErrorStream(true)
        .start()
        .let { process ->
            check(process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Timed out while sending SIG$signal to process group $processGroupId"
            }
            process.exitValue() == 0
        }

private fun processGroupIsAlive(killExecutable: File, processGroupId: Long): Boolean =
    signalProcessGroup(killExecutable, processGroupId, "0")

private fun java.time.Instant.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(toEpochMilli())
