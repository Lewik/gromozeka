package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Context is a projection of immutable history, not a replacement for that history. */
class ConversationContext(
    private val history: List<Message>,
) {
    private val byId = history.associateBy(Message::id)
    private val index = ConversationCompactionIndex(history.map(Message::compactionNode))

    /** Two passes: collect back to the latest full checkpoint, then remove covered nodes. */
    fun messages(): List<Message> = project(history)

    /**
     * Native state is replayed only by its owning provider. Otherwise recover the readable
     * history it replaced; never substitute an opaque checkpoint with an empty marker.
     * A selection is projected independently of unselected summaries elsewhere in history.
     */
    fun messagesForProvider(provider: String?, selection: Set<Message.Id>? = null): List<Message> {
        val candidates = if (selection == null) history else history.filter { it.id in selection }
        // Opaque recovery can restore sources covered by another selected summary.
        // Apply coverage once more to the recovered view, not to unselected history.
        return project(prepare(candidates, provider, emptySet()))
    }

    /** Full checkpoints protect their prefix and all transitive source versions, including themselves. */
    fun protectedMessageIds(): Set<Message.Id> {
        val boundary = history.indexOfLast(Message::isFullContextCompaction)
        if (boundary < 0) return emptySet()
        val checkpoint = history[boundary]
        return setOf(checkpoint.id) + index.coveredBy(checkpoint.compactionNode())
    }

    /** Invalidated selective summaries disappear from a new branch; their retained sources reappear. */
    fun dependentSummaryIds(changedIds: Set<Message.Id>): Set<Message.Id> {
        val affected = changedIds.toMutableSet()
        val invalidated = mutableSetOf<Message.Id>()
        do {
            var changed = false
            history.forEach { message ->
                if (message.id !in affected && !message.isFullContextCompaction() &&
                    message.compactions().any { result -> result.sourceMessageIds.any { it in affected } }
                ) {
                    affected += message.id
                    invalidated += message.id
                    changed = true
                }
            }
        } while (changed)
        return invalidated
    }

    private fun project(input: List<Message>): List<Message> {
        val selected = index.project(input.map(Message::compactionNode)).mapTo(mutableSetOf()) { it.id }
        val invalidatedReplay = input.flatMap { it.compactions() }
            .flatMapTo(mutableSetOf()) { it.invalidatedReplayMessageIds }
        return input.filter { it.id in selected }
            .map { if (it.id in invalidatedReplay) it.withoutProviderReplay() else it }
    }

    private fun prepare(input: List<Message>, provider: String?, visiting: Set<Message.Id>): List<Message> =
        project(input).flatMap { message ->
            val incompatible = message.compactions().filter {
                it.payload is ContextCompactionResult.Payload.OpaqueProviderState &&
                    (provider == null || it.providerScope?.provider != provider)
            }
            if (incompatible.isEmpty()) return@flatMap listOf(message)
            require(message.id !in visiting) { "Cyclic opaque compaction sources at ${message.id.value}" }
            // There must be a real source for recovery. A provider label is not a summary.
            val sourceIds = index.recoverySourceIds(message.id, incompatible)
            val sources = sourceIds.map { sourceId ->
                byId[sourceId] ?: error("Missing compaction source message: ${sourceId.value}")
            }
            val recovered = prepare(sources, provider, visiting + message.id).map(Message::withoutProviderReplay)
            val lastCheckpoint = message.content.indexOfLast { it in incompatible }
            val tail = message.content.drop(lastCheckpoint + 1)
            recovered + if (tail.isEmpty()) emptyList() else listOf(
                message.copy(content = tail).withoutProviderReplay()
            )
        }.distinctBy(Message::id)
}

fun Conversation.Message.compactions(): List<Conversation.Message.ContentItem.ContextCompactionResult> =
    content.filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>()

fun Conversation.Message.isFullContextCompaction(): Boolean = compactions().any { it.coversAllPrevious }

/** Request-only normalization: persisted content and usage/provenance remain unchanged. */
fun Conversation.Message.withoutProviderReplay(): Conversation.Message = copy(
    content = content.filterNot { it is Conversation.Message.ContentItem.Thinking },
    providerMetadata = JsonObject(providerMetadata.filterKeys { it == "providerManagedTool" }),
)

/** Restore provider-generated content before context projection, never the human-facing correction. */
fun Message.originalCollaborationMessage(): Message {
    val original = providerMetadata[COLLABORATION_ORIGINAL_CONTENT]
    return if (original != null && role == Message.Role.ASSISTANT && author is Message.Author.Agent) {
        copy(content = Json.decodeFromJsonElement<List<Message.ContentItem>>(original))
    } else this
}
