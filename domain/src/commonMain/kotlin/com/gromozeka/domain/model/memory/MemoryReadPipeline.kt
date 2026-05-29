package com.gromozeka.domain.model.memory

data class MemoryReadRequest(
    val namespace: MemoryNamespace,
    val threadContext: MemoryThreadContext,
)

data class MemoryReadResult(
    val plan: MemoryReadPlan,
    val retrievedHits: List<MemoryStore.SearchHit>,
    val runtimePrompt: String?,
    val trace: MemoryReadTrace = MemoryReadTrace(),
)

data class MemoryReadTrace(
    val targetText: String = "",
    val searchSteps: List<SearchStep> = emptyList(),
    val selectorTrace: MemoryReadSelectorTrace = MemoryReadSelectorTrace(),
    val selectorDecisions: List<SelectorDecision> = emptyList(),
    val selectedHits: List<Hit> = emptyList(),
    val sourceSafety: SourceSafety = SourceSafety(),
    val injectedPrompt: InjectedPrompt? = null,
) {
    data class SearchStep(
        val stage: String,
        val query: String,
        val scope: String,
        val requestedLimit: Int,
        val rawCount: Int,
        val candidateCount: Int,
        val selectedCount: Int,
        val rawTopHits: List<Hit> = emptyList(),
        val selectedTopHits: List<Hit> = emptyList(),
    )

    data class Hit(
        val ref: MemoryItemRef,
        val score: Double,
        val summary: String,
        val predicate: String? = null,
        val status: String? = null,
    )

    data class SelectorDecision(
        val ref: MemoryItemRef,
        val selected: Boolean,
        val rank: Int,
        val summary: String = "",
        val reason: String,
    )

    data class SourceSafety(
        val suppressedSources: List<Hit> = emptyList(),
        val restoredTypedHits: List<Hit> = emptyList(),
    )

    data class InjectedPrompt(
        val chars: Int,
        val preview: String,
    )
}

data class MemoryReadSelectorTrace(
    val initialCandidateCount: Int = 0,
    val finalCandidateCount: Int = 0,
    val selectedCount: Int = 0,
    val stages: List<Stage> = emptyList(),
) {
    data class Stage(
        val mode: Mode,
        val level: Int,
        val batchIndex: Int,
        val batchCount: Int,
        val inputCount: Int,
        val llmSelectedCount: Int,
        val llmCarriedCount: Int,
        val safetyAddedCount: Int,
        val outputCount: Int,
        val inputRefs: List<MemoryItemRef> = emptyList(),
        val llmSelectedRefs: List<MemoryItemRef> = emptyList(),
        val llmCarriedRefs: List<MemoryItemRef> = emptyList(),
        val safetyAddedRefs: List<MemoryItemRef> = emptyList(),
        val outputRefs: List<MemoryItemRef> = emptyList(),
    )

    enum class Mode {
        INTERMEDIATE_RECALL,
        FINAL_SELECTION,
    }
}

interface RuntimeMemoryReadService {
    suspend fun read(request: MemoryReadRequest): MemoryReadResult
}

interface MemoryReadPlanner {
    suspend fun plan(request: MemoryReadRequest): MemoryReadPlan
}

data class MemoryReadSelectionRequest(
    val readRequest: MemoryReadRequest,
    val plan: MemoryReadPlan,
    val candidateHits: List<MemoryStore.SearchHit>,
    val snapshot: MemoryNamespaceSnapshot? = null,
)

data class MemoryReadSelectionResult(
    val selectedHits: List<MemoryStore.SearchHit>,
    val decisions: List<Decision> = emptyList(),
    val summary: String = "",
    val selectorTrace: MemoryReadSelectorTrace = MemoryReadSelectorTrace(),
) {
    data class Decision(
        val ref: MemoryItemRef,
        val selected: Boolean,
        val rank: Int,
        val reason: String,
    )
}

interface MemoryReadSelector {
    suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult
}
