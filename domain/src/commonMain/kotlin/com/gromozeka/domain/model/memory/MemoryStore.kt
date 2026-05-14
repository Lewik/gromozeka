package com.gromozeka.domain.model.memory

import com.gromozeka.domain.model.Conversation
import kotlinx.datetime.Instant

/**
 * Persistence and retrieval contract for the memory domain.
 *
 * This is intentionally not a database API. Implementations may use SQL, vector
 * search, files, or a hybrid store as long as they preserve the typed domain
 * semantics exposed here.
 */
interface MemoryStore {
    suspend fun apply(batch: MemoryUpdateBatch)

    suspend fun search(request: SearchRequest): List<SearchHit>

    suspend fun loadNamespaceSnapshot(
        namespace: MemoryNamespace,
        includeArchived: Boolean = false,
    ): MemoryNamespaceSnapshot

    suspend fun loadPredicateCatalog(namespace: MemoryNamespace): MemoryPredicateCatalog

    suspend fun findEntitiesByNormalizedNames(
        namespace: MemoryNamespace,
        normalizedNames: Set<String>,
    ): List<MemoryEntity>

    suspend fun findSourcesByIds(sourceIds: List<MemorySource.Id>): List<MemorySource>

    suspend fun findRunById(runId: MemoryRun.Id): MemoryRun?

    suspend fun findRunsByParentRunId(parentRunId: MemoryRun.Id): List<MemoryRun>

    suspend fun findProfile(
        namespace: MemoryNamespace,
        ownerEntityId: MemoryEntity.Id? = null,
    ): MemoryProfile?

    suspend fun findConversationSources(conversationId: Conversation.Id): List<MemorySource>

    suspend fun touchReferences(
        references: List<MemoryItemRef>,
        usedAt: Instant,
    )

    /**
     * Bounded typed search request over memory.
     *
     * `query` is the semantic/search text; scopes and filters keep retrieval from
     * depending only on lexical matching.
     */
    data class SearchRequest(
        val query: String,
        val namespace: MemoryNamespace? = null,
        val scopes: Set<SearchScope> = setOf(SearchScope.ALL),
        val filters: SearchFilters = SearchFilters(),
        val timeWindow: MemoryTimeWindow? = null,
        val includeArchived: Boolean = false,
        val limit: Int = 10,
    )

    /**
     * Structured constraints that narrow search independently from the query text.
     */
    data class SearchFilters(
        val claimStatuses: Set<MemoryClaim.Status> = emptySet(),
        val noteStatuses: Set<MemoryNote.Status> = emptySet(),
        val entityIds: Set<MemoryEntity.Id> = emptySet(),
        val claimPredicates: Set<String> = emptySet(),
        val noteTypes: Set<MemoryNote.Type> = emptySet(),
        val taskStatuses: Set<MemoryTask.Status> = emptySet(),
        val scopes: Set<MemoryScope> = emptySet(),
    )

    /**
     * Coarse memory families that a search request may include.
     */
    enum class SearchScope {
        ALL,
        SOURCES,
        ENTITIES,
        CLAIMS,
        NOTES,
        TASKS,
        PROFILES,
        EPISODES,
        RUNS,
    }

    /**
     * Typed search result with a store-specific relevance score.
     */
    sealed interface SearchHit {
        val score: Double

        data class SourceHit(
            val source: MemorySource,
            override val score: Double,
        ) : SearchHit

        data class EntityHit(
            val entity: MemoryEntity,
            override val score: Double,
        ) : SearchHit

        data class ClaimHit(
            val claim: MemoryClaim,
            override val score: Double,
        ) : SearchHit

        data class NoteHit(
            val note: MemoryNote,
            override val score: Double,
        ) : SearchHit

        data class TaskHit(
            val task: MemoryTask,
            override val score: Double,
        ) : SearchHit

        data class ProfileHit(
            val profile: MemoryProfile,
            override val score: Double,
        ) : SearchHit

        data class EpisodeHit(
            val episode: MemoryEpisode,
            override val score: Double,
        ) : SearchHit

        data class RunHit(
            val run: MemoryRun,
            override val score: Double,
        ) : SearchHit
    }
}
