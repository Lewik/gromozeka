package com.gromozeka.server

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LiveInterpreterTranslationPromptTest {
    @Test
    fun translationPromptCarriesRollingContextAndAppendOnlyInstruction() {
        val systemPrompt = buildLiveInterpreterTranslationSystemPrompt(
            targetLanguage = "ru",
            sourceLanguageHint = "Hebrew and English",
        )
        val userPrompt = buildLiveInterpreterTranslationUserPrompt(
            LiveInterpreterTranslationPromptContext(
                finalizedOriginalTranscriptTail = "שלום\nLet's deploy after lunch",
                publishedTranslationTail = "Привет",
                newFinalOriginalDelta = "Let's deploy after lunch",
            )
        )

        assertContains(systemPrompt, "Target language: Russian")
        assertContains(systemPrompt, "Do not repeat already published translation")
        assertContains(userPrompt, "Finalized original transcript tail")
        assertContains(userPrompt, "Already published translation tail")
        assertContains(userPrompt, "New finalized original transcript delta to translate now")
        assertContains(userPrompt, "Return only the target-language text to append now")
    }

    @Test
    fun stabilizerPromptCarriesDraftsAndOriginalLanguageInstruction() {
        val systemPrompt = buildLiveInterpreterStabilizerSystemPrompt("Hebrew, English, and Russian")
        val userPrompt = buildLiveInterpreterStabilizerUserPrompt(
            LiveInterpreterStabilizerPromptContext(
                finalizedOriginalTranscriptTail = "שלום",
                pendingDrafts = listOf(
                    LiveInterpreterDraftChunk("draft-1", 1, "שלום hello"),
                    LiveInterpreterDraftChunk("draft-2", 2, "hello мир"),
                ),
            )
        )

        assertContains(systemPrompt, "Do not translate")
        assertContains(systemPrompt, "overlapping Whisper hypotheses")
        assertContains(userPrompt, "[draft-1] שלום hello")
        assertContains(userPrompt, "<stabilization>")
        assertContains(userPrompt, "<append>")
        assertContains(userPrompt, "<keep>")
        assertContains(userPrompt, "<drop>")
    }

    @Test
    fun stabilizerParsesXmlResponse() {
        val response = parseTranscriptStabilizerXml(
            """
            <stabilization>
              <append>שלום &amp; hello</append>
              <append>мир</append>
              <keep>draft-3</keep>
              <drop>draft-1</drop>
              <drop>draft-2</drop>
            </stabilization>
            """.trimIndent()
        )

        assertEquals(listOf("שלום & hello", "мир"), response.appendFinalOriginal)
        assertEquals(listOf("draft-3"), response.keepDraftIds)
        assertEquals(listOf("draft-1", "draft-2"), response.dropDraftIds)
    }

    @Test
    fun transcriptStateKeepsDraftsUntilStabilizerFinalizesThem() {
        val state = LiveInterpreterTranscriptState()

        state.recordDraft(1, "hello wor")
        assertEquals(
            emptyList(),
            state.applyStabilizerResponse(
                TranscriptStabilizerResponse(
                    appendFinalOriginal = emptyList(),
                    keepDraftIds = listOf("draft-1"),
                    dropDraftIds = emptyList(),
                )
            )
        )

        state.recordDraft(2, "hello world")
        assertEquals(
            listOf("hello world"),
            state.applyStabilizerResponse(
                TranscriptStabilizerResponse(
                    appendFinalOriginal = listOf("hello world"),
                    keepDraftIds = emptyList(),
                    dropDraftIds = listOf("draft-1", "draft-2"),
                )
            )
        )
    }

    @Test
    fun transcriptStateDoesNotDropUnmentionedDraftsWhenFinalizingPartialOverlap() {
        val state = LiveInterpreterTranscriptState()

        state.recordDraft(1, "יש פה גאפ. אנחנו צריכים להקשיב")
        state.recordDraft(2, "אנחנו צריכים להקשיב, אבל אנחנו לא יודעים")

        assertEquals(
            listOf("יש פה גאפ."),
            state.applyStabilizerResponse(
                TranscriptStabilizerResponse(
                    appendFinalOriginal = listOf("יש פה גאפ."),
                    keepDraftIds = emptyList(),
                    dropDraftIds = listOf("draft-1"),
                )
            )
        )

        assertEquals(
            listOf("draft-2"),
            state.stabilizerContext().pendingDrafts.map { it.id }
        )
    }

    @Test
    fun transcriptStateKeepsAllPendingDraftsUntilStabilizerDropsThem() {
        val state = LiveInterpreterTranscriptState()

        repeat(12) { index ->
            state.recordDraft(index + 1, "draft ${index + 1}")
        }

        assertEquals(
            (1..12).map { "draft-$it" },
            state.stabilizerContext().pendingDrafts.map { it.id }
        )
    }
}
