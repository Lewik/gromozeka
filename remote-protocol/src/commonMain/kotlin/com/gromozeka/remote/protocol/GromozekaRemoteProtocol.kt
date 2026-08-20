@file:kotlinx.serialization.UseSerializers(com.gromozeka.remote.protocol.ProtocolByteArraySerializer::class)

package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.QuickTextActionResult
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.RuntimeCatalogTemplates
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaObservation
import com.gromozeka.domain.model.ai.AiUserCredentialStatus
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSecretState
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.redactInlineSecrets
import com.gromozeka.domain.model.ai.secretStates
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.WorkerCatalogEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.time.Instant
import kotlin.jvm.JvmInline

@Serializable
data class GromozekaClientEnvelope(
    val id: String,
    val payload: ClientPayload,
)

@Serializable
data class GromozekaServerEnvelope(
    val id: String,
    val payload: ServerPayload,
)

@Serializable
@JsonClassDiscriminator("payloadType")
sealed interface ClientPayload

@Serializable
@JsonClassDiscriminator("payloadType")
sealed interface ServerPayload

@Serializable
@JsonClassDiscriminator("requestType")
sealed interface ClientRequest : ClientPayload

@Serializable
@JsonClassDiscriminator("responseType")
sealed interface ServerResponse : ServerPayload

@Serializable
sealed interface ClientPresentationDirective : ServerPayload

@Serializable
@JvmInline
value class ClientInstanceId(val value: String)

@Serializable
@JvmInline
value class ClientSessionId(val value: String)

@Serializable
enum class RemoteClientPlatform {
    DESKTOP,
    ANDROID,
    IOS,
    WEB_DESKTOP,
    WEB_TOUCH,
}

@Serializable
enum class ClientActivityKind {
    WINDOW_FOCUSED,
    USER_INTERACTION,
}

@Serializable
@SerialName("register_client_session")
data class RegisterClientSessionCommand(
    val clientInstanceId: ClientInstanceId,
    val clientSessionId: ClientSessionId,
    val platform: RemoteClientPlatform,
) : ClientPayload

@Serializable
@SerialName("report_client_activity")
data class ReportClientActivityCommand(
    val kind: ClientActivityKind,
) : ClientPayload

@Serializable
@SerialName("get_settings")
data object GetSettingsRequest : ClientRequest

@Serializable
@SerialName("save_settings")
data class SaveSettingsRequest(
    val settings: Settings,
) : ClientRequest

@Serializable
@SerialName("get_ai_catalog")
data object GetAiCatalogRequest : ClientRequest

@Serializable
@SerialName("get_ai_subscription_quota")
data class GetAiSubscriptionQuotaRequest(
    val modelConfigurationId: AiModelConfiguration.Id,
    val forceRefresh: Boolean = false,
) : ClientRequest

@Serializable
@SerialName("save_ai_catalog")
data class SaveAiCatalogRequest(
    val catalog: AiCatalog,
    val expectedRevision: Long,
    val secretMutations: List<AiCatalogSecretMutation> = emptyList(),
) : ClientRequest

@Serializable
@SerialName("list_mcp_servers")
data object ListMcpServersRequest : ClientRequest

@Serializable
@SerialName("create_mcp_server")
data class CreateMcpServerRequest(
    val config: McpServerConfig,
) : ClientRequest

@Serializable
@SerialName("update_mcp_server")
data class UpdateMcpServerRequest(
    val config: McpServerConfig,
    val expectedRevision: Long,
    val removeEnvironmentVariables: Set<String> = emptySet(),
    val removeHttpHeaders: Set<String> = emptySet(),
) : ClientRequest

@Serializable
@SerialName("refresh_mcp_server")
data class RefreshMcpServerRequest(
    val serverId: McpServerId,
    val expectedRevision: Long,
) : ClientRequest

@Serializable
@SerialName("test_browser_use")
data class TestBrowserUseRequest(
    val serverId: McpServerId,
) : ClientRequest

@Serializable
@SerialName("delete_mcp_server")
data class DeleteMcpServerRequest(
    val serverId: McpServerId,
    val expectedRevision: Long,
) : ClientRequest

@Serializable
@SerialName("list_personal_access_tokens")
data object ListPersonalAccessTokensRequest : ClientRequest

@Serializable
@SerialName("create_personal_access_token")
data class CreatePersonalAccessTokenRequest(
    val name: String,
    val scopes: Set<PersonalAccessToken.Scope>,
    val expiresInDays: Int?,
) : ClientRequest

@Serializable
@SerialName("revoke_personal_access_token")
data class RevokePersonalAccessTokenRequest(
    val tokenId: PersonalAccessToken.Id,
) : ClientRequest

@Serializable
@SerialName("get_ai_user_credential_status")
data class GetAiUserCredentialStatusRequest(
    val connectionId: AiConnection.Id,
) : ClientRequest

@Serializable
@SerialName("configure_ai_user_credential")
data class ConfigureAiUserCredentialRequest(
    val connectionId: AiConnection.Id,
    val secret: String,
) : ClientRequest {
    override fun toString(): String =
        "ConfigureAiUserCredentialRequest(connectionId=$connectionId, secret=[REDACTED])"
}

@Serializable
@SerialName("remove_ai_user_credential")
data class RemoveAiUserCredentialRequest(
    val connectionId: AiConnection.Id,
) : ClientRequest

@Serializable
@SerialName("list_named_secrets")
data object ListNamedSecretsRequest : ClientRequest

