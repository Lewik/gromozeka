package com.gromozeka.shared.domain.message

import kotlinx.serialization.*
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * Tool call results - typed representation of different tool outputs
 */
@Serializable
@JsonClassDiscriminator("tool")
sealed class ToolResultData {
    
    
    /**
     * Generic tool result for unknown/MCP tools
     */
    @Serializable
    data class Generic(
        val name: String,
        val output: JsonElement
    ) : ToolResultData()
}