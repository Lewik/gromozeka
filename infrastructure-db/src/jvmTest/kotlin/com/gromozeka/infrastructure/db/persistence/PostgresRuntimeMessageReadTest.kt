package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ToolSequenceFacts
import com.gromozeka.domain.model.UserIdentity
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import com.gromozeka.domain.model.ai.projectedMessages
import com.gromozeka.domain.model.toolSequenceRepairBodyIds
import com.gromozeka.domain.repository.RecentVisibleMessage
import com.gromozeka.domain.repository.ThreadMessageReplacement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PostgresRuntimeMessageReadTest {
    @Test
    fun `response review continuation threshold does not inspect canonical message JSON`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val turnId = "review-turn"
            val random = java.util.Random(1)
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val runtimePayload = buildString(1_000_000) {
                repeat(1_000_000) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val runtime = f.message("$turnId:runtime").copy(
                role = Conversation.Message.Role.RUNTIME,
                author = Conversation.Message.Author.Runtime(),
                content = listOf(ContentItem.RuntimeJson(JsonPrimitive(runtimePayload))),
                providerMetadata = buildJsonObject { put("collaborationNext", "CONTINUE") },
            )
            val nonContinuation = f.message("$turnId:llm:0:assistant:0").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("done"))),
                providerMetadata = buildJsonObject { put("collaborationNext", "COMPLETE") },
            )
            val continuations = (1..4).map { index ->
                f.message("$turnId:llm:$index:assistant:0").copy(
                    role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("continue $index"))),
                    providerMetadata = buildJsonObject {
                        put("collaborationNext", "CONTINUE")
                        if (index == 1) put("unrelatedEscapedNul", "\u0000")
                    },
                )
            }
            val extraCandidates = (1..8).map { index ->
                f.message("$turnId:other:$index:assistant:0").copy(
                    role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("complete $index"))),
                    providerMetadata = buildJsonObject { put("collaborationNext", "COMPLETE") },
                )
            }
            (listOf(runtime, nonContinuation) + continuations.take(3) + extraCandidates).forEachIndexed { position, message ->
                f.save(message, thread, position)
            }

            val calibrationBefore = f.messageToastBlocks()
            f.readMessageBody(runtime.id)
            assertTrue(f.messageToastBlocks() > calibrationBefore, "The Runtime body must use measured TOAST storage")

            suspend fun assertThreshold(expected: Boolean) {
                var result = false
                val toastBefore = f.messageToastBlocks()
                val measurement = f.measure {
                    result = f.links.hasAtLeastResponseReviewContinuations(thread, turnId, requiredCount = 4)
                }
                assertEquals(expected, result)
                assertEquals(0L, f.messageToastBlocks() - toastBefore)
                assertEquals(0L, measurement.bodies)
                assertEquals(1L, measurement.rows)
                assertContains(measurement.queries.single(), "response_review_continuation")
                assertContains(measurement.queries.single(), "LIMIT ?")
                assertFalse(measurement.queries.single().contains("message_json"))
                assertFalse(measurement.queries.single().contains("jsonb"))
            }

            assertThreshold(false)
            f.save(continuations.last(), thread, 13)
            assertThreshold(true)
        }
    }

    @Test
    fun `supplemental JSON filters do not read Runtime bodies`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val random = java.util.Random(0)
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val runtimePayload = buildString(1_000_000) {
                repeat(1_000_000) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val runtime = f.message("runtime").copy(
                role = Conversation.Message.Role.RUNTIME,
                author = Conversation.Message.Author.Runtime(),
                content = listOf(ContentItem.RuntimeJson(JsonPrimitive(runtimePayload))),
            )
            val plain = f.message("plain")
            val callId = ContentItem.ToolCall.Id("call")
            val thinking = f.message("thinking").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.Thinking("private reasoning")),
            )
            val call = f.message("call").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ToolCall(callId, ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))),
            )
            val result = f.message("result").copy(
                content = listOf(ContentItem.ToolResult(
                    callId,
                    "tool",
                    listOf(ContentItem.ToolResult.Data.Text("done")),
                )),
            )
            listOf(runtime, plain, thinking, call, result).forEachIndexed { position, message ->
                f.save(message, thread, position)
            }

            val calibrationBefore = f.messageToastBlocks()
            f.readMessageBody(runtime.id)
            assertTrue(f.messageToastBlocks() > calibrationBefore, "The Runtime body must use measured TOAST storage")

            lateinit var results: List<com.gromozeka.domain.repository.PositionedConversationMessage>
            val resultsToastBefore = f.messageToastBlocks()
            val resultsRead = f.measure { results = f.history.toolResults(thread, setOf(callId.value)) }
            assertEquals(listOf(result.id), results.map { it.message.id })
            assertEquals(0L, f.messageToastBlocks() - resultsToastBefore)
            assertEquals(1L, resultsRead.bodies)

            suspend fun selected(selection: ConversationMessageSelection): Pair<List<Conversation.Message.Id>, ReadMeasurement> {
                lateinit var ids: List<Conversation.Message.Id>
                val toastBefore = f.messageToastBlocks()
                val read = f.measure { ids = f.history.selectedIds(thread, selection) }
                assertEquals(0L, f.messageToastBlocks() - toastBefore, selection.name)
                if (selection != ConversationMessageSelection.ALL) {
                    val sql = read.queries.single()
                    val roleFilter = sql.indexOf("m.role <> 'RUNTIME'")
                    val bodyInspection = sql.indexOf("m.message_json::jsonb")
                    assertTrue(roleFilter >= 0 && bodyInspection > roleFilter, sql)
                }
                return ids to read
            }

            assertEquals(listOf(thinking.id), selected(ConversationMessageSelection.THINKING).first)
            assertEquals(listOf(call.id, result.id), selected(ConversationMessageSelection.TOOL).first)
            assertEquals(listOf(plain.id), selected(ConversationMessageSelection.PLAIN).first)
            val (all, allRead) = selected(ConversationMessageSelection.ALL)
            assertEquals(listOf(runtime.id, plain.id, thinking.id, call.id, result.id), all)
            val resultsSql = resultsRead.queries.single()
            val resultsRoleFilter = resultsSql.indexOf("m.role <> 'RUNTIME'")
            val resultsBodyInspection = resultsSql.indexOf("m.message_json::jsonb")
            assertTrue(resultsRoleFilter >= 0 && resultsBodyInspection > resultsRoleFilter, resultsSql)
            assertEquals(0L, allRead.bodies)
        }
    }

    @Test
    fun `recent visible history is bounded in rows and characters without message bodies`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val visible = (0 until 12).map { index ->
                f.message("visible-$index").let { message ->
                    if (index == 11) message.copy(content = listOf(ContentItem.UserMessage("V".repeat(5_000))))
                    else message
                }
            }
            visible.forEachIndexed { position, message -> f.save(message, thread, position) }
            val runtime = f.message("runtime").copy(
                role = Conversation.Message.Role.RUNTIME,
                author = Conversation.Message.Author.Runtime(),
                content = listOf(ContentItem.RuntimeJson(JsonPrimitive("RUNTIME_${"x".repeat(500_000)}"))),
            )
            val blank = f.message("blank").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText(" \n\t"))),
            )
            val source = f.message("source").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("Answer"))),
                providerMetadata = buildJsonObject { put("opaque_replay", "x".repeat(500_000)) },
            )
            f.save(runtime, thread, 12)
            f.save(blank, thread, 13)
            f.save(source, thread, 14)

            lateinit var selected: List<RecentVisibleMessage>
            val measurement = f.measure {
                selected = f.links.recentVisibleMessageTextThrough(
                    thread,
                    source.id,
                    limit = 10,
                    maxCharacters = 4_000,
                )
            }

            assertEquals(visible.drop(3).map { it.id } + source.id, selected.map { it.id })
            assertEquals("Answer", selected.last().text)
            assertEquals("V".repeat(4_000), selected.single { it.id == visible.last().id }.text)
            assertEquals(0L, measurement.bodies)
            assertEquals(0L, measurement.bytes)
            assertEquals(10L, measurement.rows)
            assertTrue(measurement.queries.single().contains("LIMIT ?", ignoreCase = true))
            assertTrue(measurement.queries.single().contains("left(m.message_text, ?)", ignoreCase = true))
            assertFalse(measurement.queries.single().contains("message_json", ignoreCase = true))
        }
    }

    @Test
    fun `model context through a source uses the latest compact and excludes later history`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val archive = (0 until 100).map { index -> f.message("archive-$index") }
            archive.forEachIndexed { position, message -> f.save(message, thread, position) }
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    ContentItem.ContextCompactionResult.Payload.ReadableSummary("Earlier conversation"),
                    ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )),
            )
            val tail = f.message("tail")
            val source = f.message("source").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("Answer"))),
            )
            val later = f.message("later")
            listOf(checkpoint, tail, source, later).forEachIndexed { offset, message ->
                f.save(message, thread, archive.size + offset)
            }

            lateinit var selected: com.gromozeka.domain.repository.ThreadContextMessages
            val measurement = f.measure {
                selected = f.links.getContextMessagesThrough(thread, source.id, nativeProvider = null)
            }
            val projected = com.gromozeka.domain.model.ai.AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = selected.messages,
            ).projectedMessages()

            assertEquals(archive.size + 3, selected.sourceMessageCount)
            assertEquals(listOf(checkpoint.id, tail.id, source.id), projected.map { it.id })
            assertFalse(selected.messages.any { it.id == later.id })
            assertEquals(3L, measurement.bodies)
        }
    }

    @Test
    fun `tool sequence validation stays body-free and ignores history before the latest full checkpoint`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            fun call(id: String, managed: Boolean = false) = f.message("$id-call").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ToolCall(
                    ContentItem.ToolCall.Id(id),
                    ContentItem.ToolCall.Data(id, JsonObject(emptyMap())),
                )),
                providerMetadata = JsonObject(if (managed) {
                    mapOf(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to JsonPrimitive(true))
                } else {
                    emptyMap()
                }),
            )
            fun result(id: String, managed: Boolean = false) = f.message("$id-result").copy(
                role = if (managed) Conversation.Message.Role.ASSISTANT else Conversation.Message.Role.USER,
                content = listOf(ContentItem.ToolResult(
                    ContentItem.ToolCall.Id(id),
                    id,
                    listOf(ContentItem.ToolResult.Data.Text("result")),
                )),
                providerMetadata = JsonObject(if (managed) {
                    mapOf(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to JsonPrimitive(true))
                } else {
                    emptyMap()
                }),
            )
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"),
                    ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )),
            )
            val validTail = listOf(
                call("ordinary"),
                call("provider", managed = true),
                result("provider", managed = true),
                result("ordinary"),
            )
            (listOf(call("old-orphan"), checkpoint) + validTail).forEachIndexed { position, message ->
                f.save(message, thread, position)
            }

            lateinit var valid: List<com.gromozeka.domain.model.ToolSequenceMessageHeader>
            val validRead = f.measure { valid = f.links.invalidToolSequenceWindow(thread) }
            assertTrue(valid.isEmpty())
            assertEquals(0L, validRead.bodies)

            val orphan = call("new-orphan")
            val interruption = f.message("interruption")
            f.save(orphan, thread, 6)
            f.save(interruption, thread, 7)

            lateinit var invalid: List<com.gromozeka.domain.model.ToolSequenceMessageHeader>
            val invalidRead = f.measure { invalid = f.links.invalidToolSequenceWindow(thread) }
            assertEquals((2..7).toList(), invalid.map { it.position })
            assertTrue(invalid.none { it.id == checkpoint.id })
            assertEquals(listOf(orphan.id), invalid.filter { it.requiresRepair }.map { it.id })
            assertEquals(0L, invalidRead.bodies)
        }
    }

    @Test
    fun `tool sequence repair hydrates only the final orphan body`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            fun call(id: String) = f.message("$id-call").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ToolCall(
                    ContentItem.ToolCall.Id(id),
                    ContentItem.ToolCall.Data(id, JsonObject(emptyMap())),
                )),
            )
            fun largeResult(id: String) = f.message("$id-result").copy(
                role = Conversation.Message.Role.USER,
                content = listOf(ContentItem.ToolResult(
                    ContentItem.ToolCall.Id(id),
                    id,
                    listOf(ContentItem.ToolResult.Data.Text("result-$id-${"x".repeat(200_000)}")),
                )),
            )
            val valid = (0 until 12).flatMap { index ->
                val id = "valid-$index"
                listOf(call(id), largeResult(id))
            }
            val orphan = call("orphan")
            (valid + orphan).forEachIndexed { position, message -> f.save(message, thread, position) }

            lateinit var window: List<com.gromozeka.domain.model.ToolSequenceMessageHeader>
            val headerRead = f.measure { window = f.links.invalidToolSequenceWindow(thread) }
            val bodyIds = toolSequenceRepairBodyIds(window)
            lateinit var bodies: List<Conversation.Message>
            val bodyRead = f.measure { bodies = f.links.compactionBodies(thread, bodyIds) }

            assertEquals(0L, headerRead.bodies)
            assertEquals(listOf(orphan.id), window.filter { it.requiresRepair }.map { it.id })
            assertEquals(listOf(orphan.id), bodyIds)
            assertEquals(listOf(orphan.id), bodies.map { it.id })
            assertEquals(1L, bodyRead.bodies)
            assertTrue(bodyRead.bytes < 100_000)
        }
    }

    @Test
    fun `Runtime history is hydrated like ordinary messages while user mode stays sparse`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val writable = f.message("writable").copy(instructions = listOf(
                Conversation.Message.Instruction.UserInstruction("mode_writable", "Writable", "Allow changes"),
            ))
            val runtime = f.message("runtime").copy(
                role = Conversation.Message.Role.RUNTIME,
                author = Conversation.Message.Author.Runtime(),
                content = listOf(ContentItem.RuntimeJson(kotlinx.serialization.json.JsonPrimitive(
                    "RUNTIME_STATE_${"x".repeat(300_000)}",
                ))),
            )
            val readonly = f.message("readonly").copy(instructions = listOf(
                Conversation.Message.Instruction.UserInstruction("mode_readonly", "Readonly", "No changes"),
            ))
            listOf(writable, runtime, readonly).forEachIndexed { index, message -> f.save(message, thread, index) }

            lateinit var visible: List<Conversation.Message>
            val historyRead = f.measure { visible = f.links.readArchiveForTest(thread) }
            lateinit var mode: String
            val modeRead = f.measure {
                mode = checkNotNull(f.history.latestUserInstructionId(
                    thread,
                    setOf("mode_readonly", "mode_writable"),
                ))
            }

            assertEquals(listOf(writable, runtime, readonly), visible)
            assertEquals(3L, historyRead.bodies)
            assertTrue(historyRead.bytes > 300_000)
            assertEquals("mode_readonly", mode)
            assertEquals(0L, modeRead.bodies)
        }
    }

    @Test
    fun `user mode metadata tolerates escaped NUL and ignores a later agent-authored override`() = runBlocking {
        withFixture(target = "97") { f ->
            val thread = f.conversation.currentThread
            val writable = f.message("writable").copy(instructions = listOf(
                Conversation.Message.Instruction.UserInstruction("mode_writable", "Writable", "Allow changes"),
            ))
            val readonly = f.message("readonly").copy(
                content = listOf(ContentItem.UserMessage("read-only\u0000request")),
                instructions = listOf(
                    Conversation.Message.Instruction.UserInstruction("mode_readonly", "Readonly", "No changes"),
                ),
            )
            val agentWritable = f.message("agent-writable").copy(
                author = Conversation.Message.Author.Agent(
                    com.gromozeka.domain.model.AgentDefinition.Id("peer"),
                    "Peer",
                ),
                instructions = listOf(
                    Conversation.Message.Instruction.UserInstruction("mode_writable", "Writable", "Allow changes"),
                ),
            )
            listOf(writable, readonly, agentWritable).forEachIndexed { index, message ->
                f.insertRaw(message, index)
            }
            assertNull(f.metadata(readonly.id))
            f.migrate()
            assertEquals(listOf("mode_readonly"), checkNotNull(f.metadata(readonly.id)).userInstructionIds)
            assertTrue(checkNotNull(f.metadata(agentWritable.id)).agentAuthored)
            assertEquals(readonly, checkNotNull(f.history.message(thread, readonly.id)).message)

            lateinit var mode: String
            val measurement = f.measure {
                mode = checkNotNull(f.history.latestUserInstructionId(
                    thread,
                    setOf("mode_readonly", "mode_writable"),
                ))
            }

            assertEquals("mode_readonly", mode)
            assertEquals(0L, measurement.bodies)
            assertEquals(0L, measurement.bytes)
        }
    }

    @Test
    fun `context metadata rejects NUL in a user instruction ID`() = runBlocking {
        withFixture { f ->
            val message = f.message("invalid-context-metadata").copy(
                instructions = listOf(
                    Conversation.Message.Instruction.UserInstruction("mode\u0000readonly", "Readonly", "No changes"),
                ),
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                f.save(message, f.conversation.currentThread, 0)
            }

            assertContains(checkNotNull(failure.message), "contains NUL")
            assertContains(checkNotNull(failure.message), message.id.value)
        }
    }

    @Test
    fun `V98 rejects legacy NUL in a user instruction ID without normalizing it`() = runBlocking {
        withFixture(target = "97") { f ->
            val message = f.message("legacy-invalid-context-metadata").copy(
                instructions = listOf(
                    Conversation.Message.Instruction.UserInstruction("mode\u0000readonly", "Readonly", "No changes"),
                ),
            )
            f.insertRaw(message, 0)
            assertNull(f.metadata(message.id))

            val failure = assertFails { f.migrate() }
            val details = generateSequence(failure as Throwable?) { it.cause }.joinToString("\n") { it.toString() }
            assertContains(details, "contains NUL")
            assertContains(details, message.id.value)
        }
    }

    @Test
    fun `large Runtime escaped NUL instruction remains body-free`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val writable = f.message("writable").copy(instructions = listOf(
                Conversation.Message.Instruction.UserInstruction("mode_writable", "Writable", "Allow changes"),
            ))
            val random = java.util.Random(2)
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val runtimePayload = buildString(1_000_001) {
                repeat(500_000) { append(alphabet[random.nextInt(alphabet.length)]) }
                append('\u0000')
                repeat(500_000) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val runtime = f.message("runtime-nul").copy(
                role = Conversation.Message.Role.RUNTIME,
                author = Conversation.Message.Author.Runtime(),
                content = listOf(ContentItem.RuntimeJson(JsonPrimitive(runtimePayload))),
                instructions = listOf(
                    Conversation.Message.Instruction.UserInstruction("mode_readonly", "Readonly", "No changes"),
                ),
            )
            f.save(writable, thread, 0)
            f.save(runtime, thread, 1)
            assertEquals(listOf("mode_readonly"), checkNotNull(f.metadata(runtime.id)).userInstructionIds)

            val calibrationBefore = f.messageToastBlocks()
            f.readMessageBody(runtime.id)
            assertTrue(f.messageToastBlocks() > calibrationBefore, "The Runtime body must use measured TOAST storage")

            lateinit var mode: String
            val toastBefore = f.messageToastBlocks()
            val measurement = f.measure {
                mode = checkNotNull(f.history.latestUserInstructionId(
                    thread,
                    setOf("mode_readonly", "mode_writable"),
                ))
            }

            assertEquals("mode_readonly", mode)
            assertEquals(0L, measurement.bodies)
            assertEquals(0L, measurement.bytes)
            assertEquals(0L, f.messageToastBlocks() - toastBefore)
        }
    }

    @Test
    fun `client point read returns the full Runtime body`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val runtime = f.message("runtime-point").copy(
                role = Conversation.Message.Role.RUNTIME,
                author = Conversation.Message.Author.Runtime(),
                content = listOf(ContentItem.RuntimeJson(kotlinx.serialization.json.JsonPrimitive(
                    "RUNTIME_STATE_${"z".repeat(1_000_000)}",
                ))),
            )
            f.save(runtime, thread, 0)

            lateinit var point: com.gromozeka.domain.repository.PositionedConversationMessage
            val measurement = f.measure {
                point = checkNotNull(f.history.clientMessage(thread, runtime.id))
            }

            assertEquals(runtime, point.message)
            assertEquals(1L, measurement.bodies)
            assertTrue(measurement.bytes > 1_000_000)
        }
    }

    @Test
    fun `raw external context reads Runtime messages without special pruning`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            fun runtime(id: String, text: String): Conversation.Message = f.message(id).copy(
                    role = Conversation.Message.Role.RUNTIME,
                    author = Conversation.Message.Author.Runtime(),
                    content = listOf(ContentItem.RuntimeJson(kotlinx.serialization.json.JsonPrimitive(text))),
                )
            val archived = f.message("archived")
            val oldBaseline = runtime("old-baseline", "OLD_RUNTIME_${"x".repeat(300_000)}")
            val oldDelta = runtime("old-delta", "OLD_RUNTIME_${"y".repeat(300_000)}")
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"),
                    ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )),
            )
            val current = runtime("current", "CURRENT_RUNTIME")
            val tail = f.message("tail")
            listOf(archived, oldBaseline, oldDelta, checkpoint, current, tail).forEachIndexed { index, message ->
                f.save(message, thread, index)
            }

            lateinit var selected: List<Conversation.Message>
            val measurement = f.measure { selected = f.links.readArchiveForTest(thread) }

            assertEquals(listOf(archived, oldBaseline, oldDelta, checkpoint, current, tail), selected)
            assertEquals(6L, measurement.bodies)
            assertTrue(measurement.bytes > 600_000)
        }
    }

    @Test
    fun `sparse model context keeps ordinary Runtime bodies`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            fun runtime(id: String, text: String): Conversation.Message = f.message(id).copy(
                    role = Conversation.Message.Role.RUNTIME,
                    author = Conversation.Message.Author.Runtime(),
                    content = listOf(ContentItem.RuntimeJson(kotlinx.serialization.json.JsonPrimitive(text))),
                )
            val root = f.message("root")
            val oldBaseline = runtime("old-baseline", "OLD_RUNTIME_${"x".repeat(300_000)}")
            val oldDelta = runtime("old-delta", "OLD_RUNTIME_${"y".repeat(300_000)}")
            val currentBaseline = runtime("current-baseline", "current")
            val currentDelta = runtime("current-delta", "current-delta")
            val tail = f.message("tail")
            val history = listOf(root, oldBaseline, oldDelta, currentBaseline, currentDelta, tail)
            history.forEachIndexed { index, message -> f.save(message, thread, index) }

            lateinit var selected: com.gromozeka.domain.repository.ThreadContextMessages
            val measurement = f.measure { selected = f.links.getContextMessagesByThread(thread, null) }

            assertEquals(history, selected.messages)
            assertEquals(history.size, selected.sourceMessageCount)
            assertEquals(6L, measurement.bodies)
            assertTrue(measurement.bytes > 600_000)
        }
    }

    @Test
    fun `timeline paging returns Runtime bodies like other rows`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val first = f.message("visible-first")
            f.save(first, thread, 0)
            List(3) { index ->
                f.message("runtime-observation-$index").copy(
                    role = Conversation.Message.Role.RUNTIME,
                    author = Conversation.Message.Author.Runtime(),
                    content = listOf(ContentItem.RuntimeJson(kotlinx.serialization.json.JsonPrimitive(
                        "RUNTIME_STATE_${"x".repeat(250_000)}",
                    ))),
                ).also { f.save(it, thread, index + 1) }
            }
            val last = f.message("visible-last")
            f.save(last, thread, 4)

            lateinit var page: List<com.gromozeka.domain.repository.PositionedConversationMessage>
            val measurement = f.measure {
                page = f.history.page(thread, before = null, after = null, limit = 2)
            }

            assertEquals(listOf("runtime-observation-2", last.id.value), page.map { it.message.id.value })
            assertEquals(2L, measurement.bodies)
            assertTrue(measurement.bytes > 250_000)
            assertEquals(Conversation.Message.Role.RUNTIME, page.first().message.role)
            assertIs<Conversation.Message.Author.Runtime>(page.first().message.author)
        }
    }

    @Test
    fun `V93 upgrades an existing V92 database after the batched legacy header backfill`() = runBlocking {
        withFixture(target = "90") { f ->
            val thread = f.conversation.currentThread
            f.source.connection.use { connection ->
                connection.prepareStatement("""
                    INSERT INTO messages(id, conversation_id, original_ids_json, role, created_at, search_text, message_json)
                    SELECT 'archive-' || i, ?, '[]', 'USER', now(), '', replace(?, 'SEED_ID', 'archive-' || i)
                    FROM generate_series(1, 16000) AS i
                """.trimIndent()).use {
                    it.setString(1, f.conversation.id.value)
                    it.setString(2, Json.encodeToString(f.message("SEED_ID")))
                    it.executeUpdate()
                }
                connection.prepareStatement("""
                    INSERT INTO thread_messages(thread_id, message_id, position)
                    SELECT ?, 'archive-' || i, i - 1 FROM generate_series(1, 16000) AS i
                """.trimIndent()).use { it.setString(1, thread.value); it.executeUpdate() }
                val checkpoint = f.message("archive-2").copy(
                    role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(ContentItem.ContextCompactionResult(
                        payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("Handoff"),
                        origin = ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                        strategy = ContentItem.ContextCompactionResult.Strategy.COMPACT,
                        sourceMessageIds = listOf(Conversation.Message.Id("archive-1")),
                        promptTemplate = ContentItem.ContextCompactionResult.PromptTemplateReference(
                            "gromozeka.message-compaction.compact", 2,
                        ),
                        coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
                    )),
                )
                val legacyCheckpoint = Json.encodeToString(checkpoint)
                    .replace("\"strategy\":\"COMPACT\"", "\"strategy\":\"SUMMARIZE\"")
                    .replace(
                        "\"id\":\"gromozeka.message-compaction.compact\"",
                        "\"id\":\"gromozeka.message-compaction.summarize\"",
                    )
                connection.prepareStatement("UPDATE messages SET role = 'ASSISTANT', message_json = ? WHERE id = 'archive-2'").use {
                    it.setString(1, legacyCheckpoint)
                    assertEquals(1, it.executeUpdate())
                }
                val fullCheckpoint = f.message("archive-15001").copy(
                    role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(ContentItem.ContextCompactionResult(
                        payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("Earlier history"),
                        origin = ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                        strategy = ContentItem.ContextCompactionResult.Strategy.COMPACT,
                        sourceMessageIds = (1..15_000).map { Conversation.Message.Id("archive-$it") },
                        coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                    )),
                )
                val fullDocument = Json.parseToJsonElement(Json.encodeToString(fullCheckpoint)).jsonObject
                val fullContent = fullDocument.getValue("content").jsonArray.single().jsonObject
                val legacyFullJson = JsonObject(fullDocument + ("content" to JsonArray(listOf(
                    JsonObject(fullContent + ("migrationCanary" to buildJsonObject { put("preserved", true) })),
                )))).toString()
                connection.prepareStatement("UPDATE messages SET role = 'ASSISTANT', message_json = ? WHERE id = 'archive-15001'").use {
                    it.setString(1, legacyFullJson)
                    assertEquals(1, it.executeUpdate())
                }
            }
            f.migrate("92")
            f.source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("""
                        SELECT count(*), count(DISTINCT xmin::text)
                        FROM messages
                        WHERE id LIKE 'archive-%' AND compaction_header IS NOT NULL
                    """.trimIndent()).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(16_000, rows.getInt(1))
                        assertEquals(16, rows.getInt(2), "V92 must commit each 1000-message backfill batch separately")
                    }
                    val oldHeader = statement.executeQuery(
                        "SELECT compaction_header FROM messages WHERE id = 'archive-15001'",
                    ).use { rows ->
                        assertTrue(rows.next())
                        Json.decodeFromString<com.gromozeka.domain.model.CompactionMessageHeader>(rows.getString(1))
                    }
                    val oldCoverage = oldHeader.coverage.single().copy(
                        sourceMessageIds = (1..15_000).map { Conversation.Message.Id("archive-$it") },
                    )
                    connection.prepareStatement(
                        "UPDATE messages SET compaction_header = ? WHERE id = 'archive-15001'",
                    ).use {
                        it.setString(1, Json.encodeToString(oldHeader.copy(coverage = listOf(oldCoverage))))
                        assertEquals(1, it.executeUpdate())
                    }
                    statement.executeQuery("SELECT message_json, compaction_header FROM messages WHERE id = 'archive-15001'").use { rows ->
                        assertTrue(rows.next())
                        val checkpoint = Json { ignoreUnknownKeys = true }.decodeFromString<Conversation.Message>(rows.getString(1))
                            .content.single() as ContentItem.ContextCompactionResult
                        assertEquals(15_000, checkpoint.sourceMessageIds.size)
                        assertNull(checkpoint.sourceBoundaryMessageId)
                        val header = Json.decodeFromString<com.gromozeka.domain.model.CompactionMessageHeader>(rows.getString(2))
                        assertEquals(15_000, header.coverage.single().sourceMessageIds.size)
                    }
                }
            }
            f.migrate()
            f.source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT message_json, compaction_header FROM messages WHERE id = 'archive-2'").use { rows ->
                        assertTrue(rows.next())
                        val messageJson = rows.getString(1)
                        assertFalse(messageJson.contains("\"strategy\":\"SUMMARIZE\""), messageJson)
                        assertFalse(messageJson.contains("gromozeka.message-compaction.summarize"), messageJson)
                        val checkpoint = Json.decodeFromString<Conversation.Message>(messageJson)
                            .content.single() as ContentItem.ContextCompactionResult
                        assertEquals(ContentItem.ContextCompactionResult.Strategy.COMPACT, checkpoint.strategy)
                        assertEquals("gromozeka.message-compaction.compact", checkpoint.promptTemplate?.id)
                        assertEquals(
                            Conversation.Message.Id("archive-2"),
                            Json.decodeFromString<com.gromozeka.domain.model.CompactionMessageHeader>(rows.getString(2)).id,
                        )
                    }
                    statement.executeQuery("SELECT message_json, compaction_header FROM messages WHERE id = 'archive-15001'").use { rows ->
                        assertTrue(rows.next())
                        val migratedJson = rows.getString(1)
                        val migratedContent = Json.parseToJsonElement(migratedJson).jsonObject
                            .getValue("content").jsonArray.single().jsonObject
                        assertEquals(buildJsonObject { put("preserved", true) }, migratedContent["migrationCanary"])
                        val checkpoint = Json { ignoreUnknownKeys = true }.decodeFromString<Conversation.Message>(migratedJson)
                            .content.single() as ContentItem.ContextCompactionResult
                        assertTrue(checkpoint.sourceMessageIds.isEmpty())
                        assertEquals(Conversation.Message.Id("archive-15000"), checkpoint.sourceBoundaryMessageId)
                        assertEquals(ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS, checkpoint.coverage)
                        val header = Json.decodeFromString<com.gromozeka.domain.model.CompactionMessageHeader>(rows.getString(2))
                        assertTrue(header.coverage.single().coversAllPrevious)
                        assertTrue(header.coverage.single().sourceMessageIds.isEmpty())
                    }
                }
            }
            val ids = listOf(Conversation.Message.Id("archive-16000"), Conversation.Message.Id("archive-3"))
            val headers = f.measure {
                val all = f.links.compactionWindowHeaders(thread)
                assertEquals(1_000, all.size)
                assertEquals(ids.first(), all.last().id)
                assertEquals("archive-16000".length.toLong(), all.last().characters)
            }
            assertEquals(0, headers.bodies)
            val selected = f.measure { assertEquals(ids, f.links.compactionBodies(thread, ids).map { it.id }) }
            assertEquals(2, selected.bodies)
        }
    }

    @Test
    fun `V97 backfills canonical tool sequence facts including imported rows without headers`() = runBlocking {
        withFixture(target = "96") { f ->
            val bulkMessages = 2_001
            f.source.connection.use { connection ->
                connection.prepareStatement("""
                    INSERT INTO messages(id, conversation_id, original_ids_json, role, created_at, search_text, message_json)
                    SELECT 'v97-bulk-' || lpad(i::text, 5, '0'), ?, '[]', 'USER', now(), '',
                        replace(?, 'V97_SEED_ID', 'v97-bulk-' || lpad(i::text, 5, '0'))
                    FROM generate_series(1, ?) AS i
                """.trimIndent()).use {
                    it.setString(1, f.conversation.id.value)
                    it.setString(2, Json.encodeToString(f.message("V97_SEED_ID")))
                    it.setInt(3, bulkMessages)
                    assertEquals(bulkMessages, it.executeUpdate())
                }
            }
            val call = f.message("provider-call").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ToolCall(
                    ContentItem.ToolCall.Id("provider"),
                    ContentItem.ToolCall.Data("web.run", JsonObject(emptyMap())),
                )),
                providerMetadata = JsonObject(mapOf(
                    AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to JsonPrimitive(true),
                )),
            )
            val result = f.message("provider-result").copy(
                content = listOf(ContentItem.ToolResult(
                    ContentItem.ToolCall.Id("provider"),
                    "web.run",
                    listOf(ContentItem.ToolResult.Data.Text("done")),
                )),
            )
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"),
                    ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                    sourceBoundaryMessageId = result.id,
                )),
            )
            listOf(call, result, checkpoint).forEachIndexed { position, message ->
                f.insertRaw(message, position)
            }

            f.migrate()

            f.source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*), count(DISTINCT xmin::text) FROM messages").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(bulkMessages + 3, rows.getInt(1))
                        assertEquals(3, rows.getInt(2), "V97 must commit three cursor-advanced backfill batches")
                    }
                    statement.executeQuery("""
                        SELECT id, tool_call_ids, tool_result_ids, provider_managed_tool,
                            tool_results_only, full_compaction_anchor
                        FROM messages
                        ORDER BY id
                    """.trimIndent()).use { rows ->
                        val facts = buildMap {
                            while (rows.next()) {
                                fun ids(column: Int) = (rows.getArray(column).array as Array<*>).map { it as String }
                                put(rows.getString(1), listOf(
                                    ids(2), ids(3), rows.getBoolean(4), rows.getBoolean(5), rows.getBoolean(6),
                                ))
                            }
                        }
                        assertEquals(listOf(listOf("provider"), emptyList<String>(), true, false, false), facts.getValue(call.id.value))
                        assertEquals(listOf(emptyList<String>(), listOf("provider"), false, true, false), facts.getValue(result.id.value))
                        assertEquals(listOf(emptyList<String>(), emptyList<String>(), false, false, true), facts.getValue(checkpoint.id.value))
                    }
                }
            }
        }
    }

    @Test
    fun `V99 carries sticky candidates and previous human time across the bounded checkpoint window`() = runBlocking {
        withFixture(target = "98") { f ->
            val thread = f.conversation.currentThread
            val oldMode = Conversation.Message.Instruction.UserInstruction("mode", "Old mode", "old")
            val pinned = Conversation.Message.Instruction.UserInstruction("pinned", "Pinned", "keep")
            val newMode = Conversation.Message.Instruction.UserInstruction("mode", "New mode", "new")
            val first = f.message("first").copy(
                instructions = listOf(oldMode, pinned),
                createdAt = f.now,
            )
            val boundary = f.message("boundary").copy(
                instructions = listOf(newMode),
                createdAt = f.now + 1.seconds,
            )
            val checkpoint = f.message("checkpoint-v99").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("history"),
                    origin = ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                    sourceBoundaryMessageId = boundary.id,
                )),
                createdAt = f.now + 2.seconds,
            )
            val tail = f.message("tail").copy(createdAt = f.now + 3.seconds)
            listOf(first, boundary, checkpoint, tail).forEachIndexed { position, message ->
                f.insertRaw(message, position)
            }

            f.migrate()

            val migrated = assertNotNull(f.links.findMessage(thread, checkpoint.id))
            val state = migrated.content.filterIsInstance<ContentItem.ContextCompactionResult>().single().continuationState
            assertEquals(mapOf("pinned" to pinned, "mode" to newMode), state.stickyInstructionCandidates.associateBy { it.id })
            assertEquals(boundary.createdAt, state.previousHumanMessageAt)
            val headers = f.links.compactionWindowHeaders(thread)
            assertEquals(listOf(checkpoint.id, tail.id), headers.map { it.id })
            assertEquals(state, headers.first().continuationState)
            val context = f.links.getContextMessagesByThread(thread, nativeProvider = null)
            assertEquals(4, context.sourceMessageCount)
            assertEquals(state, context.continuationState)
        }
    }

    @Test
    fun `V93 rejects a full checkpoint that is not an exact prefix in every branch before mutation`() = runBlocking {
        withFixture(target = "91") { f ->
            val source = f.message("source")
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("History"),
                    origin = ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                    strategy = ContentItem.ContextCompactionResult.Strategy.COMPACT,
                    sourceMessageIds = listOf(source.id),
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )),
            )
            val tail = f.message("tail")
            f.insertRaw(source, 0)
            f.insertRaw(checkpoint, 1)
            f.insertRaw(tail, 2)
            val branch = Conversation.Thread.Id("branch")
            ExposedThreadRepository().save(Conversation.Thread(
                branch, f.conversation.id, originalThread = f.conversation.currentThread,
                createdAt = f.now, updatedAt = f.now,
            ))
            f.links.add(branch, source.id, 0)
            f.links.add(branch, tail.id, 1)
            f.links.add(branch, checkpoint.id, 2)
            f.migrate("92")
            val before = f.source.connection.use { connection ->
                connection.prepareStatement("SELECT message_json, compaction_header FROM messages WHERE id = ?").use {
                    it.setString(1, checkpoint.id.value)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) to rows.getString(2) }
                }
            }

            val failure = assertFails { f.migrate() }
            val details = generateSequence(failure as Throwable?) { it.cause }.joinToString("\n") { it.toString() }
            assertContains(details, "not an exact prefix")
            f.source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("""
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'messages'
                          AND column_name = 'compaction_header'
                    """.trimIndent()).use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
                    statement.executeQuery("SELECT message_json, compaction_header FROM messages WHERE id = 'checkpoint'").use { rows ->
                        assertTrue(rows.next()); assertEquals(before, rows.getString(1) to rows.getString(2))
                    }
                }
            }
        }
    }

    @Test
    fun `V93 rejects a reused provider checkpoint with different branch prefixes`() = runBlocking {
        withFixture(target = "91") { f ->
            val original = f.message("original")
            val replacement = f.message("replacement")
            val boundary = f.message("boundary")
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    payload = ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(
                        buildJsonObject { put("state", "opaque") },
                    ),
                    origin = ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                    strategy = ContentItem.ContextCompactionResult.Strategy.PROVIDER_MANAGED,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )),
            )
            f.insertRaw(original, 0)
            f.insertRaw(boundary, 1)
            f.insertRaw(checkpoint, 2)
            val branch = Conversation.Thread.Id("branch")
            ExposedThreadRepository().save(Conversation.Thread(
                branch, f.conversation.id, originalThread = f.conversation.currentThread,
                createdAt = f.now, updatedAt = f.now,
            ))
            f.insertRawMessage(replacement)
            f.links.add(branch, replacement.id, 0)
            f.links.add(branch, boundary.id, 1)
            f.links.add(branch, checkpoint.id, 2)

            f.migrate("92")
            val before = f.source.connection.use { connection ->
                connection.prepareStatement("SELECT message_json, compaction_header FROM messages WHERE id = ?").use {
                    it.setString(1, checkpoint.id.value)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) to rows.getString(2) }
                }
            }
            val failure = assertFails { f.migrate() }
            val details = generateSequence(failure as Throwable?) { it.cause }.joinToString("\n") { it.toString() }
            assertContains(details, "different source prefixes")
            f.source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("""
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'messages'
                          AND column_name = 'compaction_header'
                    """.trimIndent()).use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
                    statement.executeQuery("SELECT message_json, compaction_header FROM messages WHERE id = 'checkpoint'").use { rows ->
                        assertTrue(rows.next()); assertEquals(before, rows.getString(1) to rows.getString(2))
                    }
                }
            }
        }
    }

    @Test
    fun `V93 gives a reused provider checkpoint its common preceding boundary`() = runBlocking {
        withFixture(target = "91") { f ->
            val source = f.message("source")
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    payload = ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(
                        buildJsonObject { put("state", "opaque") },
                    ),
                    origin = ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                    strategy = ContentItem.ContextCompactionResult.Strategy.PROVIDER_MANAGED,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )),
            )
            f.insertRaw(source, 0)
            f.insertRaw(checkpoint, 1)
            val branch = Conversation.Thread.Id("branch")
            ExposedThreadRepository().save(Conversation.Thread(
                branch, f.conversation.id, originalThread = f.conversation.currentThread,
                createdAt = f.now, updatedAt = f.now,
            ))
            f.links.add(branch, source.id, 0)
            f.links.add(branch, checkpoint.id, 1)

            f.migrate()

            val migrated = assertNotNull(ExposedMessageRepository(Json).findById(checkpoint.id))
                .content.single() as ContentItem.ContextCompactionResult
            assertTrue(migrated.sourceMessageIds.isEmpty())
            assertEquals(source.id, migrated.sourceBoundaryMessageId)
        }
    }

    @Test
    fun `V93 leaves an already boundary based body and header untouched`() = runBlocking {
        withFixture(target = "91") { f ->
            val source = f.message("source")
            val checkpoint = f.message("checkpoint").copy(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("History"),
                    origin = ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                    strategy = ContentItem.ContextCompactionResult.Strategy.COMPACT,
                    sourceBoundaryMessageId = source.id,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )),
            )
            f.insertRaw(source, 0)
            f.insertRaw(checkpoint, 1)
            f.migrate("92")
            fun stored() = f.source.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT message_json, compaction_header, xmin::text FROM messages WHERE id = ?",
                ).use {
                    it.setString(1, checkpoint.id.value)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        Triple(rows.getString(1), rows.getString(2), rows.getString(3))
                    }
                }
            }
            val before = stored()

            f.migrate("93")

            assertEquals(before, stored())
        }
    }

    @Test
    fun `point reads preserve full messages and author projection while isolating threads`() = runBlocking {
        withFixture { f ->
            val current = f.conversation.currentThread
            val old = Conversation.Thread.Id("old")
            val otherConversation = f.conversation.copy(id = Conversation.Id("other"), currentThread = Conversation.Thread.Id("foreign"))
            ExposedConversationRepository().create(otherConversation)
            ExposedThreadRepository().save(Conversation.Thread(old, f.conversation.id, createdAt = f.now, updatedAt = f.now))
            ExposedThreadRepository().save(Conversation.Thread(otherConversation.currentThread, otherConversation.id, createdAt = f.now, updatedAt = f.now))
            val target = f.message("target").copy(
                author = Conversation.Message.Author.User(f.owner.id, "Stored name"),
                providerMetadata = buildJsonObject { put("opaque-replay", "synthetic-signed-state") },
                content = listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id("call"), "tool",
                    listOf(ContentItem.ToolResult.Data.Text("x".repeat(80_000))))),
            )
            f.save(target, current, 0)
            f.links.add(old, target.id, 0)
            f.save(f.message("old-only"), old, 1)
            f.save(f.message("foreign-only").copy(conversationId = otherConversation.id), otherConversation.currentThread, 0)
            f.save(f.message("malformed"), current, 1)
            f.sql("""UPDATE messages SET message_json = jsonb_set(message_json::jsonb, '{content}', '[{"type":"INVALID_CONTENT"}]'::jsonb)::text WHERE id = 'malformed'""")
            val expected = target.copy(author = Conversation.Message.Author.User(f.owner.id, "Current name"))
            val read = f.measure { assertEquals(expected, f.history.message(current, target.id)?.message) }
            assertEquals(1, read.bodies)
            assertEquals(expected, f.history.message(old, target.id)?.message)
            assertNull(f.history.message(current, Conversation.Message.Id("old-only")))
            assertNull(f.history.message(current, Conversation.Message.Id("foreign-only")))
            assertNull(f.history.message(current, Conversation.Message.Id("missing")))
            assertNull(f.history.message(Conversation.Thread.Id("missing"), target.id))
            val membership = f.measure {
                assertTrue(f.links.contains(current, target.id))
                assertTrue(f.links.contains(current, Conversation.Message.Id("malformed")))
                assertFalse(f.links.contains(current, Conversation.Message.Id("old-only")))
                assertFalse(f.links.contains(current, Conversation.Message.Id("missing")))
                assertEquals(1, f.history.position(current, Conversation.Message.Id("malformed")))
            }
            assertEquals(0, membership.bodies)
            assertFailsWith<kotlinx.serialization.SerializationException> {
                f.history.message(current, Conversation.Message.Id("malformed"))
            }
        }
    }

    @Test
    fun `paged archive and branch copy apply replacement and exclusions without full reads`() = runBlocking {
        withFixture { f ->
            val source = f.conversation.currentThread
            val original = (0..3).map { index -> f.message("copy-$index") }
            original.forEachIndexed { position, message -> f.save(message, source, position) }
            val replacement = original[1].copy(
                id = Conversation.Message.Id("copy-replacement"),
                originalIds = listOf(original[1].id),
            )
            ExposedMessageRepository(Json).save(replacement)
            val replacedThread = Conversation.Thread.Id("copy-replaced")
            val deletedThread = Conversation.Thread.Id("copy-deleted")
            val threads = ExposedThreadRepository()
            for (thread in listOf(replacedThread, deletedThread)) {
                threads.save(Conversation.Thread(
                    id = thread,
                    conversationId = f.conversation.id,
                    originalThread = source,
                    createdAt = f.now,
                    updatedAt = f.now,
                ))
            }

            val firstPage = f.links.getMessagePage(source, limit = 2)
            val secondPage = f.links.getMessagePage(source, afterPositionExclusive = firstPage.last().position, limit = 2)
            assertEquals(original, (firstPage + secondPage).map { it.message })
            val firstLinks = f.links.getLinkPage(source, limit = 2)
            val secondLinks = f.links.getLinkPage(source, afterPositionExclusive = firstLinks.last().position, limit = 2)
            assertEquals(original.map { it.id }, (firstLinks + secondLinks).map { it.messageId })

            f.links.copyWithChanges(
                sourceThreadId = source,
                targetThreadId = replacedThread,
                replacement = ThreadMessageReplacement(original[1].id, replacement.id),
                excludedMessageIds = setOf(original[2].id),
            )
            assertEquals(
                listOf(original[0].id, replacement.id, original[3].id),
                f.links.readLinksForTest(replacedThread).map { it.messageId },
            )
            assertEquals(listOf(0, 1, 2), f.links.readLinksForTest(replacedThread).map { it.position })
            assertEquals(replacement, f.links.findMessageByIdOrOriginal(replacedThread, original[1].id))

            f.links.copyWithChanges(
                sourceThreadId = source,
                targetThreadId = deletedThread,
                excludedMessageIds = setOf(original[0].id, original[3].id),
            )
            assertEquals(
                listOf(original[1].id, original[2].id),
                f.links.readLinksForTest(deletedThread).map { it.messageId },
            )
            assertEquals(listOf(0, 1), f.links.readLinksForTest(deletedThread).map { it.position })
        }
    }

    @Test
    fun `branch copy chunks more than 65 thousand exclusions`() = runBlocking {
        withFixture { f ->
            val source = f.conversation.currentThread
            val retained = f.message("large-exclusion-retained")
            val excluded = f.message("large-exclusion-removed")
            f.save(retained, source, 0)
            f.save(excluded, source, 1)
            val target = Conversation.Thread.Id("large-exclusion-target")
            ExposedThreadRepository().save(Conversation.Thread(
                id = target,
                conversationId = f.conversation.id,
                originalThread = source,
                createdAt = f.now,
                updatedAt = f.now,
            ))
            val excludedIds = buildSet {
                add(excluded.id)
                repeat(70_000) { add(Conversation.Message.Id("absent-exclusion-$it")) }
            }

            f.links.copyWithChanges(
                sourceThreadId = source,
                targetThreadId = target,
                excludedMessageIds = excludedIds,
            )

            assertEquals(
                listOf(retained.id to 0),
                f.links.readLinksForTest(target).map { it.messageId to it.position },
            )
        }
    }

    @Test
    fun `last human activity is reader and branch bound without loading message bodies`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val other = ExposedIdentityRepository().observeTelegramIdentity(UserIdentity.Telegram(456, "Other"), f.now)
            fun human(id: String, user: com.gromozeka.domain.model.User = f.owner) = f.message(id).copy(
                author = Conversation.Message.Author.User(user.id, user.displayName),
                createdAt = kotlin.time.Instant.fromEpochSeconds(100 + id.length.toLong()))
            val first = human("first")
            f.save(first, thread, 0)
            f.save(human("other", other), thread, 1)
            f.save(human("synthetic").copy(providerMetadata = buildJsonObject { put("synthetic", true) }), thread, 2)
            f.save(f.message("no-author"), thread, 3)
            f.save(human("assistant").copy(role = Conversation.Message.Role.ASSISTANT), thread, 4)
            val read = f.measure {
                assertEquals(com.gromozeka.domain.model.ConversationUserActivity(0, first.createdAt), f.history.latestUserActivity(thread, f.owner.id))
            }
            assertEquals(1, read.rows)
            assertEquals(0, read.bodies)
            assertEquals(1, f.history.latestUserActivity(thread, other.id)?.position)
            val visual = human("visual").copy(content = listOf(ContentItem.VisualInteraction("v", "Title", 1, "e", "b", "Button",
                buildJsonObject { put("form", buildJsonObject {}); put("data", buildJsonObject {}) })))
            f.save(visual, thread, 5)
            assertEquals(com.gromozeka.domain.model.ConversationUserActivity(5, visual.createdAt), f.history.latestUserActivity(thread, f.owner.id))
            val fork = Conversation.Thread.Id("old-branch")
            ExposedThreadRepository().save(Conversation.Thread(fork, f.conversation.id, createdAt = f.now, updatedAt = f.now))
            f.links.add(fork, first.id, 0)
            assertEquals(0, f.history.latestUserActivity(fork, f.owner.id)?.position)
            assertNull(f.history.latestUserActivity(fork, other.id))
            assertNull(f.history.latestUserActivity(Conversation.Thread.Id("missing"), f.owner.id))
        }
    }

    @Test
    fun `maximum position is one SQL aggregate and membership reads one link`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            assertNull(f.links.getMaxPosition(thread))
            assertNull(f.links.getMaxPosition(Conversation.Thread.Id("missing")))
            repeat(40) { f.save(f.message("m-$it"), thread, it) }
            val maximum = f.measure { assertEquals(39, f.links.getMaxPosition(thread)) }
            assertEquals(1, maximum.rows)
            assertEquals(0, maximum.bodies)
            assertEquals(1, maximum.queries.size)
            assertTrue(maximum.queries.single().contains("MAX(", ignoreCase = true))
            val membership = f.measure { assertTrue(f.links.contains(thread, Conversation.Message.Id("m-39"))) }
            assertEquals(1, membership.rows)
            assertEquals(0, membership.bodies)
            assertEquals(1, membership.queries.size)
        }
    }

    @Test
    fun `context metadata backfills exactly and canonical writers preserve payloads`() = runBlocking {
        withFixture(target = "80") { f ->
            val prefix = listOf(
                f.message("normal").copy(instructions = listOf(Conversation.Message.Instruction.UserInstruction("mode", "Mode", "Value"))),
                f.message("synthetic").copy(providerMetadata = buildJsonObject { put("synthetic", true) }),
                f.message("synthetic-string").copy(providerMetadata = buildJsonObject { put("synthetic", "true") }),
                f.message("agent-source").copy(instructions = listOf(Conversation.Message.Instruction.Source.Agent("peer"))),
                f.message("binary").copy(content = listOf(ContentItem.UserMessage("escaped\u0000payload"))),
                f.message("visual").copy(content = listOf(ContentItem.VisualInteraction("v", "Title", 1, "e", "b", "Button",
                    buildJsonObject { put("form", buildJsonObject {}); put("data", buildJsonObject {}) }))),
            )
            val summary = f.message("summary").copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(ContentItem.ContextCompactionResult(
                ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"), ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                sourceMessageIds = prefix.map { it.id },
                coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
            )))
            val messages = prefix + listOf(
                summary,
                f.message("corrected").copy(role = Conversation.Message.Role.ASSISTANT,
                    author = Conversation.Message.Author.Agent(com.gromozeka.domain.model.AgentDefinition.Id("agent"), "Agent"),
                    providerMetadata = buildJsonObject { put(com.gromozeka.domain.model.COLLABORATION_ORIGINAL_CONTENT, Json.encodeToJsonElement(summary.content)) }),
                f.message("usage").copy(role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("answer"))),
                    providerMetadata = buildJsonObject {
                        put("provider", "OPENAI_SUBSCRIPTION"); put("connectionId", "connection"); put("model", "model")
                        put("openaiSubscriptionContextTokens", 1000); put("openaiSubscriptionContextEstimate", 900)
                    }),
                f.message("runtime").copy(
                    role = Conversation.Message.Role.RUNTIME,
                    content = listOf(ContentItem.RuntimeJson(kotlinx.serialization.json.JsonPrimitive("runtime state"))),
                    author = Conversation.Message.Author.Runtime(),
                ),
            )
            messages.forEachIndexed { index, message -> f.insertRaw(message, index) }
            f.migrate()
            messages.forEach { message ->
                val expected = if (message == summary) summary.copy(content = listOf(
                    (summary.content.single() as ContentItem.ContextCompactionResult).copy(
                        sourceMessageIds = emptyList(), sourceBoundaryMessageId = prefix.last().id,
                        continuationState = ContentItem.ContextCompactionResult.ContinuationState(
                            stickyInstructionCandidates = prefix.first().instructions
                                .filterIsInstance<Conversation.Message.Instruction.UserInstruction>(),
                            previousHumanMessageAt = prefix.last().createdAt,
                        ),
                    )
                )) else message
                assertEquals(expected, ExposedMessageRepository(Json).findById(message.id))
                assertEquals(com.gromozeka.domain.model.MessageContextMetadata.from(message), f.metadata(message.id))
            }
            val selected = f.links.getContextMessagesByThread(f.conversation.currentThread, null)
            assertFalse(selected.messages.any { it == messages.single { message -> message.id.value == "binary" } })
            assertEquals(messages.size, selected.sourceMessageCount)
            val postMigration = f.message("post-migration").copy(
                instructions = listOf(Conversation.Message.Instruction.UserInstruction("new-mode", "New mode", "Value")),
            )
            f.save(postMigration, f.conversation.currentThread, messages.size)
            assertEquals(postMigration, ExposedMessageRepository(Json).findById(postMigration.id))
            assertEquals(com.gromozeka.domain.model.MessageContextMetadata.from(postMigration), f.metadata(postMigration.id))
        }
    }

    @Test
    fun `context reads preserve branch membership and skip foreign opaque checkpoints`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            val source = f.message("source")
            val checkpoint = f.message("checkpoint").copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(ContentItem.ContextCompactionResult(
                ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(buildJsonObject { put("encrypted_content", "opaque") }),
                ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                providerScope = ContentItem.ContextCompactionResult.ProviderScope("OPENAI_SUBSCRIPTION"),
            )))
            val tail = f.message("tail")
            listOf(source, checkpoint, tail).forEachIndexed { index, message -> f.save(message, thread, index) }
            val archive = f.links.readArchiveForTest(thread)
            for (provider in listOf(null, "OPENAI_SUBSCRIPTION", "CLAUDE_CODE")) {
                val selected = f.links.getContextMessagesByThread(thread, provider)
                val target = provider?.let {
                    ContentItem.ContextCompactionResult.ProviderScope(it)
                }
                assertEquals(com.gromozeka.domain.model.ConversationContext(archive).messagesForProvider(target),
                    com.gromozeka.domain.model.ConversationContext(selected.messages).messagesForProvider(target))
                assertEquals(archive.size, selected.sourceMessageCount)
            }
            val fork = Conversation.Thread.Id("fork")
            ExposedThreadRepository().save(Conversation.Thread(fork, f.conversation.id, originalThread = thread, createdAt = f.now, updatedAt = f.now))
            f.links.add(fork, source.id, 0); f.links.add(fork, tail.id, 1)
            assertEquals(listOf(source, tail), f.links.getContextMessagesByThread(fork, null).messages)
            val bad = checkpoint.copy(id = Conversation.Message.Id("foreign-source"), content = listOf(
                (checkpoint.content.single() as ContentItem.ContextCompactionResult).copy(
                    coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
                    sourceMessageIds = listOf(checkpoint.id),
                )))
            f.save(bad, fork, 2)
            lateinit var forkContext: com.gromozeka.domain.repository.ThreadContextMessages
            val observation = f.measure { forkContext = f.links.getContextMessagesByThread(fork, null) }
            assertEquals(listOf(source.id, tail.id, bad.id), forkContext.messages.map { it.id })
            assertEquals(
                listOf(source, tail),
                com.gromozeka.domain.model.ConversationContext(forkContext.messages).messagesForProvider(null),
            )
            assertEquals(3, observation.bodies)
        }
    }

    @Test
    fun `long compacted history keeps point reads bounded`() = runBlocking {
        withFixture { f ->
            val large = System.getenv("GROMOZEKA_HISTORY_BENCHMARK") == "true"
            val count = if (large) 25_720 else 256
            val thread = f.conversation.currentThread
            val template = f.message("template").copy(content = listOf(ContentItem.UserMessage("x".repeat(10_000))))
            f.source.connection.use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("""
                    INSERT INTO messages(
                        id, conversation_id, original_ids_json, role, created_at, search_text, message_json, context_metadata_json,
                        tool_call_ids, tool_result_ids, provider_managed_tool, tool_results_only, full_compaction_anchor
                    ) VALUES (?, ?, '[]', 'USER', now(), '', ?, ?, '{}', '{}', false, false, false)
                """.trimIndent()).use { messages ->
                    connection.prepareStatement("INSERT INTO thread_messages(thread_id, message_id, position) VALUES (?, ?, ?)").use { links ->
                        repeat(count - 1) { i ->
                            val message = template.copy(id = Conversation.Message.Id("archive-$i"))
                            messages.setString(1, message.id.value); messages.setString(2, f.conversation.id.value)
                            messages.setString(3, Json.encodeToString(message))
                            messages.setString(4, message.contextMetadataJson(Json)); messages.addBatch()
                            links.setString(1, thread.value); links.setString(2, message.id.value); links.setInt(3, i); links.addBatch()
                            if (i % 128 == 127) { messages.executeBatch(); links.executeBatch() }
                        }
                        messages.executeBatch(); links.executeBatch()
                    }
                }
                connection.commit()
            }
            val summary = f.message("compaction").copy(content = listOf(ContentItem.ContextCompactionResult(
                payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("Synthetic summary"),
                origin = ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                strategy = ContentItem.ContextCompactionResult.Strategy.COMPACT,
                coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
            )))
            f.save(summary, thread, count - 1)
            val samples = linkedMapOf<String, MutableList<ReadMeasurement>>()
            suspend fun sample(name: String, operation: suspend () -> Unit): ReadMeasurement =
                f.measure(operation).also { samples.getOrPut(name, ::mutableListOf).add(it) }
            repeat(if (large) 6 else 1) { iteration ->
                val oldFind = sample("full_history_find") {
                    assertEquals(summary, f.links.readArchiveForTest(thread).firstOrNull { it.id == summary.id })
                }
                val find = sample("point_find") { assertEquals(summary, f.history.message(thread, summary.id)?.message) }
                val oldExists = sample("full_history_exists") {
                    assertTrue(f.links.readArchiveForTest(thread).any { it.id == summary.id })
                }
                val exists = sample("point_exists") { assertNotNull(f.history.position(thread, summary.id)) }
                val oldMaximum = sample("all_links_max") { assertEquals(count - 1, f.links.readLinksForTest(thread).maxOfOrNull { it.position }) }
                val maximum = sample("sql_max") { assertEquals(count - 1, f.links.getMaxPosition(thread)) }
                val fullContext = sample("full_history_context") {
                    assertEquals(listOf(summary), com.gromozeka.domain.model.ConversationContext(f.links.readArchiveForTest(thread)).messagesForProvider(null))
                }
                val sparseContext = sample("sparse_context") {
                    val selected = f.links.getContextMessagesByThread(thread, null)
                    assertEquals(count, selected.sourceMessageCount)
                    assertEquals(listOf(summary), com.gromozeka.domain.model.ConversationContext(selected.messages).messagesForProvider(null))
                }
                assertEquals(count.toLong(), fullContext.bodies)
                assertEquals(1, sparseContext.bodies)
                assertEquals(count.toLong(), oldFind.bodies); assertEquals(count.toLong(), oldExists.bodies)
                assertEquals(1, find.bodies); assertEquals(0, exists.bodies)
                assertEquals(count.toLong(), oldMaximum.rows); assertEquals(1, maximum.rows)
                if (large && iteration == 0) samples.values.forEach { it.clear() }
            }
            for ((name, values) in samples) {
                val median = values.map { it.nanos }.sorted()[values.size / 2] / 1_000_000.0
                val counts = values.first()
                println("HISTORY_READ name=$name messages=$count median_ms=$median rows=${counts.rows} bodies=${counts.bodies} body_bytes=${counts.bytes} samples=${values.size}")
            }
        }
    }

    private suspend fun withFixture(target: String? = null, test: suspend (Fixture) -> Unit) {
        assumeTrue("Requires explicit isolated PostgreSQL configuration", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "runtime_reads_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
            currentSchema = "$schema,public"
        }
        try {
            val migration = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres")
            target?.let(migration::target)
            migration.load().migrate()
            val observed = ObservedDataSource(source)
            Database.connect(observed)
            val f = Fixture(source, observed)
            f.initialize()
            test(f)
        } finally {
            source.connection.use { it.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

    private class Fixture(val source: PGSimpleDataSource, private val observed: ObservedDataSource) {
        val now = Clock.System.now()
        lateinit var owner: com.gromozeka.domain.model.User
        lateinit var conversation: Conversation
        val links = ExposedThreadMessageRepository(Json)
        val history = PostgresConversationHistoryRepository(observed, Json)
        suspend fun initialize() {
            owner = ExposedIdentityRepository().observeTelegramIdentity(UserIdentity.Telegram(123, "Current name"), now)
            val project = ExposedProjectRepository().save(Project(Project.Id("project"), "Test", createdAt = now, lastUsedAt = now))
            conversation = ExposedConversationRepository().create(Conversation(Conversation.Id("conversation"), project.id,
                setOf(Conversation.Participant.User(owner.id)), currentThread = Conversation.Thread.Id("current"), createdAt = now, updatedAt = now))
            ExposedThreadRepository().save(Conversation.Thread(conversation.currentThread, conversation.id, createdAt = now, updatedAt = now))
        }
        fun message(id: String) = Conversation.Message(Conversation.Message.Id(id), conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage(id)), createdAt = now)
        suspend fun save(message: Conversation.Message, thread: Conversation.Thread.Id, position: Int) {
            ExposedMessageRepository(Json).save(message)
            links.add(thread, message.id, position)
        }
        fun sql(sql: String) { source.connection.use { it.createStatement().use { it.execute(sql) } } }
        fun migrate(target: String? = null) {
            val schema = requireNotNull(source.currentSchema).substringBefore(',')
            val migration = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres")
            target?.let(migration::target)
            migration.load().migrate()
        }
        fun insertRawMessage(message: Conversation.Message) {
            source.connection.use { connection -> insertRawMessage(connection, message) }
        }
        fun insertRaw(message: Conversation.Message, position: Int) {
            source.connection.use { connection ->
                insertRawMessage(connection, message)
                connection.prepareStatement("INSERT INTO thread_messages(thread_id, message_id, position) VALUES (?, ?, ?)").use {
                    it.setString(1, conversation.currentThread.value); it.setString(2, message.id.value); it.setInt(3, position); it.executeUpdate()
                }
            }
        }
        private fun insertRawMessage(connection: Connection, message: Conversation.Message) {
            val hasToolSequenceFacts = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'messages' AND column_name = 'tool_call_ids'
                )
            """.trimIndent()).use { statement ->
                statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
            }
            if (!hasToolSequenceFacts) {
                connection.prepareStatement("INSERT INTO messages(id, conversation_id, original_ids_json, role, created_at, search_text, message_json) VALUES (?, ?, '[]', ?, now(), '', ?)").use {
                    it.setString(1, message.id.value); it.setString(2, message.conversationId.value)
                    it.setString(3, message.role.name); it.setString(4, Json.encodeToString(message)); it.executeUpdate()
                }
                return
            }
            val storesContextMetadata = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'messages'
                      AND column_name = 'context_metadata_json' AND is_generated = 'NEVER'
                )
            """.trimIndent()).use { statement ->
                statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
            }
            val facts = ToolSequenceFacts.from(message)
            val calls = connection.createArrayOf("text", facts.callIds.toTypedArray())
            val results = connection.createArrayOf("text", facts.resultIds.toTypedArray())
            try {
                connection.prepareStatement("""
                    INSERT INTO messages(
                        id, conversation_id, original_ids_json, role, created_at, search_text, message_json,
                        tool_call_ids, tool_result_ids, provider_managed_tool, tool_results_only, full_compaction_anchor
                        ${if (storesContextMetadata) ", context_metadata_json" else ""}
                    ) VALUES (?, ?, '[]', ?, now(), '', ?, ?, ?, ?, ?, ?
                        ${if (storesContextMetadata) ", ?" else ""})
                """.trimIndent()).use {
                    it.setString(1, message.id.value); it.setString(2, message.conversationId.value)
                    it.setString(3, message.role.name); it.setString(4, Json.encodeToString(message))
                    it.setArray(5, calls); it.setArray(6, results); it.setBoolean(7, facts.providerManaged)
                    it.setBoolean(8, facts.toolResultsOnly); it.setBoolean(9, facts.fullCompactionAnchor)
                    if (storesContextMetadata) it.setString(10, message.contextMetadataJson(Json))
                    it.executeUpdate()
                }
            } finally {
                calls.free()
                results.free()
            }
        }
        fun metadata(id: Conversation.Message.Id): com.gromozeka.domain.model.MessageContextMetadata? = source.connection.use { connection ->
            connection.prepareStatement("SELECT context_metadata_json FROM messages WHERE id = ?").use {
                it.setString(1, id.value)
                it.executeQuery().use { rows -> check(rows.next()); rows.getString(1)?.let { value -> Json.decodeFromString(value) } }
            }
        }
        fun messageToastBlocks(): Long = source.connection.use { connection ->
            connection.prepareStatement("""
                SELECT COALESCE(toast_blks_read, 0) + COALESCE(toast_blks_hit, 0)
                FROM pg_statio_all_tables
                WHERE relid = 'messages'::regclass
            """.trimIndent()).use { statement ->
                statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
            }
        }
        fun readMessageBody(id: Conversation.Message.Id) {
            source.connection.use { connection ->
                connection.prepareStatement("SELECT md5(message_json) FROM messages WHERE id = ?").use { statement ->
                    statement.setString(1, id.value)
                    statement.executeQuery().use { rows -> check(rows.next()); checkNotNull(rows.getString(1)) }
                }
            }
        }
        suspend fun measure(operation: suspend () -> Unit): ReadMeasurement {
            observed.reset()
            val start = System.nanoTime()
            operation()
            return ReadMeasurement(System.nanoTime() - start, observed.rows.get(), observed.bodies.get(), observed.bytes.get(), observed.queries.toList())
        }
    }

    private data class ReadMeasurement(val nanos: Long, val rows: Long, val bodies: Long, val bytes: Long, val queries: List<String>)

    private class ObservedDataSource(private val delegate: DataSource) : DataSource by delegate {
        val rows = AtomicLong(); val bodies = AtomicLong(); val bytes = AtomicLong()
        val queries = CopyOnWriteArrayList<String>()
        fun reset() { rows.set(0); bodies.set(0); bytes.set(0); queries.clear() }
        override fun getConnection(): Connection = connection(delegate.connection)
        override fun getConnection(username: String, password: String): Connection = connection(delegate.getConnection(username, password))
        private fun connection(target: Connection): Connection = wrap(target) { name, args, value ->
            if (name == "prepareStatement") {
                val sql = args[0] as String
                wrap(value as PreparedStatement) { method, _, result ->
                    if (method == "executeQuery") { queries += sql; resultSet(result as ResultSet) } else result
                }
            } else value
        }
        private fun resultSet(target: ResultSet): ResultSet = wrap(target) { name, args, value ->
            if (name == "next" && value == true) rows.incrementAndGet()
            if ((name == "getString" || name == "getObject") && value is String) {
                val column = when (val arg = args[0]) { is Int -> arg; is String -> target.findColumn(arg); else -> error("Unexpected column") }
                if (target.metaData.getColumnLabel(column) == "message_json") {
                    bodies.incrementAndGet(); bytes.addAndGet(value.encodeToByteArray().size.toLong())
                }
            }
            value
        }
        private inline fun <reified T> wrap(target: T, crossinline after: (String, Array<out Any?>, Any?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
                val args = arguments ?: emptyArray()
                val value = try { method.invoke(target, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
                after(method.name, args, value)
            } as T
    }
}
