# ADR 005: Tool Specification Pattern

**Status:** Accepted  
**Date:** 2025-11-27  
**Context:** Domain interface design for MCP tools in Gromozeka

---

## Context

Gromozeka exposes 13 MCP tools for AI agents to interact with file system, memory graph, and web search. Each tool needs:

- **Complete specification** - implementation agents know exactly what to build
- **Type safety** - invalid requests caught at compile time
- **Documentation** - agents know when/how to use tools
- **Evolvability** - tools can be enhanced without breaking existing code
- **Testability** - tools can be tested independently

**Problem:** How to design tool interfaces that serve as **specifications** while enabling:
- ✅ Compiler enforcement (changing spec breaks implementations)
- ✅ Parallel work (specs written before implementations)
- ✅ Technology independence (swap infrastructure without touching specs)
- ✅ Single source of truth (no documentation drift)

---

## Decision

### 1. Tool Interface as Specification

**Core principle:** Tool interface IS the complete specification - no separate spec documents needed.

**Interface structure:**
```kotlin
/**
 * [SPECIFICATION] <Purpose of tool>
 *
 * Tool exposure: <MCP tool name>
 *
 * <Detailed description of what tool does>
 *
 * **Usage:**
 * ```json
 * <JSON usage example>
 * ```
 *
 * **Returns:**
 * <Response structure with examples>
 *
 * **Errors:**
 * <All possible error cases>
 *
 * @see <Related domain service>
 */
interface XxxTool : Tool<XxxRequest, XxxResponse> {
    override fun execute(request: XxxRequest, context: ToolContext?): XxxResponse
}

/**
 * Request for XxxTool.
 *
 * @property param1 description of param1
 * @property param2 description of param2
 */
data class XxxRequest(
    val param1: String,
    val param2: Int = 0  // Default values for optional params
)
```

**WHY this structure:**

**1. [SPECIFICATION] marker**
- Makes it explicit this is a spec, not just interface
- Signals implementation agents to read carefully
- Distinguishes from runtime interfaces

**2. Tool exposure section**
- Documents MCP tool name that LLMs will call
- Makes tool discovery easy
- Links domain spec to runtime behavior

**3. JSON usage examples**
- Shows actual tool invocation format
- Helps agents understand parameters
- Serves as integration test template

**4. Response structure**
- Documents what agents receive back
- Shows success and error formats
- Enables response parsing logic

**5. Errors section**
- All failure cases documented upfront
- Enables proper error handling
- No surprises during implementation

**6. @see reference**
- Links tool to underlying domain service
- Shows delegation pattern
- Enables tracing: tool → service → repository

### 2. Type Safety Through Request Classes

**WHY dedicated request classes:**

**Reason 1: JSON Schema generation**
- Spring AI generates JSON Schema from data class
- LLM receives schema → knows parameter types, required/optional
- Kotlin nullability → JSON Schema required/optional mapping

**Reason 2: Validation at boundaries**
- Request class = validation point
- Invalid JSON → deserialization fails before execute() called
- Type safety prevents runtime errors

**Reason 3: Default values**
```kotlin
data class ReadFileRequest(
    val file_path: String,           // Required
    val limit: Int = 1000,           // Optional, safe default
    val offset: Int = 0              // Optional, starts at beginning
)
```
- Agents can omit optional parameters
- Defaults encode best practices (limit=1000 prevents accidents)
- Reduces cognitive load (common case is simple)

**Reason 4: Evolution**
- Add new optional parameter → existing calls still work
- Rename parameter → compilation breaks → forced migration
- Type change → compilation breaks → forced migration

**Type safety example:**
```kotlin
// ❌ BAD: Stringly-typed parameters
interface BadTool : Tool<Map<String, Any>, Map<String, Any>> {
    override fun execute(request: Map<String, Any>, context: ToolContext?): Map<String, Any>
}

// ✅ GOOD: Typed request/response
data class ReadFileRequest(val file_path: String, val limit: Int)
data class ReadFileResponse(val content: String, val metadata: FileMetadata)

interface GoodTool : Tool<ReadFileRequest, ReadFileResponse> {
    override fun execute(request: ReadFileRequest, context: ToolContext?): ReadFileResponse
}
```

### 3. KDoc Requirements for Tool Specifications

**Every tool interface MUST document:**

| Section | Required | Example |
|---------|----------|---------|
| **Purpose** | ✅ | "Read file contents with safety limits" |
| **Tool exposure** | ✅ | "Tool exposure: `grz_read_file`" |
| **When to use** | ✅ | "Best for: documentation, articles, blog posts" |
| **Usage example** | ✅ | JSON example with actual parameters |
| **Response structure** | ✅ | What agents receive back |
| **Error cases** | ✅ | All possible failures |
| **Related services** | ✅ | `@see FileSystemService.readFile` |

**Request class MUST document:**

