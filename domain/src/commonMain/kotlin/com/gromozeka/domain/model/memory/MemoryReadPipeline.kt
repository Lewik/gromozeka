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
