# ADR 004: Spring AI Agent Architecture

**Status:** Accepted  
**Date:** 2025-11-27  
**Context:** Gromozeka infrastructure-ai module architecture

---

## Context

Gromozeka needs AI integration layer that provides:
- **Multiple LLM providers** (Claude, Gemini, OpenAI)
- **Tool calling** (13 MCP tools for file system, memory, web search)
- **Streaming responses** (real-time UI updates)
- **Speech synthesis/recognition** (voice interface)
- **MCP server integration** (external tool providers)

**Problem:** Choose framework and architecture pattern that enables:
- ✅ Clean separation: domain specs → infrastructure implementations
- ✅ Compiler-enforced contracts (type safety)
- ✅ Independent evolution (swap implementations without breaking domain)
- ✅ Parallel agent work (specs written before implementations)

---

## Decision

### 1. Framework Choice: Spring AI

**WHY Spring AI:**

**Reason 1: Multi-provider abstraction**
- Single `ChatModel` interface → multiple providers (Claude, Gemini, OpenAI)
- Swap providers without changing application code
- Provider-specific features (streaming, tool calling) unified

**Reason 2: Built-in tool calling support**
- `@Tool` annotation → automatic JSON Schema generation
- Request/response mapping handled by framework
- LLM calls tools → framework deserializes → executes → returns result

**Reason 3: Production-ready**
- Spring ecosystem maturity (DI, configuration, testing)
- Active development by VMware/Broadcom
- Large community, extensive documentation

**Reason 4: Kotlin-friendly**
- Coroutines support through Spring Reactor adapters
- Data classes → automatic JSON Schema generation
- Null safety respected in tool parameters

**Alternatives considered:**
- ❌ **LangChain4j** - Java-first, less Kotlin-friendly, heavier abstractions
- ❌ **Custom implementation** - reinventing tool calling, JSON Schema, streaming
- ❌ **Direct provider SDKs** - no abstraction, tightly coupled to providers

### 2. Architecture Pattern: Tool Specification Through Domain Interfaces

**Core principle:** Domain interfaces = specifications that **control** development.

**Pattern:**
```
Domain Interface (specification)
    ↓ implements
Infrastructure Implementation (*Impl)
    ↓ compiler enforces
Type Safety + Contract Adherence
```

**WHY this pattern:**

**Reason 1: Specifications control development**
- Architect writes domain interface → other agents implement
- Change interface signature → infrastructure build breaks → forced update
- ✅ True specification control through compiler

**Reason 2: Parallel work enablement**
- Domain specs written first → all agents can start simultaneously
- UI reads interface → builds ViewModel
- Infrastructure reads interface → implements tool
- No coordination overhead

**Reason 3: Technology independence**
- Domain: pure Kotlin, no Spring dependencies
- Infrastructure: Spring AI, annotations, framework code
- Can swap Spring AI for LangChain4j without touching domain

**Example:**
```kotlin
// domain/tool/filesystem/GrzReadFileTool.kt
interface GrzReadFileTool : Tool<ReadFileRequest, Map<String, Any>> {
    /**
     * [SPECIFICATION] Read file with safety limits
     * Tool exposure: grz_read_file
     * ...full KDoc specification...
     */
    override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any>
}

// infrastructure-ai/tool/GrzReadFileToolImpl.kt
@Service
class GrzReadFileToolImpl(
    private val fileSystemService: FileSystemService
) : GrzReadFileTool {  // ← IMPLEMENTS domain spec!
    override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
        // Delegates to fileSystemService
        return fileSystemService.readFile(request.file_path, request.limit, request.offset)
    }
}
```

**Compiler enforcement:**
- Change `GrzReadFileTool.execute()` signature → `GrzReadFileToolImpl` no longer compiles
- Infrastructure Agent **forced** to update implementation
- ✅ Specifications truly control development

### 3. Tool Registration Strategy

**WHY JVM-specific domain interfaces:**

Domain tools are `jvmMain` (not `commonMain`) because:
- Tool calling is **infrastructure concern** (MCP, Spring AI annotations)
- Desktop UI doesn't need tool interfaces (uses domain services)
- Common domain: entities, repositories, business services
- JVM domain: tool specifications for infrastructure layer

**Registration pattern:**
```kotlin
@Configuration
class ToolRegistryConfiguration {
    @Bean
    fun toolRegistry(tools: List<Tool<*, *>>): ToolRegistry {
        return ToolRegistry(tools)  // Spring auto-discovers all Tool implementations
    }
}
```

**Spring discovers implementations:**
- `@Service` on `*ToolImpl` classes → Spring creates beans
- `List<Tool<*, *>>` injection → Spring collects all Tool beans
- MCP server exposes tools automatically

### 4. Delegation to Domain Services

**WHY tools delegate to domain services:**

**Pattern:**
```
Tool (thin adapter)
    ↓ delegates to
Domain Service (business logic)
    ↓ uses
Repository (data access)
```

