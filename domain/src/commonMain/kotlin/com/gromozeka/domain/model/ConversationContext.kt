package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import kotlinx.serialization.json.JsonObject

/** Context is a projection of immutable history, not a replacement for that history. */
class ConversationContext(
    private val history: List<Message>,
) {
    private val byId = history.associateBy(Message::id)
    private val positions = history.mapIndexed { index, message -> message.id to index }.toMap()

    init {
        require(positions.size == history.size) { "Duplicate message IDs in conversation history" }
    }

    /** Two passes: collect back to the latest full checkpoint, then remove covered nodes. */
    fun messages(): List<Message> = project(history)

    /**
     * Native state is replayed only by its owning provider. Otherwise recover the readable
     * history it replaced; never substitute an opaque checkpoint with an empty marker.
     * A selection is projected independently of unselected summaries elsewhere in history.
     */
    fun messagesForProvider(provider: String?, selection: Set<Message.Id>? = null): List<Message> {
        val candidates = if (selection == null) history else history.filter { it.id in selection }
        return prepare(candidates, provider, emptySet())
    }

    /** Full checkpoints protect their prefix and all transitive source versions, including themselves. */
    fun protectedMessageIds(): Set<Message.Id> {
        val boundary = history.indexOfLast(Message::isFullContextCompaction)
        if (boundary < 0) return emptySet()
        val checkpoint = history[boundary]
        return setOf(checkpoint.id) + coveredBy(checkpoint)
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
        val reverseCandidates = mutableListOf<Message>()
        for (message in input.asReversed()) {
            reverseCandidates += message
            if (message.isFullContextCompaction()) break
        }
        val candidateIds = reverseCandidates.mapTo(mutableSetOf(), Message::id)
        val covered = reverseCandidates.flatMapTo(mutableSetOf()) { coveredBy(it, candidateIds) }
        val invalidatedReplay = input.flatMap { it.compactions() }
            .flatMapTo(mutableSetOf()) { it.invalidatedReplayMessageIds }
        return reverseCandidates.asReversed()
            .filterNot { it.id in covered }
            .map { if (it.id in invalidatedReplay) it.withoutProviderReplay() else it }
    }

    private fun coveredBy(message: Message, relevantIds: Set<Message.Id>? = null): Set<Message.Id> {
        val covered = mutableSetOf<Message.Id>()
        val visited = mutableSetOf<Message.Id>()
        val visiting = mutableSetOf<Message.Id>()
        fun visit(current: Message) {
            require(current.id !in visiting) { "Cyclic compaction sources at ${current.id.value}" }
            if (!visited.add(current.id)) return
            visiting += current.id
            current.compactions().forEach { result ->
                if (result.coversAllPrevious && relevantIds != null) {
                    val boundary = positions[current.id]
                    if (boundary != null) {
                        covered += relevantIds.filter { id -> positions[id]?.let { it < boundary } == true }
                        return@forEach
                    }
                }
                val prefix = if (result.coversAllPrevious) {
                    positions[current.id]?.let { history.take(it).map(Message::id) }.orEmpty()
                } else emptyList()
                (prefix + result.sourceMessageIds).distinct().forEach { sourceId ->
                    require(sourceId != current.id) { "Compaction cannot cover itself: ${current.id.value}" }
                    val sourcePosition = positions[sourceId]
                    val currentPosition = positions[current.id]
                    require(sourcePosition == null || currentPosition == null || sourcePosition < currentPosition) {
                        "Compaction source must precede its result: ${sourceId.value}"
                    }
                    covered += sourceId
                    byId[sourceId]?.let(::visit)
                }
            }
            visiting -= current.id
        }
        visit(message)
        return covered
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
            val sourceIds = incompatible.flatMap { result ->
                if (result.coversAllPrevious && positions[message.id]?.let { it > 0 } == true) {
                    history.take(positions.getValue(message.id)).map(Message::id)
                } else result.sourceMessageIds
            }.distinct()
            require(sourceIds.isNotEmpty()) {
                "Cannot recover opaque compaction ${message.id.value}: original messages are unavailable"
            }
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