@Serializable
@SerialName("save_named_secret")
data class SaveNamedSecretRequest(
    val name: String,
    val description: String,
    val value: String,
) : ClientRequest {
    override fun toString(): String =
        "SaveNamedSecretRequest(name=$name, description=$description, value=[REDACTED])"
}

@Serializable
@SerialName("delete_named_secret")
data class DeleteNamedSecretRequest(
    val secretId: NamedSecret.Id,
) : ClientRequest

@Serializable
@SerialName("list_users")
data object ListUsersRequest : ClientRequest

@Serializable
@SerialName("list_security_audit_events")
data class ListSecurityAuditEventsRequest(
    val limit: Int = 100,
) : ClientRequest

@Serializable
@SerialName("create_user")
data class CreateUserRequest(
    val username: String,
    val displayName: String,
    val password: String,
    val role: User.Role,
) : ClientRequest

@Serializable
@SerialName("update_user")
data class UpdateUserRequest(
    val userId: User.Id,
    val displayName: String,
    val status: User.Status,
    val role: User.Role,
) : ClientRequest

@Serializable
@SerialName("reset_user_password")
data class ResetUserPasswordRequest(
    val userId: User.Id,
    val password: String,
) : ClientRequest

@Serializable
@SerialName("list_user_directory")
data object ListUserDirectoryRequest : ClientRequest

@Serializable
@SerialName("list_project_memberships")
data class ListProjectMembershipsRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("set_project_membership")
data class SetProjectMembershipRequest(
    val projectId: Project.Id,
    val userId: User.Id,
    val role: ProjectMembership.Role,
) : ClientRequest

@Serializable
@SerialName("remove_project_membership")
data class RemoveProjectMembershipRequest(
    val projectId: Project.Id,
    val userId: User.Id,
) : ClientRequest

@Serializable
@SerialName("get_runtime_catalog_templates")
data object GetRuntimeCatalogTemplatesRequest : ClientRequest

@Serializable
@SerialName("get_default_agent")
data object GetDefaultAgentRequest : ClientRequest

@Serializable
@SerialName("find_agent")
data class FindAgentRequest(
    val agentId: AgentDefinition.Id,
) : ClientRequest

@Serializable
@SerialName("find_agents")
data class FindAgentsRequest(
    val projectId: Project.Id? = null,
) : ClientRequest

@Serializable
@SerialName("create_agent")
data class CreateAgentRequest(
    val projectId: Project.Id?,
    val name: String,
    val prompts: List<Prompt.Id>,
    val runtimeSelection: AiRuntimeSelection,
    val runtimeOverrides: AiRuntimeOverrides = AiRuntimeOverrides(),
    val tools: List<String> = emptyList(),
    val description: String? = null,
    val skills: List<AgentSkill.Id> = emptyList(),
) : ClientRequest

@Serializable
@SerialName("duplicate_agent")
data class DuplicateAgentRequest(
    val projectId: Project.Id?,
    val sourceAgentId: AgentDefinition.Id,
    val name: String,
) : ClientRequest

@Serializable
@SerialName("update_agent")
data class UpdateAgentRequest(
    val agentId: AgentDefinition.Id,
    val name: String,
    val prompts: List<Prompt.Id>,
    val description: String? = null,
    val skills: List<AgentSkill.Id>,
    val runtimeSelection: AiRuntimeSelection,
    val runtimeOverrides: AiRuntimeOverrides,
    val tools: List<String>,
) : ClientRequest

@Serializable
@SerialName("delete_agent")
data class DeleteAgentRequest(
    val agentId: AgentDefinition.Id,
) : ClientRequest

@Serializable
@SerialName("count_agents")
data object CountAgentsRequest : ClientRequest

@Serializable
@SerialName("find_agent_skills")
data class FindAgentSkillsRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("find_agent_skill")
data class FindAgentSkillRequest(
    val skillId: AgentSkill.Id,
) : ClientRequest

@Serializable
@SerialName("import_agent_skill")
data class ImportAgentSkillRequest(
    val projectId: Project.Id,
    val source: AgentSkillPackageSource,
) : ClientRequest

@Serializable
@SerialName("export_agent_skill")
data class ExportAgentSkillRequest(
    val skillId: AgentSkill.Id,
) : ClientRequest

@Serializable
@SerialName("reanalyze_agent_skill_materialization")
data class ReanalyzeAgentSkillMaterializationRequest(
    val skillId: AgentSkill.Id,
) : ClientRequest

@Serializable
@SerialName("set_agent_skill_materialization_plan")
data class SetAgentSkillMaterializationPlanRequest(
    val skillId: AgentSkill.Id,
    val policy: AgentSkill.MaterializationPlan.Policy,
    val reason: String,
) : ClientRequest

@Serializable
@SerialName("delete_agent_skill")
data class DeleteAgentSkillRequest(
    val skillId: AgentSkill.Id,
) : ClientRequest

@Serializable
@SerialName("find_prompt")
data class FindPromptRequest(
    val promptId: Prompt.Id,
) : ClientRequest

@Serializable
@SerialName("find_prompts")
data class FindPromptsRequest(
    val projectId: Project.Id? = null,
) : ClientRequest

@Serializable
@SerialName("create_prompt")
data class CreatePromptRequest(
    val projectId: Project.Id?,
    val name: String,
    val content: String,
) : ClientRequest

@Serializable
@SerialName("update_prompt")
data class UpdatePromptRequest(
    val promptId: Prompt.Id,
    val name: String,
    val content: String,
) : ClientRequest

@Serializable
@SerialName("delete_prompt")
data class DeletePromptRequest(
    val promptId: Prompt.Id,
) : ClientRequest

