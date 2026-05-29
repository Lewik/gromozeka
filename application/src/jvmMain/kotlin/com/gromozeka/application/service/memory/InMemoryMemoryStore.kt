package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEmbeddingRecord
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.activeDefinitions
import com.gromozeka.domain.model.memory.requireValidEntityIds
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
    private val embeddings = mutableListOf<MemoryEmbeddingRecord>()

    override suspend fun apply(batch: MemoryUpdateBatch) {
        val validBatch = batch.requireValidEntityIds()
        predicateDefinitions.upsertAll(validBatch.predicateDefinitions) { it.id.value }
        sources.upsertAll(validBatch.sources) { it.id.value }
        runs.upsertAll(validBatch.runs) { it.id.value }
        entities.upsertAll(validBatch.entities) { it.id.value }
        claims.upsertAll(validBatch.claims) { it.id.value }
        notes.upsertAll(validBatch.notes) { it.id.value }
        tasks.upsertAll(validBatch.tasks) { it.id.value }
        profiles.upsertAll(validBatch.profiles) { it.id.value }
        episodes.upsertAll(validBatch.episodes) { it.id.value }
        embeddings.upsertAll(validBatch.embeddings) { it.id.value }
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
        return snapshotForNamespace(namespace, includeArchived)
    }

    override suspend fun listNamespaceSummaries(): List<MemoryNamespaceSummary> =
        allNamespaces()
            .sortedBy { it.value }
            .map { namespace ->
                MemoryNamespaceSummary.fromSnapshot(
                    namespace = namespace,
                    snapshot = snapshotForNamespace(namespace, includeArchived = false),
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

    override suspend fun findTypedMemoryByEvidenceSourceIds(
        namespace: MemoryNamespace,
        sourceIds: Set<MemorySource.Id>,
    ): List<MemoryStore.SearchHit> {
        if (sourceIds.isEmpty()) return emptyList()
        val directClaims = claims.filter {
            it.namespace == namespace && it.evidenceRefs.any { ref -> ref.sourceId in sourceIds }
        }
        val replacedClaimIds = directClaims
            .filter { it.status != MemoryClaim.Status.ACTIVE }
            .mapTo(mutableSetOf()) { it.id }
        val activeClaimIdsByRetraction = directClaims
            .mapNotNullTo(mutableSetOf()) { it.retractedByClaimId }
        val replacementClaims = claims.filter {
            it.namespace == namespace &&
                it.status == MemoryClaim.Status.ACTIVE &&
                (it.supersedesClaimId in replacedClaimIds || it.id in activeClaimIdsByRetraction)
        }
        val directNotes = notes.filter {
            it.namespace == namespace && it.evidenceRefs.any { ref -> ref.sourceId in sourceIds }
        }
        val replacedNoteIds = directNotes
            .filter { it.status != MemoryNote.Status.ACTIVE }
            .mapTo(mutableSetOf()) { it.id }
        val replacementNotes = notes.filter {
            it.namespace == namespace &&
                it.status == MemoryNote.Status.ACTIVE &&
                it.supersedesNoteId in replacedNoteIds
        }

        return buildList {
            (directClaims + replacementClaims)
                .distinctBy { it.id }
                .mapTo(this) { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) }
            (directNotes + replacementNotes)
                .distinctBy { it.id }
                .mapTo(this) { MemoryStore.SearchHit.NoteHit(it, score = 1.0) }
            tasks
                .filter { it.namespace == namespace && it.archivedAt == null && it.evidenceRefs.any { ref -> ref.sourceId in sourceIds } }
                .mapTo(this) { MemoryStore.SearchHit.TaskHit(it, score = 1.0) }
            episodes
                .filter { it.namespace == namespace && it.archivedAt == null && it.evidenceRefs.any { ref -> ref.sourceId in sourceIds } }
                .mapTo(this) { MemoryStore.SearchHit.EpisodeHit(it, score = 1.0) }
        }.distinctBy { it.toMemoryStoreItemRef() }
    }

    override suspend fun replaceEmbeddings(
        namespace: MemoryNamespace,
        embeddings: List<MemoryEmbeddingRecord>,
    ): Int {
        require(embeddings.all { it.namespace == namespace }) {
            "Replacement memory embeddings must all belong to namespace ${namespace.value}"
        }
        val removed = this.embeddings.count { it.namespace == namespace }
        this.embeddings.removeAll { it.namespace == namespace }
        this.embeddings.upsertAll(embeddings) { it.id.value }
        return removed
    }

    override suspend fun findEmbeddingIds(
        namespace: MemoryNamespace,
        ids: Set<MemoryEmbeddingRecord.Id>,
    ): Set<MemoryEmbeddingRecord.Id> {
        if (ids.isEmpty()) return emptySet()
        return embeddings
            .filter { it.namespace == namespace && it.id in ids }
            .mapTo(mutableSetOf()) { it.id }
    }

    override suspend fun findRunById(runId: MemoryRun.Id): MemoryRun? =
        runs.firstOrNull { it.id == runId }

    override suspend fun findRunsByParentRunId(parentRunId: MemoryRun.Id): List<MemoryRun> =
        runs.filter { it.parentRunId == parentRunId }

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

    private fun allNamespaces(): Set<MemoryNamespace> =
        buildSet {
            predicateDefinitions.mapNotNullTo(this) { it.namespace }
            sources.mapTo(this) { it.namespace }
            runs.mapTo(this) { it.namespace }
            entities.mapTo(this) { it.namespace }
            claims.mapTo(this) { it.namespace }
            notes.mapTo(this) { it.namespace }
            tasks.mapTo(this) { it.namespace }
            profiles.mapTo(this) { it.namespace }
            episodes.mapTo(this) { it.namespace }
        }

    private fun snapshotForNamespace(
        namespace: MemoryNamespace,
        includeArchived: Boolean,
    ): MemoryNamespaceSnapshot =
        MemoryNamespaceSnapshot(
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

    private fun <T> MutableList<T>.upsertAll(
        items: List<T>,
        key: (T) -> String,
    ) {
        val ids = items.map(key).toSet()
        removeAll { key(it) in ids }
        addAll(items)
    }
}

private fun MemoryStore.SearchHit.toMemoryStoreItemRef(): MemoryItemRef =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
        is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
        is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
        is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
        is MemoryStore.SearchHit.TaskHit -> MemoryItemRef(MemoryItemRef.Type.TASK, task.id.value)
        is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
        is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
        is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
    }
