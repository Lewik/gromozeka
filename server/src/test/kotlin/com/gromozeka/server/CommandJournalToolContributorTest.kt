package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_CALL_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Instant

class CommandJournalToolContributorTest {
    @Test
    fun `tool contributor is absent when Command Journal is disabled`() {
        ApplicationContextRunner()
            .withUserConfiguration(CommandJournalToolContributor::class.java)
            .run { context ->
                assertFalse(context.containsBean("commandJournalToolContributor"))
                assertFalse(context.startupFailure != null)
            }
    }

    @Test
    fun `only self report preloads and records explicit caller provenance`() = runBlocking {
        val user = User(
            id = User.Id("user"),
            displayName = "User",
            status = User.Status.ACTIVE,
            role = User.Role.OWNER,
            createdAt = Instant.fromEpochSeconds(1),
            updatedAt = Instant.fromEpochSeconds(1),
        )
        val journal = InMemoryJournal()
        val projectAccess = Mockito.mock(ProjectAccessService::class.java)
        val authorization = Mockito.mock(GromozekaRemoteAuthorization::class.java)
        val conversation = Mockito.mock(Conversation::class.java)
        Mockito.`when`(conversation.projectId).thenReturn(Project.Id("project"))
        Mockito.`when`(conversation.currentThread).thenReturn(Conversation.Thread.Id("thread"))
        Mockito.`when`(
            authorization.requireConversation(user, Conversation.Id("conversation"), ProjectPermission.WRITE)
        ).thenReturn(conversation)
        val factory = ServerJsonToolFactory(object : UserDirectoryService {
            override suspend fun findActiveById(id: User.Id): User? = user.takeIf { it.id == id }
            override suspend fun listActive(): List<User> = listOf(user)
        })
        val contributor = CommandJournalToolContributor(
            toolFactory = factory,
            journal = journal,
            reader = journal,
            recorder = CommandJournalRecorder(provider(journal)),
            projectAccess = projectAccess,
            authorization = authorization,
        )

        assertEquals(
            setOf(
                "grz_command_journal_report",
                "grz_command_journal_search",
                "grz_command_journal_get",
                "grz_command_journal_read_content",
                "grz_command_journal_stats",
            ),
            contributor.callbacks.mapTo(linkedSetOf()) { it.definition.name },
        )
        contributor.callbacks.forEach { callback ->
            assertEquals(
                if (callback.definition.name == "grz_command_journal_report") {
                    AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE
                } else {
                    AiToolLoadingPolicy.ON_DEMAND
                },
                callback.metadata.loadingPolicy,
            )
        }

        val report = contributor.callbacks.single { it.definition.name == "grz_command_journal_report" }
        val context = ToolExecutionContext(
            values = mapOf(
                TOOL_CONTEXT_USER_ID to user.id.value,
                TOOL_CONTEXT_PROJECT_ID to "project",
                TOOL_CONTEXT_CONVERSATION_ID to "conversation",
                TOOL_CONTEXT_THREAD_ID to "thread",
                TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent",
                TOOL_CONTEXT_TOOL_CALL_ID to "report-call",
            )
        )
        val result = report.callResult(
            buildJsonObject {
                put("command", "kubectl get pods")
                put("target", "production")
                put("output", "pod-a Running")
                put("exit_code", 0)
            }.toString(),
            context,
        )

        assertFalse(result.isError)
        val structured = assertNotNull(result.structuredContent)
        assertEquals(JsonPrimitive(true), structured["success"])
        val payload = assertNotNull(structured["result"]?.jsonObject)
        val entry = assertNotNull(journal.findEntry(CommandJournal.Entry.Id(payload.getValue("entry_id").jsonPrimitive.content)))
        assertEquals(CommandJournal.Entry.Origin.REPORTED_BY_CALLER, entry.origin)
        assertEquals("kubectl get pods", journal.content(entry.id).single { it.kind == CommandJournal.Content.Kind.COMMAND }.text)
        assertEquals("pod-a Running", journal.content(entry.id).single { it.kind == CommandJournal.Content.Kind.REPORTED_OUTPUT }.text)
        assertEquals(CommandJournal.Event.Kind.REPORTED, journal.events(entry.id).single().kind)
        Mockito.verify(authorization).requireConversation(user, Conversation.Id("conversation"), ProjectPermission.WRITE)
    }

    @Suppress("UNCHECKED_CAST")
    private fun provider(journal: CommandJournal): ObjectProvider<CommandJournal> {
        val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<CommandJournal>
        Mockito.`when`(provider.ifAvailable).thenReturn(journal)
        return provider
    }

    private class InMemoryJournal : CommandJournal, CommandJournal.Reader {
        private val entries = linkedMapOf<CommandJournal.Entry.Id, CommandJournal.Entry>()
        private val callValues = linkedMapOf<CommandJournal.Call.Id, CommandJournal.Call>()
        private val eventValues = linkedMapOf<String, CommandJournal.Event>()
        private val contentValues = linkedMapOf<String, CommandJournal.Content>()

        override suspend fun admit(
            entry: CommandJournal.Entry,
            rootCall: CommandJournal.Call,
            content: List<CommandJournal.Content>,
        ): CommandJournal.Entry {
            entries[entry.id]?.let { require(it == entry) } ?: run { entries[entry.id] = entry }
            append(rootCall, content)
            return entry
        }

        override suspend fun append(
            call: CommandJournal.Call,
            content: List<CommandJournal.Content>,
        ): CommandJournal.Call {
            callValues[call.id]?.let { require(it == call) } ?: run { callValues[call.id] = call }
            content.forEach(::store)
            return call
        }

        override suspend fun append(
            event: CommandJournal.Event,
            content: List<CommandJournal.Content>,
        ): CommandJournal.Event {
            eventValues[event.sourceKey]?.let { require(it == event) } ?: run { eventValues[event.sourceKey] = event }
            content.forEach(::store)
            return event
        }

        override suspend fun findEntry(id: CommandJournal.Entry.Id) = entries[id]

        override suspend fun findEntryBySource(
            conversationId: Conversation.Id,
            sourceToolCallId: Conversation.Message.ContentItem.ToolCall.Id,
        ) = entries.values.singleOrNull {
            it.conversationId == conversationId && it.sourceToolCallId == sourceToolCallId
        }

        override suspend fun findEntryByCommandTask(commandTaskId: CommandTask.Id): CommandJournal.Entry? = null
        override suspend fun calls(entryId: CommandJournal.Entry.Id) = callValues.values.filter { it.entryId == entryId }
        override suspend fun events(entryId: CommandJournal.Entry.Id) = eventValues.values.filter { it.entryId == entryId }
        override suspend fun content(entryId: CommandJournal.Entry.Id) = contentValues.values.filter { it.entryId == entryId }

        override suspend fun search(query: CommandJournal.SearchQuery): CommandJournal.SearchPage =
            error("Not used by this test")

        override suspend fun get(
            entryId: CommandJournal.Entry.Id,
            callLimit: Int,
            eventLimit: Int,
        ): CommandJournal.Snapshot? = error("Not used by this test")

        override suspend fun readContent(query: CommandJournal.ContentQuery): CommandJournal.ContentPage =
            error("Not used by this test")

        override suspend fun statistics(projectId: Project.Id): CommandJournal.Statistics =
            error("Not used by this test")

        private fun store(value: CommandJournal.Content) {
            val key = "${value.callId.value}:${value.eventId?.value}:${value.kind}:${value.sequence}"
            contentValues[key]?.let { require(it == value) } ?: run { contentValues[key] = value }
        }
    }
}
