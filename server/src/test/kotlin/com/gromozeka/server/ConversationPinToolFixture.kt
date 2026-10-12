package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.server.ConversationPinToolSupport.Operation
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.mockito.Mockito
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

/** Real auth + pin application service + callbacks; only storage/directories are synthetic.
 * No Server, database, dispatcher or Worker. Shared narrowly by deterministic and opt-in protocol tests.
 */
internal class ConversationPinToolFixture {
    val now = Instant.fromEpochMilliseconds(1)
    private val suffix = UUID.randomUUID().toString()
    val actor = User(User.Id("pin-user-$suffix"), displayName = "Synthetic pin user", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
    val project = Project(Project.Id("pin-project-$suffix"), "Synthetic pin test", createdAt = now, lastUsedAt = now)
    val a = AgentEndpoint(Conversation.Id("pin-A-$suffix"), Conversation.Thread.Id("pin-A-thread-$suffix"), AgentDefinition.Id("pin-A-agent-$suffix"))
    val b = AgentEndpoint(Conversation.Id("pin-B-$suffix"), Conversation.Thread.Id("pin-B-thread-$suffix"), AgentDefinition.Id("pin-B-agent-$suffix"))
    val otherAgent = AgentDefinition.Id("pin-other-$suffix")
    var activeUser: User? = actor
    var writeAllowed = true
    val directory = linkedMapOf(a.conversationId to conversation(a), b.conversationId to conversation(b))
    val definitions = linkedMapOf(a.agentId to definition(a.agentId), b.agentId to definition(b.agentId), otherAgent to definition(otherAgent))
    val conversations = Mockito.mock(ConversationRepository::class.java)
    val identities = Mockito.mock(IdentityRepository::class.java)
    val projectAccess = Mockito.mock(ProjectAccessService::class.java)
    val agents = Mockito.mock(AgentDomainService::class.java)
    val repository = SyntheticPinRepository()
    val invalidations = mutableListOf<DeclarativeStateKey>()
    val publisher = DeclarativeStateChangePublisher { keys -> invalidations += keys }
    val core = ConversationPinApplicationService(repository, conversations, identities, projectAccess, publisher)
    val access = ConversationAgentAccessService(conversations, identities, projectAccess, agents)
    val contributor = ConversationPinToolContributor(core, access)
    val callbacks = contributor.callbacks.associateBy { it.definition.name }
    val coordinator = InMemoryConversationRuntimeCoordinator()
    val history = Mockito.mock(ConversationDomainService::class.java)
    val outbox = Mockito.mock(AgentCollaborationRepository::class.java)
    val delivery = AgentDelivery("pin-existing-delivery-$suffix", null, a, b, actor.id, AgentDelivery.Kind.MESSAGE, "PRIVATE_OUTBOX_SENTINEL", now)
    private lateinit var beforeA: ConversationRuntimeSnapshot
    private lateinit var beforeB: ConversationRuntimeSnapshot

    private fun conversation(endpoint: AgentEndpoint) = Conversation(endpoint.conversationId, project.id,
        setOf(Conversation.Participant.User(actor.id), Conversation.Participant.Agent(endpoint.agentId)),
        currentThread = endpoint.threadId, createdAt = now, updatedAt = now)
    fun definition(id: AgentDefinition.Id) = AgentDefinition(id, name = "Synthetic agent", prompts = emptyList(),
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("runtime-${id.value}")),
        tools = AgentPreloadedTools(listOf("preload-${id.value}")), type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)

    suspend fun setup() {
        Mockito.`when`(identities.findUserById(actor.id)).thenAnswer { activeUser }
        for (id in listOf(a.conversationId, b.conversationId)) Mockito.`when`(conversations.findById(id)).thenAnswer { directory[id] }
        for (id in listOf(a.agentId, b.agentId, otherAgent)) Mockito.`when`(agents.findById(id)).thenAnswer { definitions[id] }
        val membership = ProjectMembership(project.id, actor.id, ProjectMembership.Role.OWNER, now, actor.id)
        Mockito.`when`(projectAccess.requirePermission(actor.id, project.id, ProjectPermission.READ)).thenReturn(membership)
        Mockito.`when`(projectAccess.requirePermission(actor.id, project.id, ProjectPermission.WRITE)).thenAnswer {
            if (!writeAllowed) throw ProjectAccessDeniedException()
            membership
        }
        val input = Conversation.Message(Conversation.Message.Id("pin-existing-input-$suffix"), b.conversationId,
            role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage("PRIVATE_HISTORY_SENTINEL")), createdAt = now)
        Mockito.`when`(history.loadCurrentMessages(b.conversationId)).thenReturn(listOf(input))
        Mockito.`when`(outbox.pendingDeliveries()).thenReturn(listOf(delivery))
        val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("pin-existing-task-$suffix"), b.conversationId, actorUserId = actor.id,
            payload = ConversationRuntimeTask.Payload.AgentInvocation(input, b.agentId), placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "pin-existing-task-$suffix", requirements = ConversationRuntimeTaskRequirements(
                setOf(ConversationRuntimeCapability.CONVERSATION_TURN, ConversationRuntimeCapability.MEMORY_PIPELINE), ConversationRuntimeTaskTarget.Server), createdAt = now)
        assertTrue(coordinator.submit(task))
        assertTrue(coordinator.requestPause(b.conversationId))
        beforeA = coordinator.snapshot(a.conversationId)
        beforeB = coordinator.snapshot(b.conversationId)
        assertEquals(ConversationExecutionState.ControlState.PAUSED, beforeB.state?.controlState)
        assertEquals(1, beforeB.pendingTasks.size)
    }

    fun context(callId: String? = "synthetic-call"): ToolExecutionContext = ToolExecutionContext(buildMap {
        put(TOOL_CONTEXT_USER_ID, actor.id.value); put(TOOL_CONTEXT_PROJECT_ID, project.id.value)
        put(TOOL_CONTEXT_CONVERSATION_ID, a.conversationId.value); put(TOOL_CONTEXT_THREAD_ID, a.threadId.value)
        put(TOOL_CONTEXT_AGENT_DEFINITION_ID, a.agentId.value)
        if (callId != null) put("toolCallId", callId)
    })
    fun peerArgs(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf("conversation_id" to JsonPrimitive(b.conversationId.value)) + fields)
    fun call(operation: Operation, args: JsonObject, callId: String? = "synthetic-call"): JsonObject =
        Json.parseToJsonElement(callbacks.getValue(operation.toolName).call(args.toString(), context(callId))).jsonObject
    suspend fun assertNoRuntimeEffects() {
        assertEquals(beforeA, coordinator.snapshot(a.conversationId))
        assertEquals(beforeB, coordinator.snapshot(b.conversationId))
        assertEquals(beforeB.lastEventSequence, coordinator.lastEventSequence(b.conversationId))
        Mockito.verifyNoInteractions(history, outbox)
        assertTrue(Mockito.mockingDetails(conversations).invocations.all { it.method.name.substringBefore('-') == "findById" })
    }
}

