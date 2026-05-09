package com.gromozeka.domain.tool.codebase

import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext

/**
 * Request parameters for index_domain_to_graph tool.
 *
 * @property project_path Absolute path to project root directory
 * @property project_id Domain Project.Id kept for compatibility with older callers
 */
data class IndexDomainToGraphRequest(
    val project_path: String,
    val project_id: String,
)

/**
 * Stable disabled contract for legacy code graph indexing.
 *
 * Tool name: `index_domain_to_graph`
 *
 * Current state:
 * - runtime implementation is intentionally disabled
 * - no code index is produced
 * - no code entities are written anywhere
 *
 * Keep this interface only so older UI wiring and callers can fail gracefully
 * without losing the tool contract.
 *
 * Use this tool only when you need to confirm that code indexing is unavailable
 * or when reintroducing a future code-indexing pipeline on top of a new design.
 *
 * Current response shape:
 * ```json
 * {
 *   "success": false,
 *   "disabled": true,
 *   "project_id": "...",
 *   "project_path": "...",
 *   "message": "Code graph indexing is currently disabled."
 * }
 * ```
 *
 * @see com.gromozeka.infrastructure.ai.tool.codebase.IndexDomainToGraphToolImpl
 */
interface IndexDomainToGraphTool : Tool<IndexDomainToGraphRequest, Map<String, Any>> {

    override val name: String
        get() = "index_domain_to_graph"

    override val description: String
        get() = """
            Code graph indexing is currently disabled. The tool remains available only as a stable compatibility contract and currently returns a disabled response.
        """.trimIndent()

    override val requestType: Class<IndexDomainToGraphRequest>
        get() = IndexDomainToGraphRequest::class.java

    override fun execute(request: IndexDomainToGraphRequest, context: ToolExecutionContext?): Map<String, Any>
}
