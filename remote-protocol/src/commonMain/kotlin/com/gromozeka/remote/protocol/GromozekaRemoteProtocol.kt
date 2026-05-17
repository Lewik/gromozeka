@file:kotlinx.serialization.UseSerializers(com.gromozeka.remote.protocol.ProtocolByteArraySerializer::class)

package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.memory.MemoryTask
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

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
@SerialName("get_settings")
data object GetSettingsRequest : ClientRequest

@Serializable
@SerialName("save_settings")
data class SaveSettingsRequest(
    val settings: Settings,
) : ClientRequest

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
    val projectPath: String? = null,
) : ClientRequest

@Serializable
@SerialName("create_agent")
data class CreateAgentRequest(
    val name: String,
    val prompts: List<Prompt.Id>,
    val runtimeSelection: AiRuntimeSelection,
    val tools: List<String> = emptyList(),
    val description: String? = null,
    val type: AgentDefinition.Type,
) : ClientRequest

@Serializable
@SerialName("update_agent")
data class UpdateAgentRequest(
    val agentId: AgentDefinition.Id,
    val prompts: List<Prompt.Id>? = null,
    val description: String? = null,
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
@SerialName("assemble_agent_system_prompt")
data class AssembleAgentSystemPromptRequest(
    val agent: AgentDefinition,
    val project: Project,
) : ClientRequest

@Serializable
@SerialName("assemble_prompt_system_prompt")
data class AssemblePromptSystemPromptRequest(
    val promptIds: List<Prompt.Id>,
    val project: Project,
) : ClientRequest

@Serializable
@SerialName("find_prompt")
data class FindPromptRequest(
    val promptId: Prompt.Id,
) : ClientRequest

@Serializable
@SerialName("find_prompts")
data object FindPromptsRequest : ClientRequest

@Serializable
@SerialName("refresh_prompts")
data object RefreshPromptsRequest : ClientRequest

@Serializable
@SerialName("create_environment_prompt")
data class CreateEnvironmentPromptRequest(
    val name: String,
    val content: String,
) : ClientRequest

@Serializable
@SerialName("copy_builtin_prompt_to_user")
data class CopyBuiltinPromptToUserRequest(
    val promptId: Prompt.Id,
) : ClientRequest

@Serializable
@SerialName("reset_all_builtin_prompts")
data object ResetAllBuiltinPromptsRequest : ClientRequest

@Serializable
@SerialName("import_all_claude_md")
data object ImportAllClaudeMdRequest : ClientRequest

@Serializable
@SerialName("get_or_create_project")
data class GetOrCreateProjectRequest(
    val path: String,
) : ClientRequest

@Serializable
@SerialName("find_project_by_id")
data class FindProjectByIdRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("find_project_by_path")
data class FindProjectByPathRequest(
    val path: String,
) : ClientRequest

@Serializable
@SerialName("update_project_last_used")
data class UpdateProjectLastUsedRequest(
    val projectId: Project.Id,
) : ClientRequest

@Serializable
@SerialName("create_conversation")
data class CreateConversationRequest(
    val projectPath: String,
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
@SerialName("find_conversations_by_project")
data class FindConversationsByProjectRequest(
    val projectPath: String,
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
@SerialName("get_token_stats")
data class GetTokenStatsRequest(
    val conversationId: Conversation.Id,
) : ClientRequest

@Serializable
@SerialName("update_stride_enabled")
data class UpdateStrideEnabledRequest(
    val conversationId: Conversation.Id,
    val enabled: Boolean,
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
@SerialName("squash_messages")
data class SquashMessagesRequest(
    val conversationId: Conversation.Id,
    val messageIds: List<Conversation.Message.Id>,
    val squashedContent: List<Conversation.Message.ContentItem>,
) : ClientRequest

@Serializable
@SerialName("squash_messages_with_ai")
data class SquashMessagesWithAiRequest(
    val conversationId: Conversation.Id,
    val messageIds: List<Conversation.Message.Id>,
    val squashType: SquashType,
    val runtimeSelection: AiRuntimeSelection,
    val projectPath: String?,
) : ClientRequest

@Serializable
@SerialName("search_conversations")
data class SearchConversationsRequest(
    val query: String,
) : ClientRequest

@Serializable
@SerialName("memory_action")
data class MemoryActionRequest(
    val conversationId: Conversation.Id,
    val action: MemoryAction,
) : ClientRequest

@Serializable
@SerialName("get_memory_tasks")
data class GetMemoryTasksRequest(
    val conversationId: Conversation.Id,
    val includeClosed: Boolean = false,
) : ClientRequest

@Serializable
@SerialName("transcribe_audio")
data class TranscribeAudioRequest(
    val recording: RemoteAudioRecording,
) : ClientRequest

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
data class RemoteAudioRecording(
    val sessionId: String,
    val mediaType: String,
    val fileExtension: String,
    val chunks: List<RemoteAudioChunk>,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
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
    val mediaType: String,
    val fileExtension: String,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
)

@Serializable
data class RemoteLiveTranscriptChunk(
    val sequenceNumber: Int,
    val text: String,
)

@Serializable
data class RemoteLiveInterpreterDraft(
    val id: String,
    val sequenceNumber: Int,
    val text: String,
)

@Serializable
@SerialName("send_message")
data class SendMessageCommand(
    val streamId: String,
    val conversationId: Conversation.Id,
    val userMessage: Conversation.Message,
    val agent: AgentDefinition,
) : ClientPayload

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
@SerialName("settings")
data class SettingsResponse(
    val settings: Settings,
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
@SerialName("count")
data class CountResponse(
    val count: Int,
) : ServerResponse

@Serializable
@SerialName("text_list")
data class TextListResponse(
    val items: List<String>,
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
@SerialName("conversations")
data class ConversationsResponse(
    val conversations: List<Conversation>,
) : ServerResponse

@Serializable
@SerialName("conversation_project_items")
data class ConversationProjectItemsResponse(
    val items: List<ConversationProjectItem>,
) : ServerResponse

@Serializable
data class ConversationProjectItem(
    val conversation: Conversation,
    val project: Project,
)

@Serializable
@SerialName("messages")
data class MessagesResponse(
    val messages: List<Conversation.Message>,
) : ServerResponse

@Serializable
@SerialName("token_stats")
data class TokenStatsResponse(
    val tokenStats: TokenUsageStatistics.ThreadTotals?,
) : ServerResponse

@Serializable
@SerialName("text")
data class TextResponse(
    val text: String,
) : ServerResponse

@Serializable
@SerialName("memory_action_completed")
data object MemoryActionCompletedResponse : ServerResponse

@Serializable
@SerialName("memory_tasks")
data class MemoryTasksResponse(
    val revision: String,
    val counts: MemoryTaskCounts,
    val tasks: List<MemoryTask>,
) : ServerResponse

@Serializable
data class MemoryTaskCounts(
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
@SerialName("error")
data class ErrorResponse(
    val message: String,
    val type: String? = null,
) : ServerResponse

@Serializable
@SerialName("message_upserted")
data class MessageUpsertedEvent(
    val streamId: String,
    val message: Conversation.Message,
) : ServerPayload

@Serializable
@SerialName("send_completed")
data class SendCompletedEvent(
    val streamId: String,
) : ServerPayload

@Serializable
@SerialName("send_failed")
data class SendFailedEvent(
    val streamId: String,
    val message: String,
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
