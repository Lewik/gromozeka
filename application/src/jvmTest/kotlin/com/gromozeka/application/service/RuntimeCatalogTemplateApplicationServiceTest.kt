package com.gromozeka.application.service

import com.gromozeka.domain.model.AiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuntimeCatalogTemplateApplicationServiceTest {
    @Test
    fun loadsModelSpecsFromDefaultResource() {
        val catalog = RuntimeCatalogTemplateApplicationService().getTemplates().aiCatalog
        val spec = catalog.modelSpecs.single {
            it.provider == AiProvider.OPENAI && it.id == "gpt-5.5"
        }

        assertNotNull(spec)
        assertEquals(272_000, spec.contextWindowTokens)
        assertEquals(217_600, spec.autoCompactionThresholdTokens)
    }
}
