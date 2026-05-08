package com.gromozeka.domain.tool.codebase

import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext

/**
 * Request parameters for index_domain_to_graph tool.
 *
 * @property project_path Absolute path to project root directory
 * @property project_id Domain Project.Id for linking code specs to project (required for project-scoped indexing)
 */
data class IndexDomainToGraphRequest(
    val project_path: String,
    val project_id: String,
)

/**
 * Domain specification for indexing codebase structure to knowledge graph.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `index_domain_to_graph`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * This tool is currently disabled while the runtime memory is being rebuilt around
 * the new knowledge graph model. The interface stays here so UI and callers can
 * keep a stable contract until code graph indexing is reintroduced on top of the
 * new store.
 *
 * ## Current State
 *
 * - Runtime implementation returns a disabled response
 * - No code entities are written into the new graph yet
 * - `unified_search` does not search code specs
 * - Code graph integration will return later as a dedicated entity subset
 *
 * # When to Use
 *
 * **Use index_domain_to_graph when:**
 * - You want to check whether code graph indexing is available again
 * - You are wiring the future code-spec pipeline back into the system
 *
 * **Don't use when:**
 * - You expect current runtime memory to ingest code symbols
 * - You need code search today
 *
 * # Parameters
 *
 * ## project_path: String (required)
 *
 * Absolute path to project root directory.
 *
 * **Examples:**
 * - `"/Users/dev/gromozeka/dev"` - Development version
 * - `"/Users/dev/my-project"` - Custom project
 *
 * **Validation:**
 * - Must be existing directory
 * - Must contain source files matching patterns
 *
 * ## project_id: String (required)
 *
 * Domain Project.Id for linking code specs to project.
 *
 * **Purpose:**
 * - Enables project-scoped code specs in knowledge graph
 * - Code specs from different projects don't interfere
 * - Allows searching code specs within specific project context
 *
 * **Source:**
 * - Must match Project.Id from domain model
 * - Obtained from current project context (e.g., current tab's project)
 *
 * **Graph structure:**
 * - Creates `(:Project)` node in graph if doesn't exist
 * - Links code specs via `(:CodeSpec)-[:BELONGS_TO_PROJECT]->(:Project)`
 *
 * **Examples:**
 * - `"01234567-89ab-cdef-0123-456789abcdef"` - UUIDv7 from Project.Id
 *
 * # Source Patterns
 *
 * Domain patterns are read from `.gromozeka/project.json` in project directory.
 * Tool does NOT accept source_patterns parameter - configuration is project-specific.
 *
 * **Configuration file:** `.gromozeka/project.json`
 * ```json
 * {
 *   "name": "My Project",
 *   "description": "Project description",
 *   "domain_patterns": [
 *     "domain/src/**/*.kt",
 *     "core/src/**/*.kt"
 *   ]
 * }
 * ```
 *
 * **Default patterns (if file doesn't exist):**
 * - `["domain/src/**/*.kt"]`
 *
 * **Implementation reads patterns via ProjectConfigRepository.getDomainPatterns()**
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with status information:
 *
 * ## Current Disabled Response
 *
 * ```json
 * {
 *   "success": false,
 *   "disabled": true,
 *   "message": "Code graph indexing is currently disabled"
 * }
 * ```
 *
 * **Fields:**
 * - `success` - `false` while feature is disabled
 * - `disabled` - Feature flag in response form
 * - `message` - Human-readable explanation
 *
 * ## Error Response
 *
 * ```json
 * {
 *   "error": "Project not found: /invalid/path",
 *   "details": "Directory does not exist or is not accessible"
 * }
 * ```
 *
 * # Error Cases
 *
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Project not found | Path doesn't exist | Check project_path spelling |
 * | Feature disabled | Code graph pipeline is not wired to the new memory | Re-enable implementation later |
 *
 * # Future Direction
 *
 * When code graph indexing returns, it should:
 * - write code entities into the same new graph store
 * - use dedicated code-specific labels or kinds
 * - keep code search behind `unified_search` only after the new model is wired
 *
 * # Usage Examples
 *
 * ## Example 1: Index Gromozeka Domain (Default)
 *
 * ```json
 * {
 *   "tool": "index_domain_to_graph",
 *   "parameters": {
 *     "project_path": "/Users/lewik/code/gromozeka/dev",
 *     "project_id": "01234567-89ab-cdef-0123-456789abcdef"
 *   }
 * }
 * ```
 *
 * **Result today:** Disabled response
 *
 * ## Example 2: Index Custom Patterns
 *
 * ```json
 * {
 *   "tool": "index_domain_to_graph",
 *   "parameters": {
 *     "project_path": "/Users/dev/my-project",
 *     "project_id": "fedcba98-7654-3210-fedc-ba9876543210",
 *     "source_patterns": [
 *       "core/src/**/*.kt",
 *       "api/src/**/*.kt"
 *     ]
 *   }
 * }
 * ```
 *
 * **Result today:** Disabled response
 *
 * ## Example 3: Re-index After Refactoring
 *
 * ```json
 * {
 *   "tool": "index_domain_to_graph",
 *   "parameters": {
 *     "project_path": "/Users/lewik/code/gromozeka/dev",
 *     "project_id": "01234567-89ab-cdef-0123-456789abcdef"
 *   }
 * }
 * ```
 *
 * **Result today:** Disabled response
 *
 * # Semantic Search Examples
 *
 * Code graph search is not available right now.
 *
 * # Performance Characteristics
 *
 * Performance characteristics are intentionally unspecified until the feature is rebuilt.
 *
 * # Transactionality
 *
 * Current implementation is read-only from the perspective of the graph because it writes nothing.
 *
 * # Limitations
 *
 * - Feature disabled
 * - Code entities are not yet part of the new graph
 *
 * # Related Tools
 *
 * - **unified_search** - Search indexed entities by semantic similarity
 *
 * # Infrastructure Implementation
 *
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.IndexDomainToGraphToolImpl
 *
 */
interface IndexDomainToGraphTool : Tool<IndexDomainToGraphRequest, Map<String, Any>> {

    override val name: String
        get() = "index_domain_to_graph"

    override val description: String
        get() = """
            Code graph indexing is currently disabled while the runtime memory is rebuilt
            around the new graph model. The tool remains available as a stable contract and
            currently returns a disabled response.
        """.trimIndent()

    override val requestType: Class<IndexDomainToGraphRequest>
        get() = IndexDomainToGraphRequest::class.java

    override fun execute(request: IndexDomainToGraphRequest, context: ToolExecutionContext?): Map<String, Any>
}