/** Contract-faithful in-memory test adapter, not production SQL or a second domain API.
 * Receipt/CAS/quotas are explicit so app/callback tests assert actual state, not canned responses.
 */
internal class SyntheticPinRepository : ConversationPinRepository {
    private data class Receipt(val text: String, val source: ConversationPinSource?, val pinId: ConversationPin.Id)
    private val mutex = Mutex()
    private val pins = linkedMapOf<ConversationPin.Id, ConversationPin>()
    private val receipts = linkedMapOf<Triple<Conversation.Id, User.Id, String>, Receipt>()
    private val ordinals = mutableMapOf<Conversation.Id, Long>()
    private var time = 1L
    val attempts = mutableListOf<String>()
    var failure: Exception? = null
    fun snapshot(): List<ConversationPin> = pins.values.toList()
    fun receiptCount(): Int = receipts.size
    private fun enter(operation: String) { attempts += operation; failure?.let { throw it } }
    private fun tick() = Instant.fromEpochMilliseconds(time++)
    private fun quota(owner: Conversation.Id, text: String, replacing: ConversationPin? = null) {
        ConversationPinLimits.validateText(text)
        val current = pins.values.filter { it.conversationId == owner }
        if (replacing == null && current.size >= ConversationPinLimits.MAX_PINS_PER_CONVERSATION) throw ConversationPinLimitException()
        if (current.sumOf { it.text.length.toLong() } - (replacing?.text?.length ?: 0) + text.length > ConversationPinLimits.MAX_TOTAL_TEXT_LENGTH) throw ConversationPinLimitException()
    }
    override suspend fun list(conversationId: Conversation.Id): List<ConversationPin> = mutex.withLock {
        enter("list"); pins.values.filter { it.conversationId == conversationId }.sortedBy { it.ordinal }
    }
    override suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin? = mutex.withLock {
        enter("get"); pins[pinId]?.takeIf { it.conversationId == conversationId }
    }
    override suspend fun create(conversationId: Conversation.Id, actor: User.Id, text: String, source: ConversationPinSource?, creationKey: String?): ConversationPinCreation = mutex.withLock {
        enter("create")
        ConversationPinLimits.validateCreationKey(creationKey)
        val receiptKey = creationKey?.let { Triple(conversationId, actor, it) }
        receiptKey?.let(receipts::get)?.let { receipt ->
            if (receipt.text != text || receipt.source != source) throw ConversationPinConflictException()
            return@withLock ConversationPinCreation(pins[receipt.pinId] ?: throw ConversationPinNotFoundException(), false)
        }
        quota(conversationId, text)
        val ordinal = (ordinals[conversationId] ?: 0) + 1
        val stamp = tick()
        val pin = ConversationPin(ConversationPin.Id(uuid7().toString()), conversationId, text, ordinal, 1, stamp, stamp, source)
        pins[pin.id] = pin; ordinals[conversationId] = ordinal
        if (receiptKey != null) receipts[receiptKey] = Receipt(text, source, pin.id)
        ConversationPinCreation(pin, true)
    }
    override suspend fun update(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long, text: String): ConversationPin = mutex.withLock {
        enter("update")
        val pin = pins[pinId]?.takeIf { it.conversationId == conversationId } ?: throw ConversationPinNotFoundException()
        if (pin.revision != expectedRevision) throw ConversationPinConflictException()
        quota(conversationId, text, pin)
        if (pin.text == text) pin else pin.copy(text = text, revision = pin.revision + 1, updatedAt = tick()).also { pins[pin.id] = it }
    }
    override suspend fun delete(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long): Unit = mutex.withLock {
        enter("delete")
        val pin = pins[pinId]?.takeIf { it.conversationId == conversationId } ?: throw ConversationPinNotFoundException()
        if (pin.revision != expectedRevision) throw ConversationPinConflictException()
        pins.remove(pinId)
        Unit
    }
}
