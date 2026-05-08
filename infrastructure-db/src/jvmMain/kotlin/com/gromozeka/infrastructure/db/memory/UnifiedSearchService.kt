package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.tool.memory.SearchScope
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class UnifiedSearchService(
    private val memoryStore: MemoryStore,
) {
    private val log = KLoggers.logger(this)

    suspend fun unifiedSearch(
        query: String,
        scopes: Set<SearchScope>,
        knowledgeKinds: Set<String> = emptySet(),
        standings: Set<String> = emptySet(),
        bases: Set<String> = emptySet(),
        relationRoles: Set<String> = emptySet(),
        perspectiveKind: String? = null,
        perspectiveValue: String? = null,
        includeInvalidated: Boolean = false,
        limit: Int = 5,
    ): List<MemoryStore.SearchHit> {
        log.info {
            "Unified search: query='$query' scopes=$scopes includeArchived=$includeInvalidated limit=$limit " +
                "ignoredLegacyFilters=${knowledgeKinds.size + standings.size + bases.size + relationRoles.size} " +
                "ignoredPerspective=${!perspectiveKind.isNullOrBlank() || !perspectiveValue.isNullOrBlank()}"
        }

        return memoryStore.search(
            MemoryStore.SearchRequest(
                query = query,
                scopes = scopes.toStoreScopes(),
                includeArchived = includeInvalidated,
                limit = limit,
            )
        ).also { results ->
            log.info { "Unified search: results=${results.size} for query='$query'" }
        }
    }

    private fun Set<SearchScope>.toStoreScopes(): Set<MemoryStore.SearchScope> {
        if (isEmpty()) {
            return setOf(MemoryStore.SearchScope.ALL)
        }

        return mapTo(linkedSetOf()) { scope ->
            when (scope) {
                SearchScope.ALL -> MemoryStore.SearchScope.ALL
                SearchScope.SOURCES, SearchScope.EPISODES, SearchScope.EVENT_LOG -> MemoryStore.SearchScope.SOURCES
                SearchScope.ENTITIES, SearchScope.WORLD_ENTITIES -> MemoryStore.SearchScope.ENTITIES
                SearchScope.CLAIMS, SearchScope.ASSERTIONS -> MemoryStore.SearchScope.CLAIMS
                SearchScope.NOTES, SearchScope.EDGES, SearchScope.RELATIONS -> MemoryStore.SearchScope.NOTES
                SearchScope.TASKS, SearchScope.PROCEDURES, SearchScope.OPERATIONAL -> MemoryStore.SearchScope.TASKS
                SearchScope.PROFILES -> MemoryStore.SearchScope.PROFILES
                SearchScope.RUNS -> MemoryStore.SearchScope.RUNS
            }
        }
    }
}