| Field | Required | Example |
|-------|----------|---------|
| **Each property** | ✅ | `@property file_path Path to file (required)` |
| **Constraints** | If any | "Must be absolute path", "1-100 range" |
| **Default values** | If any | "Default: 1000 for safety" |

**WHY these requirements:**

- **Purpose** → agents know what tool does
- **Tool exposure** → agents know how to call it
- **When to use** → agents pick right tool for task
- **Usage example** → agents see actual JSON format
- **Response structure** → agents know what to expect
- **Error cases** → agents handle failures correctly
- **Related services** → maintainers trace dependencies

**Implementation agents read this KDoc and have EVERYTHING needed to implement tool.**

### 4. Tool Organization

**Directory structure:**
```
domain/src/jvmMain/kotlin/com/gromozeka/domain/tool/
├── Tool.kt                     # Base interface
├── filesystem/
│   ├── GrzReadFileTool.kt
│   ├── GrzWriteFileTool.kt
│   ├── GrzEditFileTool.kt
│   └── GrzExecuteCommandTool.kt
├── memory/
│   ├── AddMemoryLinkTool.kt
│   ├── BuildMemoryFromTextTool.kt
│   ├── GetMemoryObjectTool.kt
│   ├── UpdateMemoryObjectTool.kt
│   ├── InvalidateMemoryLinkTool.kt
│   └── DeleteMemoryObjectTool.kt
└── web/
    ├── BraveWebSearchTool.kt
    ├── BraveLocalSearchTool.kt
    └── JinaReadUrlTool.kt
```

**WHY this organization:**

- **By category** → easy to find related tools
- **Flat structure** → no deep nesting (max 2 levels)
- **Naming convention** → `Grz*Tool` for Gromozeka tools, provider name for external (Brave*, Jina*)

### 5. Infrastructure Implementation Pattern

**Implementation naming:**
```kotlin
// domain/tool/filesystem/GrzReadFileTool.kt
interface GrzReadFileTool : Tool<ReadFileRequest, Map<String, Any>>

// infrastructure-ai/tool/GrzReadFileToolImpl.kt
@Service
class GrzReadFileToolImpl(
    private val fileSystemService: FileSystemService
) : GrzReadFileTool {
    override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
        // Implementation details
    }
}
```

**Naming convention:**
- Interface: `XxxTool`
- Implementation: `XxxToolImpl`
- Clear relationship, easy to find

**Documentation in implementation:**
```kotlin
/**
 * Implementation of GrzReadFileTool.
 *
 * @see com.gromozeka.domain.tool.filesystem.GrzReadFileTool for specification
 */
@Service
class GrzReadFileToolImpl(...) : GrzReadFileTool {
    // NO DUPLICATED DOCUMENTATION
    // All behavior specified in domain interface
}
```

**WHY minimal documentation:**
- Single source of truth (domain interface)
- Implementation details evident from code
- Changes documented once (in domain spec)

### 6. Evolution and Backwards Compatibility

**Adding optional parameter (safe):**
```kotlin
// Before
data class SearchRequest(
    val query: String,
    val limit: Int = 10
)

// After - backwards compatible
data class SearchRequest(
    val query: String,
    val limit: Int = 10,
    val offset: Int = 0  // New optional parameter with default
)
```

**Existing JSON still works:**
```json
{"query": "test", "limit": 5}  // offset defaults to 0
```

**Changing parameter type (breaking):**
```kotlin
// Before
data class SearchRequest(val query: String)

// After - BREAKING CHANGE
data class SearchRequest(val query: List<String>)  // Type changed!
```

**Migration strategy:**
1. Create new tool version: `SearchToolV2`
2. Deprecate old tool: `@Deprecated("Use SearchToolV2")`
3. Update documentation to point to new version
4. Remove old tool after migration period

**Renaming parameter (breaking):**
```kotlin
// Before
data class Request(val file_path: String)

// After - BREAKING CHANGE
data class Request(val path: String)  // Renamed!
```

**Use parameter aliases if needed:**
```kotlin
data class Request(
    @JsonAlias("file_path", "path")  // Accepts both names
    val path: String
)
```

---

## Consequences

### Positive

✅ **Complete specifications** - implementation agents have everything needed  
✅ **Compiler enforcement** - changing spec breaks implementations  
✅ **Type safety** - invalid requests caught at compile time  
✅ **Single source of truth** - domain interface = complete documentation  
✅ **Parallel work** - specs written before implementations  
✅ **Easy evolution** - optional parameters maintain compatibility  

### Negative

⚠️ **More boilerplate** - interface + request class + response class  
⚠️ **KDoc discipline** - must keep documentation complete and current  
⚠️ **Breaking changes** - parameter renames/type changes break existing code  

### Mitigations

- **More boilerplate:** Offset by type safety and compiler enforcement benefits
- **KDoc discipline:** Architect Agent enforces through review
- **Breaking changes:** Use optional parameters, deprecation, versioning strategies

---

## Examples

### Example 1: File System Tool

