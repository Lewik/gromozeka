package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.remote.protocol.*
import com.gromozeka.server.testsupport.app.E2eTestConfigurationFilter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class AsyncToolServletWiringTest {
    @Test fun `full Servlet graph starts and Spring Worker request completion copies the canonical async outcome before projection ACK`() = runBlocking<Unit> {
        val schema = "async_servlet_${UUID.randomUUID().toString().replace("-", "")}"
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val username = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        val password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        val home = Files.createTempDirectory("async-servlet-home-")
        Files.writeString(home.resolve("settings.json"), Json.encodeToString(Settings.serializer(), Settings()))
        val properties = mapOf(
            "GROMOZEKA_MODE" to "test", "GROMOZEKA_HOME" to home.toString(), "user.home" to home.toString(),
            "logging.file.path" to home.resolve("logs").toString(),
            "gromozeka.postgres.jdbc-url" to url, "gromozeka.postgres.username" to username,
            "gromozeka.postgres.password" to password, "gromozeka.postgres.schema" to schema,
            "gromozeka.remote.host" to "127.0.0.1", "gromozeka.remote.port" to "0",
            "gromozeka.auth.secure-cookie" to "false", "gromozeka.telegram.enabled" to "false",
            "gromozeka.runtime.server.enabled" to "true", "gromozeka.runtime.worker.enabled" to "false",
            "spring.main.allow-circular-references" to "false", "logging.level.root" to "WARN",
        )
        val previous = properties.mapValues { (key, value) -> System.getProperty(key).also { System.setProperty(key, value) } }
        var context: ConfigurableApplicationContext? = null
        try {
            context = SpringApplicationBuilder(GromozekaServerApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .profiles("e2e")
                .properties(mapOf("server.forward-headers-strategy" to "none", "spring.servlet.multipart.enabled" to "false",
                    "spring.mvc.async.request-timeout" to "-1", "server.error.include-message" to "never",
                    "server.error.include-stacktrace" to "never", "spring.main.banner-mode" to "off"))
                .initializers(org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> {
                    it.beanFactory.registerSingleton("servletTestConfigurationFilter", E2eTestConfigurationFilter())
                })
                .run()
            val web = assertIs<ServletWebServerApplicationContext>(context)
            val port = assertNotNull(web.webServer).port
            assertTrue(port in 1..65535)
            assertIs<GatewayAiRequestResponseExecutionClient>(context.getBean(AiRequestResponseExecutionClient::class.java))
            assertIs<TargetedAiRuntimeProvider>(context.getBean(AiRuntimeProvider::class.java))
            assertIs<TargetedAiSubscriptionQuotaProvider>(context.getBean(AiSubscriptionQuotaProvider::class.java))
            assertNotNull(context.getBean(AsyncToolOperationService::class.java))
            assertNotNull(context.getBean(AsyncWorkerResultProjector::class.java))
            HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build().use { http ->
                val status = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/status"))
                    .timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, status.statusCode())
                assertEquals(JsonPrimitive(false), Json.parseToJsonElement(status.body()).jsonObject["initialized"])
            }
            verifyActualCompletionObserver(context)
        } finally {
            try { context?.close() } finally {
                previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
                try {
                    DriverManager.getConnection(url, username, password).use { connection ->
                        connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
                    }
                } finally {
                    Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }
    }

    private suspend fun verifyActualCompletionObserver(context: ConfigurableApplicationContext) {
        val authentication = context.getBean(AuthenticationService::class.java)
        val owner = authentication.createFirstUser(assertNotNull(context.getBean(FirstUserBootstrapToken::class.java).currentToken()),
            "servlet-owner", "Servlet owner", "synthetic-test-password".toCharArray(), "servlet-test").user
        val project = context.getBean(ProjectAccessService::class.java).create(owner.id, "Servlet projection")
        val conversation = context.getBean(ConversationDomainService::class.java)
            .create(project.id, setOf(Conversation.Participant.User(owner.id)), "Servlet projection")
        val now = Clock.System.now()
        val worker = ConversationRuntimeWorkerId("servlet-worker")
        context.getBean(WorkerAccessRepository::class.java).saveWorker(WorkerResource(worker, "Synthetic Worker", owner.id,
            runtimeWideAccess = false, status = WorkerResource.Status.ACTIVE, createdAt = now, updatedAt = now))
        val operationId = AsyncToolOperation.Id("servlet-canonical-operation")
        val call = ContentItem.ToolCall(ContentItem.ToolCall.Id("servlet-call"), ContentItem.ToolCall.Data("probe_tool", JsonObject(emptyMap())))
        val target = ConversationRuntimeTaskTarget.Worker(worker)
        val origin = AsyncToolOrigin(owner.id, project.id, conversation.id, conversation.currentThread, AgentDefinition.Id("not-invoked"),
            ConversationRuntimeTask.Id("servlet-source"), call.id, Conversation.Message.Id("servlet-source:result"), true, null)
        val bindings = mapOf(TOOL_CONTEXT_USER_ID to owner.id.value, TOOL_CONTEXT_PROJECT_ID to project.id.value,
            TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value, TOOL_CONTEXT_THREAD_ID to conversation.currentThread.value,
            TOOL_CONTEXT_AGENT_DEFINITION_ID to origin.agentDefinitionId.value, TOOL_CONTEXT_WORKER_ID to worker.value, "toolCallId" to call.id.value)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        val payload = json.encodeToString(WorkerToolExecutionRequest.serializer(), WorkerToolExecutionRequest(target, listOf(call), bindings)).encodeToByteArray()
        val request = WorkerGatewayMessage.Request(operationId.value, WorkerGatewayOperation.TOOL_EXECUTION, payload,
            WorkerRequestDelivery(now + 5.minutes, 300_000))
        val record = StoredWorkerRequest(request.id, worker, WorkerGatewayCodec.encode(request), now, now + 5.minutes,
            actorUserId = owner.id, projectId = project.id, executionDeadline = now + 10.minutes)
        val records = context.getBean(WorkerRequestRepository::class.java)
        val service = context.getBean(WorkerRequestService::class.java)
        val operations = context.getBean(AsyncToolOperationRepository::class.java)
        val sql = JdbcTemplate(context.getBean(javax.sql.DataSource::class.java))
        records.create(record)
        assertTrue(records.markDispatched(record.id, now))
        val structured = buildJsonObject { put("canonical", true) }
        val body = listOf<ContentItem.ToolResult.Data>(ContentItem.ToolResult.Data.Text("canonical async body"),
            ContentItem.ToolResult.Data.Base64Data("AAECAw==", Conversation.Message.MediaType.parse("application/octet-stream"), "result.bin"))
        val result = ContentItem.ToolResult(call.id, call.call.name, body)
        val response = WorkerGatewayMessage.Response(record.id, WorkerGatewayMessage.Response.Status.SUCCEEDED,
            json.encodeToString(WorkerToolExecutionResponse.serializer(), WorkerToolExecutionResponse(listOf(result), false,
                mapOf(call.id.value to structured))).encodeToByteArray())
        val canonical = WorkerGatewayCodec.encode(response)
        val executor = ConversationRuntimeServerSessionId("servlet-claim-owner")
        val reference = AsyncToolDispatchReference(operationId, executor, 1)
        sql.update("UPDATE worker_requests SET async_tool_json=?::jsonb WHERE id=?",
            json.encodeToString(AsyncToolDispatchReference.serializer(), reference), record.id)
        assertTrue(records.complete(worker, record.id, canonical, now))

        // Only the real async observer can reject this orphan before acknowledgement.
        // Other completion observers accept this valid non-launch success; an absent async
        // observer would incorrectly mark it projected, even though its bean might exist.
        assertNull(operations.find(operationId))
        service.maintain()
        assertNull(records.find(record.id)?.completionProjectedAt, "Spring WorkerRequestService skipped its async completion observer")
        assertContentEquals(canonical, records.find(record.id)?.response)

        val invocation = AsyncToolInvocation(origin, "probe_tool", "a".repeat(64), call, target, bindings)
        operations.admit(operationId, invocation, now)
        // Publish one already-claimed parent state atomically. Normal async maintenance must
        // not claim/execute this fixture between ACK and the pre-existing child marker.
        assertEquals(1, sql.update("""UPDATE async_tool_operations SET phase='CLAIMED', acknowledged_at=?, claimed_at=?,
            executor_session_id=?, claim_generation=1, lease_deadline=?, worker_request_created_at=? WHERE id=?""",
            now.timestamp(), now.timestamp(), executor.value, (now + 5.minutes).timestamp(), now.timestamp(), operationId.value))
        service.maintain()
        val projected = assertNotNull(operations.find(operationId))
        assertEquals(AsyncToolOperation.Phase.SUCCEEDED, projected.phase)
        assertEquals(AsyncToolOutcome(body, structured, false), projected.outcome)
        assertEquals(reference, projected.claimReference)
        assertNotNull(projected.startedAt)
        assertNotNull(records.find(record.id)?.completionProjectedAt)
        assertContentEquals(canonical, records.find(record.id)?.response)
        assertEquals(1, records.deleteCompletedBefore(now + 60.minutes, 256))
        assertNull(records.find(record.id))
        assertEquals(projected.outcome, operations.find(operationId)?.outcome)
    }

    private fun Instant.timestamp() = Timestamp.from(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()))
}