@Serializable
@SerialName("create_project")
data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
) : ClientRequest

@Serializable
@SerialName("update_project")
data class UpdateProjectRequest(
    val projectId: Project.Id,
    val name: String,
    val description: String? = null,
) : ClientRequest

@Serializable
@SerialName("delete_project")
data class DeleteProjectRequest(
    val projectId: Project.Id,
) : ClientRequest


@Serializable
@SerialName("find_project_by_id")
data class FindProjectByIdRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("update_project_last_used")
data class UpdateProjectLastUsedRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("create_conversation")
data class CreateConversationRequest(
    val projectId: Project.Id,
    val agentDefinitionId: AgentDefinition.Id,
    val displayName: String = "",
) : ClientRequest

@Serializable
@SerialName("find_conversation")
data class FindConversationRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("get_project")
data class GetProjectRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("find_recent_projects")
data class FindRecentProjectsRequest(
    val limit: Int = 100,
) : ClientRequest

@Serializable
@SerialName("find_projects")
data object FindProjectsRequest : ClientRequest

@Serializable
@SerialName("find_conversations_by_project")
data class FindConversationsByProjectRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("get_conversation_tab_layout")
data object GetConversationTabLayoutRequest : ClientRequest

@Serializable
@SerialName("open_conversation_tab")
data class OpenConversationTabRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("close_conversation_tab")
data class CloseConversationTabRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("find_workspace")
data class FindWorkspaceRequest(
    val workspaceId: Workspace.Id,
) : ClientRequest

@Serializable
@SerialName("find_workspaces_by_project")
data class FindWorkspacesByProjectRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("find_workspace_mounts")
data class FindWorkspaceMountsRequest(
    val workspaceId: Workspace.Id,
) : ClientRequest

@Serializable
@SerialName("list_workers")
data object ListWorkersRequest : ClientRequest

@Serializable
@SerialName("create_filesystem_workspace")
data class CreateFilesystemWorkspaceRequest(
    val projectId: Project.Id,
    val name: String,
) : ClientRequest

@Serializable
@SerialName("update_workspace")
data class UpdateWorkspaceRequest(
    val workspaceId: Workspace.Id,
    val name: String,
) : ClientRequest

@Serializable
@SerialName("delete_workspace")
data class DeleteWorkspaceRequest(
    val workspaceId: Workspace.Id,
) : ClientRequest

@Serializable
@SerialName("delete_workspace_mount")
data class DeleteWorkspaceMountRequest(
    val mountId: WorkspaceMount.Id,
) : ClientRequest

@Serializable
@SerialName("delete_conversation")
data class DeleteConversationRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("update_conversation_display_name")
data class UpdateConversationDisplayNameRequest(
    val conversationId: Conversation.Id,
    val displayName: String,
) : ClientRequest

@Serializable
@SerialName("update_conversation_agent")
data class UpdateConversationAgentRequest(
    val conversationId: Conversation.Id,
    val agentDefinitionId: AgentDefinition.Id,
) : ClientRequest

@Serializable
@SerialName("fork_conversation")
data class ForkConversationRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("add_message")
data class AddMessageRequest(
    val conversationId: Conversation.Id,
    val message: Conversation.Message,
) : ClientRequest

@Serializable
@SerialName("load_current_messages")
data class LoadCurrentMessagesRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("regenerate_suggested_replies")
data class RegenerateSuggestedRepliesRequest(
    val conversationId: Conversation.Id,
    val sourceMessageId: Conversation.Message.Id,
) : ClientRequest

@Serializable
@SerialName("get_token_stats")
data class GetTokenStatsRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("get_ai_usage_report")
data class GetAiUsageReportRequest(
    val query: TokenUsageStatistics.ReportQuery,
) : ClientRequest

@Serializable
@SerialName("edit_message")
data class EditMessageRequest(
    val conversationId: Conversation.Id,
    val messageId: Conversation.Message.Id,
    val newContent: List<Conversation.Message.ContentItem>,
) : ClientRequest

@Serializable
@SerialName("delete_messages")
data class DeleteMessagesRequest(
    val conversationId: Conversation.Id,
    val messageIds: List<Conversation.Message.Id>,
) : ClientRequest

@Serializable
@SerialName("compact_messages")
data class CompactMessagesRequest(
    val conversationId: Conversation.Id,
    val messageIds: List<Conversation.Message.Id>,
    val strategy: SquashType,
) : ClientRequest

@Serializable
@SerialName("list_quick_text_actions")
data object ListQuickTextActionsRequest : ClientRequest

@Serializable
@SerialName("run_quick_text_action")
data class RunQuickTextActionRequest(
    val actionId: QuickTextAction.Id,
    val text: String,
) : ClientRequest {
    init {
        require(actionId.value.isNotBlank()) { "Quick text action id must not be blank" }
        require(text.isNotBlank()) { "Quick text action input must not be blank" }
    }
}

@Serializable
@SerialName("search_conversations")
data class SearchConversationsRequest(
    val search: ConversationSearchRequest,
) : ClientRequest

@Serializable
@SerialName("memory_action")
data class MemoryActionRequest(
    val conversationId: Conversation.Id,
    val action: MemoryAction,
) : ClientRequest

@Serializable
@SerialName("get_memory_action_items")
data class GetMemoryActionItemsRequest(
    val conversationId: Conversation.Id,
    val includeClosed: Boolean = false,
) : ClientRequest

@Serializable
@SerialName("transcribe_audio")
data class TranscribeAudioRequest(
    val recording: RemoteAudioRecording,
) : ClientRequest

