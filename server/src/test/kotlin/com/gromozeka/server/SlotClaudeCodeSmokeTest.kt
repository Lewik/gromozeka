package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.repository.ClaudeCodeSessionStateRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.*
import com.gromozeka.domain.tool.*
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.support.GenericApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock

/** Real Haiku protocol + real slot service/callback/enrichment/coordinator transitions, without a dev runtime.
 * Storage, authorization directory and workspace metadata are synthetic. This is not a PG, Worker RPC,
 * ConversationEngine or native compaction test. No prose from the model is evidence of a tool effect.
 */
@EnabledIfEnvironmentVariable(named = "GROMOZEKA_SLOT_CLAUDE_SMOKE", matches = "true")
class SlotClaudeCodeSmokeTest {
    @Test fun `haiku acquires automatically and can release after all discovery and history are lost`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("gromozeka-slot-claude-smoke-")
        val fixture = Fixture(root)
        try {
            fixture.setup()
            // Only these two production beans are constructed, not Server/SpringBoot/Worker services.
            // Spring bean names avoid widening internal infrastructure implementation visibility for tests.
            claudeBackendContext().use { context ->
                val connection = AiConnection.ClaudeCode(AiConnection.Id("slot-smoke-${UUID.randomUUID()}"), "Synthetic slot smoke",
                    executablePath = System.getenv("GROMOZEKA_CLAUDE_EXECUTABLE") ?: "claude", maxCachedProcesses = 1,
                    executionTarget = AiExecutionTarget.Worker(fixture.worker.id.value))
                val model = AiModelConfiguration(AiModelConfiguration.Id("slot-smoke-haiku"), connection.id, "haiku", "Haiku smoke")
                val runtime = context.getBean(AiRuntimeBackend::class.java).createRuntime(connection, model, root.toString())
                suspend fun ask(request: AiRuntimeRequest): AiRuntimeResponse {
                    // Force an independent CLI invocation: it must not remember hidden discovery/history.
                    // This production path adds --no-session-persistence and closes its own child process.
                    val stateless = request.copy(options = request.options.copy(toolContext = request.options.toolContext - TOOL_CONTEXT_THREAD_ID))
                    val response = runtime.call(stateless)
                    assertFalse(response.outcome.isFailure || response.outcome == AiStepOutcome.REFUSED, "Claude protocol step failed: ${response.outcome}")
                    return response
                }
                withTimeout(240_000) {
                    val acquire = assertNotNull(fixture.catalog.serverTool(ACQUIRE, fixture.policy))
                    val initialBase = fixture.request("Request a new WRITE occupation using automatic selection. Omit slot_number entirely. Do not list, guess a slot, run commands or release.")
                        .copy(tools = listOf(acquire))
                    val initial = fixture.enrich(initialBase)
                    assertEquals(listOf(acquire.definition.name), initial.tools.map { it.definition.name })
                    val reply = ask(initial.copy(options = initial.options.copy(toolChoice = AiToolChoice.RequiredTool(acquire.definition.name))))
                    val call = reply.toolCalls.single()
                    assertEquals(acquire.definition.name, call.call.name)
                    assertFalse("slot_number" in call.call.input.jsonObject)
                    assertEquals("WRITE", call.call.input.jsonObject.getValue("access").jsonPrimitive.content)
                    val acceptedText = fixture.execute(initial, call)
                    val accepted = Json.parseToJsonElement(acceptedText).jsonObject
                    assertEquals("accepted", accepted.getValue("status").jsonPrimitive.content)
                    val pending = Json.decodeFromJsonElement<SlotRequest>(accepted.getValue("request"))
                    assertNull(pending.slotNumber); assertEquals(SlotRequest.State.PENDING, pending.state)
                    assertTrue(fixture.repository.snapshot(fixture.first.number)!!.leases.isEmpty())
                    assertTrue(fixture.coordinator.schedulingSnapshot(fixture.conversation.id).pendingTasks.isEmpty())
                    println("SLOT_CLAUDE_SMOKE accepted omitted-number acquire")

                    fixture.slots.processPending()
                    fixture.slots.deliverPending(); fixture.slots.deliverPending()
                    val saved = fixture.repository.findRequest(pending.id)!!
                    val lease = fixture.repository.findLease(assertNotNull(saved.leaseId))!!
                    assertNull(saved.slotNumber); assertEquals(fixture.first.number, saved.grantedSlotNumber)
                    assertEquals(fixture.first.number, lease.slotNumber); assertTrue(lease.active)
                    val task = fixture.coordinator.schedulingSnapshot(fixture.conversation.id).pendingTasks.single()
                    val grant = (task.payload as ConversationRuntimeTask.Payload.AgentInvocation).userMessage
                    val grantText = (grant.content.single() as Conversation.Message.ContentItem.UserMessage).text
                    assertContains(grantText, "Slot ${lease.slotNumber} granted")
                    assertContains(grantText, "lease_id=${lease.id}")
                    assertContains(grantText, "workspace_mount_id=${fixture.first.mountId.value}")
                    assertEquals(lease.id, fixture.slots.origin(fixture.user.id, fixture.conversation.id, fixture.first.mountId)?.leaseId)
                    val acknowledged = initialBase.copy(messages = initialBase.messages + reply.messages.map {
                        fixture.message(Conversation.Message.Role.ASSISTANT, it.content)
                    } + fixture.message(Conversation.Message.Role.USER, listOf(Conversation.Message.ContentItem.ToolResult(
                        call.id, call.call.name, listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(acceptedText)),
                    ))) + grant, options = initial.options.copy(toolChoice = AiToolChoice.None))
                    // Feed the actual accepted result and actual coordinator event, not invented model text.
                    assertTrue(ask(fixture.enrich(acknowledged)).toolCalls.isEmpty())
                    println("SLOT_CLAUDE_SMOKE exact coordinator grant and mount verified")

                    fixture.replaceHistory()
                    val fresh = fixture.request("Return this Conversation's current occupation using its exact lease from current Runtime state. Do not invent confirmation_id, acquire again or use discovery.")
                    assertTrue(fresh.tools.isEmpty()); assertEquals(1, fresh.messages.size)
                    val denied = fixture.enrich(fresh.copy(options = fresh.options.copy(toolAccess = ToolAccessPolicy.AllowOnly())))
                    assertTrue(denied.tools.isEmpty(), "Holding a lease must not bypass policy")
                    val deniedByName = fixture.enrich(fresh.copy(options = fresh.options.copy(toolAccess = ToolAccessPolicy.DenyListed(setOf(ToolSelector.ByName(RELEASE))))))
                    assertTrue(deniedByName.tools.isEmpty())
                    val releaseContract = fixture.contracts.findAll().single { it.logicalName == RELEASE.name && it.variant == 2 }
                    val permitted = fresh.copy(options = fresh.options.copy(toolAccess = ToolAccessPolicy.AllowOnly(setOf(
                        ToolSelector.ExactRevision(ToolContractFingerprint(releaseContract.fingerprint)),
                    ))))
                    val enriched = fixture.enrich(permitted)
                    val release = enriched.tools.single()
                    assertEquals("grz_slot_release__v2", release.definition.name)
                    assertEquals(Json.parseToJsonElement(fixture.nativeRelease.definition.inputSchema), Json.parseToJsonElement(release.definition.inputSchema))
                    val releaseReply = ask(enriched.copy(options = enriched.options.copy(toolChoice = AiToolChoice.RequiredTool(release.definition.name))))
                    val releaseCall = releaseReply.toolCalls.single()
                    assertEquals(release.definition.name, releaseCall.call.name)
                    assertEquals(lease.slotNumber, releaseCall.call.input.jsonObject.getValue("slot_number").jsonPrimitive.long)
                    assertEquals(lease.id, releaseCall.call.input.jsonObject.getValue("lease_id").jsonPrimitive.content)
                    val result = Json.parseToJsonElement(fixture.execute(enriched, releaseCall)).jsonObject
                    assertTrue(result.getValue("released").jsonPrimitive.boolean)
                    assertFalse(fixture.repository.findLease(lease.id)!!.active)
                    assertTrue(fixture.enrich(fresh).tools.isEmpty(), "Ended lease must not keep release preloaded")
                    println("SLOT_CLAUDE_SMOKE fresh-history policy-scoped current-schema release verified")
                }
            }
        } finally {
            fixture.scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(private val root: Path) {
        private val now = Clock.System.now()
        private val id = UUID.randomUUID().toString()
        val user = User(User.Id("smoke-user-$id"), displayName = "Synthetic smoke user", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        private val project = Project(Project.Id("smoke-project-$id"), "Synthetic slot smoke", createdAt = now, lastUsedAt = now)
        private val agent = AgentDefinition.Id("smoke-agent-$id")
        var conversation = Conversation(Conversation.Id("smoke-conversation-$id"), project.id,
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent)), currentThread = Conversation.Thread.Id("before-$id"), createdAt = now, updatedAt = now)
            private set
        val worker = WorkerResource(ConversationRuntimeWorkerId("smoke-worker-$id"), "Synthetic worker", user.id, runtimeWideAccess = true, status = WorkerResource.Status.ACTIVE, createdAt = now, updatedAt = now)
        val repository = SlotSmokeRepository()
        private val conversations = Mockito.mock(ConversationDomainService::class.java)
        private val projects = Mockito.mock(ProjectAccessService::class.java)
        private val workspaces = Mockito.mock(WorkspaceDomainService::class.java)
        private val workers = Mockito.mock(WorkerAccessService::class.java)
        private val users = Mockito.mock(UserDirectoryService::class.java)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // No filesystem inspection is claimed: these are synthetic registry entries, not working checkouts.
        val slots = SlotApplicationService(repository, conversations, projects, workspaces, workers, users, object : SlotObservationProvider {
            override suspend fun inspect(slot: Slot, lease: SlotLease) = SlotObservation(Clock.System.now(), emptyList())
            override suspend fun activityWarnings(slot: Slot) = emptyList<String>()
        }, coordinator, scope)
        private val nativeTools = SlotToolContributor(slots, users).callbacks
        val nativeRelease = nativeTools.single { it.definition.name == RELEASE.name }
        val contracts = SlotSmokeContracts()
        val catalog = DistributedAiToolCatalog(Mockito.mock(ConversationRuntimeWorkerRegistry::class.java), workspaces,
            object : AiToolProvider { override fun getTools() = nativeTools }, workers, contracts)
        private val enricher = SlotRequestEnricher(slots, users, catalog)
        val policy = ToolAccessPolicy.AllowOnly(setOf(ToolSelector.ByName(ACQUIRE), ToolSelector.ByName(RELEASE)))
        lateinit var first: Slot
            private set

        suspend fun setup() {
            Mockito.`when`(users.findActiveById(user.id)).thenReturn(user)
            Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
            Mockito.`when`(projects.can(user.id, project.id, ProjectPermission.READ)).thenReturn(true)
            Mockito.`when`(projects.can(user.id, project.id, ProjectPermission.WRITE)).thenReturn(true)
            Mockito.`when`(workers.requirePermission(user, worker.id, WorkerPermission.USE, project.id)).thenReturn(worker)
            Mockito.`when`(workers.requireProjectAccess(worker.id, project.id)).thenReturn(worker)
            Mockito.`when`(workers.findAccessible(user, worker.id, project.id)).thenReturn(worker)
            suspend fun register(suffix: String): Slot {
                val workspace = Workspace(Workspace.Id("smoke-workspace-$suffix-$id"), project.id, "Synthetic $suffix", kind = Workspace.Kind.FILESYSTEM, createdAt = now, updatedAt = now)
                val mount = WorkspaceMount(WorkspaceMount.Id("smoke-mount-$suffix-$id"), workspace.id, worker.id.value, root.resolve(suffix).toString(), now, now)
                Mockito.`when`(workspaces.findMount(mount.id)).thenReturn(mount)
                Mockito.`when`(workspaces.resolveExecution(mount.id)).thenReturn(WorkspaceExecutionContext(project, workspace, mount))
                return slots.register(user, conversation.id, mount.id, "main")
            }
            first = register("first"); register("second")
            // Exercise versioned model-name resolution without changing a global tool registry.
            contracts.resolveAll(listOf(AiToolDescriptor(nativeRelease.definition.copy(description = "Synthetic previous release contract"), nativeRelease.metadata)))
        }
        suspend fun replaceHistory() {
            conversation = conversation.copy(currentThread = Conversation.Thread.Id("replaced-$id"))
            Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        }
        fun message(role: Conversation.Message.Role, content: List<Conversation.Message.ContentItem>) =
            Conversation.Message(Conversation.Message.Id(UUID.randomUUID().toString()), conversation.id, role = role, content = content, createdAt = Clock.System.now())
        private fun context(): Map<String, Any?> = mapOf(TOOL_CONTEXT_USER_ID to user.id.value, TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value,
            TOOL_CONTEXT_PROJECT_ID to project.id.value, TOOL_CONTEXT_AGENT_DEFINITION_ID to agent.value, TOOL_CONTEXT_THREAD_ID to conversation.currentThread.value)
        fun request(text: String) = AiRuntimeRequest(
            systemPrompts = listOf("Synthetic protocol smoke. Do only the explicit current step using offered Gromozeka tools. An accepted request is not a grant. Runtime grant events need only a short acknowledgment; never acquire again or release without a new operator message. No native tools, web, commands or unrelated work."),
            messages = listOf(message(Conversation.Message.Role.USER, listOf(Conversation.Message.ContentItem.UserMessage(text)))),
            options = AiRuntimeOptions(maxOutputTokens = 1024, reasoning = AiReasoningConfig(mode = AiReasoningMode.DISABLED), toolAccess = policy, toolContext = context()),
        )
        suspend fun enrich(request: AiRuntimeRequest) = enricher.enrich(conversation.id, request.messages.first().id, ConversationRuntimeTurnId("smoke-turn-$id"), MODEL, request)
        suspend fun execute(request: AiRuntimeRequest, call: Conversation.Message.ContentItem.ToolCall): String {
            require(request.tools.any { it.definition.name == call.call.name }) { "Model called a tool not offered in this request" }
            val contract = contracts.findAll().single { it.modelName == call.call.name }
            require(request.options.toolAccess.allows(contract)) { "Tool policy denied this exact contract" }
            val callback = nativeTools.single { it.definition.name == contract.logicalName && it.definition.source == contract.descriptor.definition.source }
            require(callback.metadata.executionScope == AiToolExecutionScope.SERVER)
            // Execute the real callback with trusted synthetic identity, not model-supplied identity fields.
            return callback.call(call.call.input.toString(), ToolExecutionContext(context() + ("toolCallId" to call.id.value)))
        }
    }

    private companion object {
        val ACQUIRE = QualifiedToolName("gromozeka", "grz_slot_acquire")
        val RELEASE = QualifiedToolName("gromozeka", "grz_slot_release")
        val MODEL = AiModelSpec("haiku", AiProvider.ANTHROPIC, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(200_000)))
        fun claudeBackendContext() = GenericApplicationContext().apply {
            beanFactory.registerSingleton("smokeSessions", object : ClaudeCodeSessionStateRepository {
                override suspend fun find(key: ClaudeCodeSessionState.Key): ClaudeCodeSessionState? = error("Smoke must use stateless CLI calls")
                override suspend fun save(state: ClaudeCodeSessionState): ClaudeCodeSessionState = error("Smoke must not save native sessions")
                override suspend fun delete(key: ClaudeCodeSessionState.Key): Unit = error("Smoke must not touch native sessions")
            })
            registerBeanDefinition("smokeCli", RootBeanDefinition().apply {
                beanClassName = "com.gromozeka.infrastructure.ai.claude.ProcessClaudeCodeCliExecutor"
                destroyMethodName = "close"
            })
            registerBeanDefinition("smokeBackend", RootBeanDefinition().apply {
                beanClassName = "com.gromozeka.infrastructure.ai.claude.ClaudeCodeCliRuntimeBackend"
                autowireMode = AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR
            })
            try { refresh() }
            catch (failure: Throwable) { close(); throw failure }
        }
    }
}

private class SlotSmokeContracts : AiToolContractRepository {
    private val contracts = linkedMapOf<String, AiToolContract>()
    override suspend fun findAll() = contracts.values.toList()
    override suspend fun resolveAll(descriptors: Collection<AiToolDescriptor>) = descriptors.associateBy(AiToolDescriptor::contractFingerprint).map { (fingerprint, descriptor) ->
        contracts.getOrPut(fingerprint) {
            val variant = contracts.values.count { it.logicalName == descriptor.definition.name } + 1
            AiToolContract(fingerprint, descriptor.definition.name, if (variant == 1) descriptor.definition.name else "${descriptor.definition.name}__v$variant", variant, descriptor, Clock.System.now())
        }.also { check(it.descriptor == descriptor) }
    }
}
