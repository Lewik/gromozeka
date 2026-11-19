package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.projectedMessages
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

                val bytes = byteArrayOf(0, 1, 2, 3, 127)
                val at = Clock.System.now()
                val completed = operation.copy(
                    phase = AsyncToolOperation.Phase.SUCCEEDED, acknowledgedAt = at, claimedAt = at, startedAt = at,
                    executorSessionId = ConversationRuntimeServerSessionId("wiring-completion"), claimGeneration = 1,
                    completedAt = at, outcome = AsyncToolOutcome(listOf(ContentItem.ToolResult.Data.Base64Data(
                        java.util.Base64.getEncoder().encodeToString(bytes), Conversation.Message.MediaType.parse("application/octet-stream"), "result.bin")), isError = false),
                )
                val pair = context.getBean(AsyncToolOutcomeService::class.java).completionMessages(completed)
                val artifacts = context.getBean(ConversationArtifactApplicationService::class.java)
                val persisted = pair.map { artifacts.persistAndCommitMessageOutputs(conversation, owner.id, it) }
                val appender = context.getBean(ConversationRuntimeMessageAppender::class.java)
                DriverManager.getConnection(url, username, password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET search_path TO $schema")
                        statement.execute("""CREATE FUNCTION reject_completion_result() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN
                            IF NEW.id = ''${persisted[1].id.value}'' THEN RAISE EXCEPTION ''synthetic-pair-test-failure''; END IF;
                            RETURN NEW; END'""")
                        statement.execute("CREATE TRIGGER reject_completion_result BEFORE INSERT ON messages FOR EACH ROW EXECUTE FUNCTION reject_completion_result()")
                    }
                }
                val appendFailure = assertFailsWith<Exception> { appender.appendRuntimeMessages(conversation.id, persisted) }
                assertTrue(generateSequence(appendFailure as Throwable?) { it.cause }.any { it.message?.contains("synthetic-pair-test-failure") == true })
                DriverManager.getConnection(url, username, password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET search_path TO $schema")
                        statement.executeQuery("SELECT count(*) FROM messages WHERE id IN ('${persisted[0].id.value}','${persisted[1].id.value}')").use { rows ->
                            assertTrue(rows.next()); assertEquals(0, rows.getInt(1), "Failure on the second half must roll back the first half")
                        }
                        statement.executeQuery("SELECT count(*) FROM thread_messages WHERE message_id IN ('${persisted[0].id.value}','${persisted[1].id.value}')").use { rows ->
                            assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                        }
                        statement.execute("DROP TRIGGER reject_completion_result ON messages")
                        statement.execute("DROP FUNCTION reject_completion_result()")
                    }
                }
                assertEquals(persisted, appender.appendRuntimeMessages(conversation.id, persisted))
                assertTrue(appender.appendRuntimeMessages(conversation.id, persisted).isEmpty(), "A lost append ACK must not duplicate the pair")
                val history = context.getBean(ConversationHistoryReadService::class.java)
                val stored = persisted.map { history.message(conversation.id, it.id) }
                assertEquals(persisted, stored)
                val call = assertIs<ContentItem.ToolCall>(stored[0].content.single())
                val result = assertIs<ContentItem.ToolResult>(stored[1].content.single())
                assertNotEquals(origin.sourceToolCallId, call.id)
                assertEquals(call.id, result.toolUseId)
                val reference = result.result.filterIsInstance<ContentItem.ToolResult.Data.ArtifactData>().single().artifact
                assertContentEquals(bytes, artifacts.read(reference.id))
                assertTrue(stored.none { it.id in history.selectedIds(conversation.id, ConversationMessageSelection.PLAIN) })
                val prepared = artifacts.materializeSnapshot(conversation.id, stored)
                val projected = com.gromozeka.domain.model.ai.AiRuntimeRequest(emptyList(), prepared).projectedMessages()
                assertIs<ContentItem.ToolCall>(projected[0].content.single())
                assertIs<ContentItem.ToolResult>(projected[1].content.single())
                assertEquals(pair, context.getBean(AsyncToolOutcomeService::class.java).completionMessages(completed))
            }
        } finally {
            DriverManager.getConnection(url, username, password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }
}