```kotlin
/**
 * [SPECIFICATION] Read file contents with safety limits and metadata.
 *
 * Tool exposure: `grz_read_file`
 *
 * Reads file with configurable line limits to prevent accidental loading of huge files.
 * Returns content with line numbers and metadata showing total lines available.
 *
 * **Common patterns:**
 * - Preview file safely: `{"file_path": "file.txt"}` (first 1000 lines)
 * - Read entire small file: `{"file_path": "config.json", "limit": -1}`
 * - Check file size: `{"file_path": "huge.log", "limit": 1}` (see total in metadata)
 * - Paginate large file: `{"file_path": "data.csv", "limit": 100, "offset": 1000}`
 *
 * **Usage:**
 * ```json
 * {
 *   "file_path": "/path/to/file.txt",
 *   "limit": 1000,
 *   "offset": 0
 * }
 * ```
 *
 * **Returns:**
 * ```json
 * {
 *   "content": "1\tLine content\n2\tAnother line\n...",
 *   "metadata": {
 *     "total_lines": 5000,
 *     "read_lines": 1000,
 *     "offset": 0,
 *     "has_more": true
 *   }
 * }
 * ```
 *
 * **Errors:**
 * - File not found: throws FileNotFoundException
 * - Permission denied: throws AccessDeniedException
 * - Invalid limit/offset: throws IllegalArgumentException
 *
 * @see com.gromozeka.domain.service.FileSystemService.readFile
 */
interface GrzReadFileTool : Tool<ReadFileRequest, Map<String, Any>>

/**
 * Request for reading file contents.
 *
 * @property file_path Path to file (required, absolute or relative)
 * @property limit Max lines to read (default: 1000, use -1 for entire file)
 * @property offset Skip first N lines (default: 0, for pagination)
 */
data class ReadFileRequest(
    val file_path: String,
    val limit: Int = 1000,
    val offset: Int = 0
)
```

### Example 2: Memory Tool with Warning

```kotlin
/**
 * [SPECIFICATION] Extract entities and relationships from text using LLM.
 *
 * Tool exposure: `build_memory_from_text`
 *
 * ⚠️ **EXPENSIVE OPERATION - Use Sparingly:**
 * - Makes MULTIPLE LLM requests (one per entity + relationships)
 * - Expensive in tokens and time
 * - Use ONLY for large, complex texts with many entities
 * - For simple facts, use AddMemoryLinkTool instead (direct, no LLM parsing)
 * - ALWAYS ask user permission before using
 *
 * **When to use:**
 * - ✅ Large documents with many interconnected concepts
 * - ✅ Complex technical explanations requiring entity extraction
 * - ❌ Simple facts like "X uses Y" (use AddMemoryLinkTool)
 * - ❌ Single relationships (use AddMemoryLinkTool)
 *
 * **Usage:**
 * ```json
 * {
 *   "content": "Spring AI is a framework for building AI applications...",
 *   "previousMessages": "User asked about AI frameworks..."
 * }
 * ```
 *
 * **Returns:**
 * ```json
 * {
 *   "summary": "Added 5 entities and 8 relationships to knowledge graph",
 *   "entities": ["Spring AI", "AI application", "LLM", ...],
 *   "relationships": [{"from": "Spring AI", "to": "LLM", "relation": "integrates"}]
 * }
 * ```
 *
 * @see com.gromozeka.domain.service.KnowledgeGraphService.extractAndSaveToGraph
 * @see AddMemoryLinkTool for direct fact addition (preferred for simple cases)
 */
interface BuildMemoryFromTextTool : Tool<BuildMemoryFromTextRequest, Map<String, Any>>
```

---

## Related Decisions

- **ADR 004: Spring AI Agent Architecture** - explains delegation pattern, WHY tools exist
- **ADR 001: Repository Pattern** - similar specification pattern for data access
- **ADR 002: Value Objects for IDs** - type safety principles

---

## Implementation Checklist

**For each new tool, Architect Agent must create:**

- [ ] Domain interface extending `Tool<TRequest, TResponse>`
- [ ] Request data class with all properties documented
- [ ] Response type (can be data class, Map, or domain entity)
- [ ] Complete KDoc following requirements above
- [ ] JSON usage examples
- [ ] All error cases documented
- [ ] `@see` reference to domain service
- [ ] Tool registered in proper package (filesystem/memory/web)

**Infrastructure Agent must:**

- [ ] Create `*Impl` class implementing domain interface
- [ ] Add `@Service` annotation
- [ ] Inject domain service(s) via constructor
- [ ] Delegate to domain service
- [ ] Add minimal KDoc with `@see` reference to domain interface
- [ ] Verify compilation: `./gradlew :infrastructure-ai:compileKotlin -q`

---

## References

- Spring AI Tool Calling: https://docs.spring.io/spring-ai/reference/api/tool-calling.html
- JSON Schema from Kotlin: https://github.com/Kotlin/kotlinx.serialization
- MCP Tool Protocol: https://modelcontextprotocol.io/docs/concepts/tools
