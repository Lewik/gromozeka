package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolCallback
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
    fun `command SDK self report creates one correlated child entry`() = runBlocking {
        val journal = InMemoryJournal()
        val recorder = CommandJournalRecorder(provider(journal))
        var callbacks = emptyList<AiToolCallback>()
        val fixture = UnifiedMcpTestFixture(
            object : AiToolProvider {
                override fun getTools(): List<AiToolCallback> = callbacks
            },
            recorder,
        ).initialize()
        val authorization = Mockito.mock(GromozekaRemoteAuthorization::class.java)
        Mockito.`when`(
            authorization.requireConversation(fixture.user, fixture.conversation.id, ProjectPermission.WRITE)
        ).thenReturn(fixture.conversation)
        callbacks = CommandJournalToolContributor(
            toolFactory = ServerJsonToolFactory(fixture.users),
            reader = journal,
            recorder = recorder,
            projectAccess = fixture.projects,
            authorization = authorization,
        ).callbacks
        val rootEntry = CommandJournal.Entry(
            id = CommandJournal.Entry.Id("root-entry"),
            origin = CommandJournal.Entry.Origin.CONVERSATION_TOOL_CALL,
            actorUserId = fixture.user.id,
            projectId = fixture.project.id,
            conversationId = fixture.conversation.id,
            threadId = fixture.conversation.currentThread,
            agentDefinitionId = fixture.agent.id,
            sourceToolCallId = ContentItem.ToolCall.Id("root-source"),
            createdAt = Instant.fromEpochSeconds(1),
        )
        val rootCall = CommandJournal.Call(
            id = CommandJournal.Call.Id("root-call"),
            entryId = rootEntry.id,
            sourceToolCallId = rootEntry.sourceToolCallId,
            kind = CommandJournal.Call.Kind.COMMAND,
            toolName = "grz_execute_command",
            target = CommandJournal.Call.Target(),
            createdAt = rootEntry.createdAt,
        )
        journal.admit(rootEntry, rootCall)
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
        val caller = AuthenticatedMcpCaller.CommandScript(
            CommandScriptPrincipal(
                fixture.user,
                commandTaskId,
                fixture.conversation.id,
                fixture.project.id,
                fixture.agent.id,
            )
        )

        val result = fixture.service.call(
            caller,
            COMMAND_JOURNAL_REPORT_TOOL_NAME,
            buildJsonObject { put("command", "kubectl get pods") },
        )

        assertFalse(result.isError)
        val payload = assertNotNull(result.structuredContent?.get("result")?.jsonObject)
        val reportEntry = assertNotNull(
            journal.findEntry(CommandJournal.Entry.Id(payload.getValue("entry_id").jsonPrimitive.content))
        )
        val reportCall = journal.calls(reportEntry.id).single()
        assertEquals(rootEntry.id, reportEntry.parentEntryId)
        assertEquals(1, journal.calls(rootEntry.id).size, "The report must not also create a generic nested call")
        assertEquals(
            reportCall,
            journal.findCallBySource(fixture.conversation.id, assertNotNull(reportCall.sourceToolCallId)),
        )
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
            ),
            commandJournalSource = CommandJournal.Source(CommandJournal.Source.Kind.CONVERSATION_TOOL_CALL),
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

        val secondContext = context.withValue(TOOL_CONTEXT_TOOL_CALL_ID, "report-call-2")
        val secondReport = report.callResult(
            buildJsonObject {
                put("command", "echo second")
                put("output", "x".repeat(300))
            }.toString(),
            secondContext,
        )
        assertFalse(secondReport.isError)
        val secondEntryId = assertNotNull(secondReport.structuredContent?.get("result")?.jsonObject)
            .getValue("entry_id").jsonPrimitive.content

        val searchTool = contributor.callbacks.single { it.definition.name == "grz_command_journal_search" }
        val firstSearch = searchTool.callResult(buildJsonObject { put("limit", 1) }.toString(), context)
        assertFalse(firstSearch.isError)
        val firstPage = assertNotNull(firstSearch.structuredContent?.get("result")?.jsonObject)
        val firstEntries = firstPage.getValue("entries") as kotlinx.serialization.json.JsonArray
        assertEquals(1, firstEntries.size)
        val cursor = assertNotNull(firstPage["nextCursor"]?.jsonObject)
        assertEquals(setOf("createdAt", "entryId"), cursor.keys)

        val secondSearch = searchTool.callResult(
            buildJsonObject {
                put("limit", 1)
                put("before", cursor)
            }.toString(),
            context,
        )
        assertFalse(secondSearch.isError)
        val secondEntries = assertNotNull(secondSearch.structuredContent?.get("result")?.jsonObject)
            .getValue("entries") as kotlinx.serialization.json.JsonArray
        assertEquals(1, secondEntries.size)
        assertFalse(firstEntries.single() == secondEntries.single())

        val get = contributor.callbacks.single { it.definition.name == "grz_command_journal_get" }
            .callResult(buildJsonObject { put("entry_id", entry.id.value) }.toString(), context)
        assertFalse(get.isError)

        val read = contributor.callbacks.single { it.definition.name == "grz_command_journal_read_content" }
            .callResult(buildJsonObject { put("entry_id", entry.id.value) }.toString(), context)
        assertFalse(read.isError)
        assertEquals(3, read.structuredContent?.get("result")?.jsonObject?.get("items")?.let { it as kotlinx.serialization.json.JsonArray }?.size)

        val firstContentRead = contributor.callbacks.single { it.definition.name == "grz_command_journal_read_content" }
            .callResult(
                buildJsonObject {
                    put("entry_id", secondEntryId)
                    put("kinds", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("REPORTED_OUTPUT"))))
                    put("max_characters_per_item", 256)
                }.toString(),
                context,
            )
        assertFalse(firstContentRead.isError)
        val firstContentPage = assertNotNull(firstContentRead.structuredContent?.get("result")?.jsonObject)
        val firstContentItems = firstContentPage.getValue("items") as kotlinx.serialization.json.JsonArray
        val firstOutputSlice = firstContentItems.last().jsonObject
        assertEquals(256, firstOutputSlice.getValue("text").jsonPrimitive.content.length)
        assertEquals("0", firstOutputSlice.getValue("characterStart").jsonPrimitive.content)
        assertEquals("256", firstOutputSlice.getValue("characterEnd").jsonPrimitive.content)
        val contentCursor = assertNotNull(firstContentPage["nextCursor"]?.jsonObject)

        val secondContentRead = contributor.callbacks.single { it.definition.name == "grz_command_journal_read_content" }
            .callResult(
                buildJsonObject {
                    put("entry_id", secondEntryId)
                    put("kinds", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("REPORTED_OUTPUT"))))
                    put("max_characters_per_item", 256)
                    put("cursor", contentCursor)
                }.toString(),
                context,
            )
        assertFalse(secondContentRead.isError)
        val secondContentPage = assertNotNull(secondContentRead.structuredContent?.get("result")?.jsonObject)
        val secondOutputSlice = (secondContentPage.getValue("items") as kotlinx.serialization.json.JsonArray).single().jsonObject
        assertEquals(44, secondOutputSlice.getValue("text").jsonPrimitive.content.length)
        assertEquals("256", secondOutputSlice.getValue("characterStart").jsonPrimitive.content)
        assertEquals("300", secondOutputSlice.getValue("characterEnd").jsonPrimitive.content)
        assertEquals(null, secondContentPage["nextCursor"])

        val stats = contributor.callbacks.single { it.definition.name == "grz_command_journal_stats" }
            .callResult("{}", context)
        assertFalse(stats.isError)
        assertEquals(2L, stats.structuredContent?.get("result")?.jsonObject?.get("entryCount")?.jsonPrimitive?.content?.toLong())

        Mockito.verify(authorization, Mockito.times(2))
            .requireConversation(user, Conversation.Id("conversation"), ProjectPermission.WRITE)
        Mockito.verify(projectAccess, Mockito.atLeast(4)).requirePermission(user.id, Project.Id("project"), ProjectPermission.READ)
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

        override suspend fun findCallBySource(
            conversationId: Conversation.Id,
            sourceToolCallId: Conversation.Message.ContentItem.ToolCall.Id,
        ) = callValues.values.singleOrNull { call ->
            call.sourceToolCallId == sourceToolCallId && entries[call.entryId]?.conversationId == conversationId
        }
        override suspend fun findCallByCommandTask(commandTaskId: CommandTask.Id): CommandJournal.Call? =
            eventValues.values.firstOrNull { it.commandTaskId == commandTaskId }?.let { callValues[it.callId] }
        override suspend fun calls(entryId: CommandJournal.Entry.Id) = callValues.values.filter { it.entryId == entryId }
        override suspend fun events(entryId: CommandJournal.Entry.Id) = eventValues.values.filter { it.entryId == entryId }
        override suspend fun content(entryId: CommandJournal.Entry.Id) = contentValues.values.filter { it.entryId == entryId }

        override suspend fun search(query: CommandJournal.SearchQuery): CommandJournal.SearchPage {
            val before = query.before
            val matching = entries.values
                .filter { it.projectId == query.projectId }
                .sortedWith(compareByDescending<CommandJournal.Entry> { it.createdAt }.thenByDescending { it.id.value })
                .filter { entry ->
                    before == null || entry.createdAt < before.createdAt ||
                        (entry.createdAt == before.createdAt && entry.id.value < before.entryId.value)
                }
                .take(query.limit + 1)
            val pageEntries = matching.take(query.limit)
            val summaries = pageEntries.map { entry ->
                val calls = calls(entry.id)
                val events = events(entry.id)
                CommandJournal.Summary(
                    entry = entry,
                    rootCall = calls.single { it.parentId == null },
                    latestEvent = events.lastOrNull(),
                    callCount = calls.size.toLong(),
                    eventCount = events.size.toLong(),
                    linkedContentBytes = content(entry.id).sumOf { it.sizeBytes.toLong() },
                    excerpt = content(entry.id).firstOrNull { it.kind == CommandJournal.Content.Kind.COMMAND }?.text,
                )
            }
            return CommandJournal.SearchPage(
                entries = summaries,
                nextCursor = if (matching.size > query.limit) pageEntries.lastOrNull()?.let {
                    CommandJournal.Cursor(it.createdAt, it.id)
                } else null,
            )
        }

        override suspend fun get(
            projectId: Project.Id,
            entryId: CommandJournal.Entry.Id,
            callLimit: Int,
            eventLimit: Int,
        ): CommandJournal.Snapshot? {
            val entry = entries[entryId]?.takeIf { it.projectId == projectId } ?: return null
            val calls = calls(entryId)
            val events = events(entryId)
            return CommandJournal.Snapshot(
                entry = entry,
                calls = calls.take(callLimit),
                events = events.take(eventLimit),
                callCount = calls.size.toLong(),
                eventCount = events.size.toLong(),
                callsTruncated = calls.size > callLimit,
                eventsTruncated = events.size > eventLimit,
            )
        }

        override suspend fun readContent(query: CommandJournal.ContentQuery): CommandJournal.ContentPage? {
            val entry = entries[query.entryId]?.takeIf { it.projectId == query.projectId } ?: return null
            val cursor = query.cursor
            val values = content(entry.id)
                .filter { query.callId == null || it.callId == query.callId }
                .filter { query.eventId == null || it.eventId == query.eventId }
                .filter { query.kinds.isEmpty() || it.kind in query.kinds }
                .mapIndexed { index, value -> (index.toLong() + 1) to value }
                .filter { (linkId, _) -> linkId >= (cursor?.linkId ?: 1L) }
            val page = mutableListOf<CommandJournal.ContentRecord>()
            var nextCursor: CommandJournal.ContentCursor? = null
            for ((linkId, value) in values) {
                val characterStart = if (cursor?.linkId == linkId) cursor.characterOffset else 0
                require(characterStart <= value.text.length)
                if (characterStart == value.text.length) continue
                if (page.size == query.limit) {
                    nextCursor = CommandJournal.ContentCursor(linkId, characterStart)
                    break
                }
                val characterEnd = minOf(value.text.length, characterStart + query.maxCharactersPerItem)
                val visibleText = value.text.substring(characterStart, characterEnd)
                val skippedBytes = value.text.substring(0, characterStart).encodeToByteArray().size.toLong()
                val visibleBytes = visibleText.encodeToByteArray().size.toLong()
                val visibleStart = value.startByte?.plus(skippedBytes)
                val visibleEnd = visibleStart?.plus(visibleBytes)
                page += CommandJournal.ContentRecord(
                    linkId = linkId,
                    entryId = value.entryId,
                    callId = value.callId,
                    eventId = value.eventId,
                    contentId = value.id,
                    kind = value.kind,
                    sequence = value.sequence,
                    text = visibleText,
                    sizeBytes = value.sizeBytes,
                    characterStart = characterStart,
                    characterEnd = characterEnd,
                    totalCharacters = value.text.length,
                    textTruncated = characterStart > 0 || characterEnd < value.text.length,
                    startByte = visibleStart,
                    endByte = visibleEnd,
                    totalBytes = value.totalBytes,
                    truncatedBefore = value.truncatedBefore || characterStart > 0,
                    truncatedAfter = value.truncatedAfter || characterEnd < value.text.length,
                    createdAt = value.createdAt,
                )
                if (characterEnd < value.text.length) {
                    nextCursor = CommandJournal.ContentCursor(linkId, characterEnd)
                    break
                }
            }
            return CommandJournal.ContentPage(page, nextCursor)
        }

        override suspend fun statistics(projectId: Project.Id): CommandJournal.Statistics {
            val selected = entries.values.filter { it.projectId == projectId }
            val ids = selected.mapTo(linkedSetOf()) { it.id }
            val selectedContent = contentValues.values.filter { it.entryId in ids }
            return CommandJournal.Statistics(
                projectId = projectId,
                entryCount = selected.size.toLong(),
                callCount = callValues.values.count { it.entryId in ids }.toLong(),
                eventCount = eventValues.values.count { it.entryId in ids }.toLong(),
                contentLinkCount = selectedContent.size.toLong(),
                uniqueContentCount = selectedContent.map { it.id }.distinct().size.toLong(),
                uniqueContentBytes = selectedContent.distinctBy { it.id }.sumOf { it.sizeBytes.toLong() },
                journalStorageBytes = 1,
                databaseBytes = 2,
                measuredAt = Instant.fromEpochSeconds(1),
            )
        }

        private fun store(value: CommandJournal.Content) {
            val key = "${value.callId.value}:${value.eventId?.value}:${value.kind}:${value.sequence}"
            contentValues[key]?.let { require(it == value) } ?: run { contentValues[key] = value }
        }
    }
}
