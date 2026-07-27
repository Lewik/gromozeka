package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiRuntimeAssignment

internal object MemoryContextWindowPolicy {
    fun writePreCompactThresholdTokens(aiCatalog: AiCatalog): Int? =
        preCompactThresholdTokens(aiCatalog, WRITE_STAGE_CONTEXT_PURPOSES)

    fun readPreCompactThresholdTokens(aiCatalog: AiCatalog): Int? =
        preCompactThresholdTokens(aiCatalog, READ_STAGE_CONTEXT_PURPOSES)

    private fun preCompactThresholdTokens(
        aiCatalog: AiCatalog,
        purposes: List<AiRuntimeAssignment.Purpose>,
    ): Int? {
        val contextWindows = purposes.mapNotNull { purpose ->
            val selection = aiCatalog.runtimeSelectionFor(purpose) ?: return@mapNotNull null
            val configuration = aiCatalog.modelConfigurations.firstOrNull {
                it.id == selection.modelConfigurationId
            } ?: return@mapNotNull null
            aiCatalog.modelSpecFor(configuration)?.contextWindowTokens
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