@Serializable
@SerialName("get_speech_capture_availability")
data object GetSpeechCaptureAvailabilityRequest : ClientRequest

@Serializable
@SerialName("start_speech_capture")
data class StartSpeechCaptureRequest(
    val sessionId: String,
) : ClientRequest {
    init {
        require(sessionId.isNotBlank()) { "Speech capture session id must not be blank" }
        require(sessionId.length <= MAX_SPEECH_CAPTURE_SESSION_ID_LENGTH) {
            "Speech capture session id is too long"
        }
    }
}

@Serializable
@SerialName("stop_speech_capture")
data class StopSpeechCaptureRequest(
    val sessionId: String,
) : ClientRequest {
    init {
        require(sessionId.isNotBlank()) { "Speech capture session id must not be blank" }
        require(sessionId.length <= MAX_SPEECH_CAPTURE_SESSION_ID_LENGTH) {
            "Speech capture session id is too long"
        }
    }
}

@Serializable
@SerialName("cancel_speech_capture")
data class CancelSpeechCaptureRequest(
    val sessionId: String,
) : ClientRequest {
    init {
        require(sessionId.isNotBlank()) { "Speech capture session id must not be blank" }
        require(sessionId.length <= MAX_SPEECH_CAPTURE_SESSION_ID_LENGTH) {
            "Speech capture session id is too long"
        }
    }
}

@Serializable
@SerialName("synthesize_speech")
data class SynthesizeSpeechRequest(
    val text: String,
    val tone: String = "neutral colleague",
) : ClientRequest

@Serializable
@SerialName("start_live_interpreter")
data class StartLiveInterpreterRequest(
    val targetLanguage: String = "ru",
    val sourceLanguageCode: String = "auto",
    val sourceLanguageHint: String = "Hebrew, Russian, and English workplace conversation",
    val translationRuntimeSelection: AiRuntimeSelection? = null,
) : ClientRequest

@Serializable
@SerialName("start_live_voice_provider_vad")
data class StartLiveVoiceProviderVadRequest(
    val languageCode: String? = null,
    val prompt: String? = null,
) : ClientRequest

@Serializable
@SerialName("get_live_voice_provider_vad_availability")
data object GetLiveVoiceProviderVadAvailabilityRequest : ClientRequest

@Serializable
data class RemoteAudioRecording(
    val sessionId: String,
    val format: SpeechAudioFormat,
    val chunks: List<RemoteAudioChunk>,
)

@Serializable
data class RemoteAudioChunk(
    val sequenceNumber: Int,
    val data: ByteArray,
)

@Serializable
data class RemoteLiveAudioChunk(
    val sequenceNumber: Int,
    val data: ByteArray,
    val format: SpeechAudioFormat,
)

@Serializable
data class RemoteLiveTranscriptChunk(
    val sequenceNumber: Int,
    val text: String,
)

@Serializable
enum class RemotePcmByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN,
}

@Serializable
data class RemotePcmAudioChunk(
    val sequenceNumber: Int,
    val data: ByteArray,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val byteOrder: RemotePcmByteOrder = RemotePcmByteOrder.BIG_ENDIAN,
) {
    init {
        require(sequenceNumber >= 0) { "PCM chunk sequence number must not be negative" }
        require(sampleRate > 0) { "PCM chunk sample rate must be positive" }
        require(channels > 0) { "PCM chunk channel count must be positive" }
        require(bitsPerSample == 16) { "Only PCM16 chunks are supported" }
        require(data.size % (channels * bitsPerSample / 8) == 0) {
            "PCM chunk byte size must align with frame size"
        }
    }
}

@Serializable
data class RemoteLiveInterpreterDraft(
    val id: String,
    val sequenceNumber: Int,
    val text: String,
)

@Serializable
@JsonClassDiscriminator("stateQueryType")
sealed interface RemoteStateSyncQuery

@Serializable
@SerialName("conversation_runtime")
data class ConversationRuntimeStateQuery(
    val conversationId: Conversation.Id,
) : RemoteStateSyncQuery

@Serializable
@SerialName("active_generation")
data class ActiveGenerationStateQuery(
    val conversationId: Conversation.Id,
) : RemoteStateSyncQuery

@Serializable
@SerialName("conversation_tab_layout")
data object ConversationTabLayoutStateQuery : RemoteStateSyncQuery

@Serializable
enum class RemoteDeclarativeStateResource {
    PROJECTS,
    PROJECT_CONVERSATIONS,
    PROJECT_WORKSPACES,
    WORKSPACE_MOUNTS,
    AGENTS,
    PROMPTS,
    PROJECT_AGENT_SKILLS,
    AI_CATALOG,
    MCP_SERVERS,
    WORKERS,
    USERS,
    USER_DIRECTORY,
    PROJECT_MEMBERSHIPS,
    SETTINGS,
    QUICK_TEXT_ACTIONS,
}

@Serializable
@SerialName("declarative_revision")
data class DeclarativeStateRevisionQuery(
    val resource: RemoteDeclarativeStateResource,
    val scopeId: String? = null,
) : RemoteStateSyncQuery

@Serializable
data class RemoteStateSyncCursor(
    val sourceEpoch: String,
    val streamEpoch: Long,
    val generation: Long,
)

@Serializable
@JsonClassDiscriminator("statePayloadType")
sealed interface RemoteStateSyncPayload

@Serializable
@SerialName("conversation_runtime")
data class ConversationRuntimeStatePayload(
    val snapshot: ConversationRuntimeSnapshot,
) : RemoteStateSyncPayload

