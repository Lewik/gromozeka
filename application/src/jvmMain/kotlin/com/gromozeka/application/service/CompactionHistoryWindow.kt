package com.gromozeka.application.service

import com.gromozeka.domain.model.CompactionNode
import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.ConversationCompactionIndex
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.withoutProviderReplay
import com.gromozeka.domain.repository.ThreadMessageRepository

/** Coverage and pair boundaries use immutable headers; only projected input needs bodies. */
internal class CompactionHistoryWindow(val headers: List<CompactionMessageHeader>) {
    private val positions = headers.mapIndexed { i, header -> header.id to i }.toMap()
    private val latestRuntimeBaseline = headers.indexOfLast {
        it.role == Message.Role.RUNTIME && it.durableRuntimeObservationBoundary
    }
    private val supersededRuntimeObservationIds = if (latestRuntimeBaseline > 0) {
        headers.take(latestRuntimeBaseline)
            .filter { it.role == Message.Role.RUNTIME }
            .mapTo(hashSetOf(), CompactionMessageHeader::id)
    } else emptySet()
    private val index = ConversationCompactionIndex(
        headers.map { it.node },
        excludedRecoveryMessageIds = supersededRuntimeObservationIds,
    )
    private fun isSupersededRuntimeObservation(header: CompactionMessageHeader): Boolean =
        header.id in supersededRuntimeObservationIds
    private val visible by lazy {
        index.project().map { headers[positions.getValue(it.id)] }
            .filterNot(::isSupersededRuntimeObservation)
    }

    fun isFullSelection(selected: Set<Message.Id>): Boolean = visible.all { it.id in selected }

    fun fullSelection(): List<CompactionMessageHeader> = headers.filterNot(::isSupersededRuntimeObservation)

    fun prefix(pairs: ToolCallPairingService): List<CompactionMessageHeader> {
        val invalidated = headers.flatMap { it.coverage }.flatMapTo(mutableSetOf()) { it.invalidatedReplayMessageIds }
        var boundary = headers.size
        var characters = 0L
        for (header in visible.asReversed()) {
            val position = positions.getValue(header.id)
            if (header.standaloneCheckpoint || (header.id in invalidated && header.portableStandaloneCheckpoint)) {
                if (header.node.isFull || index.project(headers.take(position + 1).map { it.node }).singleOrNull()?.id == header.id) break
                continue
            }
            boundary = position
            characters += if (header.id in invalidated) header.characters - header.thinkingCharacters else header.characters
            if (characters >= 80_000) break
        }
        if (boundary == headers.size) return headers
        val client = visible.filterNot { it.providerManaged }
        val open = client.flatMapTo(mutableSetOf()) { it.calls } - client.flatMapTo(mutableSetOf()) { it.results }
        val firstOpen = headers.indexOfFirst { header -> header.calls.any(open::contains) }
        if (firstOpen >= 0) boundary = minOf(boundary, firstOpen)
        do {
            val previous = boundary
            val paired = pairs.includePairedHeaders(visible, headers.subList(boundary, headers.size).map { it.id })
            boundary = minOf(boundary, paired.minOf { positions.getValue(it) })
        } while (boundary != previous)
        return if (visible.any { positions.getValue(it.id) < boundary }) {
            headers.take(boundary).filterNot(::isSupersededRuntimeObservation)
        } else {
            emptyList()
        }
    }

    suspend fun messages(
        repository: ThreadMessageRepository, thread: Conversation.Thread.Id,
        selection: List<CompactionMessageHeader>, provider: String?,
        prepareRuntimeObservationBoundary: Boolean = false,
    ): List<Message> {
        val retainedBoundary = headers.indexOfLast { it.durableRuntimeObservationBoundary }
        val latestCheckpoint = headers.indexOfLast { it.coverage.isNotEmpty() }
        val replayBoundary = when {
            prepareRuntimeObservationBoundary && (retainedBoundary < 0 || retainedBoundary < latestCheckpoint) -> headers.size
            retainedBoundary >= 0 -> retainedBoundary
            else -> -1
        }
        val nodes = index.project(selection.filterNot(::isSupersededRuntimeObservation).map { it.node })
        val visibleIds = nodes.mapTo(linkedSetOf()) { it.id }
        // Foreign opaque checkpoints may need their original canonical sources for recursive recovery.
        val required = visibleIds.toMutableSet()
        val expanded = mutableSetOf<Message.Id>()
        fun includeRecovery(candidates: List<CompactionNode>) {
            for (node in candidates) {
                val replayProvider = provider.takeUnless {
                    replayBoundary >= 0 && positions.getValue(node.id) < replayBoundary
                }
                val foreign = index.incompatibleCompactions(node, replayProvider)
                if (foreign.isEmpty() || !expanded.add(node.id)) continue
                val recover = index.recoveryWindow(node.id, foreign).nodes
                recover.mapTo(required) { it.id }
                includeRecovery(recover)
            }
        }
        includeRecovery(nodes)
        val invalidated = selection.flatMap { it.coverage }.flatMapTo(mutableSetOf()) { it.invalidatedReplayMessageIds }
        val ids = headers.mapNotNull { it.id.takeIf(required::contains) }
        val bodies = repository.compactionBodies(thread, ids).map {
            if ((it.author as? Message.Author.User) != headers[positions.getValue(it.id)].author) throw CompactionScopeChangedException()
            if (positions.getValue(it.id) < replayBoundary || (it.id in visibleIds && it.id in invalidated)) {
                it.withoutProviderReplay()
            } else it
        }
        val portableBodyIndex = replayBoundary.takeIf { it >= 0 }?.let { boundary ->
            bodies.indexOfFirst { positions.getValue(it.id) >= boundary }.takeIf { it >= 0 } ?: bodies.size
        }
        val projected = ConversationContext(bodies, index, portableBodyIndex).messagesForProvider(provider, visibleIds)
        return if (provider == null) projected.filterNot { it.role == Message.Role.RUNTIME } else projected
    }
}
