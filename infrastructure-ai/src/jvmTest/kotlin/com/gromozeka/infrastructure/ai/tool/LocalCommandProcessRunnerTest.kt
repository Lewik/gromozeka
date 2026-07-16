package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandTask
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalCommandProcessRunnerTest {
    private val runner = LocalCommandProcessRunner()

    @Test
    fun `runner drains large merged output into artifact`() {
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    taskId = CommandTask.Id("large-output-task"),
                    command = "i=0; while [ ${'$'}i -lt 20000 ]; do echo line-${'$'}i; i=${'$'}((i+1)); done",
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
    fun `runner terminates root and child processes`() {
        withTemporaryGromozekaHome { home ->
            val childPidFile = File(home, "child.pid")
            val process = runner.start(
                CommandProcessSpec(
                    taskId = CommandTask.Id("process-tree-task"),
                    command = "sleep 30 & child=${'$'}!; echo ${'$'}child > '${childPidFile.absolutePath}'; wait",
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
    fun `runner terminates descendant created while handling termination`() {
        withTemporaryGromozekaHome { home ->
            val readyFile = File(home, "ready")
            val lateChildPidFile = File(home, "late-child.pid")
            val process = runner.start(
                CommandProcessSpec(
                    taskId = CommandTask.Id("late-descendant-task"),
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
                    taskId = CommandTask.Id("completed-recovery-task"),
                    command = "printf recovered; exit 7",
                    workingDirectory = home.absolutePath,
                )
            )
            assertTrue(process.waitFor(5_000))

            val recovery = assertIs<CommandProcessRecovery.Completed>(
                runner.recover(
                    CommandProcessRecoverySpec(
                        processId = process.processId,
                        processStartedAt = process.processStartedAt,
                        processGroupId = process.processGroupId,
                        outputFile = process.outputFile,
                    )
                )
            )

            assertTrue(recovery.exitCode == 7)
            assertTrue(File(process.outputFile).readText() == "recovered")
        }
    }

    @Test
    fun `runner reconnects to a live command with matching process identity`() {
        withTemporaryGromozekaHome { home ->
            val process = runner.start(
                CommandProcessSpec(
                    taskId = CommandTask.Id("live-recovery-task"),
                    command = "sleep 30",
                    workingDirectory = home.absolutePath,
                )
            )

            val recovery = assertIs<CommandProcessRecovery.Running>(
                runner.recover(
                    CommandProcessRecoverySpec(
                        processId = process.processId,
                        processStartedAt = process.processStartedAt,
                        processGroupId = process.processGroupId,
                        outputFile = process.outputFile,
                    )
                )
            )

            recovery.process.terminateTree()
            assertFalse(process.isAlive())
        }
    }

    @Test
    fun `runner garbage collects only unreferenced output artifacts`() {
        withTemporaryGromozekaHome { home ->
            val retained = runner.start(
                CommandProcessSpec(
                    taskId = CommandTask.Id("retained-output-task"),
                    command = "printf retained",
                    workingDirectory = home.absolutePath,
                )
            )
            val orphaned = runner.start(
                CommandProcessSpec(
                    taskId = CommandTask.Id("orphaned-output-task"),
                    command = "printf orphaned",
                    workingDirectory = home.absolutePath,
                )
            )
            assertTrue(retained.waitFor(5_000))
            assertTrue(orphaned.waitFor(5_000))

            runner.garbageCollectOutputArtifacts(setOf(retained.outputFile))

            assertTrue(File(retained.outputFile).isFile)
            assertFalse(File(orphaned.outputFile).exists())
            assertFalse(File("${orphaned.outputFile}.pgid").exists())
            assertFalse(File("${orphaned.outputFile}.exit").exists())
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(condition(), "Condition was not met within ${timeoutMillis}ms")
    }

    private fun withTemporaryGromozekaHome(block: (File) -> Unit) {
        val previousHome = System.getProperty("GROMOZEKA_HOME")
        val home = Files.createTempDirectory("gromozeka-command-runner-test-").toFile()
        try {
            System.setProperty("GROMOZEKA_HOME", home.absolutePath)
            block(home)
        } finally {
            if (previousHome == null) {
                System.clearProperty("GROMOZEKA_HOME")
            } else {
                System.setProperty("GROMOZEKA_HOME", previousHome)
            }
            assertTrue(home.deleteRecursively())
        }
    }
}
