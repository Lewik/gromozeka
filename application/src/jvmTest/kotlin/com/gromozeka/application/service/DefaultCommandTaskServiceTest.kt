package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandOutputGarbageCollectionResult
import com.gromozeka.domain.service.CommandOutputGarbageCollectionSpec
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEvent
import com.gromozeka.domain.service.CommandTaskLifecycleEventPublisher
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultCommandTaskServiceTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val workerDescriptor = ConversationRuntimeWorkerDescriptor(
        id = ConversationRuntimeWorkerId("command-worker"),
        capabilities = setOf(
            ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
            ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
        ),
        environmentProfile = testWorkerEnvironmentProfile(),
    )

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
    fun `follow-up wait may exceed initial yield limit`() = runBlocking {
        withService { service, runner, _, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "running", yield_time_ms = 0),
                context(projectDirectory),
            )
            val waiting = async {
                service.get(conversationId, result.task.id, 0, 65_000)
            }

            delay(150)
            runner.lastProcess.complete(0)

            assertEquals(
                CommandTask.Status.COMPLETED,
                assertNotNull(waiting.await()).task.status,
            )
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
    fun `persisted cancellation request is observed by command monitor`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "running", yield_time_ms = 0),
                context(projectDirectory),
            )

            assertTrue(
                coordinator.requestCommandTaskCancellation(
                    conversationId,
                    result.task.id,
                    Clock.System.now(),
                )
            )
            waitUntil(3_000) {
                coordinator.findCommandTask(conversationId, result.task.id)?.status ==
                    CommandTask.Status.CANCELLED
            }

            assertTrue(runner.lastProcess.terminateTreeCalled)
            assertFalse(runner.lastProcess.isAlive())
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

    @Test
    fun `worker restart reconnects running command and monitors it to completion`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "running", yield_time_ms = 0),
                context(projectDirectory),
            )
            service.close()
            assertTrue(runner.lastProcess.isAlive())
            assertFalse(runner.lastProcess.terminateTreeCalled)

            val recoveredService = DefaultCommandTaskService(
                processRunner = runner,
                runtimeCoordinator = coordinator,
                runtimeEventBus = InMemoryConversationRuntimeEventBus(),
                lifecycleEventPublisher = noOpLifecycleEventPublisher(),
                runtimeWorkerDescriptor = objectProvider(workerDescriptor),
            )
            try {
                recoveredService.recoverPersistedTasks()
                assertTrue(result.task.outputFile in runner.garbageCollectionSpec.referencedOutputFiles)
                assertTrue(result.task.outputFile in runner.garbageCollectionSpec.protectedOutputFiles)
                runner.lastProcess.appendOutput("recovered output")
                runner.lastProcess.complete(0)

                waitUntil(2_000) {
                    coordinator.findCommandTask(conversationId, result.task.id)?.isTerminal == true
                }
                val recoveredTask = assertNotNull(coordinator.findCommandTask(conversationId, result.task.id))
                assertEquals(CommandTask.Status.COMPLETED, recoveredTask.status)
                assertEquals(
                    "recovered output",
                    assertNotNull(recoveredService.get(conversationId, result.task.id, 0, 0)).output,
                )
            } finally {
                recoveredService.close()
            }
        }
    }

    @Test
    fun `worker restart finalizes command that completed while offline`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "offline", yield_time_ms = 0),
                context(projectDirectory),
            )
            service.close()
            runner.lastProcess.complete(7)

            val recoveredService = DefaultCommandTaskService(
                processRunner = runner,
                runtimeCoordinator = coordinator,
                runtimeEventBus = InMemoryConversationRuntimeEventBus(),
                lifecycleEventPublisher = noOpLifecycleEventPublisher(),
                runtimeWorkerDescriptor = objectProvider(workerDescriptor),
            )
            try {
                recoveredService.recoverPersistedTasks()

                val recoveredTask = assertNotNull(coordinator.findCommandTask(conversationId, result.task.id))
                assertEquals(CommandTask.Status.FAILED, recoveredTask.status)
                assertEquals(7, recoveredTask.exitCode)
            } finally {
                recoveredService.close()
            }
        }
    }

    @Test
    fun `working command requests one completion event with bounded terminal output`() = runBlocking {
        val events = Channel<CommandTaskLifecycleEvent>(Channel.UNLIMITED)
        withService(lifecycleEventPublisher = CommandTaskLifecycleEventPublisher(events::send)) {
                service, runner, coordinator, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "background", yield_time_ms = 0),
                context(projectDirectory, AgentDefinition.Id("agent-1")),
            )

            assertEquals(CommandTask.Status.WORKING, result.task.status)
            assertNotNull(result.task.completionNotificationRequestedAt)

            runner.lastProcess.appendOutput("prefix-" + "x".repeat(10_000) + "-terminal-suffix")
            runner.lastProcess.complete(0)

            val event = withTimeout(3_000) { events.receive() }
            assertEquals(result.task.id, event.taskId)
            assertEquals(CommandTask.Status.COMPLETED, event.status)
            assertNull(withTimeoutOrNull(200) { events.receive() })

            val stored = assertNotNull(coordinator.findCommandTask(conversationId, result.task.id))
            assertTrue(stored.terminalOutput.orEmpty().endsWith("-terminal-suffix"))
            assertTrue(stored.terminalOutput.orEmpty().toByteArray().size <= 8 * 1024)
            assertTrue((stored.terminalOutputStartByte ?: 0) > 0)
        }
    }

    @Test
    fun `terminal tail remains readable after full output is garbage collected`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "large", yield_time_ms = 0),
                context(projectDirectory),
            )
            runner.lastProcess.appendOutput("discarded-" + "x".repeat(10_000) + "-retained")
            runner.lastProcess.complete(0)
            waitUntil(3_000) {
                coordinator.findCommandTask(conversationId, result.task.id)?.isTerminal == true
            }
            val stored = assertNotNull(coordinator.findCommandTask(conversationId, result.task.id))
            assertTrue(File(stored.outputFile).delete())

            val output = assertNotNull(service.get(conversationId, stored.id, 0, 0))

            assertTrue(output.output.endsWith("-retained"))
            assertTrue(output.outputStartByte > 0)
            assertEquals(stored.outputBytes, output.nextOutputByte)
            assertEquals(stored.outputBytes, output.task.outputBytes)
            assertFalse(output.hasMoreOutput)
        }
    }

    @Test
    fun `command completed during initial yield does not request completion event`() = runBlocking {
        val events = Channel<CommandTaskLifecycleEvent>(Channel.UNLIMITED)
        withService(lifecycleEventPublisher = CommandTaskLifecycleEventPublisher(events::send)) {
                service, runner, _, projectDirectory ->
            runner.onStart = { process ->
                process.appendOutput("done")
                process.complete(0)
            }

            val result = service.start(
                ExecuteCommandRequest(command = "short", yield_time_ms = 2_000),
                context(projectDirectory, AgentDefinition.Id("agent-1")),
            )

            assertEquals(CommandTask.Status.COMPLETED, result.task.status)
            assertEquals(null, result.task.completionNotificationRequestedAt)
            assertNull(withTimeoutOrNull(200) { events.receive() })
        }
    }

    @Test
    fun `running command survives control plane outage and synchronizes completion after recovery`() = runBlocking {
        val projectDirectory = Files.createTempDirectory("command-task-offline-test-").toFile()
        val runner = FakeCommandProcessRunner(projectDirectory)
        val storedCoordinator = InMemoryConversationRuntimeCoordinator()
        val coordinator = ToggleableCommandTaskCoordinator(storedCoordinator)
        val service = DefaultCommandTaskService(
            processRunner = runner,
            runtimeCoordinator = coordinator,
            runtimeEventBus = InMemoryConversationRuntimeEventBus(),
            lifecycleEventPublisher = noOpLifecycleEventPublisher(),
            runtimeWorkerDescriptor = objectProvider(workerDescriptor),
        )
        try {
            val result = service.start(
                ExecuteCommandRequest(command = "offline", yield_time_ms = 0),
                context(projectDirectory),
            )
            coordinator.unavailable = true

            delay(1_200)

            assertTrue(runner.lastProcess.isAlive())
            assertFalse(runner.lastProcess.terminateTreeCalled)

            runner.lastProcess.appendOutput("completed offline")
            runner.lastProcess.complete(0)
            delay(200)

            assertEquals(
                CommandTask.Status.WORKING,
                storedCoordinator.findCommandTask(conversationId, result.task.id)?.status,
            )
            assertFalse(runner.lastProcess.terminateTreeCalled)

            coordinator.unavailable = false
            waitUntil(3_000) {
                storedCoordinator.findCommandTask(conversationId, result.task.id)?.status ==
                    CommandTask.Status.COMPLETED
            }
            assertEquals(
                "completed offline",
                assertNotNull(service.get(conversationId, result.task.id, 0, 0)).output,
            )
        } finally {
            service.close()
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `concurrent cancellation terminates command once`() = runBlocking {
        withService { service, runner, _, projectDirectory ->
            val result = service.start(
                ExecuteCommandRequest(command = "running", yield_time_ms = 0),
                context(projectDirectory),
            )

            val first = async { service.cancel(conversationId, result.task.id) }
            val second = async { service.cancel(conversationId, result.task.id) }
            val results = listOf(first.await(), second.await())

            assertEquals(1, results.count { it })
            assertEquals(1, runner.lastProcess.terminationCount)
        }
    }

    @Test
    fun `terminal task retention deletes evicted output artifacts`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val evictedOutput = File(projectDirectory, "retained-0.log").apply { createNewFile() }
            repeat(100) { index ->
                val createdAt = Instant.fromEpochMilliseconds(index.toLong())
                val outputFile = if (index == 0) {
                    evictedOutput
                } else {
                    File(projectDirectory, "retained-$index.log").apply { createNewFile() }
                }
                coordinator.upsertCommandTask(
                    CommandTask(
                        id = CommandTask.Id("retained-$index"),
                        conversationId = conversationId,
                        workerId = workerDescriptor.id,
                        workspaceMountId = WorkspaceMount.Id("mount-1"),
                        command = "completed-$index",
                        workingDirectory = projectDirectory.absolutePath,
                        status = CommandTask.Status.COMPLETED,
                        processId = null,
                        processStartedAt = null,
                        outputFile = outputFile.absolutePath,
                        outputBytes = 0,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                        completedAt = createdAt,
                    )
                )
            }
            runner.onStart = { process -> process.complete(0) }

            service.start(
                ExecuteCommandRequest(command = "newest", yield_time_ms = 2_000),
                context(projectDirectory),
            )

            assertTrue(evictedOutput.absolutePath in runner.deletedOutputFiles)
            assertFalse(evictedOutput.exists())
        }
    }

    @Test
    fun `output retention protects active monitor and its terminal source`() = runBlocking {
        withService { service, runner, coordinator, projectDirectory ->
            val now = Clock.System.now()
            val sourceOutput = File(projectDirectory, "source.log").apply { writeText("source") }
            val monitorOutput = File(projectDirectory, "monitor.log").apply { writeText("match") }
            val source = CommandTask(
                id = CommandTask.Id("source"),
                conversationId = conversationId,
                workerId = workerDescriptor.id,
                workspaceMountId = WorkspaceMount.Id("mount-1"),
                command = "source",
                workingDirectory = projectDirectory.absolutePath,
                status = CommandTask.Status.COMPLETED,
                processId = null,
                processStartedAt = null,
                outputFile = sourceOutput.absolutePath,
                outputBytes = sourceOutput.length(),
                createdAt = now,
                updatedAt = now,
                completedAt = now,
            )
            coordinator.upsertCommandTask(source)
            coordinator.synchronizeCommandMonitor(
                CommandMonitor(
                    id = CommandMonitor.Id("monitor"),
                    conversationId = conversationId,
                    commandTaskId = source.id,
                    workerId = workerDescriptor.id,
                    workspaceMountId = source.workspaceMountId,
                    filterCommand = "grep match",
                    mode = CommandMonitor.Mode.CONTINUOUS,
                    startFrom = CommandMonitor.StartFrom.BEGINNING,
                    status = CommandMonitor.Status.WORKING,
                    sourceOutputCursor = source.outputBytes,
                    processId = 999,
                    processStartedAt = now,
                    outputFile = monitorOutput.absolutePath,
                    errorFile = File(projectDirectory, "monitor.err").apply { createNewFile() }.absolutePath,
                    outputBytes = monitorOutput.length(),
                    eventOutputCursor = monitorOutput.length(),
                    createdAt = now,
                    updatedAt = now,
                ),
                emptyList(),
            )

            service.recoverPersistedTasks()

            assertTrue(sourceOutput.absolutePath in runner.garbageCollectionSpec.referencedOutputFiles)
            assertTrue(monitorOutput.absolutePath in runner.garbageCollectionSpec.referencedOutputFiles)
            assertTrue(sourceOutput.absolutePath in runner.garbageCollectionSpec.protectedOutputFiles)
            assertTrue(monitorOutput.absolutePath in runner.garbageCollectionSpec.protectedOutputFiles)
        }
    }

    private suspend fun withService(
        lifecycleEventPublisher: CommandTaskLifecycleEventPublisher = noOpLifecycleEventPublisher(),
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
            lifecycleEventPublisher = lifecycleEventPublisher,
            runtimeWorkerDescriptor = objectProvider(workerDescriptor),
        )
        try {
            block(service, runner, coordinator, projectDirectory)
        } finally {
            service.close()
            projectDirectory.deleteRecursively()
        }
    }

    private fun context(
        projectDirectory: File,
        agentDefinitionId: AgentDefinition.Id? = null,
    ): ToolExecutionContext = ToolExecutionContext(
        buildMap {
            put("conversationId", conversationId.value)
            put("projectId", "project-1")
            put("workspaceId", "workspace-1")
            put("workspaceMountId", "mount-1")
            put("workspaceRootPath", projectDirectory.absolutePath)
            put("workerId", workerDescriptor.id.value)
            agentDefinitionId?.let { put("agentDefinitionId", it.value) }
        }
    )

    private suspend fun waitUntil(timeoutMillis: Long, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition() && System.nanoTime() < deadline) {
            delay(25)
        }
        assertTrue(condition(), "Condition was not met within ${timeoutMillis}ms")
    }

    private fun <T : Any> objectProvider(value: T): org.springframework.beans.factory.ObjectProvider<T> =
        object : org.springframework.beans.factory.ObjectProvider<T> {
            override fun getObject(): T = value
        }

    private fun noOpLifecycleEventPublisher(): CommandTaskLifecycleEventPublisher =
        CommandTaskLifecycleEventPublisher { }

    private class FakeCommandProcessRunner(
        private val outputDirectory: File,
    ) : CommandProcessRunner {
        private val nextPid = AtomicLong(1_000)
        lateinit var lastProcess: FakeRunningCommandProcess
        var onStart: (FakeRunningCommandProcess) -> Unit = {}
        val deletedOutputFiles = mutableListOf<String>()
        lateinit var garbageCollectionSpec: CommandOutputGarbageCollectionSpec

        override fun start(spec: CommandProcessSpec): RunningCommandProcess =
            FakeRunningCommandProcess(
                processId = nextPid.incrementAndGet(),
                outputArtifact = File(outputDirectory, "${spec.executionId}.log").apply { createNewFile() },
            ).also { process ->
                lastProcess = process
                onStart(process)
            }

        override fun recover(spec: CommandProcessRecoverySpec): CommandProcessRecovery {
            val process = lastProcess.takeIf {
                it.processId == spec.processId &&
                    it.processStartedAt == spec.processStartedAt &&
                    it.processTreeId == spec.processTreeId
            } ?: return CommandProcessRecovery.Unavailable("Fake process is unavailable")
            return if (process.isAlive()) {
                CommandProcessRecovery.Running(process)
            } else {
                CommandProcessRecovery.Completed(process.exitCode())
            }
        }

        override fun deleteOutputArtifacts(outputFile: String) {
            deletedOutputFiles += outputFile
            File(outputFile).delete()
        }

        override fun garbageCollectOutputArtifacts(
            spec: CommandOutputGarbageCollectionSpec,
        ): CommandOutputGarbageCollectionResult {
            garbageCollectionSpec = spec
            return CommandOutputGarbageCollectionResult(
                deletedOutputFiles = emptySet(),
                retainedBytes = 0,
                protectedBytes = 0,
            )
        }
    }

    private class ToggleableCommandTaskCoordinator(
        private val delegate: ConversationRuntimeCoordinator,
    ) : ConversationRuntimeCoordinator by delegate {
        @Volatile
        var unavailable = false

        override suspend fun findCommandTask(
            conversationId: Conversation.Id,
            taskId: CommandTask.Id,
        ): CommandTask? {
            check(!unavailable) { "Control plane is unavailable" }
            return delegate.findCommandTask(conversationId, taskId)
        }

        override suspend fun upsertCommandTask(task: CommandTask) =
            if (unavailable) {
                error("Control plane is unavailable")
            } else {
                delegate.upsertCommandTask(task)
            }
    }

    private class FakeRunningCommandProcess(
        override val processId: Long,
        private val outputArtifact: File,
    ) : RunningCommandProcess {
        override val processStartedAt: Instant = Instant.fromEpochMilliseconds(processId)
        override val processTreeId: Long = processId + 10_000
        override val outputFile: String
            get() = outputArtifact.absolutePath
        override val errorFile: String? = null
        override val acceptsInput: Boolean = true
        @Volatile
        private var alive = true
        @Volatile
        private var code = 0
        @Volatile
        var terminateTreeCalled = false
            private set
        var terminationCount = 0
            private set

        override fun isAlive(): Boolean = alive

        override fun waitFor(timeoutMillis: Long): Boolean {
            if (alive) {
                Thread.sleep(timeoutMillis)
            }
            return !alive
        }

        override fun exitCode(): Int = code

        override fun writeInput(bytes: ByteArray) {
            check(alive) { "Process is not running" }
        }

        override fun closeInput() = Unit

        override fun terminateTree() {
            terminateTreeCalled = true
            terminationCount += 1
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
