package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message

data class CompactionNode(val id: Message.Id, val compactions: List<CompactionCoverage>) {
    val isFull: Boolean get() = compactions.any { it.coversAllPrevious }
}

internal fun Message.compactionNode() = CompactionNode(id, compactions().map(::CompactionCoverage))

/** Coverage and recovery depend on ordered IDs and checkpoints, not ordinary message bodies. */
class ConversationCompactionIndex(
    private val history: List<CompactionNode>,
    private val excludedRecoveryMessageIds: Set<Message.Id> = emptySet(),
) {
    private val byId = history.associateBy { it.id }
    private val positions = history.mapIndexed { index, node -> node.id to index }.toMap()

    init {
        require(positions.size == history.size) { "Duplicate message IDs in conversation history" }
        require(excludedRecoveryMessageIds.all(byId::containsKey)) {
            "Recovery exclusions must belong to the indexed history"
        }
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

    fun incompatibleCompactions(node: CompactionNode, provider: String?): List<CompactionCoverage> =
        node.compactions.filter {
            it.opaque &&
                (provider == null || it.provider != provider)
        }

    fun recoverySourceIds(id: Message.Id, incompatible: List<CompactionCoverage>): List<Message.Id> {
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
        return sourceIds.filterNot(excludedRecoveryMessageIds::contains)
    }

    class RecoveryWindow(val nodes: List<CompactionNode>, val invalidatedReplay: Set<Message.Id>)

    fun recoveryWindow(id: Message.Id, foreign: List<CompactionCoverage>): RecoveryWindow {
        val sources = recoverySourceIds(id, foreign).map(byId::getValue)
        val invalidated = sources.flatMap { it.compactions }.flatMapTo(mutableSetOf()) { it.invalidatedReplayMessageIds }
        return RecoveryWindow(project(sources), invalidated)
    }

    /** Keep checkpoints for transitive coverage and replay invalidation, plus recovery sources. */
    fun requiredHistoryIds(provider: String?, portableBeforeIndex: Int? = null): Set<Message.Id> {
        require(portableBeforeIndex == null || portableBeforeIndex in 0..history.size) {
            "Portable replay boundary is outside conversation history"
        }
        val required = project().mapTo(mutableSetOf()) { it.id }
        history.filter { it.compactions.isNotEmpty() }.mapTo(required) { it.id }
        fun recover(input: List<CompactionNode>, visiting: Set<Message.Id>) {
            project(input).forEach { node ->
                val replayProvider = provider.takeUnless {
                    portableBeforeIndex != null && positions.getValue(node.id) < portableBeforeIndex
                }
                val incompatible = incompatibleCompactions(node, replayProvider)
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
