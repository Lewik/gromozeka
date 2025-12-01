# Claude Agent SDK TypeScript API Reference

## Источник
- **NPM пакет**: `@anthropic-ai/claude-agent-sdk@0.1.55`
- **Локальная копия**: `/Users/lewik/code/gromozeka/dev/.sources/package/`
- **Типы**: `sdk.d.ts` (649 строк)
- **Реализация**: `sdk.mjs` (545 KB)

## Основное API

### query() - One-shot запросы
```typescript
export declare function query(_params: {
    prompt: string | AsyncIterable<SDKUserMessage>;  // ← Поддерживает историю!
    options?: Options;
}): Query;
```

**Ключевой момент**: Можно передать `AsyncIterable<SDKUserMessage>` для передачи всей истории сообщений.

### V2 API (UNSTABLE)
```typescript
// Создание сессии
function unstable_v2_createSession(_options: SDKSessionOptions): SDKSession;

// Возобновление сессии
function unstable_v2_resumeSession(_sessionId: string, _options: SDKSessionOptions): SDKSession;

// One-shot промпт
function unstable_v2_prompt(_message: string, _options: SDKSessionOptions): Promise<SDKResultMessage>;
```

## Типы для Kotlin/JS wrapper

### 1. Сообщения (Messages)

#### SDKUserMessage
```typescript
type SDKUserMessage = {
    type: 'user';
    message: APIUserMessage;  // from @anthropic-ai/sdk
    parent_tool_use_id: string | null;
    isSynthetic?: boolean;
    tool_use_result?: unknown;
    uuid?: UUID;
    session_id: string;
};
```

**Kotlin mapping**:
```kotlin
@Serializable
data class SDKUserMessage(
    val type: String = "user",
    val message: ApiUserMessage,
    val parent_tool_use_id: String? = null,
    val isSynthetic: Boolean? = null,
    val tool_use_result: JsonElement? = null,
    val uuid: String? = null,
    val session_id: String
)
```

#### SDKAssistantMessage
```typescript
type SDKAssistantMessage = {
    type: 'assistant';
    message: APIAssistantMessage;  // BetaMessage from @anthropic-ai/sdk
    parent_tool_use_id: string | null;
    error?: SDKAssistantMessageError;  // 'authentication_failed' | 'billing_error' | ...
    uuid: UUID;
    session_id: string;
};
```

**Kotlin mapping**:
```kotlin
@Serializable
data class SDKAssistantMessage(
    val type: String = "assistant",
    val message: ApiBetaMessage,
    val parent_tool_use_id: String? = null,
    val error: SDKAssistantMessageError? = null,
    val uuid: String,
    val session_id: String
)

@Serializable
enum class SDKAssistantMessageError {
    @SerialName("authentication_failed") AUTHENTICATION_FAILED,
    @SerialName("billing_error") BILLING_ERROR,
    @SerialName("rate_limit") RATE_LIMIT,
    @SerialName("invalid_request") INVALID_REQUEST,
    @SerialName("server_error") SERVER_ERROR,
    @SerialName("unknown") UNKNOWN
}
```

#### SDKResultMessage
```typescript
type SDKResultMessage = {
    type: 'result';
    subtype: 'success';
    duration_ms: number;
    duration_api_ms: number;
    is_error: boolean;
    num_turns: number;
    result: string;
    total_cost_usd: number;
    usage: NonNullableUsage;
    modelUsage: { [modelName: string]: ModelUsage };
    permission_denials: SDKPermissionDenial[];
    structured_output?: unknown;
    uuid: UUID;
    session_id: string;
} | {
    type: 'result';
    subtype: 'error_during_execution' | 'error_max_turns' | 'error_max_budget_usd' | 'error_max_structured_output_retries';
    // ... остальные поля
    errors: string[];
};
```

