package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import java.security.MessageDigest

/** Re-authorizes background inference and binds its result to the input configuration, not just IDs. */
@Service
class CompactionPreparationScope(
    private val conversations: ConversationRepository,
    private val agents: AgentDomainService,
    private val users: UserDirectoryService,
    private val projects: ProjectAccessService,
    private val workers: WorkerAccessService,
    private val configurations: AiConfigurationProvider,
    private val pins: ConversationPinnedPrompt,
) {
    suspend fun pinPrompts(conversation: Conversation) = pins.append(conversation.id, emptyList())

    suspend fun fingerprint(conversation: Conversation, agent: AgentDefinition, actorId: User.Id, prompts: List<String>,
        capturedPins: List<String>? = null): String {
        val current = requireNotNull(conversations.findById(conversation.id)) { "Compaction conversation is unavailable" }
        if (current.currentThread != conversation.currentThread || current.projectId != conversation.projectId) throw CompactionScopeChangedException()
        require(Conversation.Participant.User(actorId) in current.participants && Conversation.Participant.Agent(agent.id) in current.participants)
        val actor = requireNotNull(users.findActiveById(actorId)) { "Compaction actor is inactive" }
        require(actor.canUseAi) { "Compaction actor cannot use AI" }
        projects.requirePermission(actorId, current.projectId, ProjectPermission.READ)
        if (agents.findById(agent.id) != agent) throw CompactionScopeChangedException()
        val selected = configurations.resolveAiRuntime(agent.runtimeSelection)
        val compactor = selected.modelConfiguration.compaction.compactionModelConfigurationId
            ?.let { configurations.resolveAiRuntime(AiRuntimeSelection(it)) } ?: selected
        for (resolved in listOf(selected, compactor).distinct()) {
            (resolved.connection.executionTarget as? AiExecutionTarget.Worker)?.let {
                val worker = ConversationRuntimeWorkerId(it.workerId)
                workers.requirePermission(actor, worker, WorkerPermission.USE, current.projectId)
                workers.requireProjectAccess(worker, current.projectId)
            }
        }
        return compactionFingerprint(buildJsonObject {
            put("actor", actorId.value)
            put("agent", Json.encodeToJsonElement(agent))
            put("selected", Json.encodeToJsonElement(selected))
            put("compactor", Json.encodeToJsonElement(compactor))
            put("prompts", Json.encodeToJsonElement(prompts))
            put("pins", Json.encodeToJsonElement(capturedPins ?: pinPrompts(conversation)))
        }.toString())
    }
}

/** Message versions are immutable. Only their resolved user identities can change under the same ID. */
internal fun authorFingerprint(headers: List<CompactionMessageHeader>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val encoded = mutableMapOf<Conversation.Message.Author.User, ByteArray>()
    for (header in headers) {
        val author = header.author ?: continue
        digest.update(encoded.getOrPut(author) {
            MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(author).encodeToByteArray())
        })
    }
    return digest.digest().toHexString()
}

private fun compactionFingerprint(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toHexString()

internal class CompactionScopeChangedException : IllegalStateException("Compaction source scope changed")
