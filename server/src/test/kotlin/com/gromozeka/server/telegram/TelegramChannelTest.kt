package com.gromozeka.server.telegram

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class TelegramChannelTest {
    private val route = TelegramAgentRoute(AgentDefinition.Id("agent"))
    private val binding = TelegramConversationBinding(-123, conversationId = Conversation.Id("group"), initiatorTelegramUserId = 42, routes = listOf(route))
    private val connection = TelegramConnection(12345, "test_bot", User.Id("owner"), "telegram", true, listOf(binding), acceptTriggersAfterEpochSeconds = 0)
    private val instant = Instant.fromEpochSeconds(100)

    @Test fun `only exact numeric initiator can trigger configured case insensitive substring`() {
        listOf("@grz", "prefix@GRZsuffix", "email@grz.com", "quoted @grz request").forEach {
            assertEquals(listOf(route), match(message(42, it)))
            assertTrue(match(message(99, it)).isEmpty())
        }
        val bare = binding.copy(routes = listOf(route.copy(trigger = "grz")))
        assertEquals(1, TelegramInboundPolicy.matchingRoutes(connection, bare, message(42, "grz!"), false).size)
        assertTrue(match(message(42, "grz!")).isEmpty())
        assertTrue(match(message(42, "hello")).isEmpty())
    }

    @Test fun `edits forwards bots anonymous senders and preactivation backlog never invoke`() {
        for (key in listOf("sender_chat", "forward_origin", "forward_date", "via_bot")) {
            assertTrue(match(JsonObject(message(42, "@grz") + (key to JsonObject(emptyMap())))).isEmpty())
        }
        assertTrue(match(message(42, "@grz", true)).isEmpty())
        assertTrue(TelegramInboundPolicy.matchingRoutes(connection, binding, message(42, "@grz"), true).isEmpty())
        assertTrue(TelegramInboundPolicy.matchingRoutes(connection.copy(acceptTriggersAfterEpochSeconds = 101), binding, message(42, "@grz"), false).isEmpty())
        assertTrue(TelegramInboundPolicy.matchingRoutes(connection.copy(enabled = false), binding, message(42, "@grz"), false).isEmpty())
    }

    @Test fun `matching multiple agents preserves configured order without duplicating an alias`() {
        val second = route.copy(agentId = AgentDefinition.Id("second"), trigger = "review")
        val b = binding.copy(routes = listOf(route, second))
        assertEquals(listOf(route, second), TelegramInboundPolicy.matchingRoutes(connection, b, message(42, "@grz review @grz"), false))
    }

    @Test fun `group observation creates restricted author and lazy media reference`() = runBlocking {
        val identities = Mockito.mock(IdentityRepository::class.java)
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val identity = UserIdentity.Telegram(99, "Friend", null)
        val user = User(User.Id("friend"), listOf(identity), "Friend", User.Status.ACTIVE, loginAllowed = false, aiAllowed = false, createdAt = instant, updatedAt = instant)
        Mockito.`when`(identities.observeTelegramIdentity(identity, instant)).thenReturn(user)
        Mockito.`when`(conversations.findById(binding.conversationId)).thenReturn(Conversation(binding.conversationId, Project.Id("p"),
            setOf(Conversation.Participant.User(connection.ownerUserId)), currentThread = Conversation.Thread.Id("t"), createdAt = instant, updatedAt = instant))
        val raw = JsonObject(message(99, "@grz ignored") + ("photo" to buildJsonArray { add(buildJsonObject {
            put("file_id", "download-id"); put("file_unique_id", "unique-id"); put("file_size", 24)
        }) }))
        val input = assertNotNull(TelegramInboundPolicy(identities, conversations).prepare(connection, update(1, raw)))
        assertTrue(input.routes.isEmpty())
        assertEquals(user.id, (input.message.author as Conversation.Message.Author.User).userId)
        assertEquals(identity.key, (input.message.author as Conversation.Message.Author.User).identityKey)
        assertEquals(Artifact.ContentSource.Telegram(connection.id, "download-id", "unique-id"), input.artifacts.single().source)
        val edit = assertNotNull(TelegramInboundPolicy(identities, conversations).prepare(connection, update(2, raw, true)))
        assertEquals(input.message.id, edit.replaceOriginalId)
        assertNotEquals(input.message.id, edit.message.id)
        assertTrue(edit.routes.isEmpty())
    }

    @Test fun `new messages are durable but not imported during a running invocation`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)), incoming(2), incoming(3, listOf(route)))))
        val gateway = FakeGateway()
        val processor = processor(repository, gateway)
        processor.initialize(); processor.synchronize()
        assertEquals(listOf(1L), gateway.imported)
        assertEquals(1, gateway.submissions.size)
        processor.synchronize()
        assertEquals(listOf(1L), gateway.imported)
        assertEquals(2, repository.state.inbox.size)
        gateway.complete = true
        processor.synchronize(); processor.synchronize()
        assertEquals(listOf(1L, 2L, 3L), gateway.imported)
        assertEquals(2, gateway.submissions.size)
    }

    @Test fun `multiple agents share one imported root and run sequentially`() = runBlocking {
        val second = route.copy(agentId = AgentDefinition.Id("second"), trigger = "other")
        val b = binding.copy(routes = listOf(route, second))
        val c = connection.copy(bindings = listOf(b))
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, b.routes).copy(binding = b))))
        val gateway = FakeGateway()
        val processor = processor(repository, gateway, config = c)
        processor.initialize(); processor.synchronize(); processor.synchronize()
        assertEquals(1, gateway.submissions.size)
        assertEquals(2, repository.state.invocations.size)
        assertEquals(1, repository.state.invocations.map { it.rootMessageId }.distinct().size)
        gateway.complete = true
        processor.synchronize(); processor.synchronize()
        assertEquals(2, gateway.submissions.size)
        assertEquals(listOf(1L), gateway.imported)
    }

    @Test fun `reenabling mirrors old inbox without reviving previously accepted triggers`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway()
        val processor = processor(repository, gateway, config = connection.copy(activationRevision = 2))
        processor.initialize(); processor.synchronize()
        assertEquals(listOf(1L), gateway.imported)
        assertTrue(gateway.submissions.isEmpty())
        assertTrue(repository.state.inbox.isEmpty())
    }

    @Test fun `reenabling invalidates old queued invocations`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway()
        val original = processor(repository, gateway)
        original.initialize(); original.synchronize()
        val resumed = processor(repository, gateway, config = connection.copy(activationRevision = 2))
        resumed.initialize(); resumed.synchronize()
        assertTrue(repository.state.invocations.single().completed)
        assertTrue(repository.state.invocations.single().failed)
        assertEquals(1, gateway.submissions.size)
        assertEquals(listOf(repository.state.invocations.single().id), gateway.stops)
    }

    @Test fun `crash after actor submission reuses invocation id and never duplicates root`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway()
        repository.failAfterSubmission = true
        val first = processor(repository, gateway)
        first.initialize()
        assertFailsWith<IllegalStateException> { first.synchronize() }
        val resumed = processor(repository, gateway)
        resumed.initialize(); resumed.synchronize()
        assertEquals(2, gateway.attempts)
        assertEquals(1, gateway.submissions.size)
        assertEquals(listOf(1L), gateway.imported)
    }

    @Test fun `unknown delivery is not blindly resent after restart`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway().apply { complete = true }
        val api = FakeApi(TelegramDeliveryUncertain())
        val processor = processor(repository, gateway, api)
        processor.initialize(); processor.synchronize()
        assertEquals(TelegramDelivery.State.UNKNOWN, repository.state.deliveries.single().state)
        val resumed = processor(repository, gateway, api)
        resumed.initialize(); resumed.synchronize()
        assertEquals(1, api.replyCalls)
        assertTrue(api.calls.none { it.second.string("text")?.contains("PRIVATE OTHER TURN") == true })
    }

    @Test fun `completed answer replaces its progress message instead of adding another message`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway()
        val api = FakeApi()
        val processor = processor(repository, gateway, api)
        processor.initialize(); processor.synchronize()
        val progressId = assertNotNull(repository.state.invocations.single().statusMessageId)
        gateway.complete = true
        processor.synchronize(); processor.synchronize()
        assertEquals(1, api.calls.count { it.first == "sendMessage" })
        val finalEdit = api.calls.last { it.first == "editMessageText" }.second
        assertEquals(progressId, finalEdit.long("message_id"))
        assertTrue(finalEdit.string("text")!!.contains("reply"))
        assertFalse(finalEdit.string("text")!!.contains("Completed"))
        assertTrue(finalEdit.getValue("reply_markup").jsonObject.getValue("inline_keyboard").jsonArray.isEmpty())
        assertEquals(progressId, repository.state.deliveries.single().telegramMessageId)
    }

    @Test fun `uncertain final edit retries the same message after restart without another model call`() = runBlocking {
        var time = 100L
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway()
        val api = FakeApi()
        fun create() = TelegramBotProcessor(connection, api, gateway, repository, { null }, now = { time })
        val first = create()
        first.initialize(); first.synchronize()
        val progressId = repository.state.invocations.single().statusMessageId
        gateway.complete = true; api.failure = TelegramDeliveryUncertain()
        first.synchronize()
        assertEquals(TelegramDelivery.State.PENDING, repository.state.deliveries.single().state)
        val resumed = create()
        resumed.initialize(); resumed.synchronize()
        assertEquals(1, api.replyCalls)
        time = 111; api.failure = null
        resumed.synchronize()
        assertEquals(TelegramDelivery.State.SENT, repository.state.deliveries.single().state)
        assertEquals(progressId, repository.state.deliveries.single().telegramMessageId)
        assertEquals(1, api.calls.count { it.first == "sendMessage" })
        assertEquals(1, gateway.submissions.size)
    }

    @Test fun `crash during a known final edit remains safely retryable`() = runBlocking {
        val progressId = 77L
        val item = invocation().copy(submitted = true, completed = true, statusAttempted = true, statusMessageId = progressId)
        val delivery = TelegramDelivery("final", item.id, "reply", state = TelegramDelivery.State.SENDING, replacesStatus = true)
        val repository = MemorySession(TelegramBotState(invocations = listOf(item), deliveries = listOf(delivery)))
        val api = FakeApi()
        val resumed = processor(repository, FakeGateway(), api)
        resumed.initialize(); resumed.synchronize()
        assertEquals(TelegramDelivery.State.SENT, repository.state.deliveries.single().state)
        assertEquals(progressId, repository.state.deliveries.single().telegramMessageId)
        assertEquals(listOf("editMessageText"), api.calls.map { it.first })
    }

    @Test fun `unknown progress send cannot create a duplicate final reply`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway()
        val api = FakeApi().apply { statusFailure = TelegramDeliveryUncertain() }
        val processor = processor(repository, gateway, api)
        processor.initialize(); processor.synchronize()
        assertNull(repository.state.invocations.single().statusMessageId)
        gateway.complete = true
        processor.synchronize()
        assertEquals(TelegramDelivery.State.UNKNOWN, repository.state.deliveries.single().state)
        assertEquals(1, api.calls.count { it.first == "sendMessage" })
    }

    @Test fun `known rate limit safely retries without another model call`() = runBlocking {
        var time = 100L
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway().apply { complete = true }
        val api = FakeApi(TelegramApiFailure(429, 20))
        val processor = TelegramBotProcessor(connection, api, gateway, repository, { null }, now = { time })
        processor.initialize(); processor.synchronize()
        assertEquals(TelegramDelivery.State.PENDING, repository.state.deliveries.single().state)
        processor.synchronize(); assertEquals(1, api.replyCalls)
        api.failure = null; time = 121
        processor.synchronize()
        assertEquals(TelegramDelivery.State.SENT, repository.state.deliveries.single().state)
        assertEquals(1, gateway.submissions.size)
    }

    @Test fun `stop validates initiator chat and exact status message and awaits interrupt acknowledgement`() = runBlocking {
        val repository = MemorySession(TelegramBotState(inbox = listOf(incoming(1, listOf(route)))))
        val gateway = FakeGateway()
        val processor = processor(repository, gateway)
        processor.initialize(); processor.synchronize()
        val invocation = repository.state.invocations.single()
        suspend fun callback(user: Long, chat: Long = -123, messageId: Long = invocation.statusMessageId!!) {
            processor.receive(buildJsonArray { add(buildJsonObject {
                put("update_id", repository.state.nextUpdateId)
                put("callback_query", buildJsonObject {
                    put("id", "callback"); put("data", "stop:${invocation.id}")
                    put("from", buildJsonObject { put("id", user) })
                    put("message", buildJsonObject { put("message_id", messageId); put("chat", buildJsonObject { put("id", chat) }) })
                })
            }) })
        }
        callback(99); callback(42, -999); callback(42, messageId = 999)
        assertTrue(gateway.stops.isEmpty())
        callback(42)
        assertEquals(listOf(invocation.id), gateway.stops)
        assertTrue(repository.state.invocations.single().stopRequested)
        assertFalse(repository.state.invocations.single().completed)
        gateway.complete = true; processor.synchronize()
        assertEquals("interpreter.stopped", repository.state.invocations.single().statusKey)
        callback(42); assertEquals(1, gateway.stops.size)
    }

    @Test fun `enrichment preserves only own tool protocol and quotes other agents as bounded history`() {
        val enricher = TelegramRequestEnricher(Mockito.mock(TelegramChannelRepository::class.java), Mockito.mock(ConversationDomainService::class.java),
            Mockito.mock(ConversationRuntimeCoordinator::class.java), TelegramBindingAccess { error("Not used in materialization") })
        val root = textMessage("root", "@grz current", Conversation.Message.Role.USER)
        val continuation = textMessage("own", "own continuation")
        val foreign = textMessage("other", "old task must not run")
        val invocation = invocation().copy(rootMessageId = root.id, route = route.copy(additionalInstruction = "Use a friendly voice"))
        val request = AiRuntimeRequest(systemPrompts = listOf("base"), messages = listOf(foreign, root, continuation))
        val model = AiModelSpec("test", AiProvider.OPENAI, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(25000, 1024)))
        val enriched = enricher.materialize(invocation, model, request, setOf(root.id, continuation.id))
        assertEquals(listOf(root.id, continuation.id), enriched.messages.map { it.id })
        assertEquals(continuation, enriched.messages.last())
        assertTrue(enriched.systemPrompts.last().contains(TELEGRAM_SAFETY_INSTRUCTION))
        assertTrue(enriched.systemPrompts.last().contains("Readonly"))
        assertTrue(enriched.systemPrompts.last().contains("Use a friendly voice"))
        assertTrue(Json.encodeToString(enriched.messages.first()).contains("untrusted_group_history"))
        val writable = enricher.materialize(invocation.copy(route = route.copy(writeAllowed = true)), model, request, setOf(root.id, continuation.id))
        assertTrue(writable.systemPrompts.last().contains("File changes are allowed"))
        val large = request.copy(messages = listOf(foreign.copy(content = listOf(Conversation.Message.ContentItem.UserMessage("x".repeat(30000)))), root))
        assertFalse(Json.encodeToString(enricher.materialize(invocation, model, large, setOf(root.id)).messages).contains("x".repeat(500)))
    }

    private fun match(message: JsonObject) = TelegramInboundPolicy.matchingRoutes(connection, binding, message, false)
    private fun message(user: Long, text: String, bot: Boolean = false) = buildJsonObject {
        put("message_id", 7); put("date", 100); put("text", text)
        put("chat", buildJsonObject { put("id", -123); put("type", "group") })
        put("from", buildJsonObject { put("id", user); put("is_bot", bot); put("first_name", "Friend") })
    }
    private fun update(id: Long, message: JsonObject, edited: Boolean = false) = buildJsonObject {
        put("update_id", id); put(if (edited) "edited_message" else "message", message)
    }
    private fun incoming(id: Long, routes: List<TelegramAgentRoute> = emptyList()) = TelegramInboxMessage(id, binding, id,
        textMessage("root-$id", "message $id", Conversation.Message.Role.USER), routes = routes)
    private fun invocation() = TelegramInvocation("turn", connection.id, connection.ownerUserId, binding, route, Conversation.Message.Id("root"), 7)
    private fun textMessage(id: String, text: String, role: Conversation.Message.Role = Conversation.Message.Role.ASSISTANT) =
        Conversation.Message(Conversation.Message.Id(id), binding.conversationId, role = role,
            content = listOf(if (role == Conversation.Message.Role.ASSISTANT) Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText(text))
                else Conversation.Message.ContentItem.UserMessage(text)), createdAt = instant)
    private fun processor(repository: MemorySession, gateway: FakeGateway, api: FakeApi = FakeApi(), config: TelegramConnection = connection) =
        TelegramBotProcessor(config, api, gateway, repository, { null }, now = { 100L })

    private inner class FakeGateway : TelegramConversationGateway {
        val submissions = linkedSetOf<String>(); val imported = mutableListOf<Long>(); val stops = mutableListOf<String>()
        var attempts = 0; var complete = false
        private var active: TelegramInvocation? = null
        override suspend fun validate(invocation: TelegramInvocation): String = "Test agent"
        override suspend fun import(connection: TelegramConnection, message: TelegramInboxMessage) { imported += message.updateId }
        override suspend fun cursor(binding: TelegramConversationBinding): Long = 0
        override suspend fun submit(invocation: TelegramInvocation) { attempts++; submissions += invocation.id; active = invocation }
        override suspend fun events(invocation: TelegramInvocation): List<ConversationRuntimeEventLogEntry> {
            if (!complete) return emptyList()
            return listOf(
                ConversationRuntimeEvent.MessageEmitted(binding.conversationId, null, textMessage("other", "PRIVATE OTHER TURN"), turnId = ConversationRuntimeTurnId("other")),
                ConversationRuntimeEvent.MessageEmitted(binding.conversationId, null, textMessage("reply-${invocation.id}", "reply"), turnId = ConversationRuntimeTurnId(invocation.id)),
            ).mapIndexed { index, event -> ConversationRuntimeEventLogEntry(index + 1L, binding.conversationId, event, instant) }.filter { it.sequence > invocation.eventCursor }
        }
        override suspend fun snapshot(binding: TelegramConversationBinding): ConversationRuntimeSnapshot {
            val pending = active?.takeUnless { complete }?.let {
                ConversationRuntimeTask(ConversationRuntimeTask.Id(it.id), binding.conversationId, payload = ConversationRuntimeTask.Payload.AgentResponse(it.rootMessageId, route.agentId),
                    placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = it.id,
                    requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server), createdAt = instant)
            }
            return ConversationRuntimeSnapshot(1, binding.conversationId, null, pendingTasks = listOfNotNull(pending), lastEventSequence = if (complete) 2 else 0)
        }
        override suspend fun stop(invocation: TelegramInvocation): Boolean { stops += invocation.id; return true }
    }
    private class FakeApi(var failure: RuntimeException? = null) : TelegramApi {
        val calls = mutableListOf<Pair<String, JsonObject>>(); var replyCalls = 0
        var statusFailure: RuntimeException? = null
        override suspend fun call(method: String, parameters: JsonObject): JsonElement {
            calls += method to parameters
            if (method in setOf("sendMessage", "editMessageText") && parameters.string("text")?.contains("reply") == true) { replyCalls++; failure?.let { throw it } }
            else if (method == "sendMessage") statusFailure?.let { throw it }
            return buildJsonObject { put("message_id", calls.size); put("date", 100) }
        }
    }
    private class MemorySession(var state: TelegramBotState) : TelegramBotSession {
        var failAfterSubmission = false
        override suspend fun load() = state
        override suspend fun verifyLease() = Unit
        override suspend fun save(state: TelegramBotState) {
            if (failAfterSubmission && state.invocations.any { it.submitted }) { failAfterSubmission = false; error("Simulated crash after submission") }
            this.state = state
        }
        override suspend fun close() = Unit
    }
}