**Kotlin mapping** (sealed class):
```kotlin
@Serializable
sealed class SDKResultMessage {
    abstract val type: String
    abstract val uuid: String
    abstract val session_id: String

    @Serializable
    @SerialName("success")
    data class Success(
        override val type: String = "result",
        val subtype: String = "success",
        val duration_ms: Long,
        val duration_api_ms: Long,
        val is_error: Boolean,
        val num_turns: Int,
        val result: String,
        val total_cost_usd: Double,
        val usage: NonNullableUsage,
        val modelUsage: Map<String, ModelUsage>,
        val permission_denials: List<SDKPermissionDenial>,
        val structured_output: JsonElement? = null,
        override val uuid: String,
        override val session_id: String
    ) : SDKResultMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        override val type: String = "result",
        val subtype: ErrorSubtype,
        val duration_ms: Long,
        val duration_api_ms: Long,
        val is_error: Boolean,
        val num_turns: Int,
        val total_cost_usd: Double,
        val usage: NonNullableUsage,
        val modelUsage: Map<String, ModelUsage>,
        val permission_denials: List<SDKPermissionDenial>,
        val errors: List<String>,
        override val uuid: String,
        override val session_id: String
    ) : SDKResultMessage()
}

@Serializable
enum class ErrorSubtype {
    @SerialName("error_during_execution") DURING_EXECUTION,
    @SerialName("error_max_turns") MAX_TURNS,
    @SerialName("error_max_budget_usd") MAX_BUDGET,
    @SerialName("error_max_structured_output_retries") MAX_RETRIES
}
```

#### SDKSystemMessage
```typescript
type SDKSystemMessage = {
    type: 'system';
    subtype: 'init';
    agents?: string[];
    apiKeySource: ApiKeySource;  // 'user' | 'project' | 'org' | 'temporary'
    claude_code_version: string;
    cwd: string;
    tools: string[];
    mcp_servers: { name: string; status: string; }[];
    model: string;
    permissionMode: PermissionMode;
    slash_commands: string[];
    output_style: string;
    skills: string[];
    plugins: { name: string; path: string; }[];
    uuid: UUID;
    session_id: string;
};
```

#### SDKMessage (Union type)
```typescript
type SDKMessage =
    | SDKAssistantMessage
    | SDKUserMessage
    | SDKUserMessageReplay
    | SDKResultMessage
    | SDKSystemMessage
    | SDKPartialAssistantMessage
    | SDKCompactBoundaryMessage
    | SDKStatusMessage
    | SDKHookResponseMessage
    | SDKToolProgressMessage
    | SDKAuthStatusMessage;
```

**Kotlin mapping** (sealed interface):
```kotlin
@Serializable
sealed interface SDKMessage {
    val type: String
    val uuid: String
    val session_id: String
}
```

### 2. Options

```typescript
type Options = {
    abortController?: AbortController;
    additionalDirectories?: string[];
    agents?: Record<string, AgentDefinition>;
    allowedTools?: string[];
    canUseTool?: CanUseTool;
    continue?: boolean;
    cwd?: string;
    disallowedTools?: string[];
    env?: { [envVar: string]: string | undefined };
    executable?: 'bun' | 'deno' | 'node';
    executableArgs?: string[];
    extraArgs?: Record<string, string | null>;
    fallbackModel?: string;
    forkSession?: boolean;
    hooks?: Partial<Record<HookEvent, HookCallbackMatcher[]>>;
    includePartialMessages?: boolean;
    maxThinkingTokens?: number;
    maxTurns?: number;
    maxBudgetUsd?: number;
    mcpServers?: Record<string, McpServerConfig>;
    model?: string;
    outputFormat?: OutputFormat;
    pathToClaudeCodeExecutable?: string;
    permissionMode?: PermissionMode;
    allowDangerouslySkipPermissions?: boolean;
    permissionPromptToolName?: string;
    plugins?: SdkPluginConfig[];
    resume?: string;
    resumeSessionAt?: string;
    settingSources?: SettingSource[];
    stderr?: (data: string) => void;
    strictMcpConfig?: boolean;
    systemPrompt?: string | { type: 'preset'; preset: 'claude_code'; append?: string };
};
```