@Serializable
@SerialName("active_generation")
data class ActiveGenerationStatePayload(
    val snapshot: ActiveGenerationSnapshot?,
) : RemoteStateSyncPayload

@Serializable
@SerialName("conversation_tab_layout")
data class ConversationTabLayoutStatePayload(
    val layout: ConversationTabLayout,
) : RemoteStateSyncPayload

@Serializable
@SerialName("declarative_revision")
data object DeclarativeStateRevisionPayload : RemoteStateSyncPayload

@Serializable
@SerialName("observe_state")
data class ObserveStateSyncCommand(
    val subscriptionId: String,
    val query: RemoteStateSyncQuery,
) : ClientPayload

@Serializable
@SerialName("stop_observe_state")
data class StopObserveStateSyncCommand(
    val subscriptionId: String,
) : ClientPayload

@Serializable
@SerialName("pull_state")
data class PullStateSyncRequest(
    val query: RemoteStateSyncQuery,
    val invalidationCursor: RemoteStateSyncCursor,
) : ClientRequest

@Serializable
@SerialName("observe_conversation")
data class ObserveConversationCommand(
    val subscriptionId: String,
    val conversationId: Conversation.Id,
    val afterEventSequence: Long? = null,
) : ClientPayload

@Serializable
@SerialName("stop_observe_conversation")
data class StopObserveConversationCommand(
    val subscriptionId: String,
) : ClientPayload

@Serializable
@SerialName("submit_message")
data class SubmitMessageRequest(
    val conversationId: Conversation.Id,
    val userMessage: Conversation.Message,
    val agentDefinitionId: AgentDefinition.Id,
) : ClientRequest

@Serializable
@SerialName("enqueue_message")
data class EnqueueMessageRequest(
    val conversationId: Conversation.Id,
    val userMessage: Conversation.Message,
    val agentDefinitionId: AgentDefinition.Id,
    val placement: QueuedMessagePlacement,
) : ClientRequest

@Serializable
@SerialName("cancel_queued_message")
data class CancelQueuedMessageRequest(
    val conversationId: Conversation.Id,
    val messageId: Conversation.Message.Id,
) : ClientRequest

@Serializable
@SerialName("control_conversation_runtime")
data class ControlConversationRuntimeRequest(
    val conversationId: Conversation.Id,
    val action: ConversationRuntimeControlAction,
) : ClientRequest

@Serializable
@SerialName("cancel_command_task")
data class CancelCommandTaskRequest(
    val conversationId: Conversation.Id,
    val taskId: CommandTask.Id,
) : ClientRequest

@Serializable
@SerialName("cancel_command_monitor")
data class CancelCommandMonitorRequest(
    val conversationId: Conversation.Id,
    val monitorId: CommandMonitor.Id,
) : ClientRequest

@Serializable
@SerialName("synthesize_speech_stream")
data class SynthesizeSpeechStreamCommand(
    val streamId: String,
    val text: String,
    val tone: String = "neutral colleague",
) : ClientPayload

@Serializable
@SerialName("live_interpreter_audio_chunk")
data class LiveInterpreterAudioChunkCommand(
    val sessionId: String,
    val chunk: RemoteLiveAudioChunk,
) : ClientPayload

@Serializable
@SerialName("live_interpreter_transcript_chunk")
data class LiveInterpreterTranscriptChunkCommand(
    val sessionId: String,
    val chunk: RemoteLiveTranscriptChunk,
) : ClientPayload

@Serializable
@SerialName("stop_live_interpreter")
data class StopLiveInterpreterCommand(
    val sessionId: String,
) : ClientPayload

@Serializable
@SerialName("live_voice_provider_vad_audio_chunk")
data class LiveVoiceProviderVadAudioChunkCommand(
    val sessionId: String,
    val chunk: RemotePcmAudioChunk,
) : ClientPayload

@Serializable
@SerialName("stop_live_voice_provider_vad")
data class StopLiveVoiceProviderVadCommand(
    val sessionId: String,
) : ClientPayload

@Serializable
@SerialName("settings")
data class SettingsResponse(
    val settings: Settings,
) : ServerResponse

@Serializable
@SerialName("ai_catalog")
data class AiCatalogResponse(
    val snapshot: RemoteAiCatalogSnapshot,
) : ServerResponse

@Serializable
@SerialName("ai_subscription_quota")
data class AiSubscriptionQuotaResponse(
    val observation: AiSubscriptionQuotaObservation,
) : ServerResponse

@Serializable
data class RemoteMcpServerView(
    val server: McpServer,
    val configuredEnvironmentVariables: Set<String> = emptySet(),
    val configuredHttpHeaders: Set<String> = emptySet(),
) {
    init {
        when (val transport = server.config.transport) {
            is McpServerTransport.Stdio -> require(transport.environment.isEmpty()) {
                "Remote MCP server view must not contain environment values"
            }

            is McpServerTransport.BundledStdio -> require(transport.environment.isEmpty()) {
                "Remote bundled MCP server view must not contain environment values"
            }

            is McpServerTransport.StreamableHttp -> require(transport.headers.isEmpty()) {
                "Remote MCP server view must not contain HTTP header values"
            }
        }
        require(configuredEnvironmentVariables.none(String::isBlank)) {
            "Configured MCP environment variable names must not be blank"
        }
        require(configuredHttpHeaders.none(String::isBlank)) {
            "Configured MCP HTTP header names must not be blank"
        }
    }
}

@Serializable
@SerialName("mcp_servers")
data class McpServersResponse(
    val servers: List<RemoteMcpServerView>,
) : ServerResponse

