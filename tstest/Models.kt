// To parse the JSON, install kotlin's serialization plugin and do:
//
// val json   = Json { allowStructuredMapKeys = true }
// val models = json.parse(Models.serializer(), jsonString)

package quicktype

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

@Serializable
data class Models (
    @SerialName("\$schema")
    val schema: String,

    val definitions: Definitions
)

@Serializable
data class Definitions (
    @SerialName("AbortError")
    val abortError: AbortError,

    @SerialName("AccountInfo")
    val accountInfo: AccountInfo,

    @SerialName("AgentDefinition")
    val agentDefinition: AgentDefinition,

    @SerialName("ApiKeySource")
    val apiKeySource: APIKeySource,

    @SerialName("AsyncHookJSONOutput")
    val asyncHookJSONOutput: AsyncHookJSONOutput,

    @SerialName("Base64ImageSource")
    val base64ImageSource: Base64ImageSource,

    @SerialName("Base64PDFSource")
    val base64PDFSource: Source,

    @SerialName("BaseHookInput")
    val baseHookInput: BaseHookInput,

    @SerialName("BaseOutputFormat")
    val baseOutputFormat: BaseOutputFormat,

    @SerialName("BetaBase64PDFSource")
    val betaBase64PDFSource: Source,

    @SerialName("BetaBashCodeExecutionOutputBlock")
    val betaBashCodeExecutionOutputBlock: BetaBlock,

    @SerialName("BetaBashCodeExecutionResultBlock")
    val betaBashCodeExecutionResultBlock: BetaCodeExecutionResultBlock,

    @SerialName("BetaBashCodeExecutionToolResultBlock")
    val betaBashCodeExecutionToolResultBlock: BetaToolResultBlock,

    @SerialName("BetaBashCodeExecutionToolResultError")
    val betaBashCodeExecutionToolResultError: TError,

    @SerialName("BetaCacheCreation")
    val betaCacheCreation: BetaCacheCreation,

    @SerialName("BetaCitationCharLocation")
    val betaCitationCharLocation: BetaCitationCharLocation,

    @SerialName("BetaCitationConfig")
    val betaCitationConfig: BetaCitationConfig,

    @SerialName("BetaCitationContentBlockLocation")
    val betaCitationContentBlockLocation: BetaCitationContentBlockLocation,

    @SerialName("BetaCitationPageLocation")
    val betaCitationPageLocation: BetaCitationPageLocation,

    @SerialName("BetaCitationSearchResultLocation")
    val betaCitationSearchResultLocation: BetaCitationSearchResultLocation,

    @SerialName("BetaCitationsDelta")
    val betaCitationsDelta: BetaCitationsDelta,

    @SerialName("BetaCitationsWebSearchResultLocation")
    val betaCitationsWebSearchResultLocation: BetaCitationsWebSearchResultLocation,

    @SerialName("BetaClearThinking20251015EditResponse")
    val betaClearThinking20251015EditResponse: BetaClearThinking20251015EditResponse,

    @SerialName("BetaClearToolUses20250919EditResponse")
    val betaClearToolUses20250919EditResponse: BetaClearToolUses20250919EditResponse,

    @SerialName("BetaCodeExecutionOutputBlock")
    val betaCodeExecutionOutputBlock: BetaBlock,

    @SerialName("BetaCodeExecutionResultBlock")
    val betaCodeExecutionResultBlock: BetaCodeExecutionResultBlock,

    @SerialName("BetaCodeExecutionToolResultBlock")
    val betaCodeExecutionToolResultBlock: BetaCodeExecutionToolResultBlock,

    @SerialName("BetaCodeExecutionToolResultBlockContent")
    val betaCodeExecutionToolResultBlockContent: BetaCodeExecutionToolResultBlockContent,

    @SerialName("BetaCodeExecutionToolResultError")
    val betaCodeExecutionToolResultError: BetaCodeExecutionToolResultErrorClass,

    @SerialName("BetaCodeExecutionToolResultErrorCode")
    val betaCodeExecutionToolResultErrorCode: APIKeySource,

    @SerialName("BetaContainer")
    val betaContainer: BetaContainer,

    @SerialName("BetaContainerUploadBlock")
    val betaContainerUploadBlock: BetaBlock,

    @SerialName("BetaContentBlock")
    val betaContentBlock: BetaContentBlock,

    @SerialName("BetaContextManagementResponse")
    val betaContextManagementResponse: BetaContextManagementResponse,

    @SerialName("BetaDirectCaller")
    val betaDirectCaller: BetaDirectCallerClass,

    @SerialName("BetaDocumentBlock")
    val betaDocumentBlock: BetaDocumentBlock,

    @SerialName("BetaInputJSONDelta")
    val betaInputJSONDelta: BetaInputJSONDelta,

    @SerialName("BetaMCPToolResultBlock")
    val betaMCPToolResultBlock: BetaMCPToolResultBlock,

    @SerialName("BetaMCPToolUseBlock")
    val betaMCPToolUseBlock: BetaMCPToolUseBlock,

    @SerialName("BetaMessage")
    val betaMessage: BetaMessage,

    @SerialName("BetaMessageDeltaUsage")
    val betaMessageDeltaUsage: BetaMessageDeltaUsage,

    @SerialName("BetaPlainTextSource")
    val betaPlainTextSource: Source,

    @SerialName("BetaRawContentBlockDelta")
    val betaRawContentBlockDelta: BetaCodeExecutionToolResultBlockContent,

    @SerialName("BetaRawContentBlockDeltaEvent")
    val betaRawContentBlockDeltaEvent: BetaRawContentBlockDeltaEvent,

    @SerialName("BetaRawContentBlockStartEvent")
    val betaRawContentBlockStartEvent: BetaRawContentBlockStartEvent,

    @SerialName("BetaRawContentBlockStopEvent")
    val betaRawContentBlockStopEvent: BetaRawContentBlockStopEvent,

    @SerialName("BetaRawMessageDeltaEvent")
    val betaRawMessageDeltaEvent: BetaRawMessageDeltaEvent,

    @SerialName("BetaRawMessageDeltaEvent.Delta")
    val betaRawMessageDeltaEventDelta: BetaRawMessageDeltaEventDelta,

    @SerialName("BetaRawMessageStartEvent")
    val betaRawMessageStartEvent: BetaRawMessageStartEvent,

    @SerialName("BetaRawMessageStopEvent")
    val betaRawMessageStopEvent: BetaDirectCallerClass,

    @SerialName("BetaRawMessageStreamEvent")
    val betaRawMessageStreamEvent: BetaCodeExecutionToolResultBlockContent,

    @SerialName("BetaRedactedThinkingBlock")
    val betaRedactedThinkingBlock: BetaRedactedThinkingBlock,

    @SerialName("BetaServerToolCaller")
    val betaServerToolCaller: BetaServerToolCaller,

    @SerialName("BetaServerToolUsage")
    val betaServerToolUsage: BetaServerToolUsage,

    @SerialName("BetaServerToolUseBlock")
    val betaServerToolUseBlock: BetaServerToolUseBlock,

    @SerialName("BetaSignatureDelta")
    val betaSignatureDelta: BetaSignatureDelta,

    @SerialName("BetaSkill")
    val betaSkill: BetaSkill,

    @SerialName("BetaStopReason")
    val betaStopReason: APIKeySource,

    @SerialName("BetaTextBlock")
    val betaTextBlock: BetaTextBlock,

    @SerialName("BetaTextCitation")
    val betaTextCitation: BetaCodeExecutionToolResultBlockContent,

    @SerialName("BetaTextDelta")
    val betaTextDelta: BetaTextDelta,

    @SerialName("BetaTextEditorCodeExecutionCreateResultBlock")
    val betaTextEditorCodeExecutionCreateResultBlock: BetaTextEditorCodeExecutionCreateResultBlock,

    @SerialName("BetaTextEditorCodeExecutionStrReplaceResultBlock")
    val betaTextEditorCodeExecutionStrReplaceResultBlock: BetaTextEditorCodeExecutionStrReplaceResultBlock,

    @SerialName("BetaTextEditorCodeExecutionToolResultBlock")
    val betaTextEditorCodeExecutionToolResultBlock: BetaToolResultBlock,

    @SerialName("BetaTextEditorCodeExecutionToolResultError")
    val betaTextEditorCodeExecutionToolResultError: BetaTToolResultError,

    @SerialName("BetaTextEditorCodeExecutionViewResultBlock")
    val betaTextEditorCodeExecutionViewResultBlock: BetaTextEditorCodeExecutionViewResultBlock,

    @SerialName("BetaThinkingBlock")
    val betaThinkingBlock: BetaThinkingBlock,

    @SerialName("BetaThinkingDelta")
    val betaThinkingDelta: BetaThinkingDelta,

    @SerialName("BetaToolReferenceBlock")
    val betaToolReferenceBlock: BetaToolReferenceBlock,

    @SerialName("BetaToolSearchToolResultBlock")
    val betaToolSearchToolResultBlock: BetaToolResultBlock,

    @SerialName("BetaToolSearchToolResultError")
    val betaToolSearchToolResultError: BetaTToolResultError,

    @SerialName("BetaToolSearchToolSearchResultBlock")
    val betaToolSearchToolSearchResultBlock: BetaToolSearchToolSearchResultBlock,

    @SerialName("BetaToolUseBlock")
    val betaToolUseBlock: BetaToolUseBlock,

    @SerialName("BetaUsage")
    val betaUsage: BetaUsage,

    @SerialName("BetaWebFetchBlock")
    val betaWebFetchBlock: BetaWebFetchBlock,

    @SerialName("BetaWebFetchToolResultBlock")
    val betaWebFetchToolResultBlock: BetaToolResultBlock,

    @SerialName("BetaWebFetchToolResultErrorBlock")
    val betaWebFetchToolResultErrorBlock: BetaCodeExecutionToolResultErrorClass,

    @SerialName("BetaWebFetchToolResultErrorCode")
    val betaWebFetchToolResultErrorCode: APIKeySource,

    @SerialName("BetaWebSearchResultBlock")
    val betaWebSearchResultBlock: BetaWebSearchResultBlock,

    @SerialName("BetaWebSearchToolResultBlock")
    val betaWebSearchToolResultBlock: BetaCodeExecutionToolResultBlock,

    @SerialName("BetaWebSearchToolResultBlockContent")
    val betaWebSearchToolResultBlockContent: Content,

    @SerialName("BetaWebSearchToolResultError")
    val betaWebSearchToolResultError: BetaCodeExecutionToolResultErrorClass,

    @SerialName("BetaWebSearchToolResultErrorCode")
    val betaWebSearchToolResultErrorCode: APIKeySource,

    @SerialName("CacheControlEphemeral")
    val cacheControlEphemeral: CacheControlEphemeral,

    @SerialName("CanUseTool")
    val canUseTool: CanUseTool,

    @SerialName("CatchallOutput<ZodTypeAny>")
    val catchallOutputZodTypeAny: CatchallOutputZodTypeAny,

    @SerialName("CitationCharLocationParam")
    val citationCharLocationParam: BetaCitationCharLocation,

    @SerialName("CitationContentBlockLocationParam")
    val citationContentBlockLocationParam: BetaCitationContentBlockLocation,

    @SerialName("CitationPageLocationParam")
    val citationPageLocationParam: BetaCitationPageLocation,

    @SerialName("CitationSearchResultLocationParam")
    val citationSearchResultLocationParam: BetaCitationSearchResultLocation,

    @SerialName("CitationWebSearchResultLocationParam")
    val citationWebSearchResultLocationParam: BetaCitationsWebSearchResultLocation,

    @SerialName("CitationsConfigParam")
    val citationsConfigParam: BetaCitationConfig,

    @SerialName("ConfigScope")
    val configScope: APIKeySource,

    @SerialName("ContentBlockParam")
    val contentBlockParam: BetaContentBlock,

    @SerialName("ContentBlockSource")
    val contentBlockSource: ContentBlockSource,

    @SerialName("ContentBlockSourceContent")
    val contentBlockSourceContent: BetaCodeExecutionToolResultBlockContent,

    @SerialName("DocumentBlockParam")
    val documentBlockParam: DocumentBlockParam,

    @SerialName("ExitReason")
    val exitReason: ExitReason,

    @SerialName("HookCallback")
    val hookCallback: HookCallback,

    @SerialName("HookCallbackMatcher")
    val hookCallbackMatcher: HookCallbackMatcher,

    @SerialName("HookEvent")
    val hookEvent: APIKeySource,

    @SerialName("HookInput")
    val hookInput: BetaCodeExecutionToolResultBlockContent,

    @SerialName("HookJSONOutput")
    val hookJSONOutput: BetaCodeExecutionToolResultBlockContent,

    @SerialName("ImageBlockParam")
    val imageBlockParam: ImageBlockParam,

    @SerialName("JsonSchemaOutputFormat")
    val jsonSchemaOutputFormat: JSONSchemaOutputFormat,

    @SerialName("McpHttpServerConfig")
    val mcpHTTPServerConfig: MCPServerConfig,

    @SerialName("McpSSEServerConfig")
    val mcpSSEServerConfig: MCPServerConfig,

    @SerialName("McpSdkServerConfig")
    val mcpSDKServerConfig: MCPSDKServerConfig,

    @SerialName("McpSdkServerConfigWithInstance")
    val mcpSDKServerConfigWithInstance: MCPSDKServerConfigWithInstance,

    @SerialName("McpServerConfig")
    val mcpServerConfig: BetaCodeExecutionToolResultBlockContent,

    @SerialName("McpServerConfigForProcessTransport")
    val mcpServerConfigForProcessTransport: BetaCodeExecutionToolResultBlockContent,

    @SerialName("McpServerStatus")
    val mcpServerStatus: MCPServerStatus,

    @SerialName("McpStdioServerConfig")
    val mcpStdioServerConfig: MCPStdioServerConfig,

    @SerialName("MessageParam")
    val messageParam: MessageParam,

    @SerialName("Model")
    val model: ModelClass,

    @SerialName("ModelInfo")
    val modelInfo: ModelInfo,

    @SerialName("ModelUsage")
    val modelUsage: ModelUsage,

    @SerialName("NonNullableUsage")
    val nonNullableUsage: NonNullableUsage,

    @SerialName("NotificationHookInput")
    val notificationHookInput: NotificationHookInput,

    @SerialName("Options")
    val options: OptionsClass,

    @SerialName("OutputFormat")
    val outputFormat: OutputFormat,

    @SerialName("OutputFormatType")
    val outputFormatType: OutputFormatType,

    @SerialName("PermissionBehavior")
    val permissionBehavior: APIKeySource,

    @SerialName("PermissionMode")
    val permissionMode: APIKeySource,

    @SerialName("PermissionRequestHookInput")
    val permissionRequestHookInput: PermissionRequestHookInputClass,

    @SerialName("PermissionResult")
    val permissionResult: PermissionResult,

    @SerialName("PermissionRuleValue")
    val permissionRuleValue: PermissionRuleValue,

    @SerialName("PermissionUpdate")
    val permissionUpdate: PermissionUpdate,

    @SerialName("PlainTextSource")
    val plainTextSource: Source,

    @SerialName("PostToolUseHookInput")
    val postToolUseHookInput: PermissionRequestHookInputClass,

    @SerialName("PreCompactHookInput")
    val preCompactHookInput: PreCompactHookInput,

    @SerialName("PreToolUseHookInput")
    val preToolUseHookInput: PermissionRequestHookInputClass,

    @SerialName("Query")
    val query: Query,

    @SerialName("RedactedThinkingBlockParam")
    val redactedThinkingBlockParam: BetaRedactedThinkingBlock,

    @SerialName("SDKAssistantMessage")
    val sdkAssistantMessage: SDKAssistantMessage,

    @SerialName("SDKAssistantMessageError")
    val sdkAssistantMessageError: APIKeySource,

    @SerialName("SDKAuthStatusMessage")
    val sdkAuthStatusMessage: SDKAuthStatusMessage,

    @SerialName("SDKCompactBoundaryMessage")
    val sdkCompactBoundaryMessage: SDKMessage,

    @SerialName("SDKHookResponseMessage")
    val sdkHookResponseMessage: SDKHookResponseMessage,

    @SerialName("SDKMessage")
    val sdkMessage: BetaCodeExecutionToolResultBlockContent,

    @SerialName("SDKPartialAssistantMessage")
    val sdkPartialAssistantMessage: SDKPartialAssistantMessage,

    @SerialName("SDKPermissionDenial")
    val sdkPermissionDenial: SDKPermissionDenial,

    @SerialName("SDKResultMessage")
    val sdkResultMessage: SDKResultMessage,

    @SerialName("SDKSession")
    val sdkSession: AccountInfo,

    @SerialName("SDKSessionOptions")
    val sdkSessionOptions: SDKSessionOptions,

    @SerialName("SDKStatus")
    val sdkStatus: SDKStatus,

    @SerialName("SDKStatusMessage")
    val sdkStatusMessage: SDKMessage,

    @SerialName("SDKSystemMessage")
    val sdkSystemMessage: SDKSystemMessage,

    @SerialName("SDKToolProgressMessage")
    val sdkToolProgressMessage: SDKToolProgressMessage,

    @SerialName("SDKUserMessage")
    val sdkUserMessage: SDKUserMessage,

    @SerialName("SDKUserMessageReplay")
    val sdkUserMessageReplay: SDKUserMessage,

    @SerialName("SdkPluginConfig")
    val sdkPluginConfig: SDKPluginConfig,

    @SerialName("SearchResultBlockParam")
    val searchResultBlockParam: SearchResultBlockParam,

    @SerialName("ServerToolUseBlockParam")
    val serverToolUseBlockParam: ServerToolUseBlockParam,

    @SerialName("SessionEndHookInput")
    val sessionEndHookInput: SessionEndHookInputClass,

    @SerialName("SessionStartHookInput")
    val sessionStartHookInput: SessionEndHookInputClass,

    @SerialName("SettingSource")
    val settingSource: APIKeySource,

    @SerialName("SlashCommand")
    val slashCommand: SlashCommand,

    @SerialName("StopHookInput")
    val stopHookInput: SessionEndHookInputClass,

    @SerialName("SubagentStartHookInput")
    val subagentStartHookInput: SubagentStartHookInput,

    @SerialName("SubagentStopHookInput")
    val subagentStopHookInput: SessionEndHookInputClass,

    @SerialName("SyncHookJSONOutput")
    val syncHookJSONOutput: SyncHookJSONOutput,

    @SerialName("TextBlockParam")
    val textBlockParam: TextBlockParam,

    @SerialName("TextCitationParam")
    val textCitationParam: BetaCodeExecutionToolResultBlockContent,

    @SerialName("ThinkingBlockParam")
    val thinkingBlockParam: BetaThinkingBlock,

    @SerialName("ToolResultBlockParam")
    val toolResultBlockParam: ToolResultBlockParam,

    @SerialName("ToolUseBlockParam")
    val toolUseBlockParam: BetaToolUseBlock,

    @SerialName("TypeOf<ZodObject<any>>")
    val typeOfZodObjectAny: OutputFormat,

    @SerialName("TypeOf<ZodObject<unknown>>")
    val typeOfZodObjectUnknown: OutputFormat,

    @SerialName("URLImageSource")
    val urlImageSource: URLSource,

    @SerialName("URLPDFSource")
    val urlpdfSource: URLSource,

    @SerialName("UUID")
    val uuid: ExitReason,

    @SerialName("UserPromptSubmitHookInput")
    val userPromptSubmitHookInput: SessionEndHookInputClass,

    @SerialName("WebSearchResultBlockParam")
    val webSearchResultBlockParam: BetaWebSearchResultBlock,

    @SerialName("WebSearchToolRequestError")
    val webSearchToolRequestError: TError,

    @SerialName("WebSearchToolResultBlockParam")
    val webSearchToolResultBlockParam: BetaCodeExecutionToolResultBlock,

    @SerialName("WebSearchToolResultBlockParamContent")
    val webSearchToolResultBlockParamContent: Content,

    @SerialName("createSdkMcpServer")
    val createSDKMCPServer: CreateSDKMCPServer,

    @SerialName("interface-126095811-72998-74981-126095811-0-1874815")
    val interface126095811729987498112609581101874815: Interface126095811729987498112609581101874815,

    @SerialName("objectOutputType<any,ZodTypeAny,UnknownKeysParam>")
    val objectOutputTypeAnyZodTypeAnyUnknownKeysParam: ObjectOutputTypeAnyZodTypeAnyUnknownKeysParam,

    @SerialName("objectOutputType<unknown,ZodTypeAny,UnknownKeysParam>")
    val objectOutputTypeUnknownZodTypeAnyUnknownKeysParam: ObjectOutputTypeAnyZodTypeAnyUnknownKeysParam,

    @SerialName("objectUtil.flatten<objectUtil.addQuestionMarks<baseObjectOutputType<any>>>")
    val objectUtilFlattenObjectUtilAddQuestionMarksBaseObjectOutputTypeAny: OutputFormat,

    @SerialName("objectUtil.flatten<objectUtil.addQuestionMarks<baseObjectOutputType<unknown>>>")
    val objectUtilFlattenObjectUtilAddQuestionMarksBaseObjectOutputTypeUnknown: OutputFormat,

    @SerialName("objectUtil.identity<indexed-type-1287259773-2233-2270-1287259773-2223-2271-1287259773-2194-2272-1287259773-1577-2801-1287259773-1541-2801-1287259773-0-3387<def-alias-1287259773-1958-2161-1287259773-1577-2801-1287259773-1541-2801-1287259773-0-3387<def-alias-1248838746-20163-20275-1248838746-0-51985<any>>>>")
    val objectUtilIdentityIndexedType128725977322332270128725977322232271128725977321942272128725977315772801128725977315412801128725977303387DefAlias128725977319582161128725977315772801128725977315412801128725977303387DefAlias124883874620163202751248838746051985Any: ObjectUtilIdentityIndexedType128725977322332270128725977322232271128725977321942272128725977315772801128725977315412801128725977303387_DefAlias128725977319582161128725977315772801128725977315412801128725977303387_DefAlias124883874620163202751248838746051985_Any,

    @SerialName("objectUtil.identity<indexed-type-1287259773-2233-2270-1287259773-2223-2271-1287259773-2194-2272-1287259773-1577-2801-1287259773-1541-2801-1287259773-0-3387<def-alias-1287259773-1958-2161-1287259773-1577-2801-1287259773-1541-2801-1287259773-0-3387<def-alias-1248838746-20163-20275-1248838746-0-51985<unknown>>>>")
    val objectUtilIdentityIndexedType128725977322332270128725977322232271128725977321942272128725977315772801128725977315412801128725977303387DefAlias128725977319582161128725977315772801128725977315412801128725977303387DefAlias124883874620163202751248838746051985Unknown: ObjectUtilIdentityIndexedType128725977322332270128725977322232271128725977321942272128725977315772801128725977315412801128725977303387_DefAlias128725977319582161128725977315772801128725977315412801128725977303387_DefAlias124883874620163202751248838746051985_Any,

    @SerialName("query")
    val definitionsQuery: QueryClass,

    val tool: Tool,

    @SerialName("unstable_v2_createSession")
    val unstableV2CreateSession: UnstableV2CreateSession,

    @SerialName("unstable_v2_prompt")
    val unstableV2Prompt: UnstableV2Prompt,

    @SerialName("unstable_v2_resumeSession")
    val unstableV2ResumeSession: UnstableV2ResumeSession
)

