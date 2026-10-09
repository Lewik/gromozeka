package com.gromozeka.server

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.application.service.ConversationHistoryReadService
import com.gromozeka.application.service.SlotApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.*
import com.gromozeka.domain.tool.*
import com.gromozeka.infrastructure.ai.openai.subscription.OpenAiSubscriptionSession
import com.gromozeka.server.testsupport.app.ServerTestHarness
import com.gromozeka.shared.uuid.uuid7
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.test.*
import kotlin.time.Clock

/** Opt-in live acceptance fixture. A separately managed real Worker connects using the private generated config.
 * The native web client must explicitly confirm the final reclaim; this test cannot approve it for the user.
 * All database and filesystem state is synthetic and isolated. Original provider credentials are read-only.
 */
@EnabledIfEnvironmentVariable(named = "GROMOZEKA_SLOT_LIVE_TEST", matches = "true")
class SlotLiveFlowTest {
    @Test fun `live models queue execute return and request native UI reclaim`() = runBlocking<Unit> {
        val schema = environment("GROMOZEKA_SLOT_LIVE_SCHEMA")
        require(schema.matches(Regex("slot_live_[a-z0-9_]+")))
        val state = Path.of(environment("GROMOZEKA_SLOT_LIVE_STATE_DIR")).toAbsolutePath()
        require(!Files.exists(state)) { "Use a new isolated state directory for every live run" }
        Files.createDirectories(state)
        val webRoot = File(environment("GROMOZEKA_SLOT_LIVE_WEB_ROOT"))
        require(webRoot.resolve("index.html").isFile)
        val port = environment("GROMOZEKA_SLOT_LIVE_PORT").toInt()
        val jdbc = environment("GROMOZEKA_POSTGRES_URL")
        val session = readCodexSession(Path.of(environment("GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE")))
        val publicState = linkedMapOf("url" to "http://slots-e2e.localhost:$port/", "worker_config" to state.resolve("worker.yaml").toString(), "worker_home" to state.resolve("worker-home").toString())
        fun stage(value: String) {
            publicState["stage"] = value
            Files.writeString(state.resolve("ready.json"), Json.encodeToString(publicState))
            println("SLOT_LIVE_STAGE $value")
        }
        fun database() = DriverManager.getConnection(jdbc, "gromozeka", "gromozeka")
        // Fail rather than reuse another run's persisted state.
        database().use { it.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            ServerTestHarness(subscriptionSession = session, systemProperties = mapOf(
                "gromozeka.postgres.jdbc-url" to jdbc,
                "gromozeka.postgres.schema" to schema,
                "gromozeka.llm.cassette.mode" to "off",
                "gromozeka.worker-enrollment.enabled" to "true",
                "gromozeka.worker-enrollment.capabilities" to "TOOL_EXECUTION,LOCAL_AGENT_TOOL",
                "gromozeka.ai.openai-subscription.websocket-response-timeout-ms" to "120000",
                "gromozeka.ai.openai-subscription.http-response-timeout-ms" to "120000",
            ), aiCatalogTransform = ::liveCatalog).use { harness ->
                val context = harness.context
                check(context.getBean(AiRuntimeProvider::class.java).javaClass.simpleName == "CassetteAiRuntimeProvider") {
                    "The live fixture must use the real runtime provider, not a neighboring test's model stub"
                }
                check(!context.containsBean("telegramIsolationModel"))
                check(!context.containsBean("memoryE2eReadTraceCollector"))
                println("SLOT_LIVE_PROVIDER real backend; cassette mode off")
                val auth = context.getBean(AuthenticationService::class.java)
                check(!auth.hasUsers())
                val bootstrap = context.getBean(FirstUserBootstrapToken::class.java)
                val user = auth.createFirstUser(requireNotNull(bootstrap.currentToken()), USERNAME, "Slot live test",
                    PASSWORD.toCharArray(), "slot-live").user
                val project = context.getBean(ProjectAccessService::class.java).create(user.id, "Slot live acceptance")
                val enrollment = context.getBean(WorkerEnrollmentService::class.java)
                val worker = enrollment.consume(enrollment.create(user.id).token, "slot-live-worker", "macos")
                val workerId = ConversationRuntimeWorkerId(worker.workerId)
                val credentialJson = Json.encodeToString(JsonPrimitive(worker.gatewayCredential))
                val yaml = """
                    gromozeka:
                      worker-gateway:
                        enabled: true
                        server-url: "http://127.0.0.1:$port"
                        credential: $credentialJson
                      runtime:
                        worker:
                          id: "${worker.workerId}"
                          capabilities: ["TOOL_EXECUTION", "LOCAL_AGENT_TOOL"]
                """.trimIndent()
                Files.writeString(state.resolve("worker.yaml"), yaml)
                Files.setPosixFilePermissions(state.resolve("worker.yaml"), PosixFilePermissions.fromString("rw-------"))
                context.getBean(WorkerAccessService::class.java).grantProject(user, workerId, project.id)

                val remote = context.getBean(GromozekaRemoteServer::class.java)
                val gateway = context.getBean(WorkerGatewayService::class.java)
                val workerAuth = workerGatewayAuthentication(context.getBean(WorkerGatewayAuthenticationService::class.java))
                val authorization = context.getBean(GromozekaRemoteAuthorization::class.java)
                val http = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                    installHttpAuthenticationErrors(); install(gromozekaBrowserSecurityHeaders)
                    install(WebSockets) { maxFrameSize = Long.MAX_VALUE }
                    routing {
                        gromozekaAuthentication(auth, bootstrap, context.getBean(AuthenticationAttemptLimiter::class.java), secureCookie = false)
                        webSocket("/ws") { remote.handle(this, call.requireAuthenticated(auth)) }
                        route("/worker/ws") {
                            install(workerAuth)
                            webSocket { gateway.handle(this, call.attributes[authenticatedWorkerGatewayKey]) }
                        }
                        gromozekaHistory(context.getBean(ConversationHistoryReadService::class.java), auth, authorization)
                        gromozekaArtifacts(context.getBean(ConversationArtifactApplicationService::class.java), auth, authorization)
                        gromozekaWeb(webRoot)
                    }
                }.start(wait = false)
                try {
                    stage("WAITING_FOR_WORKER")
                    val workers = context.getBean(ConversationRuntimeWorkerRegistry::class.java)
                    await("real Worker registration", 240_000) {
                        workers.find(workerId)?.tools?.any { it.definition.name == "grz_execute_command" } == true
                    }
                    stage("WORKER_CONNECTED")
                    val roots = createClones(state.resolve("repositories"))
                    val workspaces = context.getBean(WorkspaceDomainService::class.java)
                    val mounts = roots.mapIndexed { index, root -> workspaces.createAndMountFilesystemWorkspace(project.id, "Clone ${index + 1}", worker.workerId, root.toString()) }
                    val prompts = context.getBean(PromptDomainService::class.java)
                    val prompt = prompts.createPrompt(project.id, "Bounded slot acceptance test", """
                        This is a synthetic live acceptance test in isolated repositories. Do only the current operator's explicit stage.
                        Use the provided Gromozeka tools, never simulate execution. Acquire requests return accepted, not a grant.
                        Never poll pending slots or repeat an already pending/held acquisition. Runtime grant/reclaim events require only a short acknowledgement, not a new stage.
                        A grant event resumes this conversation automatically. Do not release, clean, run commands or reacquire unless a new operator message explicitly requests it.
                        Multiple slots may be held. Use numeric slot identifiers in slot tools and the exact lease_id from current runtime state.
                        For shell commands use the exact mount specified by the operator and yield_time_ms=5000.
                        At a warning-producing release, stop and wait for CONFIRM_DIRTY_RETURN. Never repair Git, stop processes or invent a force parameter.
                        For HUMAN_RECLAIM use grz_slot_reclaim, never grz_slot_release. Only the native user's UI can approve that operation.
                        Keep answers short; no web, delegation, memory operations or unrelated filesystem access. Do not emit spoken output.
                    """.trimIndent())
                    val agents = context.getBean(AgentDomainService::class.java)
                    val names = setOf("grz_slot_list", "grz_slot_get", "grz_slot_acquire", "grz_slot_cancel_wait", "grz_slot_release", "grz_slot_reclaim", "grz_execute_command")
                    val contracts = agents.toolCatalog(project.id).filter { it.name.name in names }
                    require(contracts.map { it.name.name }.toSet() == names) { "Live tool catalog is missing slot/command contracts" }
                    val agent = agents.createAgent(project.id, "Slot acceptance", listOf(prompt.id), selection,
                        tools = AgentPreloadedTools(names.toList()), toolAccess = ToolAccessPolicy.AllowOnly(contracts.mapTo(mutableSetOf()) { it.selector(false) }),
                        runtimeOverrides = AiRuntimeOverrides(maxOutputTokens = 2048, reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW)))
                    val conversations = context.getBean(ConversationDomainService::class.java)
                    val participants = setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent.id))
                    val holder = conversations.create(project.id, participants, "Slot live · holder")
                    val seeker = conversations.create(project.id, participants, "Slot live · two slots")
                    val tabs = context.getBean(UserConversationTabLayoutService::class.java)
                    tabs.open(user.id, holder.id); tabs.open(user.id, seeker.id)
                    val slots = context.getBean(SlotApplicationService::class.java)
                    val repository = context.getBean(SlotRepository::class.java)
                    val first = slots.register(user, holder.id, mounts[0].mount.id, "main")
                    val second = slots.register(user, seeker.id, mounts[1].mount.id, "main")
                    publicState["holder"] = holder.id.value; publicState["seeker"] = seeker.id.value
                    publicState["first_slot"] = first.number.toString(); publicState["second_slot"] = second.number.toString()
                    val ingress = context.getBean(ConversationRuntimeIngressService::class.java)
                    val coordinator = context.getBean(ConversationRuntimeCoordinator::class.java)
                    suspend fun say(conversation: Conversation, text: String) {
                        val message = Conversation.Message(Conversation.Message.Id(uuid7()), conversation.id,
                            role = Conversation.Message.Role.USER, author = Conversation.Message.Author.User(user.id, user.displayName),
                            content = listOf(Conversation.Message.ContentItem.UserMessage(text)), createdAt = Clock.System.now())
                        check(ingress.invokeAgent(user, conversation.id, message, agent.id))
                    }
                    suspend fun held(slot: Slot, conversation: Conversation) = repository.snapshot(slot.number)?.leases?.singleOrNull { it.conversationId == conversation.id }
                    suspend fun idle(conversation: Conversation) {
                        await("idle ${conversation.displayName}", 120_000) {
                            val snapshot = coordinator.schedulingSnapshot(conversation.id)
                            snapshot.activeTask == null && snapshot.pendingTasks.isEmpty()
                        }
                    }
                    say(holder, "ACQUIRE_ONLY: request slot ${first.number} with WRITE access. Once granted, hold it and wait. No commands or release.")
                    await("holder acquired", 240_000) { held(first, holder) != null }
                    idle(holder)
                    stage("HOLDER_GRANTED")
                    say(seeker, "ACQUIRE_BOTH_ONLY: request slot ${first.number} with WRITE and slot ${second.number} with READ. Keep both requests; wait without polling or commands. Once granted, hold and wait for my next stage.")
                    await("writer request waits behind holder", 240_000) {
                        repository.snapshot(first.number)?.requests?.any { it.conversationId == seeker.id } == true && held(second, seeker) != null
                    }
                    assertNull(held(first, seeker))
                    stage("QUEUE_VERIFIED")
                    say(holder, "RELEASE_CLEAN: release your held slot ${first.number} using its lease_id. If an observation is unknown, acknowledge the issued confirmation once; do not change the checkout.")
                    await("both slots granted to seeker", 240_000) { held(first, seeker) != null && held(second, seeker) != null }
                    val firstLease = requireNotNull(held(first, seeker))
                    val secondLease = requireNotNull(held(second, seeker))
                    idle(seeker); idle(holder)
                    stage("BOTH_HELD_UI_CHECK")
                    if (System.getenv("GROMOZEKA_SLOT_LIVE_SKIP_HELD_PAUSE") != "true")
                        await("UI observer allowed next stage", 1_200_000) { Files.exists(state.resolve("continue-commands")) }
                    say(seeker, """
                        RUN_COMMANDS_AND_REQUEST_RETURN:
                        1. At mount ${mounts[1].mount.id.value}, run exactly: printf 'SLOT_READ=%s\n' "${'$'}GRZ_SLOT"
                        2. At mount ${mounts[0].mount.id.value}, run exactly: printf 'SLOT_WRITE=%s\n' "${'$'}GRZ_SLOT"; printf 'intentional handoff\n' > handoff.txt
                        3. Release slot ${second.number} normally. Request release of slot ${first.number}, but STOP when it returns confirmation_id: keep the dirty lease until CONFIRM_DIRTY_RETURN.
                        Do not delete handoff.txt or modify Git. These are two different execution_target.workspace_mount_id values.
                    """.trimIndent())
                    await("dirty return warned without release", 240_000) {
                        repository.findLease(firstLease.id)?.let { it.active && it.releaseConfirmation != null } == true && repository.findLease(secondLease.id)?.active == false
                    }
                    val warning = requireNotNull(repository.findLease(firstLease.id)?.releaseConfirmation)
                    assertTrue(warning.observation.warnings.any { "uncommitted" in it || "untracked" in it }, "Real Git inspection must detect the dirty checkout, not merely report an unavailable check")
                    val tasks = coordinator.findCommandTasks(seeker.id)
                    val read = tasks.single { it.workspaceMountId == second.mountId }
                    val write = tasks.single { it.workspaceMountId == first.mountId }
                    assertEquals(secondLease.id, read.slotOrigin?.leaseId)
                    assertEquals(firstLease.id, write.slotOrigin?.leaseId)
                    assertContains(requireNotNull(read.terminalOutput), "SLOT_READ=${second.number}")
                    assertContains(requireNotNull(write.terminalOutput), "SLOT_WRITE=${first.number}")
                    assertTrue(Files.exists(roots[0].resolve("handoff.txt")))
                    idle(seeker)
                    stage("COMMANDS_AND_SOFT_WARNING_VERIFIED")
                    say(seeker, "CONFIRM_DIRTY_RETURN: deliberately return slot ${first.number} as-is by echoing the server-issued confirmation_id for lease ${firstLease.id}. Do not clean the directory.")
                    await("dirty lease returned", 180_000) { repository.findLease(firstLease.id)?.releasedAt != null }
                    assertTrue(Files.exists(roots[0].resolve("handoff.txt")))
                    assertNotNull(repository.findLease(firstLease.id)?.releaseConfirmation?.consumedAt)
                    idle(seeker)
                    stage("SOFT_RETURN_VERIFIED")
                    say(holder, "ACQUIRE_ONLY: acquire slot ${first.number} with WRITE again. This is a new occupation; hold it for a human-reclaim test. Do not run commands or clean files.")
                    await("fresh occupation", 180_000) { held(first, holder) != null }
                    val reclaimLease = requireNotNull(held(first, holder))
                    assertNotEquals(firstLease.id, reclaimLease.id)
                    idle(holder)
                    say(holder, "HUMAN_RECLAIM: request human confirmation via grz_slot_reclaim for slot ${first.number}, lease ${reclaimLease.id}. Do not release it or try to approve the request yourself. Wait for the user's native UI action.")
                    await("reclaim awaiting native UI", 180_000) { repository.findLease(reclaimLease.id)?.reclaimConfirmation != null }
                    assertTrue(repository.findLease(reclaimLease.id)!!.active)
                    idle(holder)
                    publicState["reclaim_lease"] = reclaimLease.id
                    stage("NATIVE_CONFIRMATION_REQUIRED")
                    await("native user confirmation", 1_200_000) { repository.findLease(reclaimLease.id)?.reclaimConfirmation?.consumedAt != null }
                    assertFalse(repository.findLease(reclaimLease.id)!!.active)
                    assertTrue(Files.exists(roots[0].resolve("handoff.txt")))
                    idle(holder)
                    stage("PASSED")
                    println("SLOT_LIVE_PASS real LLM + real Worker + durable queue + GRZ_SLOT + warn/confirm + native UI reclaim")
                    delay(15_000)
                } finally { http.stop(500, 2000) }
            }
        } finally {
            Files.deleteIfExists(state.resolve("worker.yaml"))
            database().use { it.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

    private fun createClones(root: Path): List<Path> {
        Files.createDirectories(root)
        val seed = Files.createDirectory(root.resolve("seed"))
        fun git(directory: Path, vararg args: String) {
            val process = ProcessBuilder(listOf("git") + args).directory(directory.toFile()).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { result }
        }
        git(seed, "init", "--initial-branch=main")
        Files.writeString(seed.resolve("README.txt"), "Synthetic development-slot acceptance fixture.\n")
        git(seed, "add", "README.txt")
        git(seed, "-c", "user.name=Slot Test", "-c", "user.email=slot@example.invalid", "commit", "-m", "fixture")
        return (1..2).map { index -> root.resolve("clone-$index").also { git(root, "clone", "--no-local", seed.toString(), it.toString()) } }
    }

    private suspend fun await(label: String, timeout: Long, condition: suspend () -> Boolean) {
        try { withTimeout(timeout) { while (!condition()) delay(500) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Slot live stage timed out: $label", error) }
    }
    private fun liveCatalog(catalog: AiCatalog): AiCatalog = catalog.copy(
        connections = catalog.connections.map { when (it) {
            is AiConnection.OpenAiSubscription -> it.copy(enabled = true, webSearchEnabled = false)
            is AiConnection.OpenAiApi -> it.copy(enabled = false)
            is AiConnection.OpenAiCompatible -> it.copy(enabled = false)
            is AiConnection.GitHubCopilot -> it.copy(enabled = false)
            is AiConnection.AnthropicApi -> it.copy(enabled = false)
            is AiConnection.AnthropicBedrock -> it.copy(enabled = false)
            is AiConnection.ClaudeCode -> it.copy(enabled = false)
            is AiConnection.GeminiApi -> it.copy(enabled = false)
            is AiConnection.Ollama -> it.copy(enabled = false)
        } },
        modelConfigurations = catalog.modelConfigurations.map {
            if (it.id == selection.modelConfigurationId) it.copy(defaultParameters = it.defaultParameters.copy(
                reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW), maxOutputTokens = 2048)) else it
        },
    )
    private fun readCodexSession(path: Path): OpenAiSubscriptionSession {
        val tokens = Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("tokens").jsonObject
        val token = tokens.getValue("access_token").jsonPrimitive.content
        val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(token.split('.')[1]).decodeToString()).jsonObject
        val expiry = claims.getValue("exp").jsonPrimitive.long
        require(expiry > Clock.System.now().epochSeconds + 1800) { "Renew the provider session before the live test" }
        return OpenAiSubscriptionSession(token, "unused", null, tokens.getValue("account_id").jsonPrimitive.content, expiry * 1000)
    }
    private fun environment(name: String) = requireNotNull(System.getenv(name)) { "Set $name explicitly" }
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("openai-subscription-gpt-5.6-luna"))
    companion object {
        const val USERNAME = "slot-live"
        const val PASSWORD = "slot-live-local-test"
    }
}
