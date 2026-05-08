package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext

data class UnifiedSearchRequest(
    val query: String,
    val scopes: List<SearchScope> = listOf(SearchScope.ALL),
    val knowledgeKinds: List<String>? = null,
    val standings: List<String>? = null,
    val bases: List<String>? = null,
    val relationRoles: List<String>? = null,
    val perspectiveKind: String? = null,
    val perspectiveValue: String? = null,
    val includeInvalidated: Boolean? = null,
    val limit: Int? = null,
)

/**
 * Search the new typed memory system.
 *
 * Tool name: `unified_search`
 *
 * This tool searches the new memory IR:
 * episodes, entities, claims, notes, procedures, and typed links.
 *
 * Use it to:
 * - find durable project or user facts
 * - inspect remembered claims with perspective
 * - inspect distilled notes and procedures
 * - inspect raw episodes when needed
 * - inspect links between remembered objects
 *
 * Legacy scope names are still accepted during transition.
 */
interface UnifiedSearchTool : Tool<UnifiedSearchRequest, Map<String, Any>> {
    override val name: String
        get() = "unified_search"

    override val description: String
        get() = "Search the new typed memory system. Use scopes to inspect episodes, entities, claims, notes, procedures, and links."

    override val requestType: Class<UnifiedSearchRequest>
        get() = UnifiedSearchRequest::class.java

    override fun execute(
        request: UnifiedSearchRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}
