package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryScope
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonPrimitive

class MemoryClaimPredicateQualityPolicyTest {
    @Test
    fun rejectsGenericBagPredicatesFromExtractionCandidates() {
        listOf("has_property", "is_a", "defined_as", "is_described_as").forEach { predicate ->
            assertFailsWith<IllegalArgumentException> {
                MemoryClaimPredicateQualityPolicy.validateCandidates(listOf(candidate(predicate)))
            }
        }
    }

    @Test
    fun rejectsGenericBagPredicatesFromReconciliationWrites() {
        listOf(
            MemoryReconciliationAction.INSERT,
            MemoryReconciliationAction.UPDATE,
            MemoryReconciliationAction.SUPERSEDE,
        ).forEach { action ->
            assertFailsWith<IllegalArgumentException> {
                MemoryClaimPredicateQualityPolicy.validateReconciliationOps(
                    listOf(
                        MemoryClaimReconciliationOp(
                            action = action,
                            candidate = candidate("has_property"),
                        )
                    )
                )
            }
        }
    }

    private fun candidate(predicate: String): MemoryClaimCandidate =
        MemoryClaimCandidate(
            subjectEntityId = MemoryEntity.Id("entity-user"),
            predicate = predicate,
            objectValue = JsonPrimitive("generic description"),
            normalizedText = "The user has a generic property.",
            scope = MemoryScope.Global(
                text = "Namespace-wide memory",
                basis = MemoryScope.Basis.EXPLICIT,
            ),
            evidenceQuote = "generic property",
            evidenceReason = "The source was descriptive.",
            reason = "This is too generic to be a claim.",
        )
}
