package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message

data class CompactionNode(val id: Message.Id, val compactions: List<CompactionCoverage>) {
    val isFull: Boolean get() = compactions.any { it.coversAllPrevious }
}

internal fun Message.compactionNode() = CompactionNode(id, compactions().map(::CompactionCoverage))

/** Coverage and recovery depend on ordered IDs and checkpoints, not ordinary message bodies. */
class ConversationCompactionIndex(private val history: List<CompactionNode>) {
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

    /** Keep projected messages and checkpoints for coverage/replay metadata inside one bounded window. */
    fun requiredHistoryIds(): Set<Message.Id> {
        val required = project().mapTo(mutableSetOf()) { it.id }
        history.filter { it.compactions.isNotEmpty() }.mapTo(required) { it.id }
        return required
    }

    /** Selective checkpoints derived from a changed message must not survive on a copied branch. */
    fun dependentSummaryIds(changedIds: Set<Message.Id>): Set<Message.Id> {
        val affected = changedIds.toMutableSet()
        val invalidated = mutableSetOf<Message.Id>()
        do {
            var changed = false
            history.forEach { node ->
                if (node.id !in affected && !node.isFull &&
                    node.compactions.any { compaction -> compaction.sourceMessageIds.any { it in affected } }
                ) {
                    affected += node.id
                    invalidated += node.id
                    changed = true
                }
            }
        } while (changed)
        return invalidated
    }
}
