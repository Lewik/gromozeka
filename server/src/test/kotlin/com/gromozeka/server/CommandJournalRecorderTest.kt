package com.gromozeka.server

import com.gromozeka.application.service.SlotApplicationService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Instant

class CommandJournalRecorderTest {
    @Test
    fun `disabled journal leaves managed calls untouched`() = runBlocking {
        CommandJournalRecorder(provider(null)).admitManagedCalls(
            calls = listOf(commandCall("call", "echo ok")),
            target = target(),
            context = ToolExecutionContext(),
        )
    }

    @Test
    fun `enabled journal ignores non command Worker calls without requiring command provenance`() = runBlocking {
        val ordinary = ContentItem.ToolCall(
            ContentItem.ToolCall.Id("ordinary"),
            ContentItem.ToolCall.Data("grz_read_file", buildJsonObject { put("file_path", "file.txt") }),
        )
        recorder(InMemoryJournal()).admitManagedCalls(
            calls = listOf(ordinary),
            target = target(),
            context = ToolExecutionContext(),
        )
    }

    @Test
    fun `enabled journal admission failure prevents Worker dispatch`() = runBlocking {
        val failure = IllegalStateException("journal unavailable")
        val journal = object : InMemoryJournal() {
            override suspend fun admit(
                entry: CommandJournal.Entry,
                rootCall: CommandJournal.Call,
                content: List<CommandJournal.Content>,
            ): CommandJournal.Entry = throw failure
        }
        val requests = Mockito.mock(WorkerRequestService::class.java)
        val gateway = GatewayWorkerToolExecutionClient(
            requests = requests,
            timeoutMillis = 1_000,
            slots = Mockito.mock(SlotApplicationService::class.java),
            commandJournal = recorder(journal),
        )

        val thrown = assertFailsWith<IllegalStateException> {
            gateway.execute(
                target = ConversationRuntimeWorkerIdentity(
                    ConversationRuntimeWorkerId("worker"),
                    ConversationRuntimeWorkerSessionId("session"),
                ),
                executionTarget = target(),
                toolCalls = listOf(commandCall("call", "echo ok")),
                toolContext = context(),
                resolvedSecretsByToolCallId = emptyMap(),
            )
        }

        assertEquals(failure, thrown)
        Mockito.verifyNoInteractions(requests)
    }

    @Test
    fun `command and Python roots capture trusted scope arguments and source`() = runBlocking {
        val journal = InMemoryJournal()
        val recorder = recorder(journal)
        val command = commandCall("command-call", "echo ok")
        val python = ContentItem.ToolCall(
            ContentItem.ToolCall.Id("python-call"),
            ContentItem.ToolCall.Data("grz_execute_python", buildJsonObject {
                put("code", "print('ok')")
                put("working_directory", "/workspace/python")
                put("secret_env", buildJsonObject { put("TOKEN", "secret://token") })
            }),
        )

        recorder.admitManagedCalls(listOf(command, python), target(), context())

        assertEquals(2, journal.entries.size)
        val commandEntry = assertNotNull(journal.findEntryBySource(Conversation.Id("conversation"), command.id))
        val commandCall = journal.calls(commandEntry.id).single()
        assertEquals(CommandJournal.Call.Kind.COMMAND, commandCall.kind)
        assertEquals(command.id, commandCall.sourceToolCallId)
        assertEquals("/workspace", commandCall.target.workingDirectory)
        assertEquals("echo ok", journal.content(commandEntry.id).single { it.kind == CommandJournal.Content.Kind.COMMAND }.text)

        val pythonEntry = assertNotNull(journal.findEntryBySource(Conversation.Id("conversation"), python.id))
        val pythonCall = journal.calls(pythonEntry.id).single()
        assertEquals(CommandJournal.Call.Kind.PYTHON, pythonCall.kind)
        assertEquals("/workspace/python", pythonCall.target.workingDirectory)
        assertEquals("print('ok')", journal.content(pythonEntry.id).single { it.kind == CommandJournal.Content.Kind.PYTHON_SOURCE }.text)
        val arguments = journal.content(pythonEntry.id).single { it.kind == CommandJournal.Content.Kind.ARGUMENTS }.text
        assert("secret://token" in arguments)
    }

