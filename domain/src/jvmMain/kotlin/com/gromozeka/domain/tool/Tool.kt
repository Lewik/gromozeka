package com.gromozeka.domain.tool

import org.springframework.ai.chat.model.ToolContext

// TODO: TECHNICAL DEBT - ToolContext dependency in domain layer
//
// Current situation:
// - Domain layer imports Spring AI's ToolContext (framework dependency)
// - Violates Clean Architecture Dependency Rule (domain shouldn't depend on frameworks)
//
// Why it exists:
// - Pragmatic trade-off: Reduces boilerplate adapter code
// - ToolContext is essentially a data container (Map wrapper)
// - Unlikely to change Spring AI framework in foreseeable future
//
// Ideally "clean" solution:
// - Create domain/tool/ToolExecutionContext.kt interface
// - Infrastructure provides SpringAIContextAdapter implementing it
// - Adds abstraction layer but no practical benefit currently
//
// Decision: Keep as-is until framework change becomes necessary
// Impact: Low (isolated to tool interfaces, doesn't leak into domain services)

/**
 * Base interface for all Gromozeka tools.
 * 
 * # Architecture: Domain-Driven Tool Specification Pattern
 * 
 * This interface establishes the contract for **AI-native tools** in Gromozeka. Tools are capabilities
 * exposed to LLMs (via Spring AI and MCP protocol) that allow agents to interact with the system,
 * files, web, memory, and other resources.
 * 
 * ## Design Philosophy: Specification-First Development
 * 
 * **Domain layer contains FULL specifications (interfaces):**
 * - What the tool does (semantics, use cases)
 * - Input parameters with validation rules
 * - Output format and metadata
 * - Error cases and edge cases
 * - Examples (JSON for LLM consumption)
 * - When to use vs alternatives
 * 
 * **Infrastructure layer contains MINIMAL implementations:**
 * - Concrete implementation details
 * - Spring AI integration (ToolCallback conversion)
 * - Reference to domain specification via @see
 * - Only infrastructure-specific notes
 * 
 * **Benefits:**
 * - Single source of truth for tool behavior (domain interface)
 * - Compiler-enforced contracts between layers
 * - Domain specs drive development (Test-Driven via types)
 * - Infrastructure can evolve independently
 * - Easy to understand what system can do (read domain interfaces)
 * 
 * ## Architecture Layers
 * 
 * ```
 * ┌─────────────────────────────────────────────────────────────┐
 * │ LLM (Claude, GPT, etc.)                                     │
 * │ Calls tools via Spring AI's function calling               │
 * └───────────────────────────┬─────────────────────────────────┘
 *                             │
 *                             ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Spring AI Framework                                         │
 * │ - ToolCallback (Spring AI's tool abstraction)              │
 * │ - JSON Schema generation from requestType                  │
 * │ - Tool execution orchestration                             │
 * └───────────────────────────┬─────────────────────────────────┘
 *                             │
 *                             ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Infrastructure Layer (infrastructure-ai module)             │
 * │ - Tool implementations (*Impl classes)                     │
 * │ - Delegates to domain services                             │
 * │ - Formats responses as Map<String, Any>                    │
 * │ - Registered automatically via ToolsRegistrationConfig     │
 * └───────────────────────────┬─────────────────────────────────┘
 *                             │
 *                             ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Domain Layer (domain module) ← YOU ARE HERE                │
 * │ - Tool interfaces (this file + specific tools)             │
 * │ - Request/Response types                                   │
 * │ - Full KDoc specifications                                 │
 * │ - Domain services (business logic)                         │
 * └─────────────────────────────────────────────────────────────┘
 * ```
 * 
 * ## Type Safety & JSON Schema Generation
 * 
 * Tools use typed request classes instead of raw `Map<String, Any>`:
 * 
 * ```kotlin
 * // Domain layer (specification)
 * data class ReadFileRequest(
 *     val file_path: String,
 *     val limit: Int = 1000,
 *     val offset: Int = 0
 * )
 * 
 * interface GrzReadFileTool : Tool<ReadFileRequest, Map<String, Any>> {
 *     // Full specification here
 * }
 * 
 * // Infrastructure layer (implementation)
 * @Service
 * class GrzReadFileToolImpl : GrzReadFileTool {
 *     override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
 *         // Delegate to domain service
 *     }
 * }
 * ```
 * 
 * **Spring AI automatically generates JSON Schema from `requestType`**, enabling:
 * - Compile-time parameter validation
 * - IDE autocomplete for tool parameters
 * - Refactoring safety (rename propagates)
 * - Self-documenting API contracts
 * 
 * ## Tool Categories (13 tools total)
 * 
 * **File System Tools (4):**
 * @see com.gromozeka.domain.tool.filesystem.GrzReadFileTool Read file contents with safety limits
 * @see com.gromozeka.domain.tool.filesystem.GrzWriteFileTool Write content to files
 * @see com.gromozeka.domain.tool.filesystem.GrzEditFileTool Exact string replacement in files
 * @see com.gromozeka.domain.tool.filesystem.GrzExecuteCommandTool Execute shell commands
 * 
 * **Memory/Knowledge Graph Tools (6):**
 * @see com.gromozeka.domain.tool.memory.AddMemoryLinkTool Add facts to knowledge graph
 * @see com.gromozeka.domain.tool.memory.BuildMemoryFromTextTool Extract entities from text via LLM
 * @see com.gromozeka.domain.tool.memory.GetMemoryObjectTool Retrieve entity details
 * @see com.gromozeka.domain.tool.memory.UpdateMemoryObjectTool Update entity metadata
 * @see com.gromozeka.domain.tool.memory.InvalidateMemoryLinkTool Mark facts as outdated (soft delete)
 * @see com.gromozeka.domain.tool.memory.DeleteMemoryObjectTool Permanently delete entities (DANGER)
 * 
 * **Web Tools (3):**
 * @see com.gromozeka.domain.tool.web.BraveWebSearchTool Web search via Brave API
 * @see com.gromozeka.domain.tool.web.BraveLocalSearchTool Local business search
 * @see com.gromozeka.domain.tool.web.JinaReadUrlTool Convert URLs to LLM-friendly Markdown
 * 
 * ## Tool Registration & Lifecycle
 * 
 * **Automatic Registration:**
 * All beans implementing `Tool<*, *>` are automatically converted to Spring AI's `ToolCallback`
 * by `ToolsRegistrationConfig`. No manual registration needed.
 * 
 * ```kotlin
 * @Service  // Spring will find this
 * class GrzReadFileToolImpl : GrzReadFileTool {
 *     // Automatically registered as "grz_read_file" tool
 * }
 * ```
 * 
 * **MCP Protocol Exposure:**
 * Tools are exposed to LLMs via Model Context Protocol (MCP):
 * - Tool name must follow MCP naming: alphanumeric, underscores, hyphens, dots
 * - Description guides LLM when to call the tool
 * - JSON Schema generated from requestType
 * - Responses formatted as Map<String, Any> for JSON serialization
 * 
 * ## Error Handling Strategy
 * 
 * Tools should **return errors as data**, not throw exceptions:
 * 
 * ```kotlin
 * override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
 *     return try {
 *         // ... operation ...
 *         mapOf("content" to result)
 *     } catch (e: Exception) {
 *         mapOf("error" to "Failed: ${e.message}")  // ✅ Return error
 *     }
 * }
 * ```
 * 
 * **Why:** LLMs can handle error messages and retry with adjusted parameters. Exceptions
 * break the conversation flow.
 * 
 * ## Documentation Requirements
 * 
 * Each tool interface must document:
 * - **Purpose** - What problem it solves
 * - **When to use** - Use cases and scenarios
 * - **Parameters** - Full description with defaults and validation
 * - **Returns** - Output structure with examples
 * - **Errors** - All possible error cases
 * - **Examples** - JSON usage examples for LLMs
 * - **Related tools** - Alternatives or complementary tools
 * 
 * ## Tool Evolution
 * 
 * Tools are internal API (no external consumers). **No backwards compatibility needed.**
 * Change interface freely - compiler enforces infrastructure updates
 * 
 * @param TRequest Request parameters (data class with tool arguments)
 * @param TResponse Response type (typically Map<String, Any> for Spring AI JSON serialization)
 * 
 * @see com.gromozeka.infrastructure.ai.config.ToolsRegistrationConfig Automatic tool registration
 * @see com.gromozeka.domain.service Domain services (business logic)
 */
