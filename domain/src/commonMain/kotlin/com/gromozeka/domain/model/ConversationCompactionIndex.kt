package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult

internal data class CompactionNode(val id: Message.Id, val compactions: List<ContextCompactionResult>) {
    val isFull: Boolean get() = compactions.any { it.coversAllPrevious }
}

internal fun Message.compactionNode() = CompactionNode(id, compactions())

/** Coverage and recovery depend on ordered IDs and checkpoints, not ordinary message bodies. */
internal class ConversationCompactionIndex(private val history: List<CompactionNode>) {
    private val byId = history.associateBy { it.id }
    private val positions = history.mapIndexed { index, node -> node.id to index }.toMap()

    init {
        require(positions.size == history.size) { "Duplicate message IDs in conversation history" }
    }

    fun project(input: List<CompactionNode> = history): List<CompactionNode> {
        val reverseCandidates = mutableListOf<CompactionNode>()
        for (node in input.asReversed()) {
            reverseCandidates += node
            if (node.isFull) break
        }
        val candidateIds = reverseCandidates.mapTo(mutableSetOf()) { it.id }
        val covered = reverseCandidates.flatMapTo(mutableSetOf()) { coveredBy(it, candidateIds) }
        return reverseCandidates.asReversed().filterNot { it.id in covered }
    }

    fun coveredBy(node: CompactionNode, relevantIds: Set<Message.Id>? = null): Set<Message.Id> {
        val covered = mutableSetOf<Message.Id>()
        val visited = mutableSetOf<Message.Id>()
        val visiting = mutableSetOf<Message.Id>()
        fun visit(current: CompactionNode) {
            require(current.id !in visiting) { "Cyclic compaction sources at ${current.id.value}" }
            if (!visited.add(current.id)) return
            visiting += current.id
            current.compactions.forEach { result ->
                if (result.coversAllPrevious && relevantIds != null) {
                    val boundary = positions[current.id]
                    if (boundary != null) {
                        covered += relevantIds.filter { id -> positions[id]?.let { it < boundary } == true }
                        return@forEach
                    }
                }
                val prefix = if (result.coversAllPrevious) {
                    positions[current.id]?.let { history.take(it).map(CompactionNode::id) }.orEmpty()
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
        visit(node)
        return covered
    }

    fun incompatibleCompactions(node: CompactionNode, provider: String?): List<ContextCompactionResult> =
        node.compactions.filter {
            it.payload is ContextCompactionResult.Payload.OpaqueProviderState &&
                (provider == null || it.providerScope?.provider != provider)
        }

    fun recoverySourceIds(id: Message.Id, incompatible: List<ContextCompactionResult>): List<Message.Id> {
        val sourceIds = incompatible.flatMap { result ->
            if (result.coversAllPrevious && positions[id]?.let { it > 0 } == true) {
                history.take(positions.getValue(id)).map(CompactionNode::id)
            } else result.sourceMessageIds
        }.distinct()
        require(sourceIds.isNotEmpty()) {
            "Cannot recover opaque compaction ${id.value}: original messages are unavailable"
        }
        sourceIds.forEach { sourceId ->
            check(sourceId in byId) { "Missing compaction source message: ${sourceId.value}" }
        }
        return sourceIds
    }

    /** Keep checkpoints for transitive coverage and replay invalidation, plus recovery sources. */
    fun requiredHistoryIds(provider: String?): Set<Message.Id> {
        val required = project().mapTo(mutableSetOf()) { it.id }
        history.filter { it.compactions.isNotEmpty() }.mapTo(required) { it.id }
        fun recover(input: List<CompactionNode>, visiting: Set<Message.Id>) {
            project(input).forEach { node ->
                val incompatible = incompatibleCompactions(node, provider)
                if (incompatible.isEmpty()) return@forEach
                require(node.id !in visiting) { "Cyclic opaque compaction sources at ${node.id.value}" }
                val sources = recoverySourceIds(node.id, incompatible)
                required += sources
                recover(sources.map(byId::getValue), visiting + node.id)
            }
        }
        recover(history, emptySet())
        return required
    }
}