@Serializable
@SerialName("mcp_server")
data class McpServerResponse(
    val server: RemoteMcpServerView,
) : ServerResponse

@Serializable
@SerialName("browser_use_probe")
data class BrowserUseProbeResponse(
    val screenshot: ByteArray,
    val mediaType: String,
    val fileName: String? = null,
) : ServerResponse

@Serializable
data class RemoteAiCatalogSnapshot(
    val catalog: AiCatalog,
    val revision: Long,
    val runtimeEnabledConnectionIds: Set<AiConnection.Id>,
    val secretStates: List<AiCatalogSecretState>,
) {
    init {
        require(
            catalog.secretStates().none {
                it.source == AiCatalogSecretState.Source.INLINE
            }
        ) {
            "Remote AI catalog snapshot must not contain inline secret values"
        }
    }

    fun toDomainSnapshot(): AiCatalogSnapshot =
        AiCatalogSnapshot(
            catalog = catalog,
            revision = revision,
            runtimeEnabledConnectionIds = runtimeEnabledConnectionIds,
            secretStates = secretStates,
        )

    companion object {
        fun from(snapshot: AiCatalogSnapshot): RemoteAiCatalogSnapshot {
            val redacted = snapshot.redactInlineSecrets()
            return RemoteAiCatalogSnapshot(
                catalog = redacted.catalog,
                revision = redacted.revision,
                runtimeEnabledConnectionIds = redacted.runtimeEnabledConnectionIds,
                secretStates = redacted.secretStates,
            )
        }
    }
}

@Serializable
data class PersonalAccessTokenView(
    val id: PersonalAccessToken.Id,
    val name: String,
    val tokenPrefix: String,
    val scopes: Set<PersonalAccessToken.Scope>,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
)

@Serializable
@SerialName("personal_access_tokens")
data class PersonalAccessTokensResponse(
    val tokens: List<PersonalAccessTokenView>,
) : ServerResponse

@Serializable
@SerialName("issued_personal_access_token")
data class IssuedPersonalAccessTokenResponse(
    val token: PersonalAccessTokenView,
    val rawToken: String,
) : ServerResponse

@Serializable
@SerialName("personal_access_token_revoked")
data class PersonalAccessTokenRevokedResponse(
    val revoked: Boolean,
) : ServerResponse

@Serializable
@SerialName("ai_user_credential_status")
data class AiUserCredentialStatusResponse(
    val status: AiUserCredentialStatus,
) : ServerResponse

@Serializable
@SerialName("named_secrets")
data class NamedSecretsResponse(
    val secrets: List<NamedSecret>,
) : ServerResponse

@Serializable
@SerialName("named_secret")
data class NamedSecretResponse(
    val secret: NamedSecret,
) : ServerResponse

@Serializable
@SerialName("named_secret_deleted")
data class NamedSecretDeletedResponse(
    val deleted: Boolean,
) : ServerResponse

@Serializable
@SerialName("users")
data class UsersResponse(
    val users: List<User>,
) : ServerResponse

@Serializable
@SerialName("security_audit_events")
data class SecurityAuditEventsResponse(
    val events: List<SecurityAuditEvent>,
) : ServerResponse

@Serializable
@SerialName("user")
data class UserResponse(
    val user: User,
) : ServerResponse

@Serializable
@SerialName("user_password_reset")
data object UserPasswordResetResponse : ServerResponse

@Serializable
data class UserDirectoryEntry(
    val id: User.Id,
    val username: String,
    val displayName: String,
)

@Serializable
@SerialName("user_directory")
data class UserDirectoryResponse(
    val users: List<UserDirectoryEntry>,
) : ServerResponse

@Serializable
@SerialName("project_memberships")
data class ProjectMembershipsResponse(
    val memberships: List<ProjectMembership>,
) : ServerResponse

@Serializable
@SerialName("project_membership")
data class ProjectMembershipResponse(
    val membership: ProjectMembership,
) : ServerResponse

@Serializable
@SerialName("project_membership_removed")
data class ProjectMembershipRemovedResponse(
    val removed: Boolean,
) : ServerResponse

@Serializable
@SerialName("runtime_catalog_templates")
data class RuntimeCatalogTemplatesResponse(
    val templates: RuntimeCatalogTemplates,
) : ServerResponse

@Serializable
@SerialName("saved")
data object SavedResponse : ServerResponse

@Serializable
@SerialName("default_agent")
data class DefaultAgentResponse(
    val agent: AgentDefinition,
) : ServerResponse

@Serializable
@SerialName("agent")
data class AgentResponse(
    val agent: AgentDefinition?,
) : ServerResponse

@Serializable
@SerialName("agents")
data class AgentsResponse(
    val agents: List<AgentDefinition>,
) : ServerResponse

@Serializable
@SerialName("agent_skill")
data class AgentSkillResponse(
    val skill: AgentSkill?,
) : ServerResponse

@Serializable
@SerialName("agent_skills")
data class AgentSkillsResponse(
    val skills: List<AgentSkill>,
) : ServerResponse

@Serializable
@SerialName("agent_skill_package")
data class AgentSkillPackageResponse(
    val skillPackage: AgentSkillPackage?,
) : ServerResponse

@Serializable
@SerialName("count")
data class CountResponse(
    val count: Int,
) : ServerResponse

@Serializable
@SerialName("prompt")
data class PromptResponse(
    val prompt: Prompt?,
) : ServerResponse

@Serializable
@SerialName("prompts")
data class PromptsResponse(
    val prompts: List<Prompt>,
) : ServerResponse

