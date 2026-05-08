package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore

internal enum class MemorySourceRetrievalUseCase {
    WRITE_GROUNDING,
    READ_RETRIEVAL,
    READ_EVIDENCE,
}

internal data class MemorySourceRetrievalSelection(
    val hits: List<MemoryStore.SearchHit>,
    val beforeSources: Int,
    val afterSources: Int,
    val droppedReasons: Map<String, Int>,
) {
    val changed: Boolean = beforeSources != afterSources

    fun summaryForLog(): String {
        val dropped = droppedReasons.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
            .ifBlank { "none" }

        return "sources=$beforeSources->$afterSources dropped=$dropped"
    }
}

internal object MemorySourceRetrievalPolicy {
    fun apply(
        hits: List<MemoryStore.SearchHit>,
        useCase: MemorySourceRetrievalUseCase,
    ): MemorySourceRetrievalSelection {
        val droppedReasons = mutableMapOf<String, Int>()
        var beforeSources = 0
        var afterSources = 0

        val filteredHits = hits.filter { hit ->
            if (hit !is MemoryStore.SearchHit.SourceHit) {
                return@filter true
            }

            beforeSources += 1
            val decision = hit.source.sourceDecision(useCase)
            if (decision.keep) {
                afterSources += 1
                true
            } else {
                droppedReasons[decision.reason] = droppedReasons.getOrDefault(decision.reason, 0) + 1
                false
            }
        }

        return MemorySourceRetrievalSelection(
            hits = filteredHits,
            beforeSources = beforeSources,
            afterSources = afterSources,
            droppedReasons = droppedReasons,
        )
    }

    private fun MemorySource.sourceDecision(useCase: MemorySourceRetrievalUseCase): SourceDecision {
        if (deletedAt != null) {
            return SourceDecision.drop("deleted")
        }
        if (!usagePolicy.allows(useCase)) {
            return SourceDecision.drop("source_policy_${useCase.name.lowercase()}")
        }

        return when (this) {
            is MemorySource.ChatTurn -> when (speakerRole) {
                MemorySource.ActorRole.USER,
                MemorySource.ActorRole.EXTERNAL,
                -> SourceDecision.keep()

                MemorySource.ActorRole.ASSISTANT -> SourceDecision.drop("assistant_${useCase.name.lowercase()}")
                MemorySource.ActorRole.SYSTEM -> SourceDecision.drop("system_${useCase.name.lowercase()}")
            }

            is MemorySource.DocumentChunk,
            is MemorySource.ImportedNote,
            is MemorySource.ExternalRecord,
            -> SourceDecision.keep()

            is MemorySource.ToolOutput -> SourceDecision.drop("tool_${useCase.name.lowercase()}")
        }
    }

    private data class SourceDecision(
        val keep: Boolean,
        val reason: String,
    ) {
        companion object {
            fun keep(): SourceDecision = SourceDecision(keep = true, reason = "keep")
            fun drop(reason: String): SourceDecision = SourceDecision(keep = false, reason = reason)
        }
    }
}

private fun com.gromozeka.domain.model.memory.MemorySourceUsagePolicy.allows(useCase: MemorySourceRetrievalUseCase): Boolean =
    when (useCase) {
        MemorySourceRetrievalUseCase.WRITE_GROUNDING -> allowStructuredExtraction
        MemorySourceRetrievalUseCase.READ_RETRIEVAL -> allowRecall
        MemorySourceRetrievalUseCase.READ_EVIDENCE -> allowRecall && allowEvidenceHydration
    }
