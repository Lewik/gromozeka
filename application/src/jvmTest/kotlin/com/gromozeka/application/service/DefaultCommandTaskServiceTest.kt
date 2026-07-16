package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultCommandTaskServiceTest {
    private val conversationId = Conversation.Id("conversation-1")

    @Test
    fun `short command returns completed task and output`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            runner.onStart = { process ->
                process.appendOutput("complete output")
                process.complete(0)
            }

            val result = service.start(
                ExecuteCommandRequest(command = "complete", yield_time_ms = 2_000),
                context(projectDirectory),
            )

            assertEquals(CommandTask.Status.COMPLETED, result.task.status)
            assertEquals("complete output", result.output)
            assertEquals(0, result.task.exitCode)
            assertEquals(result.task, coordinator.findCommandTask(conversationId, result.task.id))
        }
    }

    @Test
    fun `long command returns working task and supports incremental reads`() = runBlocking {
        withService { service, runner, _, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "running", yield_time_ms = 0),
                context(projectDirectory),
            )
            val process = runner.lastProcess
            process.appendOutput("first\n")

            val first = assertNotNull(service.get(conversationId, result.task.id, 0, 1_000))
            assertEquals("first\n", first.output)
            process.appendOutput("second\n")
            val second = assertNotNull(
                service.get(conversationId, result.task.id, first.nextOutputByte, 1_000)
            )

            assertEquals("second\n", second.output)
            assertEquals(CommandTask.Status.WORKING, second.task.status)
        }
    }

    @Test
    fun `output chunks preserve utf8 code point boundaries`() = runBlocking {
        withService { service, runner, _, projectDirectory ->
            runner.onStart = { process ->
                process.appendOutput("a".repeat(65_535) + "€b")
                process.complete(0)
            }

            val first = service.start(
                ExecuteCommandRequest(command = "unicode", yield_time_ms = 2_000),
                context(projectDirectory),
            )
            val second = assertNotNull(
                service.get(conversationId, first.task.id, first.nextOutputByte, 0)
            )

            assertEquals(65_535, first.output.length)
            assertFalse(first.output.contains('�'))
            assertEquals("€b", second.output)
            assertFalse(second.hasMoreOutput)
        }
    }

    @Test
    fun `working output waits for a complete utf8 code point at current eof`() = runBlocking {
        withService { service, runner, _, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "unicode-stream", yield_time_ms = 0),
                context(projectDirectory),
            )
            runner.lastProcess.appendBytes(byteArrayOf(0xE2.toByte(), 0x82.toByte()))

            val partial = assertNotNull(service.get(conversationId, result.task.id, 0, 0))
            assertEquals("", partial.output)
            assertEquals(0, partial.nextOutputByte)
            assertTrue(partial.hasMoreOutput)

            runner.lastProcess.appendBytes(byteArrayOf(0xAC.toByte()))
            val complete = assertNotNull(service.get(conversationId, result.task.id, 0, 0))
            assertEquals("€", complete.output)
            assertEquals(3, complete.nextOutputByte)
        }
    }

    @Test
    fun `cancel terminates process and persists cancelled status`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "running", yield_time_ms = 0),
                context(projectDirectory),
            )

            assertTrue(service.cancel(conversationId, result.task.id))

            assertTrue(runner.lastProcess.terminateTreeCalled)
            assertFalse(runner.lastProcess.isAlive())
            assertEquals(
                CommandTask.Status.CANCELLED,
                coordinator.findCommandTask(conversationId, result.task.id)?.status,
            )
        }
    }

    @Test
    fun `timeout terminates process and persists failure`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(
                    command = "timeout",
                    yield_time_ms = 0,
                    timeout_seconds = 1,
                ),
                context(projectDirectory),
            )

            waitUntil(3_000) {
                coordinator.findCommandTask(conversationId, result.task.id)?.isTerminal == true
            }
            val task = assertNotNull(coordinator.findCommandTask(conversationId, result.task.id))
            assertEquals(CommandTask.Status.FAILED, task.status)
            assertTrue(task.statusMessage.orEmpty().contains("timed out"))
            assertTrue(runner.lastProcess.terminateTreeCalled)
        }
    }

    private suspend fun withService(
        block: suspend (
            service: DefaultCommandTaskService,
            runner: FakeCommandProcessRunner,
            coordinator: InMemoryConversationRuntimeCoordinator,
            projectDirectory: File,
        ) -> Unit,
    ) {
        val projectDirectory = Files.createTempDirectory("command-task-service-test-").toFile()
        val runner = FakeCommandProcessRunner(projectDirectory)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val service = DefaultCommandTaskService(
            processRunner = runner,
            runtimeCoordinator = coordinator,
            runtimeEventBus = InMemoryConversationRuntimeEventBus(),
        )
        try {
            block(service, runner, coordinator, projectDirectory)
        } finally {
            service.close()
            projectDirectory.deleteRecursively()
        }
    }

    private fun context(projectDirectory: File): ToolExecutionContext = ToolExecutionContext(
        mapOf(
            "conversationId" to conversationId.value,
            "projectPath" to projectDirectory.absolutePath,
        )
    )

    private suspend fun waitUntil(timeoutMillis: Long, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition() && System.nanoTime() < deadline) {
            delay(25)
        }
        assertTrue(condition(), "Condition was not met within ${timeoutMillis}ms")
    }

    private class FakeCommandProcessRunner(
        private val outputDirectory: File,
    ) : CommandProcessRunner {
        private val nextPid = AtomicLong(1_000)
        lateinit var lastProcess: FakeRunningCommandProcess
        var onStart: (FakeRunningCommandProcess) -> Unit = {}

        override fun start(spec: CommandProcessSpec): RunningCommandProcess =
            FakeRunningCommandProcess(
                processId = nextPid.incrementAndGet(),
                outputArtifact = File(outputDirectory, "${spec.taskId.value}.log").apply { createNewFile() },
            ).also { process ->
                lastProcess = process
                onStart(process)
            }

        override fun reconnect(
            processId: Long,
            processStartedAt: Instant,
            outputFile: String,
        ): RunningCommandProcess? = lastProcess.takeIf {
            it.processId == processId && it.processStartedAt == processStartedAt
        }
    }

    private class FakeRunningCommandProcess(
        override val processId: Long,
        private val outputArtifact: File,
    ) : RunningCommandProcess {
        override val processStartedAt: Instant = Instant.fromEpochMilliseconds(processId)
        override val outputFile: String
            get() = outputArtifact.absolutePath
        @Volatile
        private var alive = true
        @Volatile
        private var code = 0
        @Volatile
        var terminateTreeCalled = false
            private set

        override fun isAlive(): Boolean = alive

        override fun waitFor(timeoutMillis: Long): Boolean {
            if (alive) {
                Thread.sleep(timeoutMillis)
            }
            return !alive
        }

        override fun exitCode(): Int = code

        override fun terminateTree() {
            terminateTreeCalled = true
            alive = false
            code = 137
        }

        fun appendOutput(value: String) {
            outputArtifact.appendText(value)
        }

        fun appendBytes(value: ByteArray) {
            FileOutputStream(outputArtifact, true).use { it.write(value) }
        }

        fun complete(exitCode: Int) {
            code = exitCode
            alive = false
        }
    }
}
