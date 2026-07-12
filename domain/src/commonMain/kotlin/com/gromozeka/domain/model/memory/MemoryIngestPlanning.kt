package com.gromozeka.domain.model.memory

import kotlinx.serialization.Serializable

/**
 * One immutable structural block from an input source.
 *
 * Blocks preserve exact source text. An ingest planner may only group adjacent
 * blocks; it must never rewrite their content.
 */
@Serializable
data class MemoryIngestBlock(
    val id: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
) {
    init {
        require(id.isNotBlank()) { "Memory ingest block id must not be blank." }
        require(startLine > 0) { "Memory ingest block startLine must be positive." }
        require(endLine >= startLine) { "Memory ingest block endLine must not precede startLine." }
        require(text.isNotBlank()) { "Memory ingest block text must not be blank." }
    }
}

@Serializable
data class MemoryIngestPlanningRequest(
    val sourceLabel: String,
    val blocks: List<MemoryIngestBlock>,
    val maxSectionChars: Int,
) {
    init {
        require(sourceLabel.isNotBlank()) { "Memory ingest source label must not be blank." }
        require(blocks.isNotEmpty()) { "Memory ingest planning requires at least one block." }
        require(blocks.map { it.id }.distinct().size == blocks.size) {
            "Memory ingest block ids must be unique."
        }
        require(maxSectionChars > 0) { "Memory ingest maxSectionChars must be positive." }
    }
}

@Serializable
data class MemoryIngestSectionPlan(
    val title: String,
    val blockIds: List<String>,
) {
    init {
        require(title.isNotBlank()) { "Memory ingest section title must not be blank." }
        require(blockIds.isNotEmpty()) { "Memory ingest section must contain at least one block." }
        require(blockIds.distinct().size == blockIds.size) {
            "Memory ingest section block ids must be unique."
        }
    }
}

@Serializable
data class MemoryIngestPlan(
    val decision: Decision,
    val sections: List<MemoryIngestSectionPlan> = emptyList(),
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "Memory ingest plan reason must not be blank." }
        require(decision == Decision.NEEDS_USER_STRUCTURE || sections.isNotEmpty()) {
            "Memory ingest plan $decision requires at least one section."
        }
        require(decision != Decision.NEEDS_USER_STRUCTURE || sections.isEmpty()) {
            "Memory ingest plan NEEDS_USER_STRUCTURE cannot contain proposed sections."
        }
        require(decision != Decision.NEEDS_USER_CONFIRMATION || sections.size > 1) {
            "Memory ingest structure confirmation requires at least two proposed sections."
        }
    }

    @Serializable
    enum class Decision {
        READY,
        NEEDS_USER_CONFIRMATION,
        NEEDS_USER_STRUCTURE,
    }
}

/**
 * Checks whether source blocks are coherent and groups them into processable sections.
 */
interface MemoryIngestPlanner {
    suspend fun plan(request: MemoryIngestPlanningRequest): MemoryIngestPlan
}
