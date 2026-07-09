package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryStore

internal class MemoryNamespaceRecallAccessGuard(
    private val store: MemoryStore,
) {
    suspend fun ensureRecallSupported(requestedNamespace: MemoryNamespace) {
        val storedNamespaces = store.listNamespaceSummaries()
            .filter { it.hasRecallRelevantItems() }
            .map { it.namespace }
            .distinctBy { it.value }
            .sortedBy { it.value }
        val effectiveNamespaces = (storedNamespaces + requestedNamespace)
            .distinctBy { it.value }
            .sortedBy { it.value }

        if (effectiveNamespaces.size > 1) {
            throw MemoryNamespaceRecallAccessException(
                requestedNamespace = requestedNamespace,
                storedNamespaces = storedNamespaces,
                effectiveNamespaces = effectiveNamespaces,
            )
        }
    }
}

internal class MemoryNamespaceRecallAccessException(
    requestedNamespace: MemoryNamespace,
    storedNamespaces: List<MemoryNamespace>,
    effectiveNamespaces: List<MemoryNamespace>,
) : IllegalStateException(
    "Memory recall across multiple namespaces is not supported until namespace access control is implemented. " +
        "requested_namespace=${requestedNamespace.value}; " +
        "stored_namespaces=${storedNamespaces.joinToString(prefix = "[", postfix = "]") { it.value }}; " +
        "effective_namespaces=${effectiveNamespaces.joinToString(prefix = "[", postfix = "]") { it.value }}."
)

private fun MemoryNamespaceSummary.hasRecallRelevantItems(): Boolean =
    counts.sources +
        counts.runs +
        counts.entities +
        counts.claims +
        counts.notes +
        counts.actionItems +
        counts.profiles +
        counts.episodes > 0
