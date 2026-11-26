package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolResult
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Context is a projection of immutable history, not a replacement for that history. */
class ConversationContext(
    private val history: List<Message>,
    private val index: ConversationCompactionIndex = ConversationCompactionIndex(history.map(Message::compactionNode)),
) {
    private val byId = history.associateBy(Message::id)

    /** Two passes: collect back to the latest full checkpoint, then remove covered nodes. */
    fun messages(): List<Message> = project(history)

    /**
     * Native state is replayed only by a compatible target. An incompatible opaque checkpoint
     * is intentionally skipped: crossing older full windows would make an ordinary turn unbounded.
     */
    fun messagesForProvider(
        target: ContextCompactionResult.ProviderScope?,
        selection: Set<Message.Id>? = null,
    ): List<Message> {
        val candidates = if (selection == null) history else history.filter { it.id in selection }
        return sanitizeProviderReplay(prepare(project(candidates), target), target)
    }

    /** Full checkpoints protect their prefix and all transitive source versions, including themselves. */
    fun protectedMessageIds(): Set<Message.Id> {
        val boundary = history.indexOfLast(Message::isFullContextCompaction)
        if (boundary < 0) return emptySet()
        val checkpoint = history[boundary]
        return setOf(checkpoint.id) + index.coveredBy(checkpoint.compactionNode())
    }

    /** Invalidated selective summaries disappear from a new branch; their retained sources reappear. */
    fun dependentSummaryIds(changedIds: Set<Message.Id>): Set<Message.Id> = index.dependentSummaryIds(changedIds)

    private fun project(input: List<Message>): List<Message> {
        val selected = index.project(input.map(Message::compactionNode)).mapTo(mutableSetOf()) { it.id }
        val invalidatedReplay = input.flatMap { it.compactions() }
            .flatMapTo(mutableSetOf()) { it.invalidatedReplayMessageIds }
        return input.filter { it.id in selected }
            .map { if (it.id in invalidatedReplay) it.withoutProviderReplay() else it }
    }

    private fun prepare(
        input: List<Message>,
        target: ContextCompactionResult.ProviderScope?,
    ): List<Message> = buildList {
        val carriedInstructions = mutableListOf<Message.Instruction.UserInstruction>()
        input.forEach { message ->
            val portableContent = message.content.filterNot { item ->
                item is ContextCompactionResult &&
                    item.payload is ContextCompactionResult.Payload.OpaqueProviderState &&
                    !item.providerScope.isReplayCompatibleWith(target)
            }
            var portable = if (portableContent.size == message.content.size) message
            else message.copy(content = portableContent).withoutProviderReplay()
            if (portable.content.isEmpty()) {
                carriedInstructions += portable.instructions.filterIsInstance<Message.Instruction.UserInstruction>()
                portable = portable.copy(instructions = portable.instructions.filterNot {
                    it is Message.Instruction.UserInstruction
                })
            } else if (carriedInstructions.isNotEmpty()) {
                portable = portable.copy(instructions = carriedInstructions + portable.instructions)
                carriedInstructions.clear()
            }
            if (portable.content.isNotEmpty() || portable.instructions.isNotEmpty()) add(portable)
        }
    }

    private fun sanitizeProviderReplay(
        input: List<Message>,
        target: ContextCompactionResult.ProviderScope?,
    ): List<Message> {
        val droppedCalls = input.asSequence()
            .filter { it.providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] == JsonPrimitive(true) }
            .filter { message -> message.replayScope()?.let { !it.isReplayCompatibleWith(target) } == true }
            .flatMap { it.content.filterIsInstance<ToolCall>().asSequence() }
            .mapTo(mutableSetOf()) { it.id }
        return input.mapNotNull { message ->
            val incompatibleReplay = message.replayScope()?.let { !it.isReplayCompatibleWith(target) } == true
            val filtered = message.content.filterNot { item ->
                (item is ToolCall && item.id in droppedCalls) ||
                    (item is ToolResult && item.toolUseId in droppedCalls)
            }
            var portable = if (filtered.size == message.content.size) message else message.copy(content = filtered)
            if (incompatibleReplay) portable = portable.withoutProviderReplay()
            if (filtered.size != message.content.size) {
                portable = portable.copy(providerMetadata = JsonObject(portable.providerMetadata.filterKeys {
                    it != AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
                }))
            }
            portable.takeUnless { it.content.isEmpty() && it.instructions.isEmpty() }
        }
    }
}

private fun ContextCompactionResult.ProviderScope?.isReplayCompatibleWith(
    target: ContextCompactionResult.ProviderScope?,
): Boolean {
    if (this == null || target == null || provider != target.provider) return false
    fun compatible(source: String?, destination: String?) = source == null || destination == null || source == destination
    return compatible(connectionId, target.connectionId) &&
        compatible(modelConfigurationId, target.modelConfigurationId) &&
        compatible(modelName, target.modelName)
}