@Serializable
@SerialName("operation_result")
data class OperationResultResponse(
    val success: Boolean,
    val count: Int? = null,
    val error: String? = null,
) : ServerResponse

@Serializable
@SerialName("conversation")
data class ConversationResponse(
    val conversation: Conversation?,
) : ServerResponse

@Serializable
@SerialName("project")
data class ProjectResponse(
    val project: Project,
) : ServerResponse

@Serializable
@SerialName("nullable_project")
data class NullableProjectResponse(
    val project: Project?,
) : ServerResponse

@Serializable
@SerialName("projects")
data class ProjectsResponse(
    val projects: List<Project>,
) : ServerResponse

@Serializable
@SerialName("workspace")
data class WorkspaceResponse(
    val workspace: Workspace?,
) : ServerResponse

@Serializable
@SerialName("workspaces")
data class WorkspacesResponse(
    val workspaces: List<Workspace>,
) : ServerResponse

@Serializable
@SerialName("workspace_mounts")
data class WorkspaceMountsResponse(
    val mounts: List<WorkspaceMount>,
) : ServerResponse

@Serializable
@SerialName("workers")
data class WorkersResponse(
    val workers: List<WorkerCatalogEntry>,
) : ServerResponse


@Serializable
@SerialName("conversations")
data class ConversationsResponse(
    val conversations: List<Conversation>,
) : ServerResponse

@Serializable
@SerialName("conversation_tab_layout")
data class ConversationTabLayoutResponse(
    val layout: ConversationTabLayout,
) : ServerResponse

@Serializable
@SerialName("conversation_search_page")
data class ConversationSearchPageResponse(
    val page: ConversationSearchPage,
) : ServerResponse

@Serializable
@SerialName("messages")
data class MessagesResponse(
    val messages: List<Conversation.Message>,
) : ServerResponse

@Serializable
@SerialName("suggested_replies")
data class SuggestedRepliesResponse(
    val sourceMessageId: Conversation.Message.Id,
    val replies: List<String>,
) : ServerResponse

@Serializable
@SerialName("token_stats")
data class TokenStatsResponse(
    val tokenStats: TokenUsageStatistics.ThreadTotals?,
) : ServerResponse

@Serializable
@SerialName("ai_usage_report")
data class AiUsageReportResponse(
    val report: TokenUsageStatistics.Report,
) : ServerResponse

@Serializable
@SerialName("text")
data class TextResponse(
    val text: String,
) : ServerResponse

@Serializable
@SerialName("quick_text_actions")
data class QuickTextActionsResponse(
    val actions: List<QuickTextAction>,
) : ServerResponse

@Serializable
@SerialName("quick_text_action_result")
data class QuickTextActionResultResponse(
    val result: QuickTextActionResult,
) : ServerResponse

@Serializable
@SerialName("memory_action_accepted")
data class MemoryActionAcceptedResponse(
    val status: String = "accepted",
    val message: String = "Memory action accepted.",
) : ServerResponse

@Serializable
@SerialName("memory_action_items")
data class MemoryActionItemsResponse(
    val revision: String,
    val counts: MemoryActionItemCounts,
    val actionItems: List<MemoryActionItem>,
) : ServerResponse

@Serializable
data class MemoryActionItemCounts(
    val open: Int = 0,
    val inProgress: Int = 0,
    val blocked: Int = 0,
    val done: Int = 0,
    val cancelled: Int = 0,
)

@Serializable
@SerialName("audio_transcription")
data class AudioTranscriptionResponse(
    val text: String,
) : ServerResponse

@Serializable
@SerialName("speech_capture_started")
data class SpeechCaptureStartedResponse(
    val sessionId: String,
) : ServerResponse

@Serializable
@SerialName("speech_capture_availability")
data class SpeechCaptureAvailabilityResponse(
    val available: Boolean,
    val unavailableReason: String? = null,
) : ServerResponse {
    init {
        require(available == (unavailableReason == null)) {
            "Speech capture availability and unavailable reason must agree"
        }
    }
}

@Serializable
@SerialName("speech_synthesis")
data class SpeechSynthesisResponse(
    val audioData: ByteArray,
    val mediaType: String,
    val fileExtension: String,
) : ServerResponse

@Serializable
@SerialName("live_interpreter_started")
data class LiveInterpreterStartedResponse(
    val sessionId: String,
) : ServerResponse

@Serializable
@SerialName("live_voice_provider_vad_started")
data class LiveVoiceProviderVadStartedResponse(
    val sessionId: String,
) : ServerResponse

@Serializable
@SerialName("live_voice_provider_vad_availability")
data class LiveVoiceProviderVadAvailabilityResponse(
    val unavailableReason: String?,
) : ServerResponse

@Serializable
@SerialName("error")
data class ErrorResponse(
    val message: String,
    val type: String? = null,
) : ServerResponse

@Serializable
@SerialName("state_snapshot")
data class StateSyncSnapshotResponse(
    val query: RemoteStateSyncQuery,
    val cursor: RemoteStateSyncCursor,
    val state: RemoteStateSyncPayload,
) : ServerResponse

@Serializable
@SerialName("state_invalidated")
data class StateSyncInvalidatedEvent(
    val subscriptionId: String,
    val query: RemoteStateSyncQuery,
    val cursor: RemoteStateSyncCursor,
) : ServerPayload

@Serializable
@SerialName("state_observation_failed")
data class StateSyncObservationFailedEvent(
    val subscriptionId: String,
    val query: RemoteStateSyncQuery,
    val message: String,
    val type: String? = null,
) : ServerPayload

