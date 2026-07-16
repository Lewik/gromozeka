package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandTask
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
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