private fun Message.replayScope(): ContextCompactionResult.ProviderScope? {
    fun string(key: String) = (providerMetadata[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val provider = string("provider") ?: return null
    return ContextCompactionResult.ProviderScope(
        provider = provider,
        connectionId = string("connectionId"),
        modelConfigurationId = string("modelConfigurationId"),
        modelName = string("model"),
    )
}

fun Conversation.Message.compactions(): List<Conversation.Message.ContentItem.ContextCompactionResult> =
    content.filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>()

fun Conversation.Message.isFullContextCompaction(): Boolean = compactions().any { it.coversAllPrevious }

// Local checkpoint metadata; never part of the provider's opaque replay window.
private const val RETAINED_OPENAI_REPLAY_IDS = "gromozekaRetainedOpenAiReplayIds"

private fun String?.supportsNativeReplayTransfer() =
    this == AiConnection.Kind.OPENAI_API.name || this == AiConnection.Kind.OPENAI_SUBSCRIPTION.name

@kotlinx.serialization.Serializable
data class RetainedOpenAiReplay(val provider: String, val targetThread: String, val ids: Set<Message.Id>)

internal fun Message.retainedOpenAiReplay(): RetainedOpenAiReplay? {
    val receipt = content.singleOrNull() as? ContextCompactionResult ?: return null
    val target = receipt.historyTransition?.targetThreadId ?: return null
    val provider = receipt.providerScope?.provider ?: return null
    if (!provider.supportsNativeReplayTransfer() || !receipt.coversAllPrevious ||
        receipt.payload !is ContextCompactionResult.Payload.OpaqueProviderState) return null
    val ids = providerMetadata[RETAINED_OPENAI_REPLAY_IDS]?.jsonArray ?: return null
    return RetainedOpenAiReplay(provider, target.value, ids.mapTo(mutableSetOf()) { Message.Id(it.jsonPrimitive.content) })
}

private fun RetainedOpenAiReplay?.idsFor(provider: String?, thread: String?): Set<Message.Id> =
    this?.takeIf { it.provider == provider && it.targetThread == thread }?.ids.orEmpty()

internal fun Message?.retainedOpenAiReplayIds(provider: String?, thread: String?): Set<Message.Id> =
    this?.retainedOpenAiReplay().idsFor(provider, thread)

data class RetainedOpenAiReplayLineage(
    val provider: String,
    val sourceThread: Conversation.Thread.Id,
    val targetThread: Conversation.Thread.Id,
    val messageIds: Set<Message.Id>,
)

fun Message.retainedOpenAiReplayLineage(targetThread: Conversation.Thread.Id): RetainedOpenAiReplayLineage? {
    val replay = retainedOpenAiReplay()?.takeIf { it.targetThread == targetThread.value } ?: return null
    val transition = (content.singleOrNull() as? ContextCompactionResult)?.historyTransition ?: return null
    check(transition.targetThreadId == targetThread) { "Retained replay targets another thread" }
    return RetainedOpenAiReplayLineage(replay.provider, transition.sourceThreadId, targetThread, replay.ids)
}

fun AiRuntimeRequest.withoutRetainedOpenAiReplay(lineage: RetainedOpenAiReplayLineage): AiRuntimeRequest =
    copy(messages = messages.map { message ->
        if (message.id in lineage.messageIds) message.withoutProviderReplay() else message
    })

/** Carry only immutable messages that could already be replayed on the verified source branch. */
fun Message.recordRetainedOpenAiReplay(previous: RetainedOpenAiReplay?, suffix: List<CompactionMessageHeader>): Message {
    require(RETAINED_OPENAI_REPLAY_IDS !in providerMetadata) { "Only the compaction commit may grant retained replay" }
    val checkpoint = content.single() as ContextCompactionResult
    val scope = checkpoint.providerScope ?: return this
    if (suffix.isEmpty() || !scope.provider.supportsNativeReplayTransfer() || !checkpoint.coversAllPrevious ||
        checkpoint.payload !is ContextCompactionResult.Payload.OpaqueProviderState ||
        scope.connectionId == null || scope.modelName == null) return this
    val sourceThread = requireNotNull(checkpoint.historyTransition).sourceThreadId.value
    val inherited = previous.idsFor(scope.provider, sourceThread)
    val invalidated = checkpoint.invalidatedReplayMessageIds.toSet()
    val ids = suffix.filter { message ->
        val metadata = message.replayMetadata
        message.role == Message.Role.ASSISTANT && message.id !in invalidated &&
            metadata["provider"] == JsonPrimitive(scope.provider) &&
            metadata["connectionId"] == JsonPrimitive(scope.connectionId) &&
            metadata["model"] == JsonPrimitive(scope.modelName) &&
            (scope.modelConfigurationId == null || metadata["modelConfigurationId"] == JsonPrimitive(scope.modelConfigurationId)) &&
            (metadata[AI_REPLAY_THREAD_METADATA_KEY] == JsonPrimitive(sourceThread) || message.id in inherited)
    }.map { JsonPrimitive(it.id.value) }
    if (ids.isEmpty()) return this
    return copy(providerMetadata = JsonObject(providerMetadata + (RETAINED_OPENAI_REPLAY_IDS to JsonArray(ids))))
}

/** Request projection only: persisted content and usage/provenance remain unchanged. */
fun Conversation.Message.withoutProviderReplay(): Conversation.Message = copy(
    content = content.filterNot { it is Conversation.Message.ContentItem.Thinking },
    providerMetadata = JsonObject(providerMetadata.filterKeys {
        it == "providerManagedTool" || it == RETAINED_OPENAI_REPLAY_IDS
    }),
)

/** Restore provider-generated content before context projection, never the human-facing correction. */
fun Message.originalCollaborationMessage(): Message {
    val original = providerMetadata[COLLABORATION_ORIGINAL_CONTENT]
    return if (original != null && role == Message.Role.ASSISTANT && author is Message.Author.Agent) {
        copy(content = Json.decodeFromJsonElement<List<Message.ContentItem>>(original))
    } else this
}