    @Test
    fun `nested SDK tool call belongs to parent command and respects logInput`() = runBlocking {
        val journal = InMemoryJournal()
        val recorder = recorder(journal)
        val root = commandCall("root-call", "python script.py")
        recorder.admitManagedCalls(listOf(root), target(), context())
        val rootEntry = assertNotNull(journal.findEntryBySource(Conversation.Id("conversation"), root.id))
        val rootCall = journal.calls(rootEntry.id).single()
        journal.append(
            CommandJournal.Event(
                id = CommandJournal.Event.Id("root-started"),
                entryId = rootEntry.id,
                callId = rootCall.id,
                sourceKey = "root-started",
                kind = CommandJournal.Event.Kind.STARTED,
                occurredAt = Instant.fromEpochSeconds(2),
                commandTaskId = CommandTask.Id("task"),
            )
        )
        val childSource = ContentItem.ToolCall(
            ContentItem.ToolCall.Id("child-call"),
            ContentItem.ToolCall.Data("grz_read_file", buildJsonObject { put("file_path", "/secret/path") }),
        )

        val recorded = assertNotNull(recorder.admitNestedCall(
            parentCommandTaskId = CommandTask.Id("task"),
            call = childSource,
            target = ConversationRuntimeTaskTarget.Server,
            context = context(),
            logInput = false,
            at = Instant.fromEpochSeconds(3),
        ))
        assertEquals(rootEntry.id, recorded.entry.id)
        assertEquals(rootCall.id, recorded.call.parentId)
        assertEquals(CommandJournal.Call.Kind.TOOL, recorded.call.kind)
        assertEquals(emptyList(), journal.content(rootEntry.id).filter { it.callId == recorded.call.id })

        recorder.recordNestedOutcome(
            recorded,
            ContentItem.ToolResult(
                toolUseId = childSource.id,
                toolName = childSource.call.name,
                result = listOf(
                    ContentItem.ToolResult.Data.Text("visible"),
                    ContentItem.ToolResult.Data.Base64Data(
                        data = "AA==",
                        mediaType = Conversation.Message.MediaType.parse("application/octet-stream"),
                    ),
                ),
                isError = false,
            ),
            at = Instant.fromEpochSeconds(4),
        )
        assertEquals("visible", journal.content(rootEntry.id).single { it.callId == recorded.call.id }.text)
        assertEquals(CommandJournal.Event.Kind.COMPLETED, journal.events(rootEntry.id).last().kind)
    }

    @Test
    fun `recursive command SDK call uses its immediate managed parent`() = runBlocking {
        val journal = InMemoryJournal()
        val recorder = recorder(journal)
        val rootSource = commandCall("root-call", "python script.py")
        recorder.admitManagedCalls(listOf(rootSource), target(), context())
        val entry = assertNotNull(journal.findEntryBySource(Conversation.Id("conversation"), rootSource.id))
        val rootCall = journal.calls(entry.id).single()
        journal.append(
            CommandJournal.Event(
                id = CommandJournal.Event.Id("root-started"),
                entryId = entry.id,
                callId = rootCall.id,
                sourceKey = "root-started",
                kind = CommandJournal.Event.Kind.STARTED,
                occurredAt = Instant.fromEpochSeconds(2),
                commandTaskId = CommandTask.Id("root-task"),
            )
        )
        val managedSource = commandCall("managed-child", "echo nested")
        val managed = assertNotNull(
            recorder.admitNestedCall(
                parentCommandTaskId = CommandTask.Id("root-task"),
                call = managedSource,
                target = target(),
                context = context(),
                logInput = true,
                at = Instant.fromEpochSeconds(3),
            )
        )
        journal.append(
            CommandJournal.Event(
                id = CommandJournal.Event.Id("managed-started"),
                entryId = entry.id,
                callId = managed.call.id,
                sourceKey = "managed-started",
                kind = CommandJournal.Event.Kind.STARTED,
                occurredAt = Instant.fromEpochSeconds(4),
                commandTaskId = CommandTask.Id("managed-task"),
            )
        )
        val descendantSource = ContentItem.ToolCall(
            ContentItem.ToolCall.Id("descendant-call"),
            ContentItem.ToolCall.Data("grz_read_file", buildJsonObject { put("file_path", "child.txt") }),
        )

        val descendant = assertNotNull(
            recorder.admitNestedCall(
                parentCommandTaskId = CommandTask.Id("managed-task"),
                call = descendantSource,
                target = ConversationRuntimeTaskTarget.Server,
                context = context(),
                logInput = true,
                at = Instant.fromEpochSeconds(5),
            )
        )

        assertEquals(entry.id, descendant.entry.id)
        assertEquals(managed.call.id, descendant.call.parentId)
        assertEquals(rootCall.id, managed.call.parentId)
    }

