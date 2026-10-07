@file:kotlinx.serialization.UseSerializers(
    com.gromozeka.domain.model.serialization.JsonElementTransportSerializer::class,
    com.gromozeka.domain.model.serialization.JsonObjectTransportSerializer::class,
)

package com.gromozeka.domain.visual

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** Persisted, server-owned visual. Client editor drafts and selected tabs are not shared state. */
@Serializable
data class Visual(
    val id: String,
    val conversationId: Conversation.Id,
    val createdBy: User.Id,
    val agentDefinitionId: AgentDefinition.Id? = null,
    val document: String,
    val title: String,
    val state: JsonObject,
    val revision: Long = 1,
    val documentRevision: Long = 1,
    val formRevision: Long = 1,
    /** Identifies an accepted button submission; null means an explicit programmatic form replacement. */
    val formEventId: String? = null,
    val handler: VisualHandler? = null,
    val status: VisualStatus = VisualStatus.ACTIVE,
    val diagnostics: List<VisualDiagnostic> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
enum class VisualStatus { STARTING, ACTIVE, STOPPED }

@Serializable
data class VisualHandlerSpec(
    val workspaceMountId: WorkspaceMount.Id,
    val command: String,
    val workingDirectory: String? = null,
)

@Serializable
data class VisualHandler(
    val spec: VisualHandlerSpec,
    val worker: ConversationRuntimeWorkerIdentity,
    val generation: String,
    val taskId: CommandTask.Id? = null,
    val outputCursor: Long = 0,
)

@Serializable
data class VisualDiagnostic(
    val code: String,
    val message: String,
    val at: Instant,
    val occurrences: Int = 1,
)

@Serializable
data class VisualCreate(
    val document: String,
    val state: JsonObject,
    val handler: VisualHandlerSpec? = null,
)

@Serializable
data class VisualUpdate(
    val document: String? = null,
    /** Complete form and/or data sections. Omitted sections remain unchanged. */
    val state: JsonObject? = null,
    /** False leaves the current handler unchanged; true + null switches to the LLM. */
    val updateHandler: Boolean = false,
    val handler: VisualHandlerSpec? = null,
)

@Serializable
data class VisualAction(
    val eventId: String,
    val visualId: String,
    val documentRevision: Long,
    val buttonId: String,
    val state: JsonObject,
)

@Serializable
data class VisualActionResult(val eventId: String, val accepted: Boolean, val error: String? = null)

@Serializable
data class VisualOutputRecord(val endByte: Long, val json: String? = null, val error: String? = null)

/** Client-facing API. All authorization and state mutation happens on the Server. */
interface VisualService {
    fun observe(conversationId: Conversation.Id): Flow<List<Visual>>
    suspend fun list(conversationId: Conversation.Id): List<Visual>
    suspend fun create(conversationId: Conversation.Id, request: VisualCreate): Visual
    suspend fun update(conversationId: Conversation.Id, visualId: String, request: VisualUpdate): Visual
    suspend fun close(conversationId: Conversation.Id, visualId: String)
    suspend fun act(conversationId: Conversation.Id, action: VisualAction): VisualActionResult
}

object VisualLimits {
    const val DOCUMENT_BYTES = 65_536
    const val STATE_BYTES = 32_768
    const val OUTPUT_LINE_BYTES = 8_192
    const val RENDER_NODES = 2_048
    const val DEPTH = 32
    const val PER_CONVERSATION = 8
    const val DIAGNOSTICS = 12
}
