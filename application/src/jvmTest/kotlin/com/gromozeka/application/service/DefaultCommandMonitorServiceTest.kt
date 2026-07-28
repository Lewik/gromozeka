package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEventPublisher
import com.gromozeka.domain.service.CommandMonitorSpec
import com.gromozeka.domain.service.CommandMonitorSyncResult
import com.gromozeka.domain.service.CommandOutputGarbageCollectionResult
import com.gromozeka.domain.service.CommandOutputGarbageCollectionSpec
import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultCommandMonitorServiceTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val workspaceMountId = WorkspaceMount.Id("mount-1")
    private val workerDescriptor = ConversationRuntimeWorkerDescriptor(
        id = ConversationRuntimeWorkerId("command-worker"),
        capabilities = setOf(
            ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
            ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
        ),
    )

    @Test
    fun `beginning monitor emits matching lines from existing terminal output`() = runBlocking {
        withService { service, _, coordinator, directory ->
            val source = sourceTask(
                directory = directory,
                output = "ignored\nerror: first\nerror: second\n",
                status = CommandTask.Status.COMPLETED,
            )
            coordinator.upsertCommandTask(source)

            val monitor = service.start(
                CommandMonitorSpec(
                    commandTaskId = source.id,
                    filterCommand = "contains:error",
                    mode = CommandMonitor.Mode.CONTINUOUS,
                    startFrom = CommandMonitor.StartFrom.BEGINNING,
                ),
                context(directory),
            )

            waitUntil {
                coordinator.findCommandMonitor(conversationId, monitor.id)?.isTerminal == true
            }
            val events = coordinator.findCommandMonitorEvents(conversationId, monitor.id)

            assertEquals(listOf("error: first", "error: second"), events.map { it.output })
            assertEquals(CommandMonitor.Status.COMPLETED, service.get(conversationId, monitor.id, 0)?.monitor?.status)
        }
    }

    @Test
    fun `now monitor ignores prior output and emits only future matches`() = runBlocking {
        withService { service, _, coordinator, directory ->
            val source = sourceTask(directory, "error: old\n")
            coordinator.upsertCommandTask(source)

            val monitor = service.start(
                CommandMonitorSpec(
                    commandTaskId = source.id,
                    filterCommand = "contains:error",
                    mode = CommandMonitor.Mode.CONTINUOUS,
                    startFrom = CommandMonitor.StartFrom.NOW,
                ),
                context(directory),
            )
            File(source.outputFile).appendText("ignored\nerror: new\n")

            waitUntil {
                coordinator.findCommandMonitorEvents(conversationId, monitor.id).isNotEmpty()
            }
            assertEquals(
                listOf("error: new"),
                coordinator.findCommandMonitorEvents(conversationId, monitor.id).map { it.output },
            )
            assertTrue(service.cancel(conversationId, monitor.id))
        }
    }

    @Test
    fun `once monitor emits one event and terminates its filter`() = runBlocking {
        withService { service, runner, coordinator, directory ->
            val source = sourceTask(
                directory = directory,
                output = "match one\nmatch two\n",
                status = CommandTask.Status.COMPLETED,
            )
            coordinator.upsertCommandTask(source)

            val monitor = service.start(
                CommandMonitorSpec(
                    commandTaskId = source.id,
                    filterCommand = "contains:match",
                    mode = CommandMonitor.Mode.ONCE,
                    startFrom = CommandMonitor.StartFrom.BEGINNING,
                ),
                context(directory),
            )

            waitUntil {
                coordinator.findCommandMonitor(conversationId, monitor.id)?.isTerminal == true
            }

            assertEquals(1, coordinator.findCommandMonitorEvents(conversationId, monitor.id).size)
            assertEquals(
                CommandMonitor.Status.COMPLETED,
                coordinator.findCommandMonitor(conversationId, monitor.id)?.status,
            )
            assertTrue(runner.processes.single().terminateTreeCalled)
        }
    }

    @Test
    fun `multiple monitors independently observe one command`() = runBlocking {
        withService { service, _, coordinator, directory ->
            val source = sourceTask(directory, "")
            coordinator.upsertCommandTask(source)
            val errors = service.start(
                CommandMonitorSpec(
                    source.id,
                    "contains:error",
                    CommandMonitor.Mode.CONTINUOUS,
                    CommandMonitor.StartFrom.NOW,
                ),
                context(directory),
            )
            val warnings = service.start(
                CommandMonitorSpec(
                    source.id,
                    "contains:warn",
                    CommandMonitor.Mode.CONTINUOUS,
                    CommandMonitor.StartFrom.NOW,
                ),
                context(directory),
            )

            File(source.outputFile).appendText("warn one\nerror one\n")
            waitUntil {
                coordinator.findCommandMonitorEvents(conversationId, null).size == 2
            }

            assertEquals(
                listOf("error one"),
                coordinator.findCommandMonitorEvents(conversationId, errors.id).map { it.output },
            )
            assertEquals(
                listOf("warn one"),
                coordinator.findCommandMonitorEvents(conversationId, warnings.id).map { it.output },
            )
            assertTrue(service.cancel(conversationId, errors.id))
            assertTrue(service.cancel(conversationId, warnings.id))
        }
    }

    @Test
    fun `cancel is idempotent and terminates filter once`() = runBlocking {
        withService { service, runner, coordinator, directory ->
            val source = sourceTask(directory, "")
            coordinator.upsertCommandTask(source)
            val monitor = service.start(
                CommandMonitorSpec(
                    source.id,
                    "contains:error",
                    CommandMonitor.Mode.CONTINUOUS,
                    CommandMonitor.StartFrom.NOW,
                ),
                context(directory),
            )

            assertTrue(service.cancel(conversationId, monitor.id))
            assertFalse(service.cancel(conversationId, monitor.id))
            assertEquals(1, runner.processes.single().terminationCount)
            assertEquals(
                CommandMonitor.Status.CANCELLED,
                coordinator.findCommandMonitor(conversationId, monitor.id)?.status,
            )
        }
    }

    @Test
    fun `monitor keeps processing while control plane is unavailable`() = runBlocking {
        val directory = Files.createTempDirectory("command-monitor-offline-test-").toFile()
        val runner = FakeCommandProcessRunner(directory)
        val storedCoordinator = InMemoryConversationRuntimeCoordinator()
        val coordinator = ToggleableCommandMonitorCoordinator(storedCoordinator)
        val service = service(runner, coordinator)
        try {
            val source = sourceTask(directory, "")
            storedCoordinator.upsertCommandTask(source)
            val monitor = service.start(
                CommandMonitorSpec(
                    source.id,
                    "contains:error",
                    CommandMonitor.Mode.CONTINUOUS,
                    CommandMonitor.StartFrom.NOW,
                ),
                context(directory),
            )
            coordinator.unavailable = true
            File(source.outputFile).appendText("error while offline\n")
            delay(500)

            assertTrue(runner.processes.single().isAlive())
            assertTrue(storedCoordinator.findCommandMonitorEvents(conversationId, monitor.id).isEmpty())

            coordinator.unavailable = false
            waitUntil(3_000) {
                storedCoordinator.findCommandMonitorEvents(conversationId, monitor.id).isNotEmpty()
            }
            assertEquals(
                "error while offline",
                storedCoordinator.findCommandMonitorEvents(conversationId, monitor.id).single().output,
            )
            assertTrue(service.cancel(conversationId, monitor.id))
        } finally {
            service.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `events preserve utf8 and bound one very long line`() = runBlocking {
        withService { service, _, coordinator, directory ->
            val source = sourceTask(
                directory = directory,
                output = "€".repeat(4_000) + "\n",
                status = CommandTask.Status.COMPLETED,
            )
            coordinator.upsertCommandTask(source)
            val monitor = service.start(
                CommandMonitorSpec(
                    source.id,
                    "cat",
                    CommandMonitor.Mode.CONTINUOUS,
                    CommandMonitor.StartFrom.BEGINNING,
                ),
                context(directory),
            )

            waitUntil {
                coordinator.findCommandMonitor(conversationId, monitor.id)?.isTerminal == true
            }
            val event = coordinator.findCommandMonitorEvents(conversationId, monitor.id).single()

            assertTrue(event.outputTruncatedBefore)
            assertFalse(event.output.contains('�'))
            assertTrue(event.output.toByteArray(StandardCharsets.UTF_8).size <= 8 * 1024)
        }
    }

    @Test
    fun `lifecycle events are published for matches and terminal state`() = runBlocking {
        val lifecycleEvents = Channel<CommandMonitorLifecycleEvent>(Channel.UNLIMITED)
        withService(CommandMonitorLifecycleEventPublisher(lifecycleEvents::send)) {
                service, _, coordinator, directory ->
            val source = sourceTask(
                directory = directory,
                output = "match\n",
                status = CommandTask.Status.COMPLETED,
                agentDefinitionId = AgentDefinition.Id("agent-1"),
            )
            coordinator.upsertCommandTask(source)
            val monitor = service.start(
                CommandMonitorSpec(
                    source.id,
                    "cat",
                    CommandMonitor.Mode.CONTINUOUS,
                    CommandMonitor.StartFrom.BEGINNING,
                ),
                context(directory, source.agentDefinitionId),
            )

            val first = withTimeout(3_000) { lifecycleEvents.receive() }
            val second = withTimeout(3_000) { lifecycleEvents.receive() }

            assertEquals(monitor.id, first.monitorId)
            assertEquals(
                setOf(
                    CommandMonitorLifecycleEvent.Kind.EVENTS_AVAILABLE,
                    CommandMonitorLifecycleEvent.Kind.TERMINAL,
                ),
                setOf(first.kind, second.kind),
            )
        }
    }

    private suspend fun withService(
        lifecycleEventPublisher: CommandMonitorLifecycleEventPublisher = CommandMonitorLifecycleEventPublisher { },
        block: suspend (
            DefaultCommandMonitorService,
            FakeCommandProcessRunner,
            InMemoryConversationRuntimeCoordinator,
            File,
        ) -> Unit,
    ) {
        val directory = Files.createTempDirectory("command-monitor-service-test-").toFile()
        val runner = FakeCommandProcessRunner(directory)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val service = service(runner, coordinator, lifecycleEventPublisher)
        try {
            block(service, runner, coordinator, directory)
        } finally {
            service.close()
            directory.deleteRecursively()
        }
    }

    private fun service(
        runner: CommandProcessRunner,
        coordinator: ConversationRuntimeCoordinator,
        lifecycleEventPublisher: CommandMonitorLifecycleEventPublisher = CommandMonitorLifecycleEventPublisher { },
    ) = DefaultCommandMonitorService(
        processRunner = runner,
        runtimeCoordinator = coordinator,
        runtimeEventBus = InMemoryConversationRuntimeEventBus(),
        lifecycleEventPublisher = lifecycleEventPublisher,
        runtimeWorkerDescriptor = objectProvider(workerDescriptor),
    )

    private fun sourceTask(
        directory: File,
        output: String,
        status: CommandTask.Status = CommandTask.Status.WORKING,
        agentDefinitionId: AgentDefinition.Id? = null,
    ): CommandTask {
        val outputFile = File(directory, "source-${System.nanoTime()}.log")
        outputFile.writeText(output)
        val now = Clock.System.now()
        return CommandTask(
            id = CommandTask.Id("source-${System.nanoTime()}"),
            conversationId = conversationId,
            workerId = workerDescriptor.id,
            workspaceMountId = workspaceMountId,
            agentDefinitionId = agentDefinitionId,
            command = "source",
            workingDirectory = directory.absolutePath,
            status = status,
            processId = null,
            processStartedAt = null,
            outputFile = outputFile.absolutePath,
            outputBytes = outputFile.length(),
            exitCode = 0.takeIf { status == CommandTask.Status.COMPLETED },
            createdAt = now,
            updatedAt = now,
            completedAt = now.takeIf { status != CommandTask.Status.WORKING },
        )
    }

    private fun context(
        directory: File,
        agentDefinitionId: AgentDefinition.Id? = null,
    ) = ToolExecutionContext(
        buildMap {
            put("conversationId", conversationId.value)
            put("projectId", "project-1")
            put("workspaceId", "workspace-1")
            put("workspaceMountId", workspaceMountId.value)
            put("workspaceRootPath", directory.absolutePath)
            put("workerId", workerDescriptor.id.value)
            agentDefinitionId?.let { put("agentDefinitionId", it.value) }
        }
    )

    private suspend fun waitUntil(
        timeoutMillis: Long = 3_000,
        condition: suspend () -> Boolean,
    ) {
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

    private class ToggleableCommandMonitorCoordinator(
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

        override suspend fun findCommandMonitor(
            conversationId: Conversation.Id,
            monitorId: CommandMonitor.Id,
        ): CommandMonitor? {
            check(!unavailable) { "Control plane is unavailable" }
            return delegate.findCommandMonitor(conversationId, monitorId)
        }

        override suspend fun synchronizeCommandMonitor(
            monitor: CommandMonitor,
            events: List<CommandMonitorEvent>,
        ): CommandMonitorSyncResult {
            check(!unavailable) { "Control plane is unavailable" }
            return delegate.synchronizeCommandMonitor(monitor, events)
        }
    }

    private class FakeCommandProcessRunner(
        private val outputDirectory: File,
    ) : CommandProcessRunner {
        private val nextPid = AtomicLong(10_000)
        val processes = mutableListOf<FakeFilterProcess>()

        override fun start(spec: CommandProcessSpec): RunningCommandProcess =
            FakeFilterProcess(
                processId = nextPid.incrementAndGet(),
                outputFile = File(outputDirectory, "${spec.executionId}.log"),
                errorFile = File(outputDirectory, "${spec.executionId}.err"),
                filterCommand = spec.command,
            ).also(processes::add)

        override fun recover(spec: CommandProcessRecoverySpec): CommandProcessRecovery =
            processes.firstOrNull {
                it.processId == spec.processId &&
                    it.processStartedAt == spec.processStartedAt &&
                    it.processTreeId == spec.processTreeId
            }?.let { process ->
                if (process.isAlive()) {
                    CommandProcessRecovery.Running(process)
                } else {
                    CommandProcessRecovery.Completed(process.exitCode())
                }
            } ?: CommandProcessRecovery.Unavailable("Fake process is unavailable")

        override fun deleteOutputArtifacts(outputFile: String) {
            File(outputFile).delete()
            File(outputFile.removeSuffix(".log") + ".err").delete()
        }

        override fun garbageCollectOutputArtifacts(
            spec: CommandOutputGarbageCollectionSpec,
        ) = CommandOutputGarbageCollectionResult(emptySet(), 0, 0)
    }

    private class FakeFilterProcess(
        override val processId: Long,
        outputFile: File,
        errorFile: File,
        private val filterCommand: String,
    ) : RunningCommandProcess {
        override val processStartedAt: Instant = Instant.fromEpochMilliseconds(processId)
        override val processTreeId: Long = processId + 10_000
        override val outputFile: String = outputFile.apply { createNewFile() }.absolutePath
        override val errorFile: String = errorFile.apply { createNewFile() }.absolutePath
        override val acceptsInput: Boolean = true
        private val pendingInput = ByteArrayOutputStream()

        @Volatile
        private var alive = true

        @Volatile
        private var code = 0

        var terminateTreeCalled = false
            private set
        var terminationCount = 0
            private set

        override fun isAlive(): Boolean = alive

        override fun waitFor(timeoutMillis: Long): Boolean {
            if (alive) Thread.sleep(timeoutMillis)
            return !alive
        }

        override fun exitCode(): Int = code

        @Synchronized
        override fun writeInput(bytes: ByteArray) {
            check(alive) { "Process is not running" }
            pendingInput.write(bytes)
            emitCompleteLines()
        }

        @Synchronized
        override fun closeInput() {
            if (!alive) return
            emitCompleteLines(flushTrailing = true)
            alive = false
            code = 0
        }

        @Synchronized
        override fun terminateTree() {
            if (!alive) return
            terminateTreeCalled = true
            terminationCount += 1
            alive = false
            code = 137
        }

        private fun emitCompleteLines(flushTrailing: Boolean = false) {
            val bytes = pendingInput.toByteArray()
            var consumed = 0
            bytes.indices.forEach { index ->
                if (bytes[index] == '\n'.code.toByte()) {
                    emit(bytes.copyOfRange(consumed, index + 1))
                    consumed = index + 1
                }
            }
            if (flushTrailing && consumed < bytes.size) {
                emit(bytes.copyOfRange(consumed, bytes.size))
                consumed = bytes.size
            }
            if (consumed > 0) {
                pendingInput.reset()
                pendingInput.write(bytes, consumed, bytes.size - consumed)
            }
        }

        private fun emit(lineBytes: ByteArray) {
            val shouldEmit = when {
                filterCommand == "cat" -> true
                filterCommand.startsWith("contains:") -> {
                    val needle = filterCommand.removePrefix("contains:")
                    String(lineBytes, StandardCharsets.UTF_8).contains(needle)
                }
                else -> error("Unsupported fake filter command: $filterCommand")
            }
            if (shouldEmit) {
                FileOutputStream(outputFile, true).use { it.write(lineBytes) }
            }
        }
    }
}