**Kotlin mapping**:
```kotlin
@Serializable
data class Options(
    val additionalDirectories: List<String>? = null,
    val agents: Map<String, AgentDefinition>? = null,
    val allowedTools: List<String>? = null,
    @Contextual val canUseTool: CanUseTool? = null,  // Callback - external declaration
    val `continue`: Boolean? = null,
    val cwd: String? = null,
    val disallowedTools: List<String>? = null,
    val env: Map<String, String?>? = null,
    val executable: Executable? = null,
    val executableArgs: List<String>? = null,
    val extraArgs: Map<String, String?>? = null,
    val fallbackModel: String? = null,
    val forkSession: Boolean? = null,
    @Contextual val hooks: Map<HookEvent, List<HookCallbackMatcher>>? = null,  // Callbacks
    val includePartialMessages: Boolean? = null,
    val maxThinkingTokens: Int? = null,
    val maxTurns: Int? = null,
    val maxBudgetUsd: Double? = null,
    val mcpServers: Map<String, McpServerConfig>? = null,
    val model: String? = null,
    val outputFormat: OutputFormat? = null,
    val pathToClaudeCodeExecutable: String? = null,
    val permissionMode: PermissionMode? = null,
    val allowDangerouslySkipPermissions: Boolean? = null,
    val permissionPromptToolName: String? = null,
    val plugins: List<SdkPluginConfig>? = null,
    val resume: String? = null,
    val resumeSessionAt: String? = null,
    val settingSources: List<SettingSource>? = null,
    @Contextual val stderr: ((String) -> Unit)? = null,  // Callback
    val strictMcpConfig: Boolean? = null,
    val systemPrompt: SystemPrompt? = null
)

@Serializable
enum class Executable {
    @SerialName("bun") BUN,
    @SerialName("deno") DENO,
    @SerialName("node") NODE
}
```

### 3. Enums

#### PermissionMode
```typescript
type PermissionMode = 'default' | 'acceptEdits' | 'bypassPermissions' | 'plan' | 'dontAsk';
```

```kotlin
@Serializable
enum class PermissionMode {
    @SerialName("default") DEFAULT,
    @SerialName("acceptEdits") ACCEPT_EDITS,
    @SerialName("bypassPermissions") BYPASS_PERMISSIONS,
    @SerialName("plan") PLAN,
    @SerialName("dontAsk") DONT_ASK
}
```

#### PermissionBehavior
```typescript
type PermissionBehavior = 'allow' | 'deny' | 'ask';
```

```kotlin
@Serializable
enum class PermissionBehavior {
    @SerialName("allow") ALLOW,
    @SerialName("deny") DENY,
    @SerialName("ask") ASK
}
```

#### HookEvent
```typescript
const HOOK_EVENTS = [
    "PreToolUse", "PostToolUse", "Notification",
    "UserPromptSubmit", "SessionStart", "SessionEnd",
    "Stop", "SubagentStart", "SubagentStop",
    "PreCompact", "PermissionRequest"
] as const;

type HookEvent = typeof HOOK_EVENTS[number];
```

```kotlin
@Serializable
enum class HookEvent {
    @SerialName("PreToolUse") PRE_TOOL_USE,
    @SerialName("PostToolUse") POST_TOOL_USE,
    @SerialName("Notification") NOTIFICATION,
    @SerialName("UserPromptSubmit") USER_PROMPT_SUBMIT,
    @SerialName("SessionStart") SESSION_START,
    @SerialName("SessionEnd") SESSION_END,
    @SerialName("Stop") STOP,
    @SerialName("SubagentStart") SUBAGENT_START,
    @SerialName("SubagentStop") SUBAGENT_STOP,
    @SerialName("PreCompact") PRE_COMPACT,
    @SerialName("PermissionRequest") PERMISSION_REQUEST
}
```

### 4. MCP Server Config

```typescript
type McpServerConfig =
    | McpStdioServerConfig
    | McpSSEServerConfig
    | McpHttpServerConfig
    | McpSdkServerConfigWithInstance;

type McpStdioServerConfig = {
    type?: 'stdio';
    command: string;
    args?: string[];
    env?: Record<string, string>;
};

type McpSSEServerConfig = {
    type: 'sse';
    url: string;
    headers?: Record<string, string>;
};

type McpHttpServerConfig = {
    type: 'http';
    url: string;
    headers?: Record<string, string>;
};

type McpSdkServerConfig = {
    type: 'sdk';
    name: string;
};
```

