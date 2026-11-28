package com.gromozeka.application.service

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeCatalogTemplateApplicationServiceTest {
    @Test
    fun loadsModelSpecsFromDefaultResource() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val spec = catalog.modelSpecs.single {
            it.provider == AiProvider.OPENAI && it.id == "gpt-5.5"
        }

        assertEquals(272_000, spec.contextWindowTokens)
        assertTrue(catalog.modelConfigurations.all { it.compaction == AiModelConfiguration.Compaction() })
    }

    @Test
    fun exposesBothStreamingAndCommittedTranscriptionWithoutChangingDictationDefault() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val transcribe = catalog.modelSpecs.single { it.provider == AiProvider.OPENAI && it.id == "gpt-transcribe" }
        val live = catalog.modelSpecs.single { it.provider == AiProvider.OPENAI && it.id == "gpt-live-transcribe" }
        assertEquals(setOf(AiModelCapability.SPEECH_TO_TEXT, AiModelCapability.REALTIME_TRANSCRIPTION), transcribe.capabilities)
        assertEquals(setOf(AiModelCapability.REALTIME_TRANSCRIPTION), live.capabilities)
        assertEquals(
            AiModelConfiguration.Id("openai-api-gpt-transcribe"),
            catalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT)?.modelConfigurationId,
        )
    }

    @Test
    fun exposesOptionalSonioxLive2WhileKeepingWhisperOutOfLive() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val soniox = catalog.modelSpecs.single { it.provider == AiProvider.SONIOX && it.id == "stt-rt-v6" }
        assertEquals(
            setOf(AiModelCapability.SPEECH_TO_TEXT, AiModelCapability.REALTIME_TRANSCRIPTION,
                AiModelCapability.SPEAKER_DIARIZATION),
            soniox.capabilities,
        )
        val whisper = catalog.modelSpecs.single { it.provider == AiProvider.OPENAI && it.id == "whisper-1" }
        assertEquals(setOf(AiModelCapability.SPEECH_TO_TEXT), whisper.capabilities)
        val configuration = catalog.modelConfigurations.single { it.id == AiModelConfiguration.Id("soniox-api-stt-rt-v6") }
        assertEquals("soniox-api", configuration.connectionId.value)
        assertEquals(false, catalog.connections.single { it.id == configuration.connectionId }.enabled)
        assertEquals(
            AiModelConfiguration.Id("openai-api-gpt-transcribe"),
            catalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPTION)?.modelConfigurationId,
        )
    }

    @Test
    fun exposesSeparateOpenAiLiveTranslationWithoutChatFallback() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val selection = requireNotNull(catalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.LIVE_TRANSLATION))
        assertEquals(AiModelConfiguration.Id("openai-api-gpt-5.4-mini"), selection.modelConfigurationId)
        val model = catalog.modelConfigurations.single { it.id == selection.modelConfigurationId }
        assertEquals("gpt-5.4-mini", model.providerModelId)
        assertEquals("openai-api", model.connectionId.value)
        assertEquals(AiReasoningMode.ADAPTIVE, model.defaultParameters.reasoning?.mode)
        assertEquals(AiReasoningEffort.LOW, model.defaultParameters.reasoning?.effort)
        val spec = catalog.modelSpecs.single { it.provider == AiProvider.OPENAI && it.id == model.providerModelId }
        assertEquals(setOf(AiReasoningMode.DISABLED, AiReasoningMode.ADAPTIVE), spec.reasoning?.modes)
        assertEquals(setOf(AiReasoningEffort.LOW, AiReasoningEffort.MEDIUM, AiReasoningEffort.HIGH, AiReasoningEffort.XHIGH),
            spec.reasoning?.efforts)
        assertEquals(setOf(AiModelCapability.TEXT_GENERATION, AiModelCapability.STRUCTURED_OUTPUT),
            AiRuntimeAssignment.Purpose.LIVE_TRANSLATION.requiredCapabilities)
        assertEquals(null, AiRuntimeAssignment.Purpose.LIVE_TRANSLATION.fallbackPurpose)
        assertEquals(false, AiRuntimeAssignment.Purpose.LIVE_TRANSLATION.requiresExplicitAssignment)
    }

    @Test
    fun exposesQwen3Embedding4bWithNativeDimensions() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val spec = catalog.modelSpecs.single {
            it.provider == AiProvider.CUSTOM && it.id == "qwen3-embedding-4b"
        }

        assertEquals(setOf(AiModelCapability.EMBEDDINGS), spec.capabilities)
        assertEquals(2_560, spec.limits.embeddings?.dimensions)
        assertEquals(32_768, spec.limits.embeddings?.maxInputTokens)
    }

    @Test
    fun exposesClaudeOpus55WithAlwaysOnThinkingAndMediumEffort() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val spec = catalog.modelSpecs.single { it.provider == AiProvider.ANTHROPIC && it.id == "claude-opus-5-5" }
        assertEquals(1_000_000, spec.contextWindowTokens)
        assertEquals(128_000, spec.maxOutputTokens)
        assertTrue(catalog.modelConfigurations.all { it.compaction == AiModelConfiguration.Compaction() })
        assertEquals(setOf(AiReasoningMode.ADAPTIVE), spec.reasoning?.modes)
        assertEquals(AiReasoningEffort.entries.toSet(), spec.reasoning?.efforts)
        assertEquals(setOf(AiReasoningDisplay.SUMMARIZED, AiReasoningDisplay.OMITTED), spec.reasoning?.displays)
        val configuration = catalog.modelConfigurations.single { it.providerModelId == spec.id }
        assertEquals(AiModelConfiguration.Id("anthropic-opus-5-5"), configuration.id)
        assertEquals(AiReasoningMode.ADAPTIVE, configuration.defaultParameters.reasoning?.mode)
        assertEquals(AiReasoningEffort.MEDIUM, configuration.defaultParameters.reasoning?.effort)
        assertEquals(AiReasoningDisplay.SUMMARIZED, configuration.defaultParameters.reasoning?.display)
    }

    @Test
    fun exposesClaudeOpus5ForConfiguredAnthropicConnections() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val spec = catalog.modelSpecs.single {
            it.provider == AiProvider.ANTHROPIC && it.id == "claude-opus-5"
        }

        assertEquals(1_000_000, spec.contextWindowTokens)
        assertEquals(128_000, spec.maxOutputTokens)
        assertTrue(catalog.modelConfigurations.all { it.compaction == AiModelConfiguration.Compaction() })
        assertEquals(setOf(AiReasoningMode.DISABLED, AiReasoningMode.ADAPTIVE), spec.reasoning?.modes)
        assertEquals(
            setOf(
                AiReasoningEffort.LOW,
                AiReasoningEffort.MEDIUM,
                AiReasoningEffort.HIGH,
                AiReasoningEffort.XHIGH,
                AiReasoningEffort.MAX,
            ),
            spec.reasoning?.efforts,
        )

        val configurations = catalog.modelConfigurations.filter { it.providerModelId == "claude-opus-5" }
        assertEquals(
            setOf(AiModelConfiguration.Id("anthropic-opus-5")),
            configurations.map { it.id }.toSet(),
        )
        assertTrue(configurations.all {
            it.defaultParameters.reasoning?.mode == AiReasoningMode.ADAPTIVE &&
                it.defaultParameters.reasoning?.effort == AiReasoningEffort.HIGH &&
                it.defaultParameters.reasoning?.display == AiReasoningDisplay.SUMMARIZED
        })
    }
}
