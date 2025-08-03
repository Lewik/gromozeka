package com.gromozeka.shared.domain.message

import kotlinx.serialization.*
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * Tool call details - typed representation of different tool invocations
 */
@Serializable
@JsonClassDiscriminator("tool")
sealed class ToolCallData {
    
    
    /**
     * Generic tool call for unknown/MCP tools
     */
    @Serializable
    data class Generic(
        val name: String,
        val input: JsonElement
    ) : ToolCallData()
}