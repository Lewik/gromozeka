package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryReconciliationAction

internal object MemoryClaimPredicateQualityPolicy {
    private val forbiddenPredicates = setOf("is_described_as")

    fun validateCandidates(candidates: List<MemoryClaimCandidate>) {
        candidates.firstOrNull { it.hasForbiddenPredicate() }?.let { candidate ->
            throw IllegalArgumentException(
                "Predicate '${candidate.predicate}' is too generic for a claim. " +
                    "Use a specific durable relation or omit this claim; descriptive context belongs to non-claim memory."
            )
        }
    }

    fun validateReconciliationOps(ops: List<MemoryClaimReconciliationOp>) {
        ops.firstOrNull { it.writesForbiddenPredicate() }?.let { op ->
            val predicate = op.candidate?.predicate.orEmpty()
            throw IllegalArgumentException(
                "Canonical predicate '$predicate' is too generic for a claim. " +
                    "Use a specific durable relation or return noop for this candidate."
            )
        }
    }

    private fun MemoryClaimCandidate.hasForbiddenPredicate(): Boolean =
        listOfNotNull(predicate, predicateFamily, predicatePolicy?.predicate)
            .any { it.normalizedPredicate() in forbiddenPredicates }

    private fun MemoryClaimReconciliationOp.writesForbiddenPredicate(): Boolean =
        action in setOf(
            MemoryReconciliationAction.INSERT,
            MemoryReconciliationAction.SUPERSEDE,
            MemoryReconciliationAction.UPDATE,
        ) && candidate?.hasForbiddenPredicate() == true

    private fun String.normalizedPredicate(): String =
        Regex("[A-Za-z0-9]+")
            .findAll(trim().lowercase())
            .joinToString("_") { it.value }
}