    @Test
    fun `caller report is explicit idempotent and never Worker confirmed`() = runBlocking {
        val journal = InMemoryJournal()
        val recorder = recorder(journal)
        val sourceId = ContentItem.ToolCall.Id("report-call")
        val observedAt = Instant.fromEpochSeconds(50)
        val arguments = buildJsonObject {
            put("command", "kubectl get pods")
            put("target", "production cluster")
            put("output", "pod-a Running")
            put("exit_code", 0)
        }

        val first = recorder.reportByCaller(
            sourceToolCallId = sourceId,
            toolName = "grz_command_journal_report",
            kind = CommandJournal.Call.Kind.COMMAND,
            commandOrCode = "kubectl get pods",
            rawArguments = arguments,
            workingDirectory = "/workspace",
            output = "pod-a Running",
            statusMessage = "reported success",
            exitCode = 0,
            observedAt = observedAt,
            context = context(),
            at = Instant.fromEpochSeconds(100),
        )
        val repeated = recorder.reportByCaller(
            sourceToolCallId = sourceId,
            toolName = "grz_command_journal_report",
            kind = CommandJournal.Call.Kind.COMMAND,
            commandOrCode = "kubectl get pods",
            rawArguments = arguments,
            workingDirectory = "/workspace",
            output = "pod-a Running",
            statusMessage = "reported success",
            exitCode = 0,
            observedAt = observedAt,
            context = context(),
            at = Instant.fromEpochSeconds(200),
        )

        assertEquals(first, repeated)
        val entry = first.entry
        assertEquals(CommandJournal.Entry.Origin.REPORTED_BY_CALLER, entry.origin)
        assertEquals(Instant.fromEpochSeconds(100), entry.createdAt)
        assertEquals(1, journal.entries.size)
        assertEquals(listOf(CommandJournal.Event.Kind.REPORTED), journal.events(entry.id).map { it.kind })
        assertEquals(observedAt, journal.events(entry.id).single().occurredAt)
        assertEquals(
            setOf(
                CommandJournal.Content.Kind.ARGUMENTS,
                CommandJournal.Content.Kind.COMMAND,
                CommandJournal.Content.Kind.REPORTED_OUTPUT,
            ),
            journal.content(entry.id).mapTo(linkedSetOf()) { it.kind },
        )
    }

    @Test
    fun `command SDK caller report is a child entry of its managed command`() = runBlocking {
        val journal = InMemoryJournal()
        val recorder = recorder(journal)
        val rootSource = commandCall("root-call", "python script.py")
        recorder.admitManagedCalls(listOf(rootSource), target(), context())
        val rootEntry = assertNotNull(journal.findEntryBySource(Conversation.Id("conversation"), rootSource.id))
        val rootCall = journal.calls(rootEntry.id).single()
        val commandTaskId = CommandTask.Id("script-command")
        journal.append(
            CommandJournal.Event(
                id = CommandJournal.Event.Id("root-started"),
                entryId = rootEntry.id,
                callId = rootCall.id,
                sourceKey = "root-started",
                kind = CommandJournal.Event.Kind.STARTED,
                occurredAt = Instant.fromEpochSeconds(2),
                commandTaskId = commandTaskId,
            )
        )
        val reportSourceId = ContentItem.ToolCall.Id("report-call")
        val report = recorder.reportByCaller(
            sourceToolCallId = reportSourceId,
            toolName = COMMAND_JOURNAL_REPORT_TOOL_NAME,
            kind = CommandJournal.Call.Kind.COMMAND,
            commandOrCode = "kubectl get pods",
            rawArguments = buildJsonObject { put("command", "kubectl get pods") },
            workingDirectory = "/workspace",
            output = "pod-a Running",
            statusMessage = null,
            exitCode = 0,
            observedAt = null,
            context = context().copy(
                commandJournalSource = CommandJournal.Source(
                    kind = CommandJournal.Source.Kind.COMMAND_SDK_MCP,
                    parentCommandTaskId = commandTaskId,
                )
            ),
            at = Instant.fromEpochSeconds(3),
        )

        assertEquals(CommandJournal.Entry.Origin.REPORTED_BY_CALLER, report.entry.origin)
        assertEquals(rootEntry.id, report.entry.parentEntryId)
        assertEquals(report.call, journal.findCallBySource(Conversation.Id("conversation"), reportSourceId))
        assertEquals(2, journal.entries.size)
    }

    @Test
    fun `managed root admission is idempotent across delivery retry`() = runBlocking {
        val journal = InMemoryJournal()
        val recorder = recorder(journal)
        val call = commandCall("retry-call", "echo ok")

        recorder.admitManagedCalls(listOf(call), target(), context(), Instant.fromEpochSeconds(1))
        recorder.admitManagedCalls(listOf(call), target(), context(), Instant.fromEpochSeconds(2))

        val entry = assertNotNull(journal.findEntryBySource(Conversation.Id("conversation"), call.id))
        assertEquals(Instant.fromEpochSeconds(1), entry.createdAt)
        assertEquals(1, journal.entries.size)
        assertEquals(listOf(CommandJournal.Event.Kind.ACCEPTED), journal.events(entry.id).map { it.kind })
    }

