package com.gromozeka.presentation.services

import com.gromozeka.client.LiveInterpreterClient
import com.gromozeka.client.LiveInterpreterRemoteSession
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.remote.protocol.LiveTextTranslatedResponse
import com.gromozeka.remote.protocol.TranslateLiveTextRequest
import com.gromozeka.remote.protocol.TranslatedLiveBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTranslationControllerTest {
    @Test
    fun `first final starts immediately and later finals are batched with the previous draft`() = runTest {
        val client = FakeClient()
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "First sentence.")
        runCurrent()
        assertEquals(1, client.requests.size)
        controller.acceptFinal(1, 1, "b", "Second sentence.")
        controller.acceptFinal(1, 2, "c", "Third sentence.")
        runCurrent()
        assertEquals(1, client.requests.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, client.requests.size)
        val second = client.requests.last().blocks.single()
        assertEquals("First sentence.\nSecond sentence.\nThird sentence.", second.text)
        assertEquals("ru:First sentence.", second.previousTranslation)
        assertEquals(1, client.maximumActive)
    }

    @Test
    fun `speaker changes close source blocks so translations keep diarized turns separate`() = runTest {
        val controller = LiveTranslationController(FakeClient(), backgroundScope, emptyList())
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "First speaker.", speakerId = "1")
        controller.acceptFinal(1, 1, "b", "Second speaker.", speakerId = "2")

        assertEquals(listOf("First speaker.", "Second speaker."), controller.state.value.blocks.map { it.text })
        assertEquals(listOf("1", "2"), controller.state.value.blocks.map { it.speakerId })
        assertTrue(controller.state.value.blocks.first().closed)
        assertFalse(controller.state.value.blocks.last().closed)
    }

    @Test
    fun `adding a language translates all old source blocks without retranslating completed languages`() = runTest {
        val client = FakeClient()
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"), blockCharacters = 40)
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "An original paragraph with several sentences. ".repeat(30))
        controller.finish()
        runCurrent()
        val original = controller.state.value.blocks
        val russianCalls = client.requests.count { it.targetLanguage == "ru" }
        val russianValues = controller.state.value.columns.getValue("ru").values
        controller.setLanguages(listOf("ru", "he"))
        runCurrent()
        assertEquals(russianCalls, client.requests.count { it.targetLanguage == "ru" })
        assertEquals(russianValues, controller.state.value.columns.getValue("ru").values)
        assertEquals(original.map { it.id }.toSet(), client.requests.filter { it.targetLanguage == "he" }
            .flatMap { it.blocks }.map { it.id }.toSet())
        assertEquals(original.size, controller.state.value.columns.getValue("he").values.size)
        assertEquals(original.lastIndex, controller.state.value.columns.getValue("he").fixedThrough)
        assertEquals(1, client.maximumActive)
    }

    @Test
    fun `round robin serves new speech while another language catches up`() = runTest {
        val client = FakeClient()
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"),
            blockCharacters = 40, batchCharacters = 120)
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "Historical words to translate. ".repeat(30))
        runCurrent()
        client.requests.clear()
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        controller.setLanguages(listOf("ru", "he"))
        runCurrent()
        assertEquals("he", client.requests.single().targetLanguage)
        controller.acceptFinal(1, 1, "b", "Fresh speech has arrived.")
        gate.complete(Unit)
        runCurrent()
        assertEquals("ru", client.requests[1].targetLanguage)
        assertTrue(client.requests[1].blocks.joinToString("") { it.text }.contains("Fresh"))
        assertTrue(client.requests.filter { it.targetLanguage == "ru" }.any { request ->
            request.blocks.joinToString("") { it.text }.contains("Fresh speech has arrived.")
        })
        assertEquals(1, client.maximumActive)
    }

    @Test
    fun `native final ordering includes empty turns and does not use arrival order`() = runTest {
        val client = FakeClient()
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 2, "c", "Third.")
        controller.acceptFinal(1, 1, "b", "Second.")
        runCurrent()
        assertTrue(client.requests.isEmpty())
        controller.acceptFinal(1, 0, "empty", "")
        runCurrent()
        assertEquals("Second.\nThird.", client.requests.single().blocks.single().text)
        assertNull(controller.state.value.sourceError)
    }

    @Test
    fun `an ASR gap keeps known out of order finals and separates their source blocks`() = runTest {
        val client = FakeClient()
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "First.")
        controller.acceptFinal(1, 2, "c", "Third.")
        controller.gap("gap", localizedText("interpreter.interrupted"))
        controller.beginEpoch(2)
        controller.acceptFinal(2, 0, "d", "After reconnect.")
        runCurrent()
        assertEquals(listOf("First.", "Third.", "After reconnect."), controller.state.value.blocks.map { it.text })
        assertEquals(2, controller.state.value.gaps.single().beforeBlock)
        assertNull(controller.state.value.sourceError)
    }

    @Test
    fun `language removal does not let an old request replace a new meeting`() = runTest {
        val client = FakeClient()
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "old", "Old meeting.")
        runCurrent()
        val oldId = client.requests.single().sessionId
        controller.setLanguages(listOf("he"))
        controller.reset()
        controller.beginEpoch(2)
        controller.acceptFinal(2, 0, "new", "New meeting.")
        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.sessionId == oldId)
        assertEquals(listOf("he"), controller.state.value.languages)
        assertEquals(setOf("he"), controller.state.value.columns.keys)
        assertTrue(controller.state.value.columns.getValue("he").values.values.all { "Old" !in it.text })
        assertEquals(1, client.maximumActive)
    }

    @Test
    fun `fixed translations cannot be revised by subsequent requests`() = runTest {
        val client = FakeClient()
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"), blockCharacters = 40)
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "Many stable words. ".repeat(40))
        runCurrent()
        val before = controller.state.value
        val fixed = before.columns.getValue("ru").fixedThrough
        assertTrue(fixed >= 0)
        val fixedIds = before.blocks.take(fixed + 1).map { it.id }.toSet()
        val fixedValues = before.columns.getValue("ru").values.filterKeys { it in fixedIds }
        client.requests.clear()
        controller.acceptFinal(1, 1, "b", "A new fact follows the fixed prefix.")
        runCurrent()
        assertTrue(client.requests.flatMap { it.blocks }.none { it.id in fixedIds })
        assertEquals(fixedValues, controller.state.value.columns.getValue("ru").values.filterKeys { it in fixedIds })
    }

    @Test
    fun `context is trimmed in large steps while substantial source context remains`() = runTest {
        val client = FakeClient()
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"),
            blockCharacters = 80, batchCharacters = 240, contextHighCharacters = 3_000,
            contextLowCharacters = 1_500, retainedSourceCharacters = 400)
        controller.beginEpoch(1)
        repeat(70) { index ->
            controller.acceptFinal(1, index, "source-$index", "A complete phrase in context. ".repeat(3))
            runCurrent()
        }
        val state = controller.state.value
        val column = state.columns.getValue("ru")
        assertTrue(column.contextStart > 0)
        assertTrue(state.blocks.subList(column.contextStart, column.fixedThrough + 1).sumOf { it.text.length } >= 400)
        val starts = client.requests.mapNotNull { it.context.firstOrNull()?.id }
        assertTrue(starts.zipWithNext().any { it.first == it.second })
        val indices = starts.distinct().map { id -> state.blocks.indexOfFirst { it.id == id } }
        assertTrue(indices.zipWithNext().any { it.second - it.first > 1 })
        assertNull(state.sourceError)
    }

    @Test
    fun `a translation failure pauses only that language and explicit retry keeps the original`() = runTest {
        val client = FakeClient()
        client.failLanguage = "ru"
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru", "he"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "The original remains intact.")
        runCurrent()
        assertNotNull(controller.state.value.columns.getValue("ru").error)
        assertNull(controller.state.value.columns.getValue("he").error)
        assertEquals("The original remains intact.", controller.state.value.blocks.single().text)
        client.failLanguage = null
        controller.retry("ru")
        runCurrent()
        assertNull(controller.state.value.columns.getValue("ru").error)
        assertEquals("ru:The original remains intact.", controller.state.value.columns.getValue("ru").values.values.single().text)
    }

    @Test
    fun `a local request timeout does not cancel the translation worker`() = runTest {
        val client = FakeClient()
        client.timeout = true
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "Original.")
        runCurrent()
        advanceTimeBy(2)
        runCurrent()
        assertNotNull(controller.state.value.columns.getValue("ru").error)
        client.timeout = false
        controller.retry("ru")
        runCurrent()
        assertNull(controller.state.value.columns.getValue("ru").error)
        assertNotNull(controller.state.value.columns.getValue("ru").values["block-0"])
    }

    @Test
    fun `interface locale supplies the default but does not replace explicit language choices`() = runTest {
        val controller = LiveTranslationController(FakeClient(), backgroundScope, listOf("en"))
        controller.useInterfaceLanguage("he")
        assertEquals(listOf("he"), controller.state.value.languages)
        controller.setLanguages(listOf("ru", "en"))
        controller.useInterfaceLanguage("de")
        assertEquals(listOf("ru", "en"), controller.state.value.languages)
    }

    @Test
    fun `source splitting preserves Hebrew emoji and whitespace and Stop fixes the translated tail`() = runTest {
        val client = FakeClient()
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"), blockCharacters = 40)
        val original = "שלום 😀 English русский. ".repeat(15)
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", original)
        controller.finish()
        runCurrent()
        val state = controller.state.value
        assertEquals(original, state.blocks.joinToString("") { it.text })
        assertEquals(state.blocks.lastIndex, state.columns.getValue("ru").fixedThrough)
        assertTrue(state.blocks.none { it.text.last() in '\uD800'..'\uDBFF' })
        assertNull(state.sourceError)
    }

    @Test
    fun `reactivating a failed language retries without changing other translations`() = runTest {
        val client = FakeClient()
        client.failLanguage = "ru"
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru", "he"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "Original.")
        runCurrent()
        val hebrew = controller.state.value.columns.getValue("he").values
        client.failLanguage = null
        controller.setLanguages(listOf("he"))
        controller.setLanguages(listOf("he", "ru"))
        runCurrent()
        assertNull(controller.state.value.columns.getValue("ru").error)
        assertEquals(hebrew, controller.state.value.columns.getValue("he").values)
        assertEquals(1, client.requests.count { it.targetLanguage == "he" })
    }

    @Test
    fun `failure of a disabled attempt cannot pause its reactivated language`() = runTest {
        val client = FakeClient()
        client.failLanguage = "ru"
        client.failOnce = true
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val controller = LiveTranslationController(client, backgroundScope, listOf("ru"))
        controller.beginEpoch(1)
        controller.acceptFinal(1, 0, "a", "Original.")
        runCurrent()
        controller.setLanguages(emptyList())
        controller.setLanguages(listOf("ru"))
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, client.requests.size)
        assertNull(controller.state.value.columns.getValue("ru").error)
        assertEquals("ru:Original.", controller.state.value.columns.getValue("ru").values.values.single().text)
    }

    @Test
    fun `rapid selection changes use current state rather than a stale UI list`() = runTest {
        val controller = LiveTranslationController(FakeClient(), backgroundScope, listOf("en"))
        controller.toggleLanguage("he")
        controller.toggleLanguage("ru")
        assertEquals(listOf("en", "he", "ru"), controller.state.value.languages)
        controller.toggleLanguage("en")
        controller.addLanguage("fr")
        controller.addLanguage("fr")
        assertEquals(listOf("he", "ru", "fr"), controller.state.value.languages)
    }

    @Test
    fun `a completed utterance that fits a fresh block is not split to fill the previous block`() = runTest {
        val controller = LiveTranslationController(FakeClient(), backgroundScope, emptyList(), blockCharacters = 40)
        controller.beginEpoch(1)
        val first = "A first complete utterance."
        val second = "A second complete utterance."
        controller.acceptFinal(1, 0, "a", first)
        controller.acceptFinal(1, 1, "b", second)
        assertEquals(listOf(first, second), controller.state.value.blocks.map { it.text })
    }

    @Test
    fun `long source blocks prefer natural boundaries but do not enforce one sentence per block`() = runTest {
        val controller = LiveTranslationController(FakeClient(), backgroundScope, emptyList(), blockCharacters = 60)
        controller.beginEpoch(1)
        val original = "One short sentence. Another short sentence. A third sentence has some more words."
        controller.acceptFinal(1, 0, "a", original)
        val blocks = controller.state.value.blocks
        assertEquals("One short sentence. Another short sentence.", blocks.first().text)
        assertEquals(original, blocks.joinToString("") { it.text })
    }

    private class FakeClient : LiveInterpreterClient {
        val requests = mutableListOf<TranslateLiveTextRequest>()
        var gate: CompletableDeferred<Unit>? = null
        var failLanguage: String? = null
        var failOnce = false
        var timeout = false
        var active = 0
        var maximumActive = 0
        override suspend fun start(
            languages: List<String>,
            selection: AiRuntimeSelection,
            nativeTranslationLanguage: String?,
        ): LiveInterpreterRemoteSession = error("Unexpected ASR start")
        override suspend fun translate(request: TranslateLiveTextRequest): LiveTextTranslatedResponse {
            requests += request
            active += 1
            maximumActive = maxOf(maximumActive, active)
            try {
                val wait = gate
                gate = null
                wait?.await()
                if (timeout) withTimeout(1) { CompletableDeferred<Unit>().await() }
                val failed = request.targetLanguage == failLanguage
                if (failed && failOnce) failLanguage = null
                check(!failed) { "Synthetic provider failure" }
                return LiveTextTranslatedResponse(request.revision, request.targetLanguage,
                    request.blocks.map { TranslatedLiveBlock(it.id, "${request.targetLanguage}:${it.text}") })
            } finally { active -= 1 }
        }
    }
}
