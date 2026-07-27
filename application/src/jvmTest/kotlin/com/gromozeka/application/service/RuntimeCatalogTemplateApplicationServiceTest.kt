package com.gromozeka.application.service

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
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
        assertEquals(217_600, spec.autoCompactionThresholdTokens)
    }

    @Test
    fun exposesClaudeOpus5ForDirectApiAndClaudeCode() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val spec = catalog.modelSpecs.single {
            it.provider == AiProvider.ANTHROPIC && it.id == "claude-opus-5"
        }

        assertEquals(1_000_000, spec.contextWindowTokens)
        assertEquals(128_000, spec.maxOutputTokens)
        assertEquals(800_000, spec.autoCompactionThresholdTokens)
        assertEquals(setOf(AiReasoningMode.DISABLED, AiReasoningMode.ADAPTIVE), spec.reasoning?.modes)
        assertEquals(
            setOf(AiReasoningEffort.LOW, AiReasoningEffort.MEDIUM, AiReasoningEffort.HIGH, AiReasoningEffort.MAX),
            spec.reasoning?.efforts,
        )

        val configurations = catalog.modelConfigurations.filter { it.providerModelId == "claude-opus-5" }
        assertEquals(
            setOf(AiModelConfiguration.Id("anthropic-opus-5"), AiModelConfiguration.Id("claude-code-opus-5")),
            configurations.map { it.id }.toSet(),
        )
        assertTrue(configurations.all {
            it.defaultParameters.reasoning?.mode == AiReasoningMode.ADAPTIVE &&
                it.defaultParameters.reasoning?.effort == AiReasoningEffort.HIGH &&
                it.defaultParameters.reasoning?.display == AiReasoningDisplay.SUMMARIZED
        })
    }
}