    private fun recorder(journal: CommandJournal): CommandJournalRecorder =
        CommandJournalRecorder(provider(journal))

    @Suppress("UNCHECKED_CAST")
    private fun provider(journal: CommandJournal?): ObjectProvider<CommandJournal> {
        val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<CommandJournal>
        Mockito.`when`(provider.ifAvailable).thenReturn(journal)
        return provider
    }

    private fun commandCall(id: String, command: String) = ContentItem.ToolCall(
        ContentItem.ToolCall.Id(id),
        ContentItem.ToolCall.Data("grz_execute_command", buildJsonObject {
            put("command", command)
            put("working_directory", "/workspace")
        }),
    )

    private fun target() = ConversationRuntimeTaskTarget.Worker(
        workerId = ConversationRuntimeWorkerId("worker"),
        workspaceMountId = WorkspaceMount.Id("mount"),
    )

    private fun context() = ToolExecutionContext(
        values = mapOf(
            TOOL_CONTEXT_USER_ID to "user",
            TOOL_CONTEXT_PROJECT_ID to "project",
            TOOL_CONTEXT_CONVERSATION_ID to "conversation",
            TOOL_CONTEXT_THREAD_ID to "thread",
            TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent",
            TOOL_CONTEXT_WORKSPACE_ID to "workspace",
            TOOL_CONTEXT_WORKSPACE_ROOT_PATH to "/workspace",
        ),
        commandJournalSource = CommandJournal.Source(
            CommandJournal.Source.Kind.CONVERSATION_TOOL_CALL,
            sourceRuntimeTaskId = ConversationRuntimeTask.Id("runtime-task"),
        ),
    )

    private open class InMemoryJournal : CommandJournal {
        val entries = linkedMapOf<CommandJournal.Entry.Id, CommandJournal.Entry>()
        private val callValues = linkedMapOf<CommandJournal.Call.Id, CommandJournal.Call>()
        private val eventValues = linkedMapOf<CommandJournal.Event.Id, CommandJournal.Event>()
        private val contentValues = linkedMapOf<String, CommandJournal.Content>()

        override suspend fun admit(entry: CommandJournal.Entry, rootCall: CommandJournal.Call, content: List<CommandJournal.Content>): CommandJournal.Entry {
            entries[entry.id]?.let { require(it == entry) } ?: run { entries[entry.id] = entry }
            append(rootCall, content)
            return entry
        }

        override suspend fun append(call: CommandJournal.Call, content: List<CommandJournal.Content>): CommandJournal.Call {
            callValues[call.id]?.let { require(it == call) } ?: run { callValues[call.id] = call }
            content.forEach(::store)
            return call
        }

        override suspend fun append(event: CommandJournal.Event, content: List<CommandJournal.Content>): CommandJournal.Event {
            eventValues[event.id]?.let { require(it == event) } ?: run { eventValues[event.id] = event }
            content.forEach(::store)
            return event
        }

        override suspend fun findEntry(id: CommandJournal.Entry.Id) = entries[id]

        override suspend fun findEntryBySource(conversationId: Conversation.Id, sourceToolCallId: ContentItem.ToolCall.Id) =
            entries.values.singleOrNull { it.conversationId == conversationId && it.sourceToolCallId == sourceToolCallId }

        override suspend fun findCallBySource(
            conversationId: Conversation.Id,
            sourceToolCallId: ContentItem.ToolCall.Id,
        ) = callValues.values.singleOrNull { call ->
            call.sourceToolCallId == sourceToolCallId && entries[call.entryId]?.conversationId == conversationId
        }

        override suspend fun findCallByCommandTask(commandTaskId: CommandTask.Id): CommandJournal.Call? =
            eventValues.values.firstOrNull { it.commandTaskId == commandTaskId }?.let { callValues[it.callId] }

        override suspend fun calls(entryId: CommandJournal.Entry.Id) = callValues.values.filter { it.entryId == entryId }
        override suspend fun events(entryId: CommandJournal.Entry.Id) = eventValues.values.filter { it.entryId == entryId }
        override suspend fun content(entryId: CommandJournal.Entry.Id) = contentValues.values.filter { it.entryId == entryId }

        private fun store(value: CommandJournal.Content) {
            val key = "${value.callId.value}:${value.eventId?.value}:${value.kind}:${value.sequence}"
            contentValues[key]?.let { require(it == value) } ?: run { contentValues[key] = value }
        }
    }
}