@Serializable
@SerialName("message_upserted")
data class MessageUpsertedEvent(
    val subscriptionId: String,
    val conversationId: Conversation.Id,
    val taskId: ConversationRuntimeTask.Id?,
    val message: Conversation.Message,
    val cursorSequence: Long? = null,
) : ServerPayload

@Serializable
enum class AssistantMessageSignal {
    ATTENTION,
    ACTIVITY,
}

@Serializable
data class AssistantMessageSpeech(
    val text: String,
    val tone: String,
)

@Serializable
@SerialName("present_assistant_message")
data class PresentAssistantMessageDirective(
    val messageId: Conversation.Message.Id,
    val conversationId: Conversation.Id,
    val signal: AssistantMessageSignal,
    val speech: AssistantMessageSpeech?,
) : ClientPresentationDirective

@Serializable
enum class ClientFeedbackEffect {
    ATTENTION,
    ACTIVITY,
    ERROR,
}

@Serializable
@SerialName("play_client_feedback")
data class PlayClientFeedbackDirective(
    val conversationId: Conversation.Id,
    val effect: ClientFeedbackEffect,
) : ClientPresentationDirective

@Serializable
@SerialName("stop_tts")
data object StopTtsDirective : ClientPresentationDirective

@Serializable
@SerialName("conversation_execution_completed")
data class ConversationExecutionCompletedEvent(
    val subscriptionId: String,
    val conversationId: Conversation.Id,
    val cursorSequence: Long? = null,
) : ServerPayload

@Serializable
@SerialName("conversation_replay_completed")
data class ConversationReplayCompletedEvent(
    val subscriptionId: String,
    val conversationId: Conversation.Id,
    val cursorSequence: Long? = null,
) : ServerPayload

@Serializable
@SerialName("conversation_execution_failed")
data class ConversationExecutionFailedEvent(
    val subscriptionId: String,
    val conversationId: Conversation.Id,
    val message: String,
    val type: String? = null,
    val cursorSequence: Long? = null,
) : ServerPayload

@Serializable
@SerialName("speech_synthesis_started")
data class SpeechSynthesisStartedEvent(
    val streamId: String,
    val mediaType: String,
    val fileExtension: String,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
) : ServerPayload

@Serializable
@SerialName("speech_synthesis_chunk")
data class SpeechSynthesisChunkEvent(
    val streamId: String,
    val sequenceNumber: Int,
    val data: ByteArray,
) : ServerPayload

@Serializable
@SerialName("speech_synthesis_completed")
data class SpeechSynthesisCompletedEvent(
    val streamId: String,
) : ServerPayload

@Serializable
@SerialName("speech_synthesis_failed")
data class SpeechSynthesisFailedEvent(
    val streamId: String,
    val message: String,
) : ServerPayload

@Serializable
@SerialName("live_interpreter_status")
data class LiveInterpreterStatusEvent(
    val sessionId: String,
    val message: String,
) : ServerPayload

@Serializable
@SerialName("live_interpreter_transcript")
data class LiveInterpreterTranscriptEvent(
    val sessionId: String,
    val segmentId: String,
    val sequenceNumber: Int,
    val text: String,
    val isFinal: Boolean = true,
) : ServerPayload

@Serializable
@SerialName("live_interpreter_drafts")
data class LiveInterpreterDraftsEvent(
    val sessionId: String,
    val drafts: List<RemoteLiveInterpreterDraft>,
) : ServerPayload

@Serializable
@SerialName("live_interpreter_translation")
data class LiveInterpreterTranslationEvent(
    val sessionId: String,
    val segmentId: String,
    val sequenceNumber: Int,
    val text: String,
    val targetLanguage: String,
    val isFinal: Boolean = true,
) : ServerPayload

@Serializable
@SerialName("live_interpreter_stopped")
data class LiveInterpreterStoppedEvent(
    val sessionId: String,
) : ServerPayload

@Serializable
@SerialName("live_interpreter_failed")
data class LiveInterpreterFailedEvent(
    val sessionId: String,
    val message: String,
) : ServerPayload

@Serializable
@SerialName("live_voice_provider_vad_status")
data class LiveVoiceProviderVadStatusEvent(
    val sessionId: String,
    val message: String,
) : ServerPayload

@Serializable
@SerialName("live_voice_provider_vad_speech_started")
data class LiveVoiceProviderVadSpeechStartedEvent(
    val sessionId: String,
) : ServerPayload

@Serializable
@SerialName("live_voice_provider_vad_speech_stopped")
data class LiveVoiceProviderVadSpeechStoppedEvent(
    val sessionId: String,
) : ServerPayload

@Serializable
@SerialName("live_voice_provider_vad_transcript_delta")
data class LiveVoiceProviderVadTranscriptDeltaEvent(
    val sessionId: String,
    val itemId: String,
    val delta: String,
) : ServerPayload

@Serializable
@SerialName("live_voice_provider_vad_transcript_completed")
data class LiveVoiceProviderVadTranscriptCompletedEvent(
    val sessionId: String,
    val itemId: String,
    val text: String,
) : ServerPayload

@Serializable
@SerialName("live_voice_provider_vad_stopped")
data class LiveVoiceProviderVadStoppedEvent(
    val sessionId: String,
) : ServerPayload

@Serializable
@SerialName("live_voice_provider_vad_failed")
data class LiveVoiceProviderVadFailedEvent(
    val sessionId: String,
    val message: String,
) : ServerPayload

private const val MAX_SPEECH_CAPTURE_SESSION_ID_LENGTH = 128