**Reason 1: Single Responsibility**
- Tool: MCP protocol adapter (request/response mapping)
- Service: business logic (validation, orchestration)
- Repository: data access (persistence)

**Reason 2: Testability**
- Test domain service logic without MCP protocol concerns
- Mock domain services when testing tools
- Mock repositories when testing services

**Reason 3: Reusability**
- UI calls domain service directly (no tool overhead)
- Multiple tools can use same service
- Service logic independent of tool protocol

**Example:**
```kotlin
interface FileSystemService {  // domain/service/
    suspend fun readFile(path: String, limit: Int, offset: Int): ReadFileResult
}

interface GrzReadFileTool : Tool<ReadFileRequest, Map<String, Any>> {  // domain/tool/
    // Specification: how MCP exposes FileSystemService.readFile
}

class GrzReadFileToolImpl(
    private val fileSystemService: FileSystemService  // ← Delegates!
) : GrzReadFileTool {
    override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
        val result = fileSystemService.readFile(request.file_path, request.limit, request.offset)
        return mapOf("content" to result.content, "metadata" to result.metadata)
    }
}
```

### 5. Documentation Strategy

**WHY full specs in domain, minimal in infrastructure:**

**Domain interfaces:**
- ✅ Complete KDoc (WHAT, params, returns, errors, examples)
- ✅ Tool exposure (MCP tool name)
- ✅ JSON usage examples
- ✅ When to use, best practices
- ✅ Relationship to other services

**Infrastructure implementations:**
- ❌ NO duplicated documentation
- ✅ Only implementation-specific details (if any)
- ✅ `@see` reference to domain interface

**Why:**
- Single source of truth (domain interface)
- Changes documented once
- Implementation agents read domain specs
- Reduces maintenance overhead

---

## Consequences

### Positive

✅ **Parallel development:** Architect writes specs → all agents start simultaneously  
✅ **Compiler enforcement:** Changing specs breaks implementations → forced updates  
✅ **Technology independence:** Domain pure Kotlin → can swap Spring AI  
✅ **Clear responsibilities:** Tool (adapter) → Service (logic) → Repository (data)  
✅ **Type safety:** Request classes + Kotlin nullability → invalid states prevented  
✅ **Single source of truth:** Domain interface = complete specification  

### Negative

⚠️ **More files:** Interface + implementation (vs just implementation)  
⚠️ **Learning curve:** Understanding Tool pattern, delegation, domain specs  
⚠️ **JVM-specific domain:** Tools in `jvmMain` (not `commonMain`)  

### Mitigations

- **More files:** Offset by compiler enforcement and parallel work benefits
- **Learning curve:** This ADR documents pattern, examples show usage
- **JVM-specific:** Acceptable - tool calling is infrastructure concern

---

## Related Decisions

- **ADR 005: Tool Specification Pattern** - details on interface design, KDoc requirements
- **ADR 001: Repository Pattern** - similar delegation pattern for data access
- **ADR 002: Value Objects for IDs** - type safety principles applied to tools

---

## Implementation Notes

**Existing tools (13 total):**

**File System (4):**
- `GrzReadFileTool` → `FileSystemService.readFile()`
- `GrzWriteFileTool` → `FileSystemService.writeFile()`
- `GrzEditFileTool` → `FileSystemService.editFile()`
- `GrzExecuteCommandTool` → `CommandExecutionService.execute()`

**Memory (6):**
- `AddMemoryLinkTool` → `MemoryManagementService.addFactDirectly()`
- `BuildMemoryFromTextTool` → `KnowledgeGraphService.extractAndSaveToGraph()`
- `GetMemoryObjectTool` → `MemoryManagementService.getMemoryObject()`
- `UpdateMemoryObjectTool` → `MemoryManagementService.updateMemoryObject()`
- `InvalidateMemoryLinkTool` → `MemoryManagementService.invalidateFact()`
- `DeleteMemoryObjectTool` → `MemoryManagementService.deleteMemoryObject()`

**Web (3):**
- `BraveWebSearchTool` → `WebSearchService.webSearch()`
- `BraveLocalSearchTool` → `WebSearchService.localSearch()`
- `JinaReadUrlTool` → `WebSearchService.readUrl()`

**Build verification:**
```bash
./gradlew :infrastructure-ai:compileKotlin -q
```

**Migration path for new tools:**
1. Architect creates domain interface + request class
2. Infrastructure Agent implements interface
3. Register implementation with `@Service`
4. Spring auto-discovers and exposes via MCP

---

## References

- Spring AI Documentation: https://docs.spring.io/spring-ai/reference/
- Spring AI Tool Calling: https://docs.spring.io/spring-ai/reference/api/tool-calling.html
- MCP Protocol: https://modelcontextprotocol.io/
- `.sources/spring-ai/` - Spring AI source code for deep investigation
