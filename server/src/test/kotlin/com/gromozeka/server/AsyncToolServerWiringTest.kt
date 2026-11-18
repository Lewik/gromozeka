package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.server.testsupport.app.ServerTestHarness
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class AsyncToolServerWiringTest {
    @Test fun `isolated real Spring graph wires async without cycles and inspection enforces owner project and current ACL`() = runBlocking<Unit> {
        val schema = "async_wiring_${UUID.randomUUID().toString().replace("-", "")}"
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val username = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        val password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        try {
            ServerTestHarness(subscriptionSession = null, aiCatalogTransform = { it }, systemProperties = mapOf(
                "gromozeka.postgres.jdbc-url" to url, "gromozeka.postgres.username" to username,
                "gromozeka.postgres.password" to password, "gromozeka.postgres.schema" to schema,
                "gromozeka.telegram.enabled" to "false", "gromozeka.llm.cassette.mode" to "replay-only",
            )).use { harness ->
                val context = harness.context
                assertNotNull(context.getBean(AsyncToolOperationService::class.java))
                assertNotNull(context.getBean(AsyncToolAdmissionService::class.java))
                assertIs<GatewayWorkerToolExecutionClient>(context.getBean(AsyncWorkerToolExecutionClient::class.java))
                assertTrue(context.getBeansOfType(WorkerRequestCompletionObserver::class.java).values.any { it is AsyncWorkerResultProjector })
                val owner = context.getBean(AuthenticationService::class.java).createFirstUser(
                    assertNotNull(context.getBean(FirstUserBootstrapToken::class.java).currentToken()),
                    "async-owner", "Async owner", "synthetic-test-password".toCharArray(), "async-wiring").user
                val project = context.getBean(ProjectAccessService::class.java).create(owner.id, "Async")
                val conversations = context.getBean(ConversationDomainService::class.java)
                val conversation = conversations.create(project.id, setOf(Conversation.Participant.User(owner.id)), "Async owner")
                val origin = AsyncToolOrigin(owner.id, project.id, conversation.id, conversation.currentThread, AgentDefinition.Id("not-invoked"),
                    ConversationRuntimeTask.Id("source"), ContentItem.ToolCall.Id("call"), Conversation.Message.Id("source:result"), false, null)
                val invocation = AsyncToolInvocation(origin, "not-invoked", "a".repeat(64), ContentItem.ToolCall(origin.sourceToolCallId,
                    ContentItem.ToolCall.Data("not-invoked", JsonObject(emptyMap()))), ConversationRuntimeTaskTarget.Server,
                    mapOf(TOOL_CONTEXT_USER_ID to owner.id.value, TOOL_CONTEXT_PROJECT_ID to project.id.value,
                        TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value, TOOL_CONTEXT_THREAD_ID to conversation.currentThread.value,
                        TOOL_CONTEXT_AGENT_DEFINITION_ID to origin.agentDefinitionId.value, "toolCallId" to "call"))
                val repository = context.getBean(AsyncToolOperationRepository::class.java)
                val operation = repository.admit(AsyncToolOperation.Id("wiring-operation"), invocation, Clock.System.now())
                val tools = context.getBean(AsyncToolOperationToolContributor::class.java).callbacks.associateBy { it.definition.name }
                val input = """{"operation_id":"wiring-operation"}"""
                val bound = ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to owner.id.value, TOOL_CONTEXT_PROJECT_ID to project.id.value))
                val get = tools.getValue("grz_async_tool_get")
                val cancel = tools.getValue("grz_async_tool_cancel")
                assertEquals("ACCEPTED", Json.parseToJsonElement(get.call(input, bound)).jsonObject.getValue("status").jsonPrimitive.content)
                assertFailsWith<IllegalArgumentException> { get.call(input, bound.withValue(TOOL_CONTEXT_PROJECT_ID, "another-project")) }
                assertFailsWith<IllegalArgumentException> { get.call(input, bound.withValue(TOOL_CONTEXT_USER_ID, "another-actor")) }
                val memberships = context.getBean(ProjectMembershipRepository::class.java)
                memberships.save(assertNotNull(memberships.find(project.id, owner.id)).copy(role = ProjectMembership.Role.VIEWER))
                assertEquals(operation.id.value, Json.parseToJsonElement(get.call(input, bound)).jsonObject.getValue("operation_id").jsonPrimitive.content)
                assertFailsWith<ProjectAccessDeniedException> { cancel.call(input, bound) }
                assertNull(repository.find(operation.id)?.cancelRequestedAt)
                memberships.save(assertNotNull(memberships.find(project.id, owner.id)).copy(role = ProjectMembership.Role.OWNER))
                val cancelled = Json.parseToJsonElement(cancel.call(input, bound)).jsonObject
                assertEquals(JsonPrimitive(true), cancelled["cancellation_request_accepted"])
                assertEquals("ACCEPTED", cancelled.getValue("status").jsonPrimitive.content)
                assertNull(repository.find(operation.id)?.startedAt)
            }
        } finally {
            DriverManager.getConnection(url, username, password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }
}
