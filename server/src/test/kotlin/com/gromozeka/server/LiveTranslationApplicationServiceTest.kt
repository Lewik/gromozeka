package com.gromozeka.server

import com.gromozeka.application.service.RuntimeCatalogTemplateApplicationService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolAccessPolicy
import com.gromozeka.remote.protocol.LiveTranslationSourceBlock
import com.gromozeka.remote.protocol.TranslateLiveTextRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class LiveTranslationApplicationServiceTest {
    @Test
    fun `translation binds user purpose model tools and stable output schema`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.service.translate(liveTranslationTestUser(), request())
        assertEquals(7, result.revision)
        assertEquals("ru", result.targetLanguage)
        assertEquals("Перевод", result.blocks.single().text)
        val sent = fixture.runtime.requests.single()
        assertEquals("live-test-user", sent.options.toolContext[TOOL_CONTEXT_USER_ID])
        assertEquals("LIVE_TRANSLATION", sent.options.usagePurpose)
        assertEquals(AiToolChoice.None, sent.options.toolChoice)
        assertEquals(null, sent.options.reasoning, "Reasoning must come from the selected model, not a universal Live override")
        assertEquals(ToolAccessPolicy.AllowOnly(), sent.options.toolAccess)
        assertTrue(sent.tools.isEmpty())
        assertEquals("openai-api-gpt-5.4-mini", fixture.selections.single().modelConfigurationId.value)
        assertTrue(sent.options.responseFormat is AiResponseFormat.JsonSchema)
        assertEquals(64, (sent.options.toolContext.getValue("promptCacheKey") as String).length)
    }

    @Test
    fun `longer history preserves the rendered source prefix and volatile metadata stays out of it`() = runBlocking {
        val fixture = Fixture()
        val history = LiveTranslationSourceBlock("history", "Earlier original.", "Ранний перевод.")
        val first = request().copy(context = listOf(history))
        val second = first.copy(revision = 8, targetLanguage = "he", context = first.context +
            LiveTranslationSourceBlock("history-2", "Later original.", "Поздний перевод."))
        fixture.service.translate(liveTranslationTestUser(), first)
        fixture.service.translate(liveTranslationTestUser(), second)
        val a = fixture.runtime.requests[0]
        val b = fixture.runtime.requests[1]
        assertEquals((a.options.responseFormat as AiResponseFormat.JsonSchema).schema,
            (b.options.responseFormat as AiResponseFormat.JsonSchema).schema)
        val prefix = fixture.service.prompt(first).substringBefore("\nEDITABLE SOURCE BLOCKS")
        assertTrue(fixture.service.prompt(second).startsWith(prefix))
        assertFalse(fixture.service.prompt(first).contains(first.sessionId))
    }

    @Test
    fun `all current originals follow the drafts so later source evidence is available for earlier revisions`() {
        val fixture = Fixture()
        val input = request().copy(blocks = listOf(
            LiveTranslationSourceBlock("first", "An earlier source reference.", "Старый ошибочный черновик."),
            LiveTranslationSourceBlock("later", "Later source evidence resolves the reference."),
        ))
        val prompt = fixture.service.prompt(input)
        assertTrue(prompt.indexOf("Старый ошибочный черновик.") < prompt.indexOf("An earlier source reference."))
        assertTrue(prompt.indexOf("An earlier source reference.") < prompt.indexOf("Later source evidence resolves the reference."))
    }

    @Test
    fun `continuous editable passage retains source order and gaps independently of output boundaries`() {
        val fixture = Fixture()
        val input = request().copy(blocks = listOf(
            LiveTranslationSourceBlock("first", "First line.\nSecond line."),
            LiveTranslationSourceBlock("later", "Unrelated speech after a gap.", gapBefore = true),
        ))
        val prompt = fixture.service.prompt(input)
        val passage = prompt.substringAfter("CURRENT ORIGINAL — CONTINUOUS PASSAGE\n")
            .substringBefore("\nOUTPUT ALIGNMENT")
        assertEquals("First line.\nSecond line.\n[UNKNOWN SOURCE GAP]\nUnrelated speech after a gap.",
            kotlinx.serialization.json.Json.decodeFromString<String>(passage))
        val alignment = prompt.substringAfter("OUTPUT ALIGNMENT — SAME ORIGINAL, NOT ADDITIONAL SPEECH\n")
            .substringBefore("\nTARGET LANGUAGE")
        val aligned = alignment.lineSequence().filter { it.isNotBlank() }
            .map { kotlinx.serialization.json.Json.decodeFromString<LiveTranslationSourceBlock>(it) }.toList()
        assertEquals(input.blocks, aligned)
    }

    @Test
    fun `cache identity is stable across revisions and isolated between users and target languages`() = runBlocking {
        val fixture = Fixture()
        val user = liveTranslationTestUser()
        fixture.service.translate(user, request())
        fixture.service.translate(user, request().copy(revision = 9))
        fixture.service.translate(liveTranslationTestUser("other-user"), request())
        fixture.service.translate(user, request().copy(targetLanguage = "he"))
        val keys = fixture.runtime.requests.map { it.options.toolContext.getValue("promptCacheKey") }
        assertEquals(keys[0], keys[1])
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun `source order is retained even when the model returns blocks out of order`() = runBlocking {
        val fixture = Fixture()
        fixture.runtime.output = """{"blocks":[{"id":"two","text":"Второй"},{"id":"work","text":"Первый"}]}"""
        val result = fixture.service.translate(liveTranslationTestUser(), request().copy(blocks = request().blocks +
            LiveTranslationSourceBlock("two", "Second.")))
        assertEquals(listOf("work", "two"), result.blocks.map { it.id })
    }

    @Test
    fun `missing duplicate unknown blank or malformed model output is rejected without a repair loop`() = runBlocking {
        for (output in listOf(
            """{"blocks":[]}""",
            """{"blocks":[{"id":"unknown","text":"Text"}]}""",
            """{"blocks":[{"id":"work","text":"One"},{"id":"work","text":"Two"}]}""",
            """{"blocks":[{"id":"work","text":""}]}""",
            "not json",
        )) {
            val fixture = Fixture()
            fixture.runtime.output = output
            assertFailsWith<Exception> { fixture.service.translate(liveTranslationTestUser(), request()) }
            assertEquals(1, fixture.runtime.requests.size)
        }
    }

    @Test
    fun `refusal and incomplete output never become successful translation`() = runBlocking {
        for (outcome in listOf(AiStepOutcome.REFUSED, AiStepOutcome.INCOMPLETE, AiStepOutcome.FAILED)) {
            val fixture = Fixture()
            fixture.runtime.outcome = outcome
            assertFailsWith<IllegalStateException> { fixture.service.translate(liveTranslationTestUser(), request()) }
        }
    }

    @Test
    fun `AI permission is checked before resolving a runtime`() = runBlocking {
        val fixture = Fixture()
        assertFailsWith<IllegalStateException> {
            fixture.service.translate(liveTranslationTestUser().copy(aiAllowed = false), request())
        }
        assertTrue(fixture.selections.isEmpty())
        assertTrue(fixture.runtime.requests.isEmpty())
    }

    @Test
    fun `non OpenAI and speech only configurations are rejected before execution`() = runBlocking {
        for (model in listOf("anthropic-opus-5-5", "openai-api-gpt-transcribe")) {
            val fixture = Fixture()
            assertFailsWith<IllegalArgumentException> {
                fixture.service.translate(liveTranslationTestUser(), request().copy(
                    runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id(model))))
            }
            assertTrue(fixture.runtime.requests.isEmpty())
        }
    }

    @Test
    fun `catalog changes during runtime preparation cannot reroute meeting text`() = runBlocking {
        val fixture = Fixture()
        fixture.beforeRuntime = { fixture.configuration.advanceRevision() }
        assertFailsWith<IllegalStateException> { fixture.service.translate(liveTranslationTestUser(), request()) }
        assertTrue(fixture.runtime.requests.isEmpty())
    }

    @Test
    fun `unknown model output limits remain unspecified rather than inventing a provider limit`() = runBlocking {
        val fixture = Fixture()
        fixture.service.translate(liveTranslationTestUser(), request().copy(
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("openai-api-gpt-4o-mini"))))
        assertEquals(null, fixture.runtime.requests.single().options.maxOutputTokens)
    }

    @Test
    fun `missing Live assignment does not fall back to chat or subscription`() = runBlocking {
        val fixture = Fixture()
        fixture.configuration.removeAssignment()
        assertFailsWith<IllegalStateException> { fixture.service.translate(liveTranslationTestUser(), request()) }
        assertTrue(fixture.runtime.requests.isEmpty())
    }

    private fun request() = TranslateLiveTextRequest("opaque-session-id", 7, "ru", emptyList(),
        listOf(LiveTranslationSourceBlock("work", "Translate these sentences together. They are one block.")))

    private class Fixture {
        val configuration = LiveTranslationTestConfiguration()
        val runtime = FakeRuntime()
        val selections = mutableListOf<AiRuntimeSelection>()
        var beforeRuntime: (() -> Unit)? = null
        val service = LiveTranslationApplicationService(configuration, object : AiRuntimeProvider {
            override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime {
                assertEquals(null, workspaceRootPath)
                selections += selection
                beforeRuntime?.invoke()
                return runtime
            }
        })
    }

    private class FakeRuntime : AiRuntime {
        val requests = mutableListOf<AiRuntimeRequest>()
        var output = """{"blocks":[{"id":"work","text":"Перевод"}]}"""
        var outcome = AiStepOutcome.COMPLETE
        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            requests += request
            return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(
                Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText(output)),
            ))), outcome = outcome)
        }
        override fun stream(request: AiRuntimeRequest) = flow { emit(call(request)) }
    }
}

internal class LiveTranslationTestConfiguration : AiConfigurationProvider {
    private val templates = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
    override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(AiCatalogSnapshot(
        templates, 1, runtimeEnabledConnectionIds = setOf(AiConnection.Id("openai-api")),
    ))
    override val snapshot: AiCatalogSnapshot get() = requireNotNull(snapshotFlow.value)
    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime =
        requireNotNull(snapshot.resolveRuntimeIfAvailable(selection)) { "Unavailable test runtime" }
    fun advanceRevision() { snapshotFlow.value = snapshot.copy(revision = snapshot.revision + 1) }
    fun removeAssignment() { snapshotFlow.value = snapshot.copy(catalog = snapshot.catalog.copy(
        runtimeAssignments = snapshot.catalog.runtimeAssignments.filterNot { it.purpose == AiRuntimeAssignment.Purpose.LIVE_TRANSLATION })) }
}

internal fun liveTranslationTestUser(id: String = "live-test-user") = User(
    id = User.Id(id), displayName = "Live translation test", status = User.Status.ACTIVE,
    createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0),
)
