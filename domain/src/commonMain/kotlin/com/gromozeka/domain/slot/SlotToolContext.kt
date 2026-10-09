package com.gromozeka.domain.slot

import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.serialization.json.Json

const val TOOL_CONTEXT_SLOT_ORIGIN = "slotOrigin"
val SLOT_ENVIRONMENT_KEYS: Set<String> = setOf("GRZ_SLOT", "GRZ_SLOT_ID", "GRZ_SLOT_LEASE_ID")

fun ToolExecutionContext.commandSlotOrigin(): SlotCommandOrigin? = getString(TOOL_CONTEXT_SLOT_ORIGIN)?.let {
    Json.decodeFromString<SlotCommandOrigin>(it).also { origin ->
        require(origin.slotNumber > 0 && origin.slotId.isNotBlank() && origin.leaseId.isNotBlank()) { "Invalid trusted slot origin" }
    }
}

fun SlotCommandOrigin?.commandEnvironment(): Map<String, String> = this?.let {
    mapOf("GRZ_SLOT" to slotNumber.toString(), "GRZ_SLOT_ID" to slotId, "GRZ_SLOT_LEASE_ID" to leaseId)
} ?: emptyMap()