**Kotlin mapping** (sealed class):
```kotlin
@Serializable
sealed class McpServerConfig {
    abstract val type: String

    @Serializable
    @SerialName("stdio")
    data class Stdio(
        override val type: String = "stdio",
        val command: String,
        val args: List<String>? = null,
        val env: Map<String, String>? = null
    ) : McpServerConfig()

    @Serializable
    @SerialName("sse")
    data class SSE(
        override val type: String = "sse",
        val url: String,
        val headers: Map<String, String>? = null
    ) : McpServerConfig()

    @Serializable
    @SerialName("http")
    data class Http(
        override val type: String = "http",
        val url: String,
        val headers: Map<String, String>? = null
    ) : McpServerConfig()

    @Serializable
    @SerialName("sdk")
    data class Sdk(
        override val type: String = "sdk",
        val name: String
    ) : McpServerConfig()
}
```

### 5. Query Interface

```typescript
interface Query extends AsyncGenerator<SDKMessage, void> {
    // Control requests
    interrupt(): Promise<void>;
    setPermissionMode(mode: PermissionMode): Promise<void>;
    setModel(model?: string): Promise<void>;
    setMaxThinkingTokens(maxThinkingTokens: number | null): Promise<void>;
    supportedCommands(): Promise<SlashCommand[]>;
    supportedModels(): Promise<ModelInfo[]>;
    mcpServerStatus(): Promise<McpServerStatus[]>;
    accountInfo(): Promise<AccountInfo>;
    streamInput(stream: AsyncIterable<SDKUserMessage>): Promise<void>;
}
```

**Kotlin/JS wrapper**:
```kotlin
// commonMain
expect class Query {
    suspend fun interrupt()
    suspend fun setPermissionMode(mode: PermissionMode)
    suspend fun setModel(model: String?)
    suspend fun setMaxThinkingTokens(maxThinkingTokens: Int?)
    suspend fun supportedCommands(): List<SlashCommand>
    suspend fun supportedModels(): List<ModelInfo>
    suspend fun mcpServerStatus(): List<McpServerStatus>
    suspend fun accountInfo(): AccountInfo
    suspend fun streamInput(stream: Flow<SDKUserMessage>)

    // AsyncGenerator<SDKMessage, void>
    fun asFlow(): Flow<SDKMessage>
}

// jsMain
actual external class Query {
    fun interrupt(): Promise<Unit>
    fun setPermissionMode(mode: String): Promise<Unit>
    fun setModel(model: String?): Promise<Unit>
    fun setMaxThinkingTokens(maxThinkingTokens: Int?): Promise<Unit>
    fun supportedCommands(): Promise<Array<SlashCommand>>
    fun supportedModels(): Promise<Array<ModelInfo>>
    fun mcpServerStatus(): Promise<Array<McpServerStatus>>
    fun accountInfo(): Promise<AccountInfo>
    fun streamInput(stream: dynamic): Promise<Unit>
}

actual fun Query.asFlow(): Flow<SDKMessage> = flow {
    val iter = this@asFlow.asDynamic()
    while (true) {
        val result = iter.next().await()
        if (result.done == true) break
        emit(result.value.unsafeCast<SDKMessage>())
    }
}
```

### 6. Usage & ModelUsage

```typescript
type ModelUsage = {
    inputTokens: number;
    outputTokens: number;
    cacheReadInputTokens: number;
    cacheCreationInputTokens: number;
    webSearchRequests: number;
    costUSD: number;
    contextWindow: number;
};
```

```kotlin
@Serializable
data class ModelUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadInputTokens: Int,
    val cacheCreationInputTokens: Int,
    val webSearchRequests: Int,
    val costUSD: Double,
    val contextWindow: Int
)
```

## Callbacks и External Declarations

### CanUseTool
```typescript
type CanUseTool = (
    toolName: string,
    input: Record<string, unknown>,
    options: {
        signal: AbortSignal;
        suggestions?: PermissionUpdate[];
        blockedPath?: string;
        decisionReason?: string;
        toolUseID: string;
        agentID?: string;
    }
) => Promise<PermissionResult>;
```

