package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import kotlin.test.Test
import kotlin.test.assertTrue

class MemoryPredicateCatalogPromptRenderingTest {
    @Test
    fun rendersMachineSemanticsAndAggregateEffects() {
        val rendered = MemoryPredicateCatalogDefaults
            .forNamespace(MemoryNamespace("predicate-rendering-test"))
            .renderForMemoryPrompt(maxDefinitions = 200)

        assertTrue(rendered.contains("current_metric_value:"))
        assertTrue(rendered.contains("semantics=aggregate_value"))
        assertTrue(rendered.contains("aggregate_effect=set_current_value"))
        assertTrue(rendered.contains("aggregate_increase:"))
        assertTrue(rendered.contains("aggregate_effect=increase"))
        assertTrue(rendered.contains("aggregate_decrease:"))
        assertTrue(rendered.contains("aggregate_effect=decrease"))
        assertTrue(rendered.contains("owns:"))
        assertTrue(rendered.contains("semantics=possession"))
    }
}