interface Tool<TRequest, TResponse> {
    
    /**
     * Unique tool name exposed to LLMs via MCP protocol.
     * 
     * **Naming conventions:**
     * - Use lowercase with underscores (snake_case)
     * - Prefix with "grz_" for Gromozeka built-in tools
     * - Be descriptive but concise (e.g., "grz_read_file")
     * - Must be valid MCP tool name: alphanumeric, underscores, hyphens, dots
     * 
     * **Examples:**
     * - `grz_read_file` - Built-in file reading
     * - `grz_web_search` - Built-in web search
     * - `custom_analyze_code` - Project-specific tool
     */
    val name: String
    
    /**
     * Human-readable description for LLM to understand when to call the tool.
     * 
     * **Best practices:**
     * - Start with action verb (e.g., "Read file contents...")
     * - Explain WHEN to use (not just WHAT it does)
     * - Mention key parameters and behavior
     * - Keep under 500 chars (LLM context efficiency)
     * - Include common use cases
     * 
     * **Example:**
     * ```
     * "Read file contents with safety limits and metadata.
     * Use this when you need to inspect files without loading entire content.
     * Supports pagination (limit/offset), images (base64), and PDFs."
     * ```
     * 
     * **Note:** Full specification lives in domain interface KDoc, this is
     * just a brief summary for LLM tool selection.
     */
    val description: String
    