**Kotlin/JS**:
```kotlin
// Внешнее объявление для JS callback
external interface CanUseToolCallback {
    @JsName("invoke")
    operator fun invoke(
        toolName: String,
        input: dynamic,
        options: CanUseToolOptions
    ): Promise<PermissionResult>
}

external interface CanUseToolOptions {
    var signal: AbortSignal
    var suggestions: Array<PermissionUpdate>?
    var blockedPath: String?
    var decisionReason: String?
    var toolUseID: String
    var agentID: String?
}

// Kotlin wrapper
typealias CanUseTool = suspend (
    toolName: String,
    input: Map<String, Any>,
    options: CanUseToolContext
) -> PermissionResult

data class CanUseToolContext(
    val signal: AbortSignal,
    val suggestions: List<PermissionUpdate>? = null,
    val blockedPath: String? = null,
    val decisionReason: String? = null,
    val toolUseID: String,
    val agentID: String? = null
)
```

### HookCallback
```typescript
type HookCallback = (
    input: HookInput,
    toolUseID: string | undefined,
    options: { signal: AbortSignal }
) => Promise<HookJSONOutput>;
```

**Kotlin/JS**:
```kotlin
external interface HookCallbackJS {
    @JsName("invoke")
    operator fun invoke(
        input: dynamic,
        toolUseID: String?,
        options: HookCallbackOptions
    ): Promise<dynamic>
}

external interface HookCallbackOptions {
    var signal: AbortSignal
}

typealias HookCallback = suspend (
    input: HookInput,
    toolUseID: String?,
    signal: AbortSignal
) -> HookJSONOutput
```

## Структура Kotlin Multiplatform модуля

```
infrastructure-ai-claude-agent-sdk/
├── commonMain/
│   ├── kotlin/
│   │   ├── types/
│   │   │   ├── Messages.kt          (SDKMessage, SDKUserMessage, etc.)
│   │   │   ├── Options.kt           (Options, Enums)
│   │   │   ├── Permissions.kt       (PermissionMode, PermissionUpdate, etc.)
│   │   │   ├── Hooks.kt             (HookEvent, HookInput, etc.)
│   │   │   ├── MCP.kt               (McpServerConfig)
│   │   │   └── Usage.kt             (ModelUsage, NonNullableUsage)
│   │   └── ClaudeAgentSdk.kt        (expect declarations)
├── jsMain/
│   ├── kotlin/
│   │   ├── external/
│   │   │   └── ClaudeAgentSdkJs.kt  (external interface)
│   │   └── ClaudeAgentSdkImpl.kt    (actual implementations)
└── jvmMain/
    └── kotlin/
        └── ClaudeAgentSdkJvm.kt     (Process wrapper)
```

## Важные замечания для реализации

1. **AsyncIterable → Flow**: TypeScript `AsyncIterable<SDKUserMessage>` маппится в Kotlin `Flow<SDKUserMessage>`

2. **Promise → suspend**: Все Promise-based методы становятся suspend функциями

3. **Callbacks**: Для JS callbacks нужны external interface с operator fun invoke

4. **Union types**: TypeScript union types → Kotlin sealed class/interface с @Serializable

5. **Optional fields**: TypeScript `?` → Kotlin nullable `?` с default `null`

6. **Record<K,V>**: TypeScript Record → Kotlin `Map<K,V>`

7. **AbortController**: Нужен JS wrapper для AbortController/AbortSignal
   ```kotlin
   external class AbortController {
       val signal: AbortSignal
       fun abort()
   }

   external interface AbortSignal {
       val aborted: Boolean
   }
   ```

8. **AsyncGenerator**: Для Query нужен wrapper через `asFlow()` extension

## Пример использования в Kotlin

```kotlin
// Передача истории
suspend fun queryWithHistory(messages: List<SDKUserMessage>) {
    val query = query(
        prompt = messages.asFlow(),
        options = Options(
            model = "claude-sonnet-4-5",
            permissionMode = PermissionMode.ACCEPT_EDITS
        )
    )

    query.asFlow().collect { message ->
        when (message) {
            is SDKAssistantMessage -> println("Assistant: ${message.message}")
            is SDKResultMessage.Success -> println("Done: ${message.result}")
            is SDKResultMessage.Error -> println("Error: ${message.errors}")
            else -> println("Other: $message")
        }
    }
}
```
