package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandOutputGarbageCollectionSpec
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.RunningCommandProcess
import kotlin.time.Instant
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalCommandProcessRunnerTest {
    private val startedProcesses = mutableListOf<RunningCommandProcess>()
    private val localRunner = LocalCommandProcessRunner()
    private val runner = object : CommandProcessRunner by localRunner {
        override fun start(spec: CommandProcessSpec): RunningCommandProcess =
            localRunner.start(spec).also(startedProcesses::add)
    }
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    @Test
    fun `runner drains large merged output into artifact`() {
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "large-output-task",
                    command = platformCommand(
                        posix = "i=0; while [ ${'$'}i -lt 20000 ]; do echo line-${'$'}i; i=${'$'}((i+1)); done",
                        windows = "powershell.exe -NoProfile -NonInteractive -Command \"" +
                            "0..19999 | ForEach-Object { [Console]::WriteLine('line-' + ${'$'}_) }\"",
                    ),
                    workingDirectory = home.absolutePath,
                )
            )

            assertTrue(process.waitFor(10_000))
            assertTrue(process.exitCode() == 0)
            val output = File(process.outputFile).readText()
            assertTrue(output.startsWith("line-0"))
            assertTrue(output.contains("line-19999"))
        }
    }

    @Test
    fun `runner streams standard input and captures standard error separately`() {
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "stream-input-task",
                    command = platformCommand(
                        posix = "grep match; printf diagnostic >&2",
                        windows = "findstr /C:match & echo diagnostic 1>&2",
                    ),
                    workingDirectory = home.absolutePath,
                    captureStandardErrorSeparately = true,
                )
            )

            assertTrue(process.acceptsInput)
            process.writeInput("skip\nmatch\n".toByteArray())
            process.closeInput()

            assertTrue(process.waitFor(5_000))
            assertEquals(0, process.exitCode())
            assertTrue(File(process.outputFile).readText().contains("match"))
            assertTrue(File(requireNotNull(process.errorFile)).readText().contains("diagnostic"))
        }
    }

    @Test
    fun `runner injects command environment without changing command text`() {
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "secret-environment-task",
                    command = platformCommand(
                        posix = "printf '%s' \"${'$'}GH_TOKEN\"",
                        windows = "set /p \"=%GH_TOKEN%\" <nul\nexit /B 0",
                    ),
                    workingDirectory = home.absolutePath,
                    environment = mapOf("GH_TOKEN" to "actual-token"),
                )
            )

            assertTrue(process.waitFor(5_000))
            assertEquals(0, process.exitCode())
            assertEquals("actual-token", File(process.outputFile).readText())
        }
    }

    @Test
    fun `runner terminates root and child processes`() {
        withTemporaryGromozekaHome { home ->
            val childPidFile = File(home, "child.pid")
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "process-tree-task",
                    command = platformCommand(
                        posix = "sleep 30 & child=${'$'}!; echo ${'$'}child > '${childPidFile.absolutePath}'; wait",
                        windows = windowsProcessTreeCommand(childPidFile),
                    ),
                    workingDirectory = home.absolutePath,
                )
            )
            waitUntil(5_000) { childPidFile.exists() && childPidFile.readText().trim().isNotEmpty() }
            val childPid = childPidFile.readText().trim().toLong()

            assertTrue(ProcessHandle.of(process.processId).orElseThrow().isAlive)
            assertTrue(ProcessHandle.of(childPid).orElseThrow().isAlive)

            process.terminateTree()

            waitUntil(5_000) {
                ProcessHandle.of(process.processId).map { !it.isAlive }.orElse(true) &&
                    ProcessHandle.of(childPid).map { !it.isAlive }.orElse(true)
            }
            assertFalse(ProcessHandle.of(process.processId).map(ProcessHandle::isAlive).orElse(false))
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
        }
    }

    @Test
    fun `Worker lifetime binding terminates the managed process tree when its lifeline closes`() {
        withTemporaryGromozekaHome { home ->
            val childPidFile = File(home, "bound-child.pid")
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "worker-bound-process-tree-task",
                    command = platformCommand(
                        posix = "sleep 30 & child=${'$'}!; echo ${'$'}child > '${childPidFile.absolutePath}'; wait",
                        windows = windowsProcessTreeCommand(childPidFile),
                    ),
                    workingDirectory = home.absolutePath,
                    lifetime = CommandTask.ProcessLifetime.RESUMABLE,
                )
            )
            waitUntil(5_000) { childPidFile.exists() && childPidFile.readText().trim().isNotEmpty() }
            val childPid = childPidFile.readText().trim().toLong()
            val binding = currentLocalCommandHost().bindToWorker(
                processTreeId = process.processTreeId,
                outputFile = File(process.outputFile),
            )

            binding.terminateCommand()

            waitUntil(5_000) {
                ProcessHandle.of(process.processId).map { !it.isAlive }.orElse(true) &&
                    ProcessHandle.of(childPid).map { !it.isAlive }.orElse(true)
            }
            assertFalse(ProcessHandle.of(process.processId).map(ProcessHandle::isAlive).orElse(false))
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
        }
    }

    @Test
    fun `Worker-bound process tree stops after abrupt Worker JVM exit`() {
        verifyAbruptWorkerExit(CommandTask.ProcessLifetime.WORKER_BOUND)
    }

    @Test
    fun `resumable process tree survives abrupt Worker JVM exit and can be recovered`() {
        verifyAbruptWorkerExit(CommandTask.ProcessLifetime.RESUMABLE)
    }

    private fun verifyAbruptWorkerExit(lifetime: CommandTask.ProcessLifetime) {
        assumeFalse("POSIX process groups are required", isWindows)
        withTemporaryGromozekaHome { home ->
            val identityFile = File(home, "abrupt-worker-exit-processes")
            val helperOutput = File(home, "abrupt-worker-exit-helper.log")
            val helper = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp",
                System.getProperty("java.class.path"),
                CommandWorkerLifetimeTestProcess::class.java.name,
                home.absolutePath,
                identityFile.absolutePath,
                lifetime.name,
            )
                .redirectErrorStream(true)
                .redirectOutput(helperOutput)
                .start()
            var processTreeId: Long? = null
            var childPid: Long? = null
            try {
                waitUntil(10_000) { identityFile.isFile && identityFile.readText().isNotBlank() }
                val identity = identityFile.readLines()
                val commandProcessTreeId = identity[0].toLong()
                val commandChildPid = identity[1].toLong()
                processTreeId = commandProcessTreeId
                childPid = commandChildPid

                helper.destroyForcibly()
                assertTrue(helper.waitFor(5_000, java.util.concurrent.TimeUnit.MILLISECONDS))

                if (lifetime == CommandTask.ProcessLifetime.RESUMABLE) {
                    val recovered = assertIs<CommandProcessRecovery.Running>(
                        runner.recover(
                            CommandProcessRecoverySpec(
                                processId = commandProcessTreeId,
                                processStartedAt = Instant.fromEpochMilliseconds(identity[2].toLong()),
                                processTreeId = commandProcessTreeId,
                                outputFile = identity[3],
                            )
                        )
                    )
                    assertTrue(ProcessHandle.of(commandChildPid).orElseThrow().isAlive)
                    recovered.process.terminateTree()
                }
                waitUntil(10_000) {
                    ProcessHandle.of(commandProcessTreeId).map { !it.isAlive }.orElse(true) &&
                        ProcessHandle.of(commandChildPid).map { !it.isAlive }.orElse(true)
                }
            } finally {
                if (helper.isAlive) helper.destroyForcibly()
                processTreeId?.let { terminateRemainingProcessTree(it) }
                childPid?.let { pid ->
                    ProcessHandle.of(pid).ifPresent { handle ->
                        if (handle.isAlive) handle.destroyForcibly()
                    }
                }
            }
        }
    }

    @Test
    fun `runner terminates descendant created while handling termination`() {
        assumeFalse("POSIX process groups are required", isWindows)
        withTemporaryGromozekaHome { home ->
            val readyFile = File(home, "ready")
            val lateChildPidFile = File(home, "late-child.pid")
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "late-descendant-task",
                    command = "trap 'sleep 30 & late=${'$'}!; echo ${'$'}late > '${lateChildPidFile.absolutePath}'; wait ${'$'}late' TERM; " +
                        "echo ready > '${readyFile.absolutePath}'; while :; do sleep 30; done",
                    workingDirectory = home.absolutePath,
                )
            )
            waitUntil(5_000) { readyFile.isFile }

            process.terminateTree()

            waitUntil(5_000) { lateChildPidFile.isFile && lateChildPidFile.readText().trim().isNotEmpty() }
            val lateChildPid = lateChildPidFile.readText().trim().toLong()
            assertFalse(ProcessHandle.of(lateChildPid).map(ProcessHandle::isAlive).orElse(false))
        }
    }

    @Test
    fun `runner recovers completed command from exit artifact`() {
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "completed-recovery-task",
                    command = platformCommand(
                        posix = "printf recovered; exit 7",
                        windows = "set /P \"=recovered\" <NUL\nexit /B 7",
                    ),
                    workingDirectory = home.absolutePath,
                )
            )
            assertTrue(process.waitFor(5_000))

            val recovery = assertIs<CommandProcessRecovery.Completed>(
                runner.recover(
                    CommandProcessRecoverySpec(
                        processId = process.processId,
                        processStartedAt = process.processStartedAt,
                        processTreeId = process.processTreeId,
                        outputFile = process.outputFile,
                    )
                )
            )

            assertEquals(7, recovery.exitCode)
            assertEquals("recovered", File(process.outputFile).readText())
        }
    }

    @Test
    fun `runner reconnects to a live command with matching process identity`() {
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "live-recovery-task",
                    command = platformCommand(
                        posix = "sleep 30",
                        windows = "ping.exe -n 31 127.0.0.1 >NUL",
                    ),
                    workingDirectory = home.absolutePath,
                    lifetime = CommandTask.ProcessLifetime.RESUMABLE,
                )
            )

            val recovery = assertIs<CommandProcessRecovery.Running>(
                runner.recover(
                    CommandProcessRecoverySpec(
                        processId = process.processId,
                        processStartedAt = process.processStartedAt,
                        processTreeId = process.processTreeId,
                        outputFile = process.outputFile,
                    )
                )
            )

            assertFalse(recovery.process.acceptsInput)
            assertFailsWith<IllegalStateException> {
                recovery.process.writeInput("unavailable".toByteArray())
            }
            recovery.process.terminateTree()
            assertFalse(process.isAlive())
        }
    }

    @Test
    fun `linux runner records the actual session leader as process tree id`() {
        assumeTrue("Linux sessions are required", isLinux)
        withTemporaryGromozekaHome { home ->
            val identityFile = File(home, "process-identity")
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "linux-session-identity-task",
                    command = "pid=${'$'}${'$'}; " +
                        "pgid=${'$'}(ps -o pgid= -p ${'$'}${'$'} | tr -d ' '); " +
                        "sid=${'$'}(ps -o sid= -p ${'$'}${'$'} | tr -d ' '); " +
                        "printf '%s %s %s' \"${'$'}pid\" \"${'$'}pgid\" \"${'$'}sid\" > '${identityFile.absolutePath}'; " +
                        "sleep 30",
                    workingDirectory = home.absolutePath,
                )
            )
            try {
                waitUntil(5_000) { identityFile.isFile && identityFile.readText().isNotBlank() }
                val (pid, processGroupId, sessionId) = identityFile.readText().trim().split(' ').map(String::toLong)

                assertNotEquals(process.processTreeId, pid)
                assertEquals(process.processTreeId, process.processId)
                assertEquals(process.processTreeId, processGroupId)
                assertEquals(process.processTreeId, sessionId)
            } finally {
                if (process.isAlive()) process.terminateTree()
            }
        }
    }

    @Test
    fun `rejected termination does not signal through the Worker lifetime binding`() {
        assumeFalse("POSIX process groups are required", isWindows)
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    executionId = "rejected-termination-task",
                    command = "sleep 30",
                    workingDirectory = home.absolutePath,
                    lifetime = CommandTask.ProcessLifetime.RESUMABLE,
                )
            )
            val binding = currentLocalCommandHost().bindToWorker(process.processTreeId, File(process.outputFile))
            val guardedProcess = LocalCommandProcessRunner.LocalRunningCommandProcess(
                process = null,
                processHandle = ProcessHandle.of(process.processId).orElseThrow(),
                startedAt = process.processStartedAt,
                processTree = PosixProcessTree(
                    id = process.processTreeId,
                    processGroupInspector = PosixProcessGroupInspector { process.processTreeId },
                    signalSender = PosixProcessGroupSignalSender { _, _ -> error("Unexpected signal") },
                ),
                workerLifetimeBinding = binding,
                outputArtifact = File(process.outputFile),
                errorArtifact = null,
                exitCodeArtifact = File("${process.outputFile}.exit"),
            )
            try {
                val error = assertFailsWith<IllegalStateException> { guardedProcess.terminateTree() }
                assertContains(requireNotNull(error.message), "Worker process group")
                assertTrue(process.isAlive(), "The watchdog must not bypass rejected termination")
            } finally {
                if (process.isAlive()) process.terminateTree()
                binding.disarm()
            }
        }
    }

    @Test
    fun `Worker lifetime binding refuses an unisolated process before starting its watchdog`() {
        assumeFalse("POSIX process groups are required", isWindows)
        withTemporaryGromozekaHome { home ->
            val unisolated = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
            try {
                val error = assertFailsWith<IllegalStateException> {
                    currentLocalCommandHost().bindToWorker(unisolated.pid(), File(home, "unused.log"))
                }
                assertContains(requireNotNull(error.message), "belongs to group")
                assertTrue(unisolated.isAlive)
            } finally {
                unisolated.toHandle().descendants().forEach { it.destroyForcibly() }
                unisolated.destroyForcibly()
                unisolated.waitFor()
            }
        }
    }

    @Test
    fun `posix termination refuses the worker process group before sending a signal`() {
        assumeFalse("POSIX process groups are required", isWindows)
        val wrapper = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
        val sentSignals = mutableListOf<String>()
        try {
            val unsafeGroupId = wrapper.pid()
            val tree = PosixProcessTree(
                id = unsafeGroupId,
                processGroupInspector = PosixProcessGroupInspector { processId ->
                    when (processId) {
                        ProcessHandle.current().pid(), wrapper.pid() -> unsafeGroupId
                        else -> null
                    }
                },
                signalSender = PosixProcessGroupSignalSender { _, signal ->
                    sentSignals += signal
                    true
                },
            )

            val error = assertFailsWith<IllegalStateException> {
                tree.terminate(wrapper.toHandle())
            }

            assertContains(requireNotNull(error.message), "Worker process group")
            assertTrue(sentSignals.isEmpty())
        } finally {
            wrapper.destroyForcibly()
            wrapper.waitFor()
        }
    }

    @Test
    fun `posix termination refuses special process group ids before sending a signal`() {
        assumeFalse("POSIX process groups are required", isWindows)
        val wrapper = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
        val sentSignals = mutableListOf<String>()
        try {
            listOf(-1L, 0L, 1L).forEach { unsafeGroupId ->
                val tree = PosixProcessTree(
                    id = unsafeGroupId,
                    processGroupInspector = PosixProcessGroupInspector { error("Unexpected inspection") },
                    signalSender = PosixProcessGroupSignalSender { _, signal ->
                        sentSignals += signal
                        true
                    },
                )

                val error = assertFailsWith<IllegalStateException> {
                    tree.terminate(wrapper.toHandle())
                }

                assertContains(requireNotNull(error.message), "unsafe command process group")
            }
            assertTrue(sentSignals.isEmpty())
        } finally {
            wrapper.destroyForcibly()
            wrapper.waitFor()
        }
    }

    @Test
    fun `posix termination refuses a process group that does not match the command root`() {
        assumeFalse("POSIX process groups are required", isWindows)
        val wrapper = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
        val unrelated = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
        val sentSignals = mutableListOf<String>()
        try {
            val tree = PosixProcessTree(
                id = unrelated.pid(),
                processGroupInspector = PosixProcessGroupInspector { processId -> processId },
                signalSender = PosixProcessGroupSignalSender { _, signal ->
                    sentSignals += signal
                    true
                },
            )

            val error = assertFailsWith<IllegalStateException> {
                tree.terminate(wrapper.toHandle())
            }

            assertContains(requireNotNull(error.message), "does not match command root")
            assertTrue(sentSignals.isEmpty())
        } finally {
            wrapper.destroyForcibly()
            unrelated.destroyForcibly()
            wrapper.waitFor()
            unrelated.waitFor()
        }
    }

    @Test
    fun `runner garbage collects only unreferenced output artifacts`() {
        withTemporaryGromozekaHome { home ->
            val retained = runner.start(
                CommandProcessSpec(
                    executionId = "retained-output-task",
                    command = platformCommand(
                        posix = "printf retained",
                        windows = "<NUL set /P =retained",
                    ),
                    workingDirectory = home.absolutePath,
                )
            )
            val orphaned = runner.start(
                CommandProcessSpec(
                    executionId = "orphaned-output-task",
                    command = platformCommand(
                        posix = "printf orphaned",
                        windows = "<NUL set /P =orphaned",
                    ),
                    workingDirectory = home.absolutePath,
                )
            )
            assertTrue(retained.waitFor(5_000))
            assertTrue(orphaned.waitFor(5_000))

            runner.garbageCollectOutputArtifacts(
                CommandOutputGarbageCollectionSpec(
                    referencedOutputFiles = setOf(retained.outputFile),
                    protectedOutputFiles = emptySet(),
                    expireBefore = Instant.fromEpochMilliseconds(0),
                    maxTotalBytes = Long.MAX_VALUE,
                )
            )

            assertTrue(File(retained.outputFile).isFile)
            assertFalse(File(orphaned.outputFile).exists())
            assertFalse(File("${orphaned.outputFile}.tree").exists())
            assertFalse(File("${orphaned.outputFile}.start").exists())
            assertFalse(File("${orphaned.outputFile}.exit").exists())
            assertFalse(File("${orphaned.outputFile}.command.cmd").exists())
            assertFalse(File("${orphaned.outputFile}.wrapper.cmd").exists())
            assertFalse(File("${orphaned.outputFile}.watchdog.cmd").exists())
        }
    }

    @Test
    fun `runner expires old referenced output but keeps recent output`() {
        withTemporaryGromozekaHome { home ->
            val expired = completedProcess(home, "expired-output-task", "expired")
            val recent = completedProcess(home, "recent-output-task", "recent")
            artifactsFor(expired.outputFile).forEach { it.setLastModified(1_000) }
            artifactsFor(recent.outputFile).forEach { it.setLastModified(3_000) }

            val result = runner.garbageCollectOutputArtifacts(
                CommandOutputGarbageCollectionSpec(
                    referencedOutputFiles = setOf(expired.outputFile, recent.outputFile),
                    protectedOutputFiles = emptySet(),
                    expireBefore = Instant.fromEpochMilliseconds(2_000),
                    maxTotalBytes = Long.MAX_VALUE,
                )
            )

            assertEquals(setOf(File(expired.outputFile).canonicalPath), result.deletedOutputFiles)
            assertFalse(File(expired.outputFile).exists())
            assertTrue(File(recent.outputFile).isFile)
        }
    }

    @Test
    fun `runner applies global quota oldest first and never deletes protected output`() {
        withTemporaryGromozekaHome { home ->
            val protected = completedProcess(home, "protected-output-task", "protected")
            val oldest = completedProcess(home, "oldest-output-task", "x".repeat(256))
            val newest = completedProcess(home, "newest-output-task", "newest")
            artifactsFor(oldest.outputFile).forEach { it.setLastModified(1_000) }
            artifactsFor(newest.outputFile).forEach { it.setLastModified(2_000) }
            artifactsFor(protected.outputFile).forEach { it.setLastModified(3_000) }
            val expectedRetainedBytes =
                artifactsFor(protected.outputFile).sumOf(File::length) +
                    artifactsFor(newest.outputFile).sumOf(File::length)

            val result = runner.garbageCollectOutputArtifacts(
                CommandOutputGarbageCollectionSpec(
                    referencedOutputFiles = setOf(protected.outputFile, oldest.outputFile, newest.outputFile),
                    protectedOutputFiles = setOf(protected.outputFile),
                    expireBefore = Instant.fromEpochMilliseconds(0),
                    maxTotalBytes = expectedRetainedBytes,
                )
            )

            assertEquals(setOf(File(oldest.outputFile).canonicalPath), result.deletedOutputFiles)
            assertTrue(File(protected.outputFile).isFile)
            assertFalse(File(oldest.outputFile).exists())
            assertTrue(File(newest.outputFile).isFile)
            assertEquals(expectedRetainedBytes, result.retainedBytes)
            assertEquals(artifactsFor(protected.outputFile).sumOf(File::length), result.protectedBytes)
        }
    }

    @Test
    fun `windows host keeps long command out of cmd arguments`() {
        val directory = Files.createTempDirectory("gromozeka-windows-command-test-").toFile()
        try {
            val outputFile = File(directory, "command-test.log").apply { createNewFile() }
            val exitCodeFile = File("${outputFile.absolutePath}.exit")
            val command = "echo " + "x".repeat(12_000)
            val processBuilder = WindowsLocalCommandHost(
                commandInterpreter = "cmd.exe",
                taskkillExecutable = "taskkill.exe",
            ).prepareProcessBuilder(
                command = command,
                workingDirectory = directory,
                outputFile = outputFile,
                exitCodeFile = exitCodeFile,
                injectedEnvironment = mapOf("GH_TOKEN" to "actual-token"),
            )

            val commandFile = File("${outputFile.absolutePath}.command.cmd")
            val wrapperFile = File("${outputFile.absolutePath}.wrapper.cmd")
            assertEquals("cmd.exe", processBuilder.command().first())
            assertEquals(wrapperFile.absolutePath, processBuilder.command().last())
            assertFalse(processBuilder.command().joinToString(" ").contains(command))
            assertEquals(command.toWindowsCommandFile(), commandFile.readText(Charsets.UTF_8))
            val wrapperText = wrapperFile.readText(Charsets.UTF_8)
            assertContains(wrapperText, "%GROMOZEKA_COMMAND_FILE%")
            assertContains(wrapperText, "del /Q \"%GROMOZEKA_COMMAND_FILE%\"")
            assertFalse(wrapperText.contains("%~f0"))
            assertEquals(commandFile.absolutePath, processBuilder.environment()["GROMOZEKA_COMMAND_FILE"])
            assertEquals(exitCodeFile.absolutePath, processBuilder.environment()["GROMOZEKA_EXIT_FILE"])
            assertEquals("actual-token", processBuilder.environment()["GH_TOKEN"])
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun `windows platform selects windows command host`() {
        assertIs<WindowsLocalCommandHost>(currentLocalCommandHost("Windows 11"))
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(condition(), "Condition was not met within ${timeoutMillis}ms")
    }

    private fun completedProcess(
        home: File,
        taskId: String,
        output: String,
    ) = runner.start(
        CommandProcessSpec(
            executionId = taskId,
            command = platformCommand(
                posix = "printf '$output'",
                windows = "set /P \"=$output\" <NUL\nexit /B 0",
            ),
            workingDirectory = home.absolutePath,
        )
    ).also { process ->
        assertTrue(process.waitFor(5_000))
        assertEquals(0, process.exitCode())
    }

    private fun artifactsFor(outputFile: String): List<File> {
        val output = File(outputFile)
        return output.parentFile.listFiles().orEmpty()
            .filter { it.name == output.name || it.name.startsWith("${output.name}.") }
    }

    private fun withTemporaryGromozekaHome(block: (File) -> Unit) {
        val previousHome = System.getProperty("GROMOZEKA_HOME")
        val home = Files.createTempDirectory("gromozeka-command-runner-test-").toFile()
        var testFailure: Throwable? = null
        try {
            System.setProperty("GROMOZEKA_HOME", home.absolutePath)
            block(home)
        } catch (error: Throwable) {
            testFailure = error
            home.walkTopDown().filter(File::isFile).forEach { artifact ->
                println("${artifact.relativeTo(home)}: ${artifact.readText().takeLast(2_000)}")
            }
            throw error
        } finally {
            val cleanup = runCatching {
                startedProcesses.forEach { process ->
                    if (process.isAlive()) process.terminateTree() else process.waitFor(0)
                }
                waitUntil(5_000) { home.deleteRecursively() }
            }
            startedProcesses.clear()
            if (previousHome == null) {
                System.clearProperty("GROMOZEKA_HOME")
            } else {
                System.setProperty("GROMOZEKA_HOME", previousHome)
            }
            cleanup.exceptionOrNull()?.let { error ->
                testFailure?.addSuppressed(error) ?: throw error
            }
        }
    }

    private fun platformCommand(posix: String, windows: String): String =
        if (isWindows) windows else posix

    private fun windowsProcessTreeCommand(childPidFile: File): String {
        val escapedPath = childPidFile.absolutePath.replace("'", "''")
        return "powershell.exe -NoProfile -NonInteractive -Command " +
            "\"${'$'}child = Start-Process powershell.exe " +
            "-ArgumentList '-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30' -PassThru; " +
            "Set-Content -NoNewline -LiteralPath '$escapedPath' -Value ${'$'}child.Id; " +
            "Wait-Process -Id ${'$'}child.Id\""
    }

    private fun terminateRemainingProcessTree(processTreeId: Long) {
        val processHandle = ProcessHandle.of(processTreeId).orElse(null) ?: return
        if (!processHandle.isAlive) return
        runCatching {
            currentLocalCommandHost().processTree(processTreeId).terminate(processHandle)
        }
    }
}

internal object CommandWorkerLifetimeTestProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("GROMOZEKA_HOME", args[0])
        val identityFile = File(args[1])
        val childPidFile = File(args[0], "helper-child.pid")
        val process = LocalCommandProcessRunner().start(
            CommandProcessSpec(
                executionId = "abrupt-worker-exit-task",
                command = "sleep 30 & child=${'$'}!; printf '%s' ${'$'}child > '${childPidFile.absolutePath}'; wait",
                workingDirectory = args[0],
                lifetime = CommandTask.ProcessLifetime.valueOf(args[2]),
            )
        )
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!childPidFile.isFile || childPidFile.readText().isBlank()) {
            check(System.nanoTime() < deadline) { "Command child did not start" }
            Thread.sleep(10)
        }
        val temporaryIdentity = File("${identityFile.absolutePath}.tmp")
        temporaryIdentity.writeText(
            listOf(
                process.processTreeId.toString(),
                childPidFile.readText().trim(),
                process.processStartedAt.toEpochMilliseconds().toString(),
                process.outputFile,
            ).joinToString("\n")
        )
        check(temporaryIdentity.renameTo(identityFile))
        while (process.isAlive()) {
            Thread.sleep(1_000)
        }
    }
}