    /**
     * Request parameter class for automatic JSON Schema generation.
     * 
     * Spring AI uses this type to:
     * - Generate JSON Schema for tool parameters
     * - Validate incoming LLM requests
     * - Provide type safety in execute() method
     * 
     * **Requirements:**
     * - Must be a data class (for JSON serialization)
     * - Use snake_case for field names (LLM convention)
     * - Provide sensible defaults where possible
     * - Document each field in data class KDoc
     * 
     * **Example:**
     * ```kotlin
     * data class ReadFileRequest(
     *     val file_path: String,           // Required
     *     val limit: Int = 1000,            // Optional with default
     *     val offset: Int = 0               // Optional with default
     * )
     * ```
     */
    val requestType: Class<TRequest>
    
    /**
     * Execute tool with typed request and optional context.
     * 
     * **Implementation contract:**
     * 1. Extract `projectPath` from context (required for most tools)
     * 2. Validate request parameters (fail fast with clear errors)
     * 3. Delegate business logic to domain service
     * 4. Format result as Map<String, Any> for JSON serialization
     * 5. Handle errors gracefully (return error map, don't throw)
     * 
     * **Context usage:**
     * ```kotlin
     * val projectPath = context?.getContext()?.get("projectPath") as? String
     *     ?: error("Project path is required in tool context")
     * ```
     * 
     * **Success response format:**
     * ```kotlin
     * mapOf(
     *     "success" to true,
     *     "data" to resultData,
     *     "metadata" to optionalMetadata
     * )
     * ```
     * 
     * **Error response format:**
     * ```kotlin
     * mapOf(
     *     "error" to "Clear error message",
     *     "details" to optionalDetails
     * )
     * ```
     * 
     * @param request Validated and typed request parameters from LLM
     * @param context Optional tool context (contains projectPath, conversation context, etc.)
     * @return Tool execution result formatted for Spring AI JSON serialization
     */
    fun execute(request: TRequest, context: ToolContext?): TResponse
}
