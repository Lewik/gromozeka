package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.activeDefinitions

internal fun MemoryPredicateCatalog.renderForMemoryPrompt(
    retrievalPlan: MemoryWriteRetrievalPlan? = null,
    maxDefinitions: Int = 80,
): String {
    val hints = retrievalPlan?.predicateHints
        .orEmpty()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.lowercase() }
        .toSet()

    val definitions = activeDefinitions()
        .sortedWith(
            compareByDescending<com.gromozeka.domain.model.memory.MemoryPredicateDefinition> {
                it.predicate.lowercase() in hints
            }.thenBy { it.predicate },
        )
        .take(maxDefinitions)

    if (definitions.isEmpty()) {
        return "none"
    }

    return definitions.joinToString("\n") { definition ->
        "- ${definition.predicate}: ${definition.description}; " +
            "subject=${definition.subjectType?.name ?: "any"}; object=${definition.objectKind.name}; " +
            "cardinality=${definition.cardinality.name}; temporal=${definition.temporalPolicy.name}; " +
            "conflict=${definition.conflictPolicy.name}; importance=${definition.defaultImportance}"
    }
}
