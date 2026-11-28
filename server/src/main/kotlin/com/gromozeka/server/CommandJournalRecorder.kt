package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKSPACE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKSPACE_ROOT_PATH
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_PYTHON_TOOL_NAME
import com.gromozeka.domain.tool.filesystem.isManagedCommandLaunchTool
import com.gromozeka.shared.utils.sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Instant

@Service
class CommandJournalRecorder(
    private val journalProvider: ObjectProvider<CommandJournal>,
) {
    suspend fun admitManagedCalls(
        calls: List<Conversation.Message.ContentItem.ToolCall>,
        target: ConversationRuntimeTaskTarget.Worker,
        context: ToolExecutionContext,
        at: Instant = Clock.System.now(),
    ) {
        val managedCalls = calls.filter { isManagedCommandLaunchTool(it.call.name) }
        if (managedCalls.isEmpty()) return
        val journal = journalProvider.ifAvailable ?: return
        val source = requireNotNull(context.commandJournalSource) {
            "A managed command requires trusted Command Journal provenance"
        }
        managedCalls.forEach { call ->
            val input = call.call.input as? JsonObject
                ?: error("Managed command input must be an object")
            if (source.kind == CommandJournal.Source.Kind.COMMAND_SDK_MCP) return@forEach
            val conversationId = required(context, TOOL_CONTEXT_CONVERSATION_ID).let(Conversation::Id)
            val existing = journal.findEntryBySource(conversationId, call.id)
            val entry = existing ?: when (source.kind) {
                CommandJournal.Source.Kind.CONVERSATION_TOOL_CALL,
                CommandJournal.Source.Kind.DIRECT_MCP,
                -> newEntry(call, source, context, at)

                CommandJournal.Source.Kind.COMMAND_SDK_MCP ->
                    error("A command SDK source cannot originate a managed root call")
            }
            val journalCall = existing?.let {
                journal.calls(it.id).single { stored -> stored.parentId == null && stored.sourceToolCallId == call.id }
            } ?: call(call, entry.id, null, target, context, input, entry.createdAt)
            if (existing == null) {
                val content = content(entry.id, journalCall.id, call, input, entry.createdAt)
                journal.admit(entry, journalCall, content)
            }
            journal.append(
                CommandJournal.Event(
                    id = eventId(entry.id, call.id, "accepted"),
                    entryId = entry.id,
                    callId = journalCall.id,
                    sourceKey = "tool-call:${call.id.value}:accepted",
                    kind = CommandJournal.Event.Kind.ACCEPTED,
                    occurredAt = entry.createdAt,
                )
            )
        }
    }

    data class RecordedCall(
        val entry: CommandJournal.Entry,
        val call: CommandJournal.Call,
    )

    suspend fun reportByCaller(
        sourceToolCallId: ContentItem.ToolCall.Id,
        toolName: String,
        kind: CommandJournal.Call.Kind,
        commandOrCode: String,
        rawArguments: JsonObject,
        workingDirectory: String?,
        output: String?,
        statusMessage: String?,
        exitCode: Int?,
        observedAt: Instant?,
        context: ToolExecutionContext,
        at: Instant = Clock.System.now(),
    ): RecordedCall {
        require(kind == CommandJournal.Call.Kind.COMMAND || kind == CommandJournal.Call.Kind.PYTHON) {
            "A caller report must describe a command or Python invocation"
        }
        val journal = requireNotNull(journalProvider.ifAvailable) { "Command Journal is disabled" }
        val conversationId = required(context, TOOL_CONTEXT_CONVERSATION_ID).let(Conversation::Id)
        val threadId = required(context, TOOL_CONTEXT_THREAD_ID).let(Conversation.Thread::Id)
        val existing = journal.findEntryBySource(conversationId, sourceToolCallId)
        val entry = existing ?: CommandJournal.Entry(
            id = entryId(conversationId, threadId, sourceToolCallId),
            origin = CommandJournal.Entry.Origin.REPORTED_BY_CALLER,
            actorUserId = required(context, TOOL_CONTEXT_USER_ID).let(User::Id),
            projectId = required(context, TOOL_CONTEXT_PROJECT_ID).let(Project::Id),
            conversationId = conversationId,
            threadId = threadId,
            agentDefinitionId = context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.let(AgentDefinition::Id),
            sourceToolCallId = sourceToolCallId,
            createdAt = at,
        )
        val rootCall = existing?.let {
            journal.calls(it.id).single { stored -> stored.parentId == null && stored.sourceToolCallId == sourceToolCallId }
        } ?: CommandJournal.Call(
            id = callId(entry.id, sourceToolCallId),
            entryId = entry.id,
            sourceToolCallId = sourceToolCallId,
            kind = kind,
            toolName = toolName,
            target = CommandJournal.Call.Target(workingDirectory = workingDirectory),
            createdAt = entry.createdAt,
        )
        if (existing == null) {
            val inputContent = listOf(
                content(
                    entry.id,
                    rootCall.id,
                    CommandJournal.Content.Kind.ARGUMENTS,
                    rawArguments.toString(),
                    0,
                    entry.createdAt,
                ),
                content(
                    entry.id,
                    rootCall.id,
                    if (kind == CommandJournal.Call.Kind.PYTHON) CommandJournal.Content.Kind.PYTHON_SOURCE
                    else CommandJournal.Content.Kind.COMMAND,
                    commandOrCode,
                    0,
                    entry.createdAt,
                ),
            )
            journal.admit(entry, rootCall, inputContent)
        }
        val event = CommandJournal.Event(
            id = eventId(entry.id, sourceToolCallId, "reported"),
            entryId = entry.id,
            callId = rootCall.id,
            sourceKey = "tool-call:${sourceToolCallId.value}:reported",
            kind = CommandJournal.Event.Kind.REPORTED,
            occurredAt = observedAt ?: entry.createdAt,
            statusMessage = statusMessage,
            exitCode = exitCode,
        )
        val outputContent = output?.takeIf(String::isNotEmpty)?.let {
            listOf(
                content(
                    entry.id,
                    rootCall.id,
                    CommandJournal.Content.Kind.REPORTED_OUTPUT,
                    it,
                    0,
                    event.occurredAt,
                    event.id,
                )
            )
        }.orEmpty()
        journal.append(event, outputContent)
        return RecordedCall(entry, rootCall)
    }

    suspend fun admitNestedCall(
        parentCommandTaskId: CommandTask.Id,
        call: Conversation.Message.ContentItem.ToolCall,
        target: ConversationRuntimeTaskTarget,
        context: ToolExecutionContext,
        logInput: Boolean,
        at: Instant = Clock.System.now(),
    ): RecordedCall? {
        val journal = journalProvider.ifAvailable ?: return null
        val parent = journal.findCallByCommandTask(parentCommandTaskId) ?: return null
        val entry = requireNotNull(journal.findEntry(parent.entryId)) {
            "Command Journal call ${parent.id.value} lost its entry ${parent.entryId.value}"
        }
        val input = call.call.input as? JsonObject ?: error("MCP tool input must be an object")
        val journalCall = call(call, entry.id, parent.id, target, context, input, at)
        journal.append(
            journalCall,
            if (logInput) content(entry.id, journalCall.id, call, input, at) else emptyList(),
        )
        journal.append(
            CommandJournal.Event(
                id = eventId(entry.id, call.id, "accepted"),
                entryId = entry.id,
                callId = journalCall.id,
                sourceKey = "tool-call:${call.id.value}:accepted",
                kind = CommandJournal.Event.Kind.ACCEPTED,
                occurredAt = at,
            )
        )
        return RecordedCall(entry, journalCall)
    }

    suspend fun recordNestedOutcome(
        recorded: RecordedCall,
        result: ContentItem.ToolResult,
        at: Instant = Clock.System.now(),
    ) {
        if (recorded.call.kind in setOf(CommandJournal.Call.Kind.COMMAND, CommandJournal.Call.Kind.PYTHON) && !result.isError) {
            return // CommandTask lifecycle is the authoritative process outcome.
        }
        val journal = journalProvider.ifAvailable ?: return
        val sourceToolCallId = requireNotNull(recorded.call.sourceToolCallId)
        val eventKind = if (result.isError) CommandJournal.Event.Kind.FAILED else CommandJournal.Event.Kind.COMPLETED
        val event = CommandJournal.Event(
            id = eventId(recorded.entry.id, sourceToolCallId, "outcome"),
            entryId = recorded.entry.id,
            callId = recorded.call.id,
            sourceKey = "tool-call:${sourceToolCallId.value}:outcome",
            kind = eventKind,
            occurredAt = at,
        )
        val output = result.result.mapIndexedNotNull { sequence, item ->
            val text = when (item) {
                is ContentItem.ToolResult.Data.Text -> item.content
                is ContentItem.ToolResult.Data.UrlData -> item.url
                is ContentItem.ToolResult.Data.ArtifactData ->
                    Json.encodeToString(ContentItem.ToolResult.Data.serializer(), item)
                is ContentItem.ToolResult.Data.Base64Data -> null
            }?.takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
            content(
                recorded.entry.id,
                recorded.call.id,
                CommandJournal.Content.Kind.OUTPUT,
                text,
                sequence,
                at,
                event.id,
            )
        }
        journal.append(event, output)
    }

    suspend fun recordNestedFailure(
        recorded: RecordedCall,
        kind: CommandJournal.Event.Kind = CommandJournal.Event.Kind.FAILED,
        at: Instant = Clock.System.now(),
    ) {
        require(kind in setOf(CommandJournal.Event.Kind.FAILED, CommandJournal.Event.Kind.OUTCOME_UNKNOWN))
        val journal = journalProvider.ifAvailable ?: return
        val sourceToolCallId = requireNotNull(recorded.call.sourceToolCallId)
        journal.append(
            CommandJournal.Event(
                id = eventId(recorded.entry.id, sourceToolCallId, "outcome"),
                entryId = recorded.entry.id,
                callId = recorded.call.id,
                sourceKey = "tool-call:${sourceToolCallId.value}:outcome",
                kind = kind,
                occurredAt = at,
            )
        )
    }

    private fun newEntry(
        call: Conversation.Message.ContentItem.ToolCall,
        source: CommandJournal.Source,
        context: ToolExecutionContext,
        at: Instant,
    ): CommandJournal.Entry {
        val conversationId = required(context, TOOL_CONTEXT_CONVERSATION_ID).let(Conversation::Id)
        val threadId = required(context, TOOL_CONTEXT_THREAD_ID).let(Conversation.Thread::Id)
        return CommandJournal.Entry(
            id = entryId(conversationId, threadId, call.id),
            origin = when (source.kind) {
                CommandJournal.Source.Kind.CONVERSATION_TOOL_CALL -> CommandJournal.Entry.Origin.CONVERSATION_TOOL_CALL
                CommandJournal.Source.Kind.DIRECT_MCP -> CommandJournal.Entry.Origin.DIRECT_MCP
                CommandJournal.Source.Kind.COMMAND_SDK_MCP -> error("Nested MCP calls belong to their parent Entry")
            },
            actorUserId = required(context, TOOL_CONTEXT_USER_ID).let(User::Id),
            projectId = required(context, TOOL_CONTEXT_PROJECT_ID).let(Project::Id),
            conversationId = conversationId,
            threadId = threadId,
            agentDefinitionId = context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.let(AgentDefinition::Id),
            sourceRuntimeTaskId = source.sourceRuntimeTaskId,
            sourceToolCallId = call.id,
            createdAt = at,
        )
    }

    private fun call(
        source: Conversation.Message.ContentItem.ToolCall,
        entryId: CommandJournal.Entry.Id,
        parentId: CommandJournal.Call.Id?,
        target: ConversationRuntimeTaskTarget,
        context: ToolExecutionContext,
        input: JsonObject,
        at: Instant,
    ) = CommandJournal.Call(
        id = callId(entryId, source.id),
        entryId = entryId,
        parentId = parentId,
        sourceToolCallId = source.id,
        kind = when (source.call.name) {
            GRZ_EXECUTE_COMMAND_TOOL_NAME -> CommandJournal.Call.Kind.COMMAND
            GRZ_EXECUTE_PYTHON_TOOL_NAME -> CommandJournal.Call.Kind.PYTHON
            else -> CommandJournal.Call.Kind.TOOL
        },
        toolName = source.call.name,
        target = when (target) {
            ConversationRuntimeTaskTarget.Server -> CommandJournal.Call.Target()
            is ConversationRuntimeTaskTarget.Worker -> CommandJournal.Call.Target(
                workerId = target.workerId,
                workspaceId = context.getString(TOOL_CONTEXT_WORKSPACE_ID)?.let(Workspace::Id),
                workspaceMountId = target.workspaceMountId,
                workspaceRootPath = context.getString(TOOL_CONTEXT_WORKSPACE_ROOT_PATH),
                workingDirectory = input["working_directory"]?.jsonPrimitive?.contentOrNull,
            )
        },
        createdAt = at,
    )

    private fun content(
        entryId: CommandJournal.Entry.Id,
        callId: CommandJournal.Call.Id,
        call: Conversation.Message.ContentItem.ToolCall,
        input: JsonObject,
        at: Instant,
    ): List<CommandJournal.Content> = buildList {
        add(content(entryId, callId, CommandJournal.Content.Kind.ARGUMENTS, call.call.input.toString(), 0, at))
        when (call.call.name) {
            GRZ_EXECUTE_COMMAND_TOOL_NAME -> input["command"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                add(content(entryId, callId, CommandJournal.Content.Kind.COMMAND, it, 0, at))
            }
            GRZ_EXECUTE_PYTHON_TOOL_NAME -> input["code"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                add(content(entryId, callId, CommandJournal.Content.Kind.PYTHON_SOURCE, it, 0, at))
            }
        }
    }

    private fun content(
        entryId: CommandJournal.Entry.Id,
        callId: CommandJournal.Call.Id,
        kind: CommandJournal.Content.Kind,
        text: String,
        sequence: Int,
        at: Instant,
        eventId: CommandJournal.Event.Id? = null,
    ) = CommandJournal.Content(
        id = CommandJournal.Content.Id(text.sha256()),
        entryId = entryId,
        callId = callId,
        eventId = eventId,
        kind = kind,
        sequence = sequence,
        text = text,
        createdAt = at,
    )

    private fun required(context: ToolExecutionContext, key: String): String =
        requireNotNull(context.getString(key)?.takeIf(String::isNotBlank)) {
            "Command Journal requires trusted $key"
        }

    private fun entryId(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        callId: Conversation.Message.ContentItem.ToolCall.Id,
    ) = CommandJournal.Entry.Id("journal:${"${conversationId.value}:${threadId.value}:${callId.value}".sha256()}")

    private fun callId(entryId: CommandJournal.Entry.Id, sourceId: Conversation.Message.ContentItem.ToolCall.Id) =
        CommandJournal.Call.Id("call:${"${entryId.value}:${sourceId.value}".sha256()}")

    private fun eventId(
        entryId: CommandJournal.Entry.Id,
        sourceId: Conversation.Message.ContentItem.ToolCall.Id,
        suffix: String,
    ) = CommandJournal.Event.Id("event:${"${entryId.value}:${sourceId.value}:$suffix".sha256()}")
}