@Serializable
data class AbortError (
    val additionalProperties: Boolean,
    val properties: AbortErrorProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class AbortErrorProperties (
    val message: ExitReason,
    val name: ExitReason,
    val stack: ExitReason
)

@Serializable
data class ExitReason (
    val type: ExitReasonType
)

@Serializable
enum class ExitReasonType(val value: String) {
    @SerialName("number") Number("number"),
    @SerialName("boolean") TypeBoolean("boolean"),
    @SerialName("string") TypeString("string");
}

@Serializable
enum class AbortErrorType(val value: String) {
    @SerialName("null") Null("null"),
    @SerialName("object") Object("object"),
    @SerialName("string") TypeString("string");
}

@Serializable
data class AccountInfo (
    val additionalProperties: Boolean,
    val description: String,
    val properties: AccountInfoProperties? = null,
    val type: AbortErrorType
)

@Serializable
data class AccountInfoProperties (
    val apiKeySource: ExitReason,
    val email: ExitReason,
    val organization: ExitReason,
    val subscriptionType: ExitReason,
    val tokenSource: ExitReason
)

@Serializable
data class AgentDefinition (
    val additionalProperties: Boolean,
    val properties: AgentDefinitionProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class AgentDefinitionProperties (
    val description: ExitReason,
    val disallowedTools: DisallowedTools,
    val model: APIKeySource,
    val prompt: ExitReason,
    val tools: DisallowedTools
)

@Serializable
data class DisallowedTools (
    val items: ExitReason? = null,
    val type: DisallowedToolsType
)

@Serializable
enum class DisallowedToolsType(val value: String) {
    @SerialName("null") Null("null"),
    @SerialName("array") TypeArray("array"),
    @SerialName("string") TypeString("string");
}

@Serializable
data class APIKeySource (
    val enum: List<String>? = null,
    val type: ExitReasonType
)

@Serializable
data class AsyncHookJSONOutput (
    val additionalProperties: Boolean,
    val properties: AsyncHookJSONOutputProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class AsyncHookJSONOutputProperties (
    val async: Async,
    val asyncTimeout: ExitReason
)

@Serializable
data class Async (
    val const: Boolean,
    val type: ExitReasonType
)

@Serializable
data class Base64ImageSource (
    val additionalProperties: Boolean,
    val properties: Base64ImageSourceProperties,
    val required: List<Base64ImageSourceRequired>,
    val type: AbortErrorType
)

@Serializable
data class Base64ImageSourceProperties (
    val data: ExitReason,

    @SerialName("media_type")
    val mediaType: APIKeySource,

    val type: OutputFormatType
)

@Serializable
data class OutputFormatType (
    val const: String,
    val type: ExitReasonType
)

@Serializable
enum class Base64ImageSourceRequired(val value: String) {
    @SerialName("data") Data("data"),
    @SerialName("media_type") MediaType("media_type"),
    @SerialName("type") Type("type");
}

@Serializable
data class Source (
    val additionalProperties: Boolean,
    val properties: Base64PDFSourceProperties,
    val required: List<Base64ImageSourceRequired>,
    val type: AbortErrorType
)

@Serializable
data class Base64PDFSourceProperties (
    val data: ExitReason,

    @SerialName("media_type")
    val mediaType: OutputFormatType,

    val type: OutputFormatType
)

@Serializable
data class BaseHookInput (
    val additionalProperties: Boolean,
    val properties: BaseHookInputProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BaseHookInputProperties (
    val cwd: ExitReason,

    @SerialName("permission_mode")
    val permissionMode: ExitReason,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("transcript_path")
    val transcriptPath: ExitReason
)

@Serializable
data class BaseOutputFormat (
    val additionalProperties: Boolean,
    val properties: BaseOutputFormatProperties,
    val required: List<Base64ImageSourceRequired>,
    val type: AbortErrorType
)

@Serializable
data class BaseOutputFormatProperties (
    val type: OutputFormat
)

@Serializable
data class OutputFormat (
    @SerialName("\$ref")
    val ref: String
)

@Serializable
data class BetaBlock (
    val additionalProperties: Boolean,
    val properties: BetaBashCodeExecutionOutputBlockProperties,
    val required: List<String>,
    val type: AbortErrorType,
    val description: String? = null
)

@Serializable
data class BetaBashCodeExecutionOutputBlockProperties (
    @SerialName("file_id")
    val fileID: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaCodeExecutionResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaBashCodeExecutionResultBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaBashCodeExecutionResultBlockProperties (
    val content: ContentElement,

    @SerialName("return_code")
    val returnCode: ExitReason,

    val stderr: ExitReason,
    val stdout: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class ContentElement (
    val items: OutputFormat? = null,
    val type: DisallowedToolsType
)

@Serializable
data class BetaToolResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaBashCodeExecutionToolResultBlockProperties,
    val required: List<BetaBashCodeExecutionToolResultBlockRequired>,
    val type: AbortErrorType
)

@Serializable
data class BetaBashCodeExecutionToolResultBlockProperties (
    val content: BetaCodeExecutionToolResultBlockContent,

    @SerialName("tool_use_id")
    val toolUseID: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaCodeExecutionToolResultBlockContent (
    val anyOf: List<OutputFormat>
)

@Serializable
enum class BetaBashCodeExecutionToolResultBlockRequired(val value: String) {
    @SerialName("content") Content("content"),
    @SerialName("tool_use_id") ToolUseID("tool_use_id"),
    @SerialName("type") Type("type");
}

@Serializable
data class TError (
    val additionalProperties: Boolean,
    val properties: BetaBashCodeExecutionToolResultErrorProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaBashCodeExecutionToolResultErrorProperties (
    @SerialName("error_code")
    val errorCode: APIKeySource,

    val type: OutputFormatType
)

@Serializable
data class BetaCacheCreation (
    val additionalProperties: Boolean? = null,
    val properties: BetaCacheCreationProperties? = null,
    val required: List<String>? = null,
    val type: AbortErrorType
)

@Serializable
data class BetaCacheCreationProperties (
    @SerialName("ephemeral_1h_input_tokens")
    val ephemeral1HInputTokens: Ephemeral1_HInputTokens,

    @SerialName("ephemeral_5m_input_tokens")
    val ephemeral5MInputTokens: Ephemeral1_HInputTokens
)

@Serializable
data class Ephemeral1_HInputTokens (
    val description: String,
    val type: ExitReasonType
)

@Serializable
data class BetaCitationCharLocation (
    val additionalProperties: Boolean,
    val properties: BetaCitationCharLocationProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaCitationCharLocationProperties (
    @SerialName("cited_text")
    val citedText: ExitReason,

    @SerialName("document_index")
    val documentIndex: ExitReason,

    @SerialName("document_title")
    val documentTitle: DocumentTitle,

    @SerialName("end_char_index")
    val endCharIndex: ExitReason,

    @SerialName("file_id")
    val fileID: DocumentTitle? = null,

    @SerialName("start_char_index")
    val startCharIndex: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class DocumentTitle (
    val type: List<DocumentTitleType>
)

@Serializable
enum class DocumentTitleType(val value: String) {
    @SerialName("null") Null("null"),
    @SerialName("number") Number("number"),
    @SerialName("string") TypeString("string");
}

@Serializable
data class BetaCitationConfig (
    val additionalProperties: Boolean,
    val properties: BetaCitationConfigProperties,
    val required: List<String>? = null,
    val type: AbortErrorType
)

@Serializable
data class BetaCitationConfigProperties (
    val enabled: ExitReason
)

@Serializable
data class BetaCitationContentBlockLocation (
    val additionalProperties: Boolean,
    val properties: BetaCitationContentBlockLocationProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaCitationContentBlockLocationProperties (
    @SerialName("cited_text")
    val citedText: ExitReason,

    @SerialName("document_index")
    val documentIndex: ExitReason,

    @SerialName("document_title")
    val documentTitle: DocumentTitle,

    @SerialName("end_block_index")
    val endBlockIndex: ExitReason,

    @SerialName("file_id")
    val fileID: DocumentTitle? = null,

    @SerialName("start_block_index")
    val startBlockIndex: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaCitationPageLocation (
    val additionalProperties: Boolean,
    val properties: BetaCitationPageLocationProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaCitationPageLocationProperties (
    @SerialName("cited_text")
    val citedText: ExitReason,

    @SerialName("document_index")
    val documentIndex: ExitReason,

    @SerialName("document_title")
    val documentTitle: DocumentTitle,

    @SerialName("end_page_number")
    val endPageNumber: ExitReason,

    @SerialName("file_id")
    val fileID: DocumentTitle? = null,

    @SerialName("start_page_number")
    val startPageNumber: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaCitationSearchResultLocation (
    val additionalProperties: Boolean,
    val properties: BetaCitationSearchResultLocationProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaCitationSearchResultLocationProperties (
    @SerialName("cited_text")
    val citedText: ExitReason,

    @SerialName("end_block_index")
    val endBlockIndex: ExitReason,

    @SerialName("search_result_index")
    val searchResultIndex: ExitReason,

    val source: ExitReason,

    @SerialName("start_block_index")
    val startBlockIndex: ExitReason,

    val title: DocumentTitle,
    val type: OutputFormatType
)

@Serializable
data class BetaCitationsDelta (
    val additionalProperties: Boolean,
    val properties: BetaCitationsDeltaProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaCitationsDeltaProperties (
    val citation: BetaCodeExecutionToolResultBlockContent,
    val type: OutputFormatType
)

@Serializable
data class BetaCitationsWebSearchResultLocation (
    val additionalProperties: Boolean,
    val properties: BetaCitationsWebSearchResultLocationProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaCitationsWebSearchResultLocationProperties (
    @SerialName("cited_text")
    val citedText: ExitReason,

    @SerialName("encrypted_index")
    val encryptedIndex: ExitReason,

    val title: DocumentTitle,
    val type: OutputFormatType,
    val url: ExitReason
)

@Serializable
data class BetaClearThinking20251015EditResponse (
    val additionalProperties: Boolean,
    val properties: BetaClearThinking20251015EditResponseProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaClearThinking20251015EditResponseProperties (
    @SerialName("cleared_input_tokens")
    val clearedInputTokens: Ephemeral1_HInputTokens,

    @SerialName("cleared_thinking_turns")
    val clearedThinkingTurns: Ephemeral1_HInputTokens,

    val type: RoleClass
)

@Serializable
data class RoleClass (
    val const: String,
    val description: String,
    val type: ExitReasonType
)

@Serializable
data class BetaClearToolUses20250919EditResponse (
    val additionalProperties: Boolean,
    val properties: BetaClearToolUses20250919EditResponseProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaClearToolUses20250919EditResponseProperties (
    @SerialName("cleared_input_tokens")
    val clearedInputTokens: Ephemeral1_HInputTokens,

    @SerialName("cleared_tool_uses")
    val clearedToolUses: Ephemeral1_HInputTokens,

    val type: RoleClass
)

@Serializable
data class BetaCodeExecutionToolResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaCodeExecutionToolResultBlockProperties,
    val required: List<BetaBashCodeExecutionToolResultBlockRequired>,
    val type: AbortErrorType
)

@Serializable
data class BetaCodeExecutionToolResultBlockProperties (
    val content: OutputFormat,

    @SerialName("tool_use_id")
    val toolUseID: ExitReason,

    val type: OutputFormatType,

    @SerialName("cache_control")
    val cacheControl: CacheControl? = null
)

@Serializable
data class CacheControl (
    val anyOf: List<CacheControlAnyOf>,
    val description: String
)

@Serializable
data class CacheControlAnyOf (
    @SerialName("\$ref")
    val ref: String? = null,

    val type: DisallowedToolsType? = null
)

@Serializable
data class BetaCodeExecutionToolResultErrorClass (
    val additionalProperties: Boolean,
    val properties: BetaCodeExecutionToolResultErrorProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaCodeExecutionToolResultErrorProperties (
    @SerialName("error_code")
    val errorCode: OutputFormat,

    val type: OutputFormatType
)

@Serializable
data class BetaContainer (
    val additionalProperties: Boolean,
    val description: String,
    val properties: BetaContainerProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaContainerProperties (
    @SerialName("expires_at")
    val expiresAt: Ephemeral1_HInputTokens,

    val id: Ephemeral1_HInputTokens,
    val skills: Skills
)

@Serializable
data class Skills (
    val anyOf: List<ContentElement>,
    val description: String
)

@Serializable
data class BetaContentBlock (
    val anyOf: List<OutputFormat>,
    val description: String
)

@Serializable
data class BetaContextManagementResponse (
    val additionalProperties: Boolean,
    val properties: BetaContextManagementResponseProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaContextManagementResponseProperties (
    @SerialName("applied_edits")
    val appliedEdits: AppliedEdits
)

@Serializable
data class AppliedEdits (
    val description: String,
    val items: BetaCodeExecutionToolResultBlockContent,
    val type: DisallowedToolsType
)

@Serializable
data class BetaDirectCallerClass (
    val additionalProperties: Boolean,
    val description: String? = null,
    val properties: BetaDirectCallerProperties,
    val required: List<Base64ImageSourceRequired>,
    val type: AbortErrorType
)

@Serializable
data class BetaDirectCallerProperties (
    val type: OutputFormatType
)

@Serializable
data class BetaDocumentBlock (
    val additionalProperties: Boolean,
    val properties: BetaDocumentBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaDocumentBlockProperties (
    val citations: CacheControl,
    val source: BetaCodeExecutionToolResultBlockContent,
    val title: Title,
    val type: OutputFormatType
)

@Serializable
data class Title (
    val description: String,
    val type: List<DocumentTitleType>
)

@Serializable
data class BetaInputJSONDelta (
    val additionalProperties: Boolean,
    val properties: BetaInputJSONDeltaProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaInputJSONDeltaProperties (
    @SerialName("partial_json")
    val partialJSON: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaMCPToolResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaMCPToolResultBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaMCPToolResultBlockProperties (
    val content: CitationsClass,

    @SerialName("is_error")
    val isError: ExitReason,

    @SerialName("tool_use_id")
    val toolUseID: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class CitationsClass (
    val anyOf: List<ContentElement>
)

@Serializable
data class BetaMCPToolUseBlock (
    val additionalProperties: Boolean,
    val properties: BetaMCPToolUseBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaMCPToolUseBlockProperties (
    val id: ExitReason,
    val input: CatchallOutputZodTypeAny,
    val name: Ephemeral1_HInputTokens,

    @SerialName("server_name")
    val serverName: Ephemeral1_HInputTokens,

    val type: OutputFormatType
)

@Serializable
class CatchallOutputZodTypeAny()

@Serializable
data class BetaMessage (
    val additionalProperties: Boolean,
    val properties: BetaMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaMessageProperties (
    val container: CacheControl,
    val content: PluginsClass,

    @SerialName("context_management")
    val contextManagement: CacheControl,

    val id: Ephemeral1_HInputTokens,
    val model: Model,
    val role: RoleClass,

    @SerialName("stop_reason")
    val stopReason: CacheControl,

    @SerialName("stop_sequence")
    val stopSequence: Title,

    val type: RoleClass,
    val usage: Model
)

@Serializable
data class PluginsClass (
    val description: String,
    val items: OutputFormat,
    val type: DisallowedToolsType
)

@Serializable
data class Model (
    @SerialName("\$ref")
    val ref: String,

    val description: String
)

@Serializable
data class BetaMessageDeltaUsage (
    val additionalProperties: Boolean,
    val properties: BetaMessageDeltaUsageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaMessageDeltaUsageProperties (
    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Title,

    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Title,

    @SerialName("input_tokens")
    val inputTokens: Title,

    @SerialName("output_tokens")
    val outputTokens: Ephemeral1_HInputTokens,

    @SerialName("server_tool_use")
    val serverToolUse: CacheControl
)

@Serializable
data class BetaRawContentBlockDeltaEvent (
    val additionalProperties: Boolean,
    val properties: BetaRawContentBlockDeltaEventProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaRawContentBlockDeltaEventProperties (
    val delta: OutputFormat,
    val index: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaRawContentBlockStartEvent (
    val additionalProperties: Boolean,
    val properties: BetaRawContentBlockStartEventProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaRawContentBlockStartEventProperties (
    @SerialName("content_block")
    val contentBlock: BetaContentBlock,

    val index: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaRawContentBlockStopEvent (
    val additionalProperties: Boolean,
    val properties: BetaRawContentBlockStopEventProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaRawContentBlockStopEventProperties (
    val index: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaRawMessageDeltaEvent (
    val additionalProperties: Boolean,
    val properties: BetaRawMessageDeltaEventProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaRawMessageDeltaEventProperties (
    @SerialName("context_management")
    val contextManagement: CacheControl,

    val delta: OutputFormat,
    val type: OutputFormatType,
    val usage: Model
)

@Serializable
data class BetaRawMessageDeltaEventDelta (
    val additionalProperties: Boolean,
    val properties: BetaRawMessageDeltaEventDeltaProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaRawMessageDeltaEventDeltaProperties (
    val container: CacheControl,

    @SerialName("stop_reason")
    val stopReason: StopReason,

    @SerialName("stop_sequence")
    val stopSequence: DocumentTitle
)

@Serializable
data class StopReason (
    val anyOf: List<CacheControlAnyOf>
)

@Serializable
data class BetaRawMessageStartEvent (
    val additionalProperties: Boolean,
    val properties: BetaRawMessageStartEventProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaRawMessageStartEventProperties (
    val message: OutputFormat,
    val type: OutputFormatType
)

@Serializable
data class BetaRedactedThinkingBlock (
    val additionalProperties: Boolean,
    val properties: BetaRedactedThinkingBlockProperties,
    val required: List<Base64ImageSourceRequired>,
    val type: AbortErrorType
)

@Serializable
data class BetaRedactedThinkingBlockProperties (
    val data: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaServerToolCaller (
    val additionalProperties: Boolean,
    val description: String,
    val properties: BetaServerToolCallerProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaServerToolCallerProperties (
    @SerialName("tool_id")
    val toolID: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaServerToolUsage (
    val additionalProperties: Boolean? = null,
    val properties: BetaServerToolUsageProperties? = null,
    val required: List<String>? = null,
    val type: AbortErrorType
)

@Serializable
data class BetaServerToolUsageProperties (
    @SerialName("web_fetch_requests")
    val webFetchRequests: Ephemeral1_HInputTokens,

    @SerialName("web_search_requests")
    val webSearchRequests: Ephemeral1_HInputTokens
)

@Serializable
data class BetaServerToolUseBlock (
    val additionalProperties: Boolean,
    val properties: BetaServerToolUseBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaServerToolUseBlockProperties (
    val caller: BetaContentBlock,
    val id: ExitReason,
    val input: Input,
    val name: APIKeySource,
    val type: OutputFormatType
)

@Serializable
data class Input (
    val additionalProperties: CatchallOutputZodTypeAny,
    val type: AbortErrorType
)

@Serializable
data class BetaSignatureDelta (
    val additionalProperties: Boolean,
    val properties: BetaSignatureDeltaProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaSignatureDeltaProperties (
    val signature: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaSkill (
    val additionalProperties: Boolean,
    val description: String,
    val properties: BetaSkillProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaSkillProperties (
    @SerialName("skill_id")
    val skillID: Ephemeral1_HInputTokens,

    val type: TTLClass,
    val version: Ephemeral1_HInputTokens
)

@Serializable
data class TTLClass (
    val description: String,
    val enum: List<String>,
    val type: ExitReasonType
)

@Serializable
data class BetaTextBlock (
    val additionalProperties: Boolean,
    val properties: BetaTextBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaTextBlockProperties (
    val citations: Skills,
    val text: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaTextDelta (
    val additionalProperties: Boolean,
    val properties: BetaTextDeltaProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaTextDeltaProperties (
    val text: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaTextEditorCodeExecutionCreateResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaTextEditorCodeExecutionCreateResultBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaTextEditorCodeExecutionCreateResultBlockProperties (
    @SerialName("is_file_update")
    val isFileUpdate: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaTextEditorCodeExecutionStrReplaceResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaTextEditorCodeExecutionStrReplaceResultBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaTextEditorCodeExecutionStrReplaceResultBlockProperties (
    val lines: Lines,

    @SerialName("new_lines")
    val newLines: DocumentTitle,

    @SerialName("new_start")
    val newStart: DocumentTitle,

    @SerialName("old_lines")
    val oldLines: DocumentTitle,

    @SerialName("old_start")
    val oldStart: DocumentTitle,

    val type: OutputFormatType
)

@Serializable
data class Lines (
    val anyOf: List<DisallowedTools>
)

@Serializable
data class BetaTToolResultError (
    val additionalProperties: Boolean,
    val properties: BetaTextEditorCodeExecutionToolResultErrorProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaTextEditorCodeExecutionToolResultErrorProperties (
    @SerialName("error_code")
    val errorCode: APIKeySource,

    @SerialName("error_message")
    val errorMessage: DocumentTitle,

    val type: OutputFormatType
)

@Serializable
data class BetaTextEditorCodeExecutionViewResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaTextEditorCodeExecutionViewResultBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaTextEditorCodeExecutionViewResultBlockProperties (
    val content: ExitReason,

    @SerialName("file_type")
    val fileType: APIKeySource,

    @SerialName("num_lines")
    val numLines: DocumentTitle,

    @SerialName("start_line")
    val startLine: DocumentTitle,

    @SerialName("total_lines")
    val totalLines: DocumentTitle,

    val type: OutputFormatType
)

@Serializable
data class BetaThinkingBlock (
    val additionalProperties: Boolean,
    val properties: BetaThinkingBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaThinkingBlockProperties (
    val signature: ExitReason,
    val thinking: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaThinkingDelta (
    val additionalProperties: Boolean,
    val properties: BetaThinkingDeltaProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaThinkingDeltaProperties (
    val thinking: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class BetaToolReferenceBlock (
    val additionalProperties: Boolean,
    val properties: BetaToolReferenceBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaToolReferenceBlockProperties (
    @SerialName("tool_name")
    val toolName: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class BetaToolSearchToolSearchResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaToolSearchToolSearchResultBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaToolSearchToolSearchResultBlockProperties (
    @SerialName("tool_references")
    val toolReferences: ContentElement,

    val type: OutputFormatType
)

@Serializable
data class BetaToolUseBlock (
    val additionalProperties: Boolean,
    val properties: BetaToolUseBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaToolUseBlockProperties (
    val caller: BetaContentBlock? = null,
    val id: ExitReason,
    val input: CatchallOutputZodTypeAny,
    val name: ExitReason,
    val type: OutputFormatType,

    @SerialName("cache_control")
    val cacheControl: CacheControl? = null
)

@Serializable
data class BetaUsage (
    val additionalProperties: Boolean,
    val properties: BetaUsageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaUsageProperties (
    @SerialName("cache_creation")
    val cacheCreation: CacheControl,

    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Title,

    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Title,

    @SerialName("input_tokens")
    val inputTokens: Ephemeral1_HInputTokens,

    @SerialName("output_tokens")
    val outputTokens: Ephemeral1_HInputTokens,

    @SerialName("server_tool_use")
    val serverToolUse: CacheControl,

    @SerialName("service_tier")
    val serviceTier: ServiceTier
)

@Serializable
data class ServiceTier (
    val description: String,
    val enum: List<String?>,
    val type: List<DisallowedToolsType>
)

@Serializable
data class BetaWebFetchBlock (
    val additionalProperties: Boolean,
    val properties: BetaWebFetchBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaWebFetchBlockProperties (
    val content: OutputFormat,

    @SerialName("retrieved_at")
    val retrievedAt: Title,

    val type: OutputFormatType,
    val url: Ephemeral1_HInputTokens
)

@Serializable
data class BetaWebSearchResultBlock (
    val additionalProperties: Boolean,
    val properties: BetaWebSearchResultBlockProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BetaWebSearchResultBlockProperties (
    @SerialName("encrypted_content")
    val encryptedContent: ExitReason,

    @SerialName("page_age")
    val pageAge: DocumentTitle,

    val title: ExitReason,
    val type: OutputFormatType,
    val url: ExitReason
)

@Serializable
data class Content (
    val anyOf: List<BetaWebSearchToolResultBlockContentAnyOf>
)

@Serializable
data class BetaWebSearchToolResultBlockContentAnyOf (
    @SerialName("\$ref")
    val ref: String? = null,

    val items: OutputFormat? = null,
    val type: DisallowedToolsType? = null
)

@Serializable
data class CacheControlEphemeral (
    val additionalProperties: Boolean,
    val properties: CacheControlEphemeralProperties,
    val required: List<Base64ImageSourceRequired>,
    val type: AbortErrorType
)

@Serializable
data class CacheControlEphemeralProperties (
    val ttl: TTLClass,
    val type: OutputFormatType
)

@Serializable
data class CanUseTool (
    @SerialName("\$comment")
    val comment: String,

    val properties: CanUseToolProperties,
    val type: AbortErrorType
)

@Serializable
data class CanUseToolProperties (
    val namedArgs: PurpleNamedArgs
)

@Serializable
data class PurpleNamedArgs (
    val additionalProperties: Boolean,
    val properties: PurpleProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class PurpleProperties (
    val input: Input,
    val options: Options,
    val toolName: ExitReason
)

@Serializable
data class Options (
    val additionalProperties: Boolean,
    val properties: FluffyProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class FluffyProperties (
    val agentID: Ephemeral1_HInputTokens,
    val blockedPath: Ephemeral1_HInputTokens,
    val decisionReason: Ephemeral1_HInputTokens,
    val signal: Interface126095811729987498112609581101874815,
    val suggestions: PluginsClass,
    val toolUseID: Ephemeral1_HInputTokens
)

@Serializable
data class Interface126095811729987498112609581101874815 (
    val additionalProperties: Boolean,
    val description: String? = null,
    val properties: Interface126095811729987498112609581101874815_Properties,
    val required: List<Interface126095811729987498112609581101874815_Required>,
    val type: AbortErrorType
)

@Serializable
data class Interface126095811729987498112609581101874815_Properties (
    val aborted: ExitReason,
    val onabort: Onabort,
    val reason: CatchallOutputZodTypeAny
)

@Serializable
data class Onabort (
    val anyOf: List<OnabortAnyOf>
)

@Serializable
data class OnabortAnyOf (
    @SerialName("\$comment")
    val comment: String? = null,

    val properties: TentacledProperties? = null,
    val type: AbortErrorType
)

@Serializable
data class TentacledProperties (
    val namedArgs: FluffyNamedArgs
)

@Serializable
data class FluffyNamedArgs (
    val additionalProperties: Boolean,
    val properties: StickyProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class StickyProperties (
    val ev: Ev,

    @SerialName("this")
    val propertiesThis: OutputFormat
)

@Serializable
data class Ev (
    val additionalProperties: Boolean,
    val properties: EvProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class EvProperties (
    @SerialName("AT_TARGET")
    val atTarget: AtTarget,

    @SerialName("BUBBLING_PHASE")
    val bubblingPhase: AtTarget,

    @SerialName("CAPTURING_PHASE")
    val capturingPhase: AtTarget,

    @SerialName("NONE")
    val none: AtTarget,

    val bubbles: ExitReason,
    val cancelBubble: ExitReason,
    val cancelable: ExitReason,
    val composed: ExitReason,
    val currentTarget: Prompt,
    val defaultPrevented: ExitReason,
    val eventPhase: ExitReason,
    val isTrusted: ExitReason,
    val returnValue: ExitReason,
    val srcElement: Prompt,
    val target: Prompt,
    val timeStamp: ExitReason,
    val type: ExitReason
)

@Serializable
data class AtTarget (
    val const: Long,
    val type: ExitReasonType
)

@Serializable
data class Prompt (
    val anyOf: List<ObjectUtilIdentityIndexedType128725977322332270128725977322232271128725977321942272128725977315772801128725977315412801128725977303387_DefAlias128725977319582161128725977315772801128725977315412801128725977303387_DefAlias124883874620163202751248838746051985_Any>
)

@Serializable
data class ObjectUtilIdentityIndexedType128725977322332270128725977322232271128725977321942272128725977315772801128725977315412801128725977303387_DefAlias128725977319582161128725977315772801128725977315412801128725977303387_DefAlias124883874620163202751248838746051985_Any (
    val additionalProperties: Boolean? = null,
    val type: AbortErrorType
)

@Serializable
enum class Interface126095811729987498112609581101874815_Required(val value: String) {
    @SerialName("aborted") Aborted("aborted"),
    @SerialName("onabort") Onabort("onabort"),
    @SerialName("reason") Reason("reason");
}

@Serializable
data class ContentBlockSource (
    val additionalProperties: Boolean,
    val properties: ContentBlockSourceProperties,
    val required: List<BetaBashCodeExecutionToolResultBlockRequired>,
    val type: AbortErrorType
)

@Serializable
data class ContentBlockSourceProperties (
    val content: CitationsClass,
    val type: OutputFormatType
)

@Serializable
data class CreateSDKMCPServer (
    @SerialName("\$comment")
    val comment: String,

    val properties: CreateSDKMCPServerProperties,
    val type: AbortErrorType
)

@Serializable
data class CreateSDKMCPServerProperties (
    val namedArgs: IndigoNamedArgs
)

@Serializable
data class IndigoNamedArgs (
    val additionalProperties: Boolean,
    val properties: Properties2,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties2 (
    @SerialName("_options")
    val options: PropertiesOptions
)

@Serializable
data class PropertiesOptions (
    val additionalProperties: Boolean,
    val properties: OptionsPropertiesClass,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class OptionsPropertiesClass (
    val name: ExitReason,
    val tools: Tools,
    val version: ExitReason
)

@Serializable
data class Tools (
    val items: ToolsItems,
    val type: DisallowedToolsType
)

@Serializable
data class ToolsItems (
    val additionalProperties: Boolean,
    val properties: Properties3,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties3 (
    val description: ExitReason,
    val handler: Handler,
    val inputSchema: CatchallOutputZodTypeAny,
    val name: ExitReason
)

@Serializable
data class Handler (
    @SerialName("\$comment")
    val comment: String,

    val properties: HandlerProperties,
    val type: AbortErrorType
)

@Serializable
data class HandlerProperties (
    val namedArgs: IndecentNamedArgs
)

@Serializable
data class IndecentNamedArgs (
    val additionalProperties: Boolean,
    val properties: Properties4,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties4 (
    val args: OutputFormat,
    val extra: CatchallOutputZodTypeAny
)

@Serializable
data class QueryClass (
    @SerialName("\$comment")
    val comment: String,

    val properties: QueryProperties,
    val type: AbortErrorType
)

@Serializable
data class QueryProperties (
    val namedArgs: HilariousNamedArgs
)

@Serializable
data class HilariousNamedArgs (
    val additionalProperties: Boolean,
    val properties: Properties5,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties5 (
    @SerialName("_params")
    val params: Params
)

@Serializable
data class Params (
    val additionalProperties: Boolean,
    val properties: ParamsProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class ParamsProperties (
    val options: OutputFormat,
    val prompt: Prompt
)

@Serializable
data class DocumentBlockParam (
    val additionalProperties: Boolean,
    val properties: DocumentBlockParamProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class DocumentBlockParamProperties (
    @SerialName("cache_control")
    val cacheControl: CacheControl,

    val citations: StopReason,
    val context: DocumentTitle,
    val source: BetaCodeExecutionToolResultBlockContent,
    val title: DocumentTitle,
    val type: OutputFormatType
)

@Serializable
data class HookCallback (
    @SerialName("\$comment")
    val comment: String,

    val properties: HookCallbackProperties,
    val type: AbortErrorType
)

@Serializable
data class HookCallbackProperties (
    val namedArgs: TentacledNamedArgs
)

@Serializable
data class TentacledNamedArgs (
    val additionalProperties: Boolean,
    val properties: IndigoProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class IndigoProperties (
    val input: OutputFormat,
    val options: AbortController,
    val toolUseID: ExitReason
)

@Serializable
data class AbortController (
    val additionalProperties: Boolean,
    val properties: AbortControllerProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class AbortControllerProperties (
    val signal: Interface126095811729987498112609581101874815
)

@Serializable
data class HookCallbackMatcher (
    val additionalProperties: Boolean,
    val properties: HookCallbackMatcherProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class HookCallbackMatcherProperties (
    val hooks: ContentElement,
    val matcher: ExitReason,
    val timeout: Ephemeral1_HInputTokens
)

@Serializable
data class ImageBlockParam (
    val additionalProperties: Boolean,
    val properties: ImageBlockParamProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class ImageBlockParamProperties (
    @SerialName("cache_control")
    val cacheControl: CacheControl,

    val source: BetaCodeExecutionToolResultBlockContent,
    val type: OutputFormatType
)

@Serializable
data class JSONSchemaOutputFormat (
    val additionalProperties: Boolean,
    val properties: JSONSchemaOutputFormatProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class JSONSchemaOutputFormatProperties (
    val schema: Input,
    val type: OutputFormat
)

@Serializable
data class MCPServerConfig (
    val additionalProperties: Boolean,
    val properties: MCPHTTPServerConfigProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MCPHTTPServerConfigProperties (
    val headers: Headers,
    val type: OutputFormatType,
    val url: ExitReason
)

@Serializable
data class Headers (
    val additionalProperties: ExitReason,
    val type: AbortErrorType
)

@Serializable
data class MCPSDKServerConfig (
    val additionalProperties: Boolean,
    val properties: MCPSDKServerConfigProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MCPSDKServerConfigProperties (
    val name: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class MCPSDKServerConfigWithInstance (
    val additionalProperties: Boolean,
    val properties: MCPSDKServerConfigWithInstanceProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MCPSDKServerConfigWithInstanceProperties (
    val instance: CatchallOutputZodTypeAny,
    val name: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class MCPServerStatus (
    val additionalProperties: Boolean,
    val properties: MCPServerStatusProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MCPServerStatusProperties (
    val name: ExitReason,
    val serverInfo: ServerInfo,
    val status: APIKeySource
)

@Serializable
data class ServerInfo (
    val additionalProperties: Boolean,
    val properties: ServerInfoProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class ServerInfoProperties (
    val name: ExitReason,
    val version: ExitReason
)

@Serializable
data class MCPStdioServerConfig (
    val additionalProperties: Boolean,
    val properties: MCPStdioServerConfigProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MCPStdioServerConfigProperties (
    val args: DisallowedTools,
    val command: ExitReason,
    val env: Headers,
    val type: OutputFormatType
)

@Serializable
data class MessageParam (
    val additionalProperties: Boolean,
    val properties: MessageParamProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MessageParamProperties (
    val content: CitationsClass,
    val role: APIKeySource
)

@Serializable
data class ModelClass (
    val anyOf: List<APIKeySource>,
    val description: String
)

@Serializable
data class ModelInfo (
    val additionalProperties: Boolean,
    val properties: ModelInfoProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class ModelInfoProperties (
    val description: ExitReason,
    val displayName: ExitReason,
    val value: ExitReason
)

@Serializable
data class ModelUsage (
    val additionalProperties: Boolean,
    val properties: ModelUsageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class ModelUsageProperties (
    val cacheCreationInputTokens: ExitReason,
    val cacheReadInputTokens: ExitReason,
    val contextWindow: ExitReason,
    val costUSD: ExitReason,
    val inputTokens: ExitReason,
    val outputTokens: ExitReason,
    val webSearchRequests: ExitReason
)

@Serializable
data class NonNullableUsage (
    val additionalProperties: Boolean,
    val properties: NonNullableUsageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class NonNullableUsageProperties (
    @SerialName("cache_creation")
    val cacheCreation: CacheCreation,

    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: DocumentTitle,

    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: DocumentTitle,

    @SerialName("input_tokens")
    val inputTokens: InputTokens,

    @SerialName("output_tokens")
    val outputTokens: InputTokens,

    @SerialName("server_tool_use")
    val serverToolUse: ServerToolUse,

    @SerialName("service_tier")
    val serviceTier: ObjectOutputTypeAnyZodTypeAnyUnknownKeysParam
)

@Serializable
data class CacheCreation (
    val anyOf: List<BetaCacheCreation>
)

@Serializable
data class InputTokens (
    val properties: CatchallOutputZodTypeAny? = null,
    val type: AbortErrorType
)

@Serializable
data class ServerToolUse (
    val anyOf: List<BetaServerToolUsage>
)

@Serializable
data class ObjectOutputTypeAnyZodTypeAnyUnknownKeysParam (
    val anyOf: List<InputTokens>
)

@Serializable
data class NotificationHookInput (
    val additionalProperties: Boolean,
    val properties: NotificationHookInputProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class NotificationHookInputProperties (
    val cwd: ExitReason,

    @SerialName("hook_event_name")
    val hookEventName: OutputFormatType,

    val message: ExitReason,

    @SerialName("notification_type")
    val notificationType: ExitReason,

    @SerialName("permission_mode")
    val permissionMode: ExitReason,

    @SerialName("session_id")
    val sessionID: ExitReason,

    val title: ExitReason,

    @SerialName("transcript_path")
    val transcriptPath: ExitReason
)

@Serializable
data class OptionsClass (
    val additionalProperties: Boolean,
    val properties: OptionsProperties,
    val type: AbortErrorType
)

@Serializable
data class OptionsProperties (
    val abortController: AbortController,
    val additionalDirectories: DisallowedTools,
    val agents: Agents,
    val allowDangerouslySkipPermissions: ExitReason,
    val allowedTools: DisallowedTools,
    val canUseTool: OutputFormat,

    @SerialName("continue")
    val propertiesContinue: ExitReason,

    val cwd: ExitReason,
    val disallowedTools: DisallowedTools,
    val env: Env,
    val executable: APIKeySource,
    val executableArgs: DisallowedTools,
    val extraArgs: ExtraArgs,
    val fallbackModel: ExitReason,
    val forkSession: Ephemeral1_HInputTokens,
    val hooks: Hooks,
    val includePartialMessages: ExitReason,
    val maxBudgetUsd: ExitReason,
    val maxThinkingTokens: ExitReason,
    val maxTurns: ExitReason,
    val mcpServers: Agents,
    val model: ExitReason,
    val outputFormat: OutputFormat,
    val pathToClaudeCodeExecutable: ExitReason,
    val permissionMode: OutputFormat,
    val permissionPromptToolName: ExitReason,
    val plugins: PluginsClass,
    val resume: ExitReason,
    val resumeSessionAt: Ephemeral1_HInputTokens,
    val settingSources: ContentElement,
    val stderr: Stderr,

    @SerialName("strictMcpConfig")
    val strictMCPConfig: ExitReason,

    val systemPrompt: SystemPrompt
)

@Serializable
data class Agents (
    val additionalProperties: OutputFormat,
    val type: AbortErrorType
)

@Serializable
data class Env (
    val additionalProperties: AdditionalProperties,
    val type: AbortErrorType
)

@Serializable
data class AdditionalProperties (
    val anyOf: List<AdditionalPropertiesAnyOf>
)

@Serializable
data class AdditionalPropertiesAnyOf (
    val type: ExitReasonType? = null,
    val not: CatchallOutputZodTypeAny? = null
)

@Serializable
data class ExtraArgs (
    val additionalProperties: DocumentTitle,
    val type: AbortErrorType
)

@Serializable
data class Hooks (
    val additionalProperties: Boolean,
    val properties: HooksProperties,
    val type: AbortErrorType
)

@Serializable
data class HooksProperties (
    @SerialName("Notification")
    val notification: ContentElement,

    @SerialName("PermissionRequest")
    val permissionRequest: ContentElement,

    @SerialName("PostToolUse")
    val postToolUse: ContentElement,

    @SerialName("PreCompact")
    val preCompact: ContentElement,

    @SerialName("PreToolUse")
    val preToolUse: ContentElement,

    @SerialName("SessionEnd")
    val sessionEnd: ContentElement,

    @SerialName("SessionStart")
    val sessionStart: ContentElement,

    @SerialName("Stop")
    val stop: ContentElement,

    @SerialName("SubagentStart")
    val subagentStart: ContentElement,

    @SerialName("SubagentStop")
    val subagentStop: ContentElement,

    @SerialName("UserPromptSubmit")
    val userPromptSubmit: ContentElement
)

@Serializable
data class Stderr (
    @SerialName("\$comment")
    val comment: String,

    val properties: StderrProperties,
    val type: AbortErrorType
)

@Serializable
data class StderrProperties (
    val namedArgs: StickyNamedArgs
)

@Serializable
data class StickyNamedArgs (
    val additionalProperties: Boolean,
    val properties: IndecentProperties,
    val required: List<Base64ImageSourceRequired>,
    val type: AbortErrorType
)

@Serializable
data class IndecentProperties (
    val data: ExitReason
)

@Serializable
data class SystemPrompt (
    val anyOf: List<SystemPromptAnyOf>
)

@Serializable
data class SystemPromptAnyOf (
    val type: AbortErrorType,
    val additionalProperties: Boolean? = null,
    val properties: HilariousProperties? = null,
    val required: List<String>? = null
)

@Serializable
data class HilariousProperties (
    val append: ExitReason,
    val preset: OutputFormatType,
    val type: OutputFormatType
)

@Serializable
data class PermissionRequestHookInputClass (
    val additionalProperties: Boolean,
    val properties: PermissionRequestHookInputProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class PermissionRequestHookInputProperties (
    val cwd: ExitReason,

    @SerialName("hook_event_name")
    val hookEventName: OutputFormatType,

    @SerialName("permission_mode")
    val permissionMode: ExitReason,

    @SerialName("permission_suggestions")
    val permissionSuggestions: ContentElement? = null,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("tool_input")
    val toolInput: CatchallOutputZodTypeAny,

    @SerialName("tool_name")
    val toolName: ExitReason,

    @SerialName("transcript_path")
    val transcriptPath: ExitReason,

    @SerialName("tool_response")
    val toolResponse: CatchallOutputZodTypeAny? = null,

    @SerialName("tool_use_id")
    val toolUseID: ExitReason? = null
)

@Serializable
data class PermissionResult (
    val anyOf: List<PermissionResultAnyOf>
)

@Serializable
data class PermissionResultAnyOf (
    val additionalProperties: Boolean,
    val properties: AmbitiousProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class AmbitiousProperties (
    val behavior: OutputFormatType,
    val toolUseID: Ephemeral1_HInputTokens,
    val updatedInput: UpdatedInput? = null,
    val updatedPermissions: PluginsClass? = null,
    val interrupt: Ephemeral1_HInputTokens? = null,
    val message: Ephemeral1_HInputTokens? = null
)

@Serializable
data class UpdatedInput (
    val additionalProperties: CatchallOutputZodTypeAny,
    val description: String,
    val type: AbortErrorType
)

@Serializable
data class PermissionRuleValue (
    val additionalProperties: Boolean,
    val properties: PermissionRuleValueProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class PermissionRuleValueProperties (
    val ruleContent: ExitReason,
    val toolName: ExitReason
)

@Serializable
data class PermissionUpdate (
    val anyOf: List<PermissionUpdateAnyOf>
)

@Serializable
data class PermissionUpdateAnyOf (
    val additionalProperties: Boolean,
    val properties: CunningProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class CunningProperties (
    val behavior: OutputFormat? = null,
    val destination: APIKeySource,
    val rules: ContentElement? = null,
    val type: OutputFormatType,
    val mode: OutputFormat? = null,
    val directories: DisallowedTools? = null
)

@Serializable
data class PreCompactHookInput (
    val additionalProperties: Boolean,
    val properties: PreCompactHookInputProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class PreCompactHookInputProperties (
    @SerialName("custom_instructions")
    val customInstructions: DocumentTitle,

    val cwd: ExitReason,

    @SerialName("hook_event_name")
    val hookEventName: OutputFormatType,

    @SerialName("permission_mode")
    val permissionMode: ExitReason,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("transcript_path")
    val transcriptPath: ExitReason,

    val trigger: APIKeySource
)

@Serializable
data class Query (
    val additionalProperties: Boolean,
    val properties: CatchallOutputZodTypeAny,
    val type: AbortErrorType
)

@Serializable
data class SDKAssistantMessage (
    val additionalProperties: Boolean,
    val properties: SDKAssistantMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKAssistantMessageProperties (
    val error: OutputFormat,
    val message: OutputFormat,

    @SerialName("parent_tool_use_id")
    val parentToolUseID: DocumentTitle,

    @SerialName("session_id")
    val sessionID: ExitReason,

    val type: OutputFormatType,
    val uuid: OutputFormat
)

@Serializable
data class SDKAuthStatusMessage (
    val additionalProperties: Boolean,
    val properties: SDKAuthStatusMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKAuthStatusMessageProperties (
    val error: ExitReason,
    val isAuthenticating: ExitReason,
    val output: DisallowedTools,

    @SerialName("session_id")
    val sessionID: ExitReason,

    val type: OutputFormatType,
    val uuid: OutputFormat
)

@Serializable
data class SDKMessage (
    val additionalProperties: Boolean,
    val properties: SDKCompactBoundaryMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKCompactBoundaryMessageProperties (
    @SerialName("compact_metadata")
    val compactMetadata: CompactMetadata? = null,

    @SerialName("session_id")
    val sessionID: ExitReason,

    val subtype: OutputFormatType,
    val type: OutputFormatType,
    val uuid: OutputFormat,
    val status: OutputFormat? = null
)

@Serializable
data class CompactMetadata (
    val additionalProperties: Boolean,
    val properties: CompactMetadataProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class CompactMetadataProperties (
    @SerialName("pre_tokens")
    val preTokens: ExitReason,

    val trigger: APIKeySource
)

@Serializable
data class SDKHookResponseMessage (
    val additionalProperties: Boolean,
    val properties: SDKHookResponseMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKHookResponseMessageProperties (
    @SerialName("exit_code")
    val exitCode: ExitReason,

    @SerialName("hook_event")
    val hookEvent: ExitReason,

    @SerialName("hook_name")
    val hookName: ExitReason,

    @SerialName("session_id")
    val sessionID: ExitReason,

    val stderr: ExitReason,
    val stdout: ExitReason,
    val subtype: OutputFormatType,
    val type: OutputFormatType,
    val uuid: OutputFormat
)

@Serializable
data class SDKPartialAssistantMessage (
    val additionalProperties: Boolean,
    val properties: SDKPartialAssistantMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKPartialAssistantMessageProperties (
    val event: OutputFormat,

    @SerialName("parent_tool_use_id")
    val parentToolUseID: DocumentTitle,

    @SerialName("session_id")
    val sessionID: ExitReason,

    val type: OutputFormatType,
    val uuid: OutputFormat
)

@Serializable
data class SDKPermissionDenial (
    val additionalProperties: Boolean,
    val properties: SDKPermissionDenialProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKPermissionDenialProperties (
    @SerialName("tool_input")
    val toolInput: Input,

    @SerialName("tool_name")
    val toolName: ExitReason,

    @SerialName("tool_use_id")
    val toolUseID: ExitReason
)

@Serializable
data class SDKPluginConfig (
    val additionalProperties: Boolean,
    val properties: SDKPluginConfigProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKPluginConfigProperties (
    val path: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class SDKResultMessage (
    val anyOf: List<SDKResultMessageAnyOf>
)

@Serializable
data class SDKResultMessageAnyOf (
    val additionalProperties: Boolean,
    val properties: MagentaProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MagentaProperties (
    @SerialName("duration_api_ms")
    val durationAPIMS: ExitReason,

    @SerialName("duration_ms")
    val durationMS: ExitReason,

    @SerialName("is_error")
    val isError: ExitReason,

    val modelUsage: Agents,

    @SerialName("num_turns")
    val numTurns: ExitReason,

    @SerialName("permission_denials")
    val permissionDenials: ContentElement,

    val result: ExitReason? = null,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("structured_output")
    val structuredOutput: CatchallOutputZodTypeAny? = null,

    val subtype: Subtype,

    @SerialName("total_cost_usd")
    val totalCostUsd: ExitReason,

    val type: OutputFormatType,
    val usage: OutputFormat,
    val uuid: OutputFormat,
    val errors: DisallowedTools? = null
)

@Serializable
data class Subtype (
    val const: String? = null,
    val type: ExitReasonType,
    val enum: List<String>? = null
)

@Serializable
data class SDKSessionOptions (
    val additionalProperties: Boolean,
    val description: String,
    val properties: SDKSessionOptionsProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKSessionOptionsProperties (
    val executable: TTLClass,
    val executableArgs: ExecutableArgs,
    val model: Ephemeral1_HInputTokens,
    val pathToClaudeCodeExecutable: Ephemeral1_HInputTokens
)

@Serializable
data class ExecutableArgs (
    val description: String,
    val items: ExitReason,
    val type: DisallowedToolsType
)

@Serializable
data class SDKStatus (
    val enum: List<String?>,
    val type: List<DisallowedToolsType>
)

@Serializable
data class SDKSystemMessage (
    val additionalProperties: Boolean,
    val properties: SDKSystemMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKSystemMessageProperties (
    val agents: DisallowedTools,
    val apiKeySource: OutputFormat,

    @SerialName("claude_code_version")
    val claudeCodeVersion: ExitReason,

    val cwd: ExitReason,

    @SerialName("mcp_servers")
    val mcpServers: MCPServers,

    val model: ExitReason,

    @SerialName("output_style")
    val outputStyle: ExitReason,

    val permissionMode: OutputFormat,
    val plugins: Plugins,

    @SerialName("session_id")
    val sessionID: ExitReason,

    val skills: DisallowedTools,

    @SerialName("slash_commands")
    val slashCommands: DisallowedTools,

    val subtype: OutputFormatType,
    val tools: DisallowedTools,
    val type: OutputFormatType,
    val uuid: OutputFormat
)

@Serializable
data class MCPServers (
    val items: MCPServersItems,
    val type: DisallowedToolsType
)

@Serializable
data class MCPServersItems (
    val additionalProperties: Boolean,
    val properties: FriskyProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class FriskyProperties (
    val name: ExitReason,
    val status: ExitReason
)

@Serializable
data class Plugins (
    val items: PluginsItems,
    val type: DisallowedToolsType
)

@Serializable
data class PluginsItems (
    val additionalProperties: Boolean,
    val properties: MischievousProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class MischievousProperties (
    val name: ExitReason,
    val path: ExitReason
)

@Serializable
data class SDKToolProgressMessage (
    val additionalProperties: Boolean,
    val properties: SDKToolProgressMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKToolProgressMessageProperties (
    @SerialName("elapsed_time_seconds")
    val elapsedTimeSeconds: ExitReason,

    @SerialName("parent_tool_use_id")
    val parentToolUseID: DocumentTitle,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("tool_name")
    val toolName: ExitReason,

    @SerialName("tool_use_id")
    val toolUseID: ExitReason,

    val type: OutputFormatType,
    val uuid: OutputFormat
)

@Serializable
data class SDKUserMessage (
    val additionalProperties: Boolean,
    val properties: SDKUserMessageProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SDKUserMessageProperties (
    val isSynthetic: Ephemeral1_HInputTokens,
    val message: OutputFormat,

    @SerialName("parent_tool_use_id")
    val parentToolUseID: DocumentTitle,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("tool_use_result")
    val toolUseResult: ToolUseResult,

    val type: OutputFormatType,
    val uuid: OutputFormat,
    val isReplay: IsReplay? = null
)

@Serializable
data class IsReplay (
    val const: Boolean,
    val description: String,
    val type: ExitReasonType
)

@Serializable
data class ToolUseResult (
    val description: String
)

@Serializable
data class SearchResultBlockParam (
    val additionalProperties: Boolean,
    val properties: SearchResultBlockParamProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SearchResultBlockParamProperties (
    @SerialName("cache_control")
    val cacheControl: CacheControl,

    val citations: OutputFormat,
    val content: ContentElement,
    val source: ExitReason,
    val title: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class ServerToolUseBlockParam (
    val additionalProperties: Boolean,
    val properties: ServerToolUseBlockParamProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class ServerToolUseBlockParamProperties (
    @SerialName("cache_control")
    val cacheControl: CacheControl,

    val id: ExitReason,
    val input: CatchallOutputZodTypeAny,
    val name: OutputFormatType,
    val type: OutputFormatType
)

@Serializable
data class SessionEndHookInputClass (
    val additionalProperties: Boolean,
    val properties: SessionEndHookInputProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SessionEndHookInputProperties (
    val cwd: ExitReason,

    @SerialName("hook_event_name")
    val hookEventName: OutputFormatType,

    @SerialName("permission_mode")
    val permissionMode: ExitReason,

    val reason: OutputFormat? = null,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("transcript_path")
    val transcriptPath: ExitReason,

    val source: APIKeySource? = null,

    @SerialName("stop_hook_active")
    val stopHookActive: ExitReason? = null,

    @SerialName("agent_id")
    val agentID: ExitReason? = null,

    @SerialName("agent_transcript_path")
    val agentTranscriptPath: ExitReason? = null,

    val prompt: ExitReason? = null
)

@Serializable
data class SlashCommand (
    val additionalProperties: Boolean,
    val properties: SlashCommandProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SlashCommandProperties (
    val argumentHint: ExitReason,
    val description: ExitReason,
    val name: ExitReason
)

@Serializable
data class SubagentStartHookInput (
    val additionalProperties: Boolean,
    val properties: SubagentStartHookInputProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class SubagentStartHookInputProperties (
    @SerialName("agent_id")
    val agentID: ExitReason,

    @SerialName("agent_type")
    val agentType: ExitReason,

    val cwd: ExitReason,

    @SerialName("hook_event_name")
    val hookEventName: OutputFormatType,

    @SerialName("permission_mode")
    val permissionMode: ExitReason,

    @SerialName("session_id")
    val sessionID: ExitReason,

    @SerialName("transcript_path")
    val transcriptPath: ExitReason
)

@Serializable
data class SyncHookJSONOutput (
    val additionalProperties: Boolean,
    val properties: SyncHookJSONOutputProperties,
    val type: AbortErrorType
)

@Serializable
data class SyncHookJSONOutputProperties (
    @SerialName("continue")
    val propertiesContinue: ExitReason,

    val decision: APIKeySource,
    val hookSpecificOutput: HookSpecificOutput,
    val reason: ExitReason,
    val stopReason: ExitReason,
    val suppressOutput: ExitReason,
    val systemMessage: ExitReason
)

@Serializable
data class HookSpecificOutput (
    val anyOf: List<HookSpecificOutputAnyOf>
)

@Serializable
data class HookSpecificOutputAnyOf (
    val additionalProperties: Boolean,
    val properties: BraggadociousProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class BraggadociousProperties (
    val hookEventName: OutputFormatType,
    val permissionDecision: APIKeySource? = null,
    val permissionDecisionReason: ExitReason? = null,
    val updatedInput: Input? = null,
    val additionalContext: ExitReason? = null,
    val updatedMCPToolOutput: CatchallOutputZodTypeAny? = null,
    val decision: Decision? = null
)

@Serializable
data class Decision (
    val anyOf: List<DecisionAnyOf>
)

@Serializable
data class DecisionAnyOf (
    val additionalProperties: Boolean,
    val properties: Properties1,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties1 (
    val behavior: OutputFormatType,
    val updatedInput: Input? = null,
    val updatedPermissions: ContentElement? = null,
    val interrupt: ExitReason? = null,
    val message: ExitReason? = null
)

@Serializable
data class TextBlockParam (
    val additionalProperties: Boolean,
    val properties: TextBlockParamProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class TextBlockParamProperties (
    @SerialName("cache_control")
    val cacheControl: CacheControl,

    val citations: CitationsClass,
    val text: ExitReason,
    val type: OutputFormatType
)

@Serializable
data class Tool (
    @SerialName("\$comment")
    val comment: String,

    val properties: ToolProperties,
    val type: AbortErrorType
)

@Serializable
data class ToolProperties (
    val namedArgs: AmbitiousNamedArgs
)

@Serializable
data class AmbitiousNamedArgs (
    val additionalProperties: Boolean,
    val properties: Properties6,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties6 (
    @SerialName("_description")
    val description: ExitReason,

    @SerialName("_handler")
    val handler: Handler,

    @SerialName("_inputSchema")
    val inputSchema: ToolUseResult,

    @SerialName("_name")
    val name: ExitReason
)

@Serializable
data class ToolResultBlockParam (
    val additionalProperties: Boolean,
    val properties: ToolResultBlockParamProperties,
    val required: List<BetaBashCodeExecutionToolResultBlockRequired>,
    val type: AbortErrorType
)

@Serializable
data class ToolResultBlockParamProperties (
    @SerialName("cache_control")
    val cacheControl: CacheControl,

    val content: PurpleContent,

    @SerialName("is_error")
    val isError: ExitReason,

    @SerialName("tool_use_id")
    val toolUseID: ExitReason,

    val type: OutputFormatType
)

@Serializable
data class PurpleContent (
    val anyOf: List<ContentAnyOf>
)

@Serializable
data class ContentAnyOf (
    val type: DisallowedToolsType,
    val items: BetaCodeExecutionToolResultBlockContent? = null
)

@Serializable
data class UnstableV2CreateSession (
    @SerialName("\$comment")
    val comment: String,

    val properties: UnstableV2CreateSessionProperties,
    val type: AbortErrorType
)

@Serializable
data class UnstableV2CreateSessionProperties (
    val namedArgs: CunningNamedArgs
)

@Serializable
data class CunningNamedArgs (
    val additionalProperties: Boolean,
    val properties: Properties7,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties7 (
    @SerialName("_options")
    val options: OutputFormat
)

@Serializable
data class UnstableV2Prompt (
    @SerialName("\$comment")
    val comment: String,

    val properties: UnstableV2PromptProperties,
    val type: AbortErrorType
)

@Serializable
data class UnstableV2PromptProperties (
    val namedArgs: MagentaNamedArgs
)

@Serializable
data class MagentaNamedArgs (
    val additionalProperties: Boolean,
    val properties: Properties8,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties8 (
    @SerialName("_message")
    val message: ExitReason,

    @SerialName("_options")
    val options: OutputFormat
)

@Serializable
data class UnstableV2ResumeSession (
    @SerialName("\$comment")
    val comment: String,

    val properties: UnstableV2ResumeSessionProperties,
    val type: AbortErrorType
)

@Serializable
data class UnstableV2ResumeSessionProperties (
    val namedArgs: FriskyNamedArgs
)

@Serializable
data class FriskyNamedArgs (
    val additionalProperties: Boolean,
    val properties: Properties9,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class Properties9 (
    @SerialName("_options")
    val options: OutputFormat,

    @SerialName("_sessionId")
    val sessionID: ExitReason
)

@Serializable
data class URLSource (
    val additionalProperties: Boolean,
    val properties: URLImageSourceProperties,
    val required: List<String>,
    val type: AbortErrorType
)

@Serializable
data class URLImageSourceProperties (
    val type: OutputFormatType,
    val url: ExitReason
)
