package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.UserIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

class PostgresRuntimeMessageReadTest {
    @Test
    fun `V91 normalizes compact terminology before V92 indexes 16000 legacy messages`() = runBlocking {
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
            f.migrate()
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
                val all = f.links.compactionHeaders(thread)
                assertEquals(16_000, all.size)
                assertEquals(ids.first(), all.last().id)
                assertEquals("archive-16000".length.toLong(), all.last().characters)
            }
            assertEquals(0, headers.bodies)
            val selected = f.measure { assertEquals(ids, f.links.compactionBodies(thread, ids).map { it.id }) }
            assertEquals(2, selected.bodies)
        }
    }

    @Test
    fun `V92 rejects a full checkpoint that is not an exact prefix in every branch before mutation`() = runBlocking {
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
            val before = f.source.connection.use { connection ->
                connection.prepareStatement("SELECT message_json FROM messages WHERE id = ?").use {
                    it.setString(1, checkpoint.id.value)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
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
                    """.trimIndent()).use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                    statement.executeQuery("SELECT message_json FROM messages WHERE id = 'checkpoint'").use { rows ->
                        assertTrue(rows.next()); assertEquals(before, rows.getString(1))
                    }
                }
            }
        }
    }

    @Test
    fun `V92 rejects a reused provider checkpoint with different branch prefixes`() = runBlocking {
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
                    """.trimIndent()).use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                }
            }
        }
    }

    @Test
    fun `V92 gives a reused provider checkpoint its common preceding boundary`() = runBlocking {
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
    fun `context metadata backfills and follows canonical writes without rewriting payloads`() = runBlocking {
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
            )
            messages.forEachIndexed { index, message -> f.insertRaw(message, index) }
            f.migrate()
            messages.forEach { message ->
                val expected = if (message == summary) summary.copy(content = listOf(
                    (summary.content.single() as ContentItem.ContextCompactionResult).copy(
                        sourceMessageIds = emptyList(), sourceBoundaryMessageId = prefix.last().id,
                    )
                )) else message
                assertEquals(expected, ExposedMessageRepository(Json).findById(message.id))
                if (message.id.value == "binary") assertNull(f.metadata(message.id))
                else assertEquals(com.gromozeka.domain.model.MessageContextMetadata.from(message), f.metadata(message.id))
            }
            val selected = f.links.getContextMessagesByThread(f.conversation.currentThread, null)
            assertTrue(selected.any { it == messages.single { message -> message.id.value == "binary" } })
            val replacement = messages[0].copy(providerMetadata = buildJsonObject { put("synthetic", true) }, instructions = emptyList())
            f.source.connection.use { connection ->
                connection.prepareStatement("UPDATE messages SET message_json = ? WHERE id = ?").use {
                    it.setString(1, Json.encodeToString(replacement)); it.setString(2, replacement.id.value); it.executeUpdate()
                }
            }
            assertEquals(com.gromozeka.domain.model.MessageContextMetadata.from(replacement), f.metadata(replacement.id))
            assertFailsWith<java.sql.SQLException> { f.sql("UPDATE messages SET context_metadata_json = '{}' WHERE id = 'normal'") }
        }
    }

    @Test
    fun `context reads preserve branch membership and recover foreign opaque checkpoints`() = runBlocking {
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
            val archive = f.links.getMessagesByThread(thread)
            for (provider in listOf(null, "OPENAI_SUBSCRIPTION", "CLAUDE_CODE")) {
                val selected = f.links.getContextMessagesByThread(thread, provider)
                assertEquals(com.gromozeka.domain.model.ConversationContext(archive).messagesForProvider(provider),
                    com.gromozeka.domain.model.ConversationContext(selected).messagesForProvider(provider))
            }
            val fork = Conversation.Thread.Id("fork")
            ExposedThreadRepository().save(Conversation.Thread(fork, f.conversation.id, originalThread = thread, createdAt = f.now, updatedAt = f.now))
            f.links.add(fork, source.id, 0); f.links.add(fork, tail.id, 1)
            assertEquals(listOf(source, tail), f.links.getContextMessagesByThread(fork, null))
            val bad = checkpoint.copy(id = Conversation.Message.Id("foreign-source"), content = listOf(
                (checkpoint.content.single() as ContentItem.ContextCompactionResult).copy(
                    coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
                    sourceMessageIds = listOf(checkpoint.id),
                )))
            f.save(bad, fork, 2)
            val observation = f.measure { assertFailsWith<IllegalStateException> { f.links.getContextMessagesByThread(fork, null) } }
            assertEquals(1, observation.bodies)
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
                connection.prepareStatement("INSERT INTO messages(id, conversation_id, original_ids_json, role, created_at, search_text, message_json) VALUES (?, ?, '[]', 'USER', now(), '', ?)").use { messages ->
                    connection.prepareStatement("INSERT INTO thread_messages(thread_id, message_id, position) VALUES (?, ?, ?)").use { links ->
                        repeat(count - 1) { i ->
                            val message = template.copy(id = Conversation.Message.Id("archive-$i"))
                            messages.setString(1, message.id.value); messages.setString(2, f.conversation.id.value)
                            messages.setString(3, Json.encodeToString(message)); messages.addBatch()
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
                    assertEquals(summary, f.links.getMessagesByThread(thread).firstOrNull { it.id == summary.id })
                }
                val find = sample("point_find") { assertEquals(summary, f.history.message(thread, summary.id)?.message) }
                val oldExists = sample("full_history_exists") {
                    assertTrue(f.links.getMessagesByThread(thread).any { it.id == summary.id })
                }
                val exists = sample("point_exists") { assertNotNull(f.history.position(thread, summary.id)) }
                val oldMaximum = sample("all_links_max") { assertEquals(count - 1, f.links.getByThread(thread).maxOfOrNull { it.position }) }
                val maximum = sample("sql_max") { assertEquals(count - 1, f.links.getMaxPosition(thread)) }
                val fullContext = sample("full_history_context") {
                    assertEquals(listOf(summary), com.gromozeka.domain.model.ConversationContext(f.links.getMessagesByThread(thread)).messagesForProvider(null))
                }
                val sparseContext = sample("sparse_context") {
                    val selected = f.links.getContextMessagesByThread(thread, null)
                    assertEquals(listOf(summary), com.gromozeka.domain.model.ConversationContext(selected).messagesForProvider(null))
                }
                assertEquals(count.toLong(), fullContext.bodies)
                assertEquals(3, sparseContext.bodies)
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
        fun migrate() {
            val schema = requireNotNull(source.currentSchema).substringBefore(',')
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load().migrate()
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
            connection.prepareStatement("INSERT INTO messages(id, conversation_id, original_ids_json, role, created_at, search_text, message_json) VALUES (?, ?, '[]', ?, now(), '', ?)").use {
                it.setString(1, message.id.value); it.setString(2, message.conversationId.value)
                it.setString(3, message.role.name); it.setString(4, Json.encodeToString(message)); it.executeUpdate()
            }
        }
        fun metadata(id: Conversation.Message.Id): com.gromozeka.domain.model.MessageContextMetadata? = source.connection.use { connection ->
            connection.prepareStatement("SELECT context_metadata_json FROM messages WHERE id = ?").use {
                it.setString(1, id.value)
                it.executeQuery().use { rows -> check(rows.next()); rows.getString(1)?.let { value -> Json.decodeFromString(value) } }
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
