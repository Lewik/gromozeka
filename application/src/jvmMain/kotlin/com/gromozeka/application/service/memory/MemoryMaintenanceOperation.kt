package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryMaintenanceAction(
    val toolName: String,
    val displayName: String,
    val runType: MemoryRun.Type,
) {
    CONSOLIDATE("consolidate", "Memory consolidation", MemoryRun.Type.CONSOLIDATE_NOTES),
    REPAIR("repair", "Memory repair", MemoryRun.Type.REPAIR_MEMORY),
    MAINTAIN_ENTITIES("maintain_entities", "Memory entity maintenance", MemoryRun.Type.MAINTAIN_ENTITIES),
    APPLY_RETENTION("apply_retention", "Memory retention", MemoryRun.Type.APPLY_RETENTION),
    REBUILD_EMBEDDINGS("rebuild_embeddings", "Memory embedding rebuild", MemoryRun.Type.REBUILD_EMBEDDINGS);

    companion object {
        fun from(value: String): MemoryMaintenanceAction =
            entries.firstOrNull {
                it.toolName == value.trim().lowercase() || it.name == value.trim().uppercase()
            } ?: throw IllegalArgumentException("Unsupported memory_maintenance action: $value")
    }
}

data class MemoryMaintenanceQueuedResult(
    val runId: MemoryRun.Id,
    val action: MemoryMaintenanceAction,
    val targetKind: MemoryMaintenanceTargetKind,
    val targetValue: String,
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val queueSize: Int,
)
