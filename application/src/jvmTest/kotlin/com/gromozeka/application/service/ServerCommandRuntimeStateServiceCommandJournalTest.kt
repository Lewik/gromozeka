package com.gromozeka.application.service

import com.gromozeka.domain.model.BinaryContent
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserNotification
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class ServerCommandRuntimeStateServiceCommandJournalTest {
    @Test
    fun `nested command task lifecycle records stable start terminal status and retained text output`() = runBlocking {
        val coordinator = Mockito.mock(ConversationRuntimeCoordinator::class.java)
        val journal = InMemoryJournal()
        val entry = CommandJournal.Entry(
            id = CommandJournal.Entry.Id("entry"),
            origin = CommandJournal.Entry.Origin.CONVERSATION_TOOL_CALL,
            actorUserId = User.Id("user"),
            projectId = Project.Id("project"),
            conversationId = Conversation.Id("conversation"),
            threadId = Conversation.Thread.Id("thread"),
            sourceToolCallId = ContentItem.ToolCall.Id("root-call"),
            createdAt = Instant.fromEpochSeconds(1),
        )
        val rootCall = CommandJournal.Call(
            id = CommandJournal.Call.Id("root-call-record"),
            entryId = entry.id,
            sourceToolCallId = entry.sourceToolCallId,
            kind = CommandJournal.Call.Kind.PYTHON,
            toolName = "grz_execute_python",
            target = CommandJournal.Call.Target(
                workerId = ConversationRuntimeWorkerId("worker"),
                workspaceMountId = WorkspaceMount.Id("mount"),
            ),
            createdAt = entry.createdAt,
        )
        val nestedCall = CommandJournal.Call(
            id = CommandJournal.Call.Id("nested-call-record"),
            entryId = entry.id,
            parentId = rootCall.id,
            sourceToolCallId = ContentItem.ToolCall.Id("call"),
            kind = CommandJournal.Call.Kind.COMMAND,
            toolName = "grz_execute_command",
            target = rootCall.target,
            createdAt = Instant.fromEpochSeconds(2),
        )
        journal.admit(entry, rootCall)
        journal.append(nestedCall)
        val working = task(CommandTask.Status.WORKING)
        val completed = working.copy(
            status = CommandTask.Status.COMPLETED,
            outputBytes = 21,
            exitCode = 0,
            statusMessage = "Command completed",
            updatedAt = Instant.fromEpochSeconds(5),
            completedAt = Instant.fromEpochSeconds(5),
            terminalOutputStartByte = 7,
            terminalOutputContent = BinaryContent.fromBytes(
                byteArrayOf(0x9f.toByte(), 0x90.toByte(), 0xb1.toByte()) + "tail-output".encodeToByteArray()
            ),
        )
        Mockito.`when`(coordinator.upsertCommandTask(working)).thenReturn(CommandTaskUpsertResult(working))
        Mockito.`when`(coordinator.upsertCommandTask(completed)).thenReturn(CommandTaskUpsertResult(completed))
        val service = ServerCommandRuntimeStateService(
            runtimeCoordinator = coordinator,
            runtimeStateSyncService = Mockito.mock(ConversationRuntimeStateSyncService::class.java),
            commandTaskLifecycleEventPublisher = CommandTaskLifecycleEventPublisher { },
            commandMonitorLifecycleEventPublisher = CommandMonitorLifecycleEventPublisher { },
            commandJournal = journal,
        )

        service.upsertCommandTask(working)
        service.upsertCommandTask(completed)
        service.upsertCommandTask(completed)

        val events = journal.events(entry.id)
        assertEquals(
            listOf(CommandJournal.Event.Kind.STARTED, CommandJournal.Event.Kind.COMPLETED),
            events.map { it.kind },
        )
        assertEquals(listOf(nestedCall.id, nestedCall.id), events.map { it.callId })
        assertEquals(nestedCall, journal.findCallByCommandTask(CommandTask.Id("task")))
        val output = assertNotNull(journal.content(entry.id).singleOrNull())
        assertEquals(nestedCall.id, output.callId)
        assertEquals("tail-output", output.text)
        assertEquals(10, output.startByte)
        assertEquals(21, output.endByte)
        assertEquals(21, output.totalBytes)
        assertEquals(true, output.truncatedBefore)
        assertEquals(false, output.truncatedAfter)
    }

    @Test
    fun `best effort lifecycle storage failure preserves task upsert and notifies the actor`() = runBlocking {
        val coordinator = Mockito.mock(ConversationRuntimeCoordinator::class.java)
        var appendAttempts = 0
        val journal = object : InMemoryJournal() {
            override suspend fun append(
                event: CommandJournal.Event,
                content: List<CommandJournal.Content>,
            ): CommandJournal.Event {
                appendAttempts++
                throw CommandJournal.StorageException(SQLException("database unavailable"))
            }
        }
        val entry = CommandJournal.Entry(
            id = CommandJournal.Entry.Id("entry"),
            origin = CommandJournal.Entry.Origin.CONVERSATION_TOOL_CALL,
            actorUserId = User.Id("user"),
            projectId = Project.Id("project"),
            conversationId = Conversation.Id("conversation"),
            threadId = Conversation.Thread.Id("thread"),
            sourceToolCallId = ContentItem.ToolCall.Id("call"),
            createdAt = Instant.fromEpochSeconds(1),
        )
        journal.admit(
            entry,
            CommandJournal.Call(
                id = CommandJournal.Call.Id("call"),
                entryId = entry.id,
                sourceToolCallId = entry.sourceToolCallId,
                kind = CommandJournal.Call.Kind.COMMAND,
                toolName = "grz_execute_command",
                target = CommandJournal.Call.Target(),
                createdAt = entry.createdAt,
            ),
        )
        val working = task(CommandTask.Status.WORKING)
        Mockito.`when`(coordinator.upsertCommandTask(working)).thenReturn(CommandTaskUpsertResult(working))
        val notifications = mutableListOf<UserNotification>()
        val service = ServerCommandRuntimeStateService(
            runtimeCoordinator = coordinator,
            runtimeStateSyncService = Mockito.mock(ConversationRuntimeStateSyncService::class.java),
            commandTaskLifecycleEventPublisher = CommandTaskLifecycleEventPublisher { },
            commandMonitorLifecycleEventPublisher = CommandMonitorLifecycleEventPublisher { },
            commandJournal = journal,
            commandJournalWritePolicy = CommandJournalWritePolicy(
                CommandJournal.FailureMode.BEST_EFFORT,
                UserNotificationPublisher { user, draft ->
                    UserNotification(
                        id = UserNotification.Id("notification"),
                        userId = user,
                        title = draft.title,
                        message = draft.message,
                        level = draft.level,
                        createdAt = Instant.fromEpochSeconds(2),
                        conversationId = draft.conversationId,
                    ).also(notifications::add)
                },
            ),
        )

        assertEquals(working, service.upsertCommandTask(working).task)
        assertEquals(1, appendAttempts)
        assertEquals(User.Id("user"), notifications.single().userId)
    }

    private fun task(status: CommandTask.Status) = CommandTask(
        id = CommandTask.Id("task"),
        conversationId = Conversation.Id("conversation"),
        workerId = ConversationRuntimeWorkerId("worker"),
        workspaceMountId = WorkspaceMount.Id("mount"),
        command = "echo output",
        workingDirectory = "/workspace",
        status = status,
        processId = 10,
        processStartedAt = Instant.fromEpochSeconds(2),
        processTreeId = 10,
        outputFile = "/tmp/output",
        outputBytes = 0,
        statusMessage = "Command is running",
        createdAt = Instant.fromEpochSeconds(1),
        updatedAt = Instant.fromEpochSeconds(2),
        launchReference = BackgroundActivityLaunchReference("request", "call"),
    )

    private open class InMemoryJournal : CommandJournal {
        private val entries = linkedMapOf<CommandJournal.Entry.Id, CommandJournal.Entry>()
        private val calls = linkedMapOf<CommandJournal.Call.Id, CommandJournal.Call>()
        private val events = linkedMapOf<String, CommandJournal.Event>()
        private val content = linkedMapOf<String, CommandJournal.Content>()

        override suspend fun admit(entry: CommandJournal.Entry, rootCall: CommandJournal.Call, content: List<CommandJournal.Content>): CommandJournal.Entry {
            entries[entry.id] = entry
            calls[rootCall.id] = rootCall
            content.forEach(::store)
            return entry
        }

        override suspend fun recordReport(
            entry: CommandJournal.Entry,
            rootCall: CommandJournal.Call,
            event: CommandJournal.Event,
            content: List<CommandJournal.Content>,
        ): CommandJournal.Entry {
            admit(entry, rootCall, content.filter { it.eventId == null })
            append(event, content.filter { it.eventId == event.id })
            return entry
        }

        override suspend fun append(call: CommandJournal.Call, content: List<CommandJournal.Content>): CommandJournal.Call {
            calls[call.id] = call
            content.forEach(::store)
            return call
        }

        override suspend fun append(event: CommandJournal.Event, content: List<CommandJournal.Content>): CommandJournal.Event {
            events[event.sourceKey]?.let { require(it == event) } ?: run { events[event.sourceKey] = event }
            content.forEach(::store)
            return event
        }

        override suspend fun findEntry(id: CommandJournal.Entry.Id) = entries[id]
        override suspend fun findEntryBySource(conversationId: Conversation.Id, sourceToolCallId: ContentItem.ToolCall.Id) =
            entries.values.singleOrNull { it.conversationId == conversationId && it.sourceToolCallId == sourceToolCallId }
        override suspend fun findCallBySource(conversationId: Conversation.Id, sourceToolCallId: ContentItem.ToolCall.Id) =
            calls.values.singleOrNull { call ->
                call.sourceToolCallId == sourceToolCallId && entries[call.entryId]?.conversationId == conversationId
            }
        override suspend fun findCallByCommandTask(commandTaskId: CommandTask.Id) =
            events.values.firstOrNull { it.commandTaskId == commandTaskId }?.let { calls[it.callId] }
        override suspend fun calls(entryId: CommandJournal.Entry.Id) = calls.values.filter { it.entryId == entryId }
        override suspend fun events(entryId: CommandJournal.Entry.Id) = events.values.filter { it.entryId == entryId }
        override suspend fun content(entryId: CommandJournal.Entry.Id) = content.values.filter { it.entryId == entryId }

        private fun store(value: CommandJournal.Content) {
            val key = "${value.callId.value}:${value.eventId?.value}:${value.kind}:${value.sequence}"
            content[key]?.let { require(it == value) } ?: run { content[key] = value }
        }
    }
}
