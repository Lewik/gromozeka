package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiRuntimeAssignment

internal object MemoryContextWindowPolicy {
    fun writePreCompactThresholdTokens(snapshot: AiCatalogSnapshot): Int? =
        preCompactThresholdTokens(snapshot, WRITE_STAGE_CONTEXT_PURPOSES)

    fun readPreCompactThresholdTokens(snapshot: AiCatalogSnapshot): Int? =
        preCompactThresholdTokens(snapshot, READ_STAGE_CONTEXT_PURPOSES)

    private fun preCompactThresholdTokens(
        snapshot: AiCatalogSnapshot,
        purposes: List<AiRuntimeAssignment.Purpose>,
    ): Int? {
        val contextWindows = purposes.mapNotNull { purpose ->
            val selection = snapshot.availableRuntimeSelectionFor(purpose) ?: return@mapNotNull null
            val configuration = snapshot.catalog.modelConfigurations.firstOrNull {
                it.id == selection.modelConfigurationId
            } ?: return@mapNotNull null
            snapshot.catalog.modelSpecFor(configuration)?.contextWindowTokens
        }

        return contextWindows
            .minOrNull()
            ?.let { (it.toLong() * PRE_COMPACT_CONTEXT_WINDOW_PERCENT / 100).toInt().coerceAtLeast(1) }
    }

    private const val PRE_COMPACT_CONTEXT_WINDOW_PERCENT = 70

    private val WRITE_STAGE_CONTEXT_PURPOSES = listOf(
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_INGEST_PLANNER,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_RETRIEVAL_PLANNER,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_ENTITY_CANONICALIZER,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_NOTE_CONSTRUCTOR,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_NOTE_RECONCILER,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_EXTRACTOR,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_RECONCILER,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_ACTION_ITEM_UPDATER,
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_FORGET_PLANNER,
    )

    private val READ_STAGE_CONTEXT_PURPOSES = listOf(
        AiRuntimeAssignment.Purpose.MEMORY_READ_PLANNER,
        AiRuntimeAssignment.Purpose.MEMORY_READ_SELECTOR,
    )
}
