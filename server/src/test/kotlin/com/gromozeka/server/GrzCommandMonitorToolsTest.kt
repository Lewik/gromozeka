package com.gromozeka.server

import com.gromozeka.application.service.InMemoryConversationRuntimeCoordinator
import com.gromozeka.application.service.InMemoryConversationRuntimeEventBus
import com.gromozeka.application.service.ServerCommandRuntimeStateService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorOutput
import com.gromozeka.domain.service.CommandMonitorService
import com.gromozeka.domain.service.CommandMonitorSpec
import com.gromozeka.domain.service.CommandMonitorLifecycleEventPublisher
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEventPublisher
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.CancelCommandMonitorRequest
import com.gromozeka.domain.tool.filesystem.GetCommandMonitorRequest
import com.gromozeka.domain.tool.filesystem.ListCommandsAndMonitorsRequest
import com.gromozeka.domain.tool.filesystem.MonitorCommandRequest
import com.gromozeka.infrastructure.ai.tool.GrzCancelCommandMonitorToolImpl
import com.gromozeka.infrastructure.ai.tool.GrzGetCommandMonitorToolImpl
import com.gromozeka.infrastructure.ai.tool.GrzListCommandsAndMonitorsToolImpl
import com.gromozeka.infrastructure.ai.tool.GrzMonitorCommandToolImpl
import com.gromozeka.infrastructure.ai.config.ToolsRegistrationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GrzCommandMonitorToolsTest {
    private val now = Instant.fromEpochMilliseconds(1_000)
    private val conversationId = Conversation.Id("conversation-1")
    private val otherConversationId = Conversation.Id("conversation-2")
    private val workerId = ConversationRuntimeWorkerId("worker-1")
    private val workspaceMountId = WorkspaceMount.Id("mount-1")

    @Test
    fun `monitor command delegates the exact request and execution context`() {
        val monitor = monitor().copy(
            mode = CommandMonitor.Mode.ONCE,
            startFrom = CommandMonitor.StartFrom.BEGINNING,
        )
        val service = RecordingCommandMonitorService(startResult = monitor)
        val tool = GrzMonitorCommandToolImpl(service)
        val context = context()

        val result = tool.execute(
            MonitorCommandRequest(
                task_id = "command-1",
                filter_command = "grep --line-buffered READY",
                mode = CommandMonitor.Mode.ONCE,
                start_from = CommandMonitor.StartFrom.BEGINNING,
            ),
            context,
        )

        assertEquals(
            CommandMonitorSpec(
                commandTaskId = CommandTask.Id("command-1"),
                filterCommand = "grep --line-buffered READY",
                mode = CommandMonitor.Mode.ONCE,
                startFrom = CommandMonitor.StartFrom.BEGINNING,
            ),
            service.startedSpec,
        )
        assertSame(context, service.startedContext)
        assertEquals(monitor.id.value, result["monitor_id"])
        assertEquals("ONCE", result["mode"])
        assertEquals("BEGINNING", result["start_from"])
        assertTrue(result["output_is_untrusted"] as Boolean)
    }

    @Test
    fun `tool contracts expose owner routing modes and bounded long polling`() {
        val service = RecordingCommandMonitorService()
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val runtimeState = commandRuntimeState(coordinator)
        val monitor = GrzMonitorCommandToolImpl(service)
        val get = GrzGetCommandMonitorToolImpl(service, runtimeState)
        val cancel = GrzCancelCommandMonitorToolImpl(service)
        val list = GrzListCommandsAndMonitorsToolImpl(runtimeState)
        val callbacks = ToolsRegistrationConfig()
            .toolCallbacksRegistrar(listOf(monitor, get, cancel, list))
            .callbacks
            .associateBy { it.definition.name }

        assertEquals(AiToolExecutionScope.COMMAND_TASK_OWNER, monitor.metadata.executionScope)
        assertEquals(AiToolExecutionScope.COMMAND_MONITOR_OWNER, get.metadata.executionScope)
        assertEquals(AiToolExecutionScope.COMMAND_MONITOR_OWNER, cancel.metadata.executionScope)
        assertEquals(AiToolExecutionScope.CONVERSATION_RUNTIME, list.metadata.executionScope)

        val monitorProperties = Json.parseToJsonElement(
            callbacks.getValue(monitor.name).definition.inputSchema
        ).jsonObject.getValue("properties").jsonObject
        assertEquals(
            setOf("ONCE", "CONTINUOUS"),
            monitorProperties.getValue("mode").jsonObject
                .getValue("enum").jsonArray
                .mapTo(mutableSetOf()) { it.jsonPrimitive.content },
        )
        assertEquals(
            setOf("NOW", "BEGINNING"),
            monitorProperties.getValue("start_from").jsonObject
                .getValue("enum").jsonArray
                .mapTo(mutableSetOf()) { it.jsonPrimitive.content },
        )

        val getProperties = Json.parseToJsonElement(
            callbacks.getValue(get.name).definition.inputSchema
        ).jsonObject.getValue("properties").jsonObject
        assertEquals(
            300_000,
            getProperties.getValue("wait_ms").jsonObject.getValue("maximum").jsonPrimitive.long,
        )
    }

    @Test
    fun `get command monitor returns at most sixty four persisted events`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val monitor = monitor(outputBytes = 100, eventOutputCursor = 100, eventCount = 70)
        val events = (1L..70L).map { byte ->
            CommandMonitorEvent(
                id = CommandMonitorEvent.Id("event-$byte"),
                conversationId = conversationId,
                monitorId = monitor.id,
                outputStartByte = byte - 1,
                outputEndByte = byte,
                output = "event-$byte",
                outputTruncatedBefore = false,
                occurredAt = now,
                deliveryRequested = false,
            )
        }
        coordinator.synchronizeCommandMonitor(monitor, events)
        val service = RecordingCommandMonitorService(
            getResult = CommandMonitorOutput(
                monitor = monitor,
                output = "bounded output",
                outputStartByte = 0,
                nextOutputByte = 100,
                hasMoreOutput = false,
            )
        )
        val tool = GrzGetCommandMonitorToolImpl(service, commandRuntimeState(coordinator))

        val result = tool.execute(
            GetCommandMonitorRequest(
                monitor_id = monitor.id.value,
                after_byte = 7,
                wait_ms = 12_345,
            ),
            context(),
        )

        assertEquals(conversationId, service.getConversationId)
        assertEquals(monitor.id, service.getMonitorId)
        assertEquals(7, service.getAfterByte)
        assertEquals(12_345, service.getWaitMillis)
        assertEquals(64, (result["events"] as List<*>).size)
        assertTrue(result["has_more_events"] as Boolean)
        assertTrue(result["output_is_untrusted"] as Boolean)
    }

    @Test
    fun `cancel command monitor delegates within the current conversation`() {
        val service = RecordingCommandMonitorService(cancelResult = true)
        val tool = GrzCancelCommandMonitorToolImpl(service)

        val result = tool.execute(CancelCommandMonitorRequest("monitor-1"), context())

        assertEquals(conversationId, service.cancelConversationId)
        assertEquals(CommandMonitor.Id("monitor-1"), service.cancelMonitorId)
        assertEquals(CommandMonitor.Status.CANCELLED.name, result["status"])
    }

    @Test
    fun `list commands and monitors spans workers but stays in the current conversation`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        coordinator.upsertCommandTask(commandTask("active-a", conversationId, "worker-a", terminal = false))
        coordinator.upsertCommandTask(commandTask("terminal-b", conversationId, "worker-b", terminal = true))
        coordinator.upsertCommandTask(commandTask("foreign", otherConversationId, "worker-c", terminal = false))
        coordinator.synchronizeCommandMonitor(
            monitor(
                id = "active-monitor",
                commandTaskId = "active-a",
                worker = "worker-a",
            )
        )
        coordinator.synchronizeCommandMonitor(
            monitor(
                id = "terminal-monitor",
                commandTaskId = "terminal-b",
                worker = "worker-b",
                terminal = true,
            )
        )
        coordinator.synchronizeCommandMonitor(
            monitor(
                id = "foreign-monitor",
                conversation = otherConversationId,
                commandTaskId = "foreign",
                worker = "worker-c",
            )
        )
        val tool = GrzListCommandsAndMonitorsToolImpl(commandRuntimeState(coordinator))

        val activeResult = tool.execute(ListCommandsAndMonitorsRequest(include_terminal = false), context())
        val allResult = tool.execute(ListCommandsAndMonitorsRequest(include_terminal = true), context())

        assertEquals(1, activeResult["command_count"])
        assertEquals(1, activeResult["monitor_count"])
        assertEquals(2, allResult["command_count"])
        assertEquals(2, allResult["monitor_count"])
        val allCommands = allResult["commands"] as List<*>
        assertFalse(allCommands.toString().contains("foreign"))
        assertTrue(allCommands.toString().contains("worker-a"))
        assertTrue(allCommands.toString().contains("worker-b"))
    }

    private fun context(): ToolExecutionContext =
        ToolExecutionContext(mapOf("conversationId" to conversationId.value))

    private fun commandRuntimeState(
        coordinator: InMemoryConversationRuntimeCoordinator,
    ): CommandRuntimeStateService =
        ServerCommandRuntimeStateService(
            runtimeCoordinator = coordinator,
            runtimeEventBus = InMemoryConversationRuntimeEventBus(),
            commandTaskLifecycleEventPublisher = CommandTaskLifecycleEventPublisher { },
            commandMonitorLifecycleEventPublisher = CommandMonitorLifecycleEventPublisher { },
        )

    private fun commandTask(
        id: String,
        conversation: Conversation.Id,
        worker: String,
        terminal: Boolean,
    ): CommandTask =
        CommandTask(
            id = CommandTask.Id(id),
            conversationId = conversation,
            workerId = ConversationRuntimeWorkerId(worker),
            workspaceMountId = WorkspaceMount.Id("mount-$worker"),
            command = "sleep 60",
            workingDirectory = "/workspace",
            status = if (terminal) CommandTask.Status.COMPLETED else CommandTask.Status.WORKING,
            processId = 100,
            processStartedAt = now,
            outputFile = "/tmp/$id.log",
            outputBytes = 0,
            exitCode = 0.takeIf { terminal },
            createdAt = now,
            updatedAt = now,
            completedAt = now.takeIf { terminal },
        )

    private fun monitor(
        id: String = "monitor-1",
        conversation: Conversation.Id = conversationId,
        commandTaskId: String = "command-1",
        worker: String = workerId.value,
        terminal: Boolean = false,
        outputBytes: Long = 0,
        eventOutputCursor: Long = outputBytes,
        eventCount: Long = 0,
    ): CommandMonitor =
        CommandMonitor(
            id = CommandMonitor.Id(id),
            conversationId = conversation,
            commandTaskId = CommandTask.Id(commandTaskId),
            workerId = ConversationRuntimeWorkerId(worker),
            workspaceMountId = if (worker == workerId.value) {
                workspaceMountId
            } else {
                WorkspaceMount.Id("mount-$worker")
            },
            filterCommand = "grep READY",
            mode = CommandMonitor.Mode.CONTINUOUS,
            startFrom = CommandMonitor.StartFrom.NOW,
            status = if (terminal) CommandMonitor.Status.COMPLETED else CommandMonitor.Status.WORKING,
            sourceOutputCursor = 0,
            processId = 101,
            processStartedAt = now,
            outputFile = "/tmp/$id.log",
            errorFile = "/tmp/$id.err",
            outputBytes = outputBytes,
            eventOutputCursor = eventOutputCursor,
            eventCount = eventCount,
            createdAt = now,
            updatedAt = now,
            completedAt = now.takeIf { terminal },
        )

    private class RecordingCommandMonitorService(
        private val startResult: CommandMonitor? = null,
        private val getResult: CommandMonitorOutput? = null,
        private val cancelResult: Boolean = false,
    ) : CommandMonitorService {
        var startedSpec: CommandMonitorSpec? = null
        var startedContext: ToolExecutionContext? = null
        var getConversationId: Conversation.Id? = null
        var getMonitorId: CommandMonitor.Id? = null
        var getAfterByte: Long? = null
        var getWaitMillis: Long? = null
        var cancelConversationId: Conversation.Id? = null
        var cancelMonitorId: CommandMonitor.Id? = null

        override suspend fun start(
            spec: CommandMonitorSpec,
            context: ToolExecutionContext,
        ): CommandMonitor {
            startedSpec = spec
            startedContext = context
            return checkNotNull(startResult)
        }

        override suspend fun get(
            conversationId: Conversation.Id,
            monitorId: CommandMonitor.Id,
            afterByte: Long,
            waitMillis: Long,
        ): CommandMonitorOutput? {
            getConversationId = conversationId
            getMonitorId = monitorId
            getAfterByte = afterByte
            getWaitMillis = waitMillis
            return getResult
        }

        override suspend fun cancel(
            conversationId: Conversation.Id,
            monitorId: CommandMonitor.Id,
        ): Boolean {
            cancelConversationId = conversationId
            cancelMonitorId = monitorId
            return cancelResult
        }
    }
}
