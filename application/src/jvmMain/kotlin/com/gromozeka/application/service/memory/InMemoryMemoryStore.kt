package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.activeDefinitions
import kotlinx.datetime.Instant

class InMemoryMemoryStore(
    initialSnapshot: MemoryNamespaceSnapshot = MemoryNamespaceSnapshot(),
) : MemoryStore {
    private val predicateDefinitions = initialSnapshot.predicateDefinitions.toMutableList()
    private val sources = initialSnapshot.sources.toMutableList()
    private val runs = initialSnapshot.runs.toMutableList()
    private val entities = initialSnapshot.entities.toMutableList()
    private val claims = initialSnapshot.claims.toMutableList()
    private val notes = initialSnapshot.notes.toMutableList()
    private val tasks = initialSnapshot.tasks.toMutableList()
    private val profiles = initialSnapshot.profiles.toMutableList()
    private val episodes = initialSnapshot.episodes.toMutableList()

    override suspend fun apply(batch: MemoryUpdateBatch) {
        predicateDefinitions.upsertAll(batch.predicateDefinitions) { it.id.value }
        sources.upsertAll(batch.sources) { it.id.value }
        runs.upsertAll(batch.runs) { it.id.value }
        entities.upsertAll(batch.entities) { it.id.value }
        claims.upsertAll(batch.claims) { it.id.value }
        notes.upsertAll(batch.notes) { it.id.value }
        tasks.upsertAll(batch.tasks) { it.id.value }
        profiles.upsertAll(batch.profiles) { it.id.value }
        episodes.upsertAll(batch.episodes) { it.id.value }
    }

    override suspend fun search(request: MemoryStore.SearchRequest): List<MemoryStore.SearchHit> {
        val includeAll = request.scopes.contains(MemoryStore.SearchScope.ALL)
        val hits = buildList {
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.SOURCES)) {
                sources
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .filter { request.includeArchived || it.deletedAt == null }
                    .mapTo(this) { MemoryStore.SearchHit.SourceHit(it, score = 1.0) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.ENTITIES)) {
                entities
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .filter { request.filters.entityIds.isEmpty() || it.id in request.filters.entityIds }
                    .mapTo(this) { MemoryStore.SearchHit.EntityHit(it, score = 1.0) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.CLAIMS)) {
                claims
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .filter { request.filters.claimStatuses.isEmpty() || it.status in request.filters.claimStatuses }
                    .filter { request.filters.entityIds.isEmpty() || it.subjectEntityId in request.filters.entityIds || it.objectEntityId in request.filters.entityIds }
                    .filter { request.filters.claimPredicates.isEmpty() || it.predicate in request.filters.claimPredicates }
                    .filter { request.filters.scopes.isEmpty() || it.scope in request.filters.scopes }
                    .filter { request.includeArchived || it.archivedAt == null }
                    .mapTo(this) { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.NOTES)) {
                notes
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .filter { request.filters.noteStatuses.isEmpty() || it.status in request.filters.noteStatuses }
                    .filter { request.filters.noteTypes.isEmpty() || it.noteType in request.filters.noteTypes }
                    .filter {
                        request.filters.entityIds.isEmpty() ||
                            it.anchorEntityId in request.filters.entityIds ||
                            it.entityRefs.any { ref -> ref.entityId in request.filters.entityIds }
                    }
                    .filter { request.filters.scopes.isEmpty() || it.scope in request.filters.scopes }
                    .filter { request.includeArchived || it.archivedAt == null }
                    .mapTo(this) { MemoryStore.SearchHit.NoteHit(it, score = 1.0) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.TASKS)) {
                tasks
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .filter { request.filters.taskStatuses.isEmpty() || it.status in request.filters.taskStatuses }
                    .filter {
                        request.filters.entityIds.isEmpty() ||
                            it.ownerEntityId in request.filters.entityIds ||
                            it.assigneeEntityId in request.filters.entityIds ||
                            it.relatedEntityIds.any { entityId -> entityId in request.filters.entityIds }
                    }
                    .filter { request.filters.scopes.isEmpty() || it.scope in request.filters.scopes }
                    .filter { request.includeArchived || it.archivedAt == null }
                    .mapTo(this) { MemoryStore.SearchHit.TaskHit(it, score = 1.0) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.PROFILES)) {
                profiles
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .filter { request.filters.entityIds.isEmpty() || it.ownerEntityId in request.filters.entityIds }
                    .mapTo(this) { MemoryStore.SearchHit.ProfileHit(it, score = 1.0) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.EPISODES)) {
                episodes
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .filter { request.filters.entityIds.isEmpty() || it.ownerEntityId in request.filters.entityIds }
                    .filter { request.includeArchived || it.archivedAt == null }
                    .mapTo(this) { MemoryStore.SearchHit.EpisodeHit(it, score = 1.0) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.RUNS)) {
                runs
                    .filter { request.namespace == null || it.namespace == request.namespace }
                    .mapTo(this) { MemoryStore.SearchHit.RunHit(it, score = 1.0) }
            }
        }

        return hits.take(request.limit)
    }

    override suspend fun loadNamespaceSnapshot(
        namespace: MemoryNamespace,
        includeArchived: Boolean,
    ): MemoryNamespaceSnapshot {
        ensureDefaultPredicateCatalog(namespace)
        return MemoryNamespaceSnapshot(
            predicateDefinitions = predicateDefinitions
                .filter { it.namespace == namespace }
                .activeDefinitions(),
            sources = sources.filter { it.namespace == namespace && (includeArchived || it.deletedAt == null) },
            runs = runs.filter { it.namespace == namespace },
            entities = entities.filter { it.namespace == namespace },
            claims = claims.filter { it.namespace == namespace && (includeArchived || it.archivedAt == null) },
            notes = notes.filter { it.namespace == namespace && (includeArchived || it.archivedAt == null) },
            tasks = tasks.filter { it.namespace == namespace && (includeArchived || it.archivedAt == null) },
            profiles = profiles.filter { it.namespace == namespace },
            episodes = episodes.filter { it.namespace == namespace && (includeArchived || it.archivedAt == null) },
        )
    }

    override suspend fun loadPredicateCatalog(namespace: MemoryNamespace): MemoryPredicateCatalog {
        ensureDefaultPredicateCatalog(namespace)
        return predicateDefinitions
            .filter { it.namespace == namespace }
            .activeDefinitions()
    }

    override suspend fun findEntitiesByNormalizedNames(
        namespace: MemoryNamespace,
        normalizedNames: Set<String>,
    ): List<MemoryEntity> {
        return entities.filter {
            it.namespace == namespace &&
                (it.normalizedName in normalizedNames || it.aliases.any { alias -> alias.normalizedText in normalizedNames })
        }
    }

    override suspend fun findSourcesByIds(sourceIds: List<MemorySource.Id>): List<MemorySource> {
        val ids = sourceIds.toSet()
        return sources.filter { it.id in ids }
    }

    override suspend fun findProfile(
        namespace: MemoryNamespace,
        ownerEntityId: MemoryEntity.Id?,
    ): MemoryProfile? {
        return profiles.firstOrNull {
            it.namespace == namespace && (ownerEntityId == null || it.ownerEntityId == ownerEntityId)
        }
    }

    override suspend fun findConversationSources(conversationId: Conversation.Id): List<MemorySource> {
        return sources.filter {
            it is MemorySource.ChatTurn && it.conversationId == conversationId
        }
    }

    override suspend fun touchReferences(
        references: List<MemoryItemRef>,
        usedAt: Instant,
    ) = Unit

    private fun ensureDefaultPredicateCatalog(namespace: MemoryNamespace) {
        val existingPredicates = predicateDefinitions
            .filter { it.namespace == namespace }
            .mapTo(mutableSetOf()) { it.predicate.lowercase() }
        val missingDefaults = MemoryPredicateCatalogDefaults.forNamespace(namespace)
            .filter { it.predicate.lowercase() !in existingPredicates }

        predicateDefinitions.upsertAll(missingDefaults) { it.id.value }
    }

    private fun <T> MutableList<T>.upsertAll(
        items: List<T>,
        key: (T) -> String,
    ) {
        val ids = items.map(key).toSet()
        removeAll { key(it) in ids }
        addAll(items)
    }
}
