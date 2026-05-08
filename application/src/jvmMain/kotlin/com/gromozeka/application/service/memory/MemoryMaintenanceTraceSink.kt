package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace

interface MemoryMaintenanceTraceSink {
    fun onMemoryMaintenance(event: MemoryMaintenanceTraceEvent)
}

data class MemoryMaintenanceTraceEvent(
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val stage: Stage,
    val payload: Payload,
) {
    enum class Stage {
        NOTE_CONSOLIDATION,
        MEMORY_REPAIR,
        ENTITY_MAINTENANCE,
        RETENTION,
    }

    sealed interface Payload {
        data class NoteConsolidation(
            val result: MemoryNoteConsolidationPipelineResult,
        ) : Payload

        data class MemoryRepair(
            val result: MemoryRepairPipelineResult,
        ) : Payload

        data class EntityMaintenance(
            val result: MemoryEntityMaintenancePipelineResult,
        ) : Payload

        data class Retention(
            val result: MemoryRetentionPipelineResult,
        ) : Payload
    }
}
