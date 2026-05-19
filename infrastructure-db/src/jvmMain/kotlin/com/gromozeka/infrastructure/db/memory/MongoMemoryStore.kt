package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisode
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
import com.gromozeka.infrastructure.db.persistence.mongo.MongoIndexInitializer
import com.gromozeka.infrastructure.db.persistence.mongo.findByDomainId
import com.gromozeka.infrastructure.db.persistence.mongo.upsertByDomainId
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import klog.KLoggers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.conversions.Bson
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class MongoMemoryStore(
    database: MongoDatabase,
) : MemoryStore {
    private val log = KLoggers.logger(this)

    private val predicateDefinitions: MongoCollection<MemoryPredicateDefinition> = database.getCollection("memory_predicate_definitions")
    private val sources: MongoCollection<MemorySource> = database.getCollection("memory_sources")
    private val runs: MongoCollection<MemoryRun> = database.getCollection("memory_runs")
    private val entities: MongoCollection<MemoryEntity> = database.getCollection("memory_entities")
    private val claims: MongoCollection<MemoryClaim> = database.getCollection("memory_claims")
    private val notes: MongoCollection<MemoryNote> = database.getCollection("memory_notes")
    private val tasks: MongoCollection<MemoryTask> = database.getCollection("memory_tasks")
    private val profiles: MongoCollection<MemoryProfile> = database.getCollection("memory_profiles")
    private val episodes: MongoCollection<MemoryEpisode> = database.getCollection("memory_episodes")

    private val indexes = MongoIndexInitializer {
        createDomainIndexes(predicateDefinitions, listOf("namespace", "predicate", "active"))
        createDomainIndexes(sources, listOf("namespace", "contentHash", "conversationId", "searchText"))
        createDomainIndexes(
            runs,
            listOf("namespace", "createdAt", "sourceIds", "inputHash", "parentRunId", "childRunIds", "status", "runType"),
        )
        createDomainIndexes(entities, listOf("namespace", "normalizedName", "aliases.normalizedText", "entityType"))
        createDomainIndexes(claims, listOf("namespace", "subjectEntityId", "predicate", "status"))
        createDomainIndexes(notes, listOf("namespace", "anchorEntityId", "status", "noteType"))
        createDomainIndexes(tasks, listOf("namespace", "ownerEntityId", "status"))
        createDomainIndexes(profiles, listOf("namespace", "ownerEntityId"))
        createDomainIndexes(episodes, listOf("namespace", "ownerEntityId"))
    }

    override suspend fun apply(batch: MemoryUpdateBatch) {
        indexes.ensure()
        val validBatch = batch.requireValidEntityIds()
        validBatch.predicateDefinitions.forEach { predicateDefinitions.upsertByDomainId(it.id.value, it) }
        validBatch.sources.forEach { sources.upsertByDomainId(it.id.value, it) }
        validBatch.runs.forEach { runs.upsertByDomainId(it.id.value, it) }
        validBatch.entities.forEach { entities.upsertByDomainId(it.id.value, it) }
        validBatch.claims.forEach { claims.upsertByDomainId(it.id.value, it) }
        validBatch.notes.forEach { notes.upsertByDomainId(it.id.value, it) }
        validBatch.tasks.forEach { tasks.upsertByDomainId(it.id.value, it) }
        validBatch.profiles.forEach { profiles.upsertByDomainId(it.id.value, it) }
        validBatch.episodes.forEach { episodes.upsertByDomainId(it.id.value, it) }
    }

    override suspend fun search(request: MemoryStore.SearchRequest): List<MemoryStore.SearchHit> {
        indexes.ensure()
        val snapshot = loadSnapshot(request.namespace, request.includeArchived)
        val includeAll = request.scopes.contains(MemoryStore.SearchScope.ALL)
        val entityById = snapshot.entities.associateBy { it.id }

        val candidates = buildList {
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.SOURCES)) {
                snapshot.sources
                    .mapTo(this) { MemoryStore.SearchHit.SourceHit(it, score = MemorySearchScorer.sourceScore(request.query, it)) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.ENTITIES)) {
                snapshot.entities
                    .filter { request.filters.entityIds.isEmpty() || it.id in request.filters.entityIds }
                    .mapTo(this) { MemoryStore.SearchHit.EntityHit(it, score = MemorySearchScorer.entityScore(request.query, it)) }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.CLAIMS)) {
                snapshot.claims
                    .filter { request.filters.claimStatuses.isEmpty() || it.status in request.filters.claimStatuses }
                    .filter { it.matchesEntityFilter(request.filters.entityIds, entityById) }
                    .filter { request.filters.claimPredicates.isEmpty() || it.predicate in request.filters.claimPredicates }
                    .filter { request.filters.scopes.isEmpty() || it.scope in request.filters.scopes }
                    .mapTo(this) {
                        MemoryStore.SearchHit.ClaimHit(
                            it,
                            score = MemorySearchScorer.claimScore(
                                query = request.query,
                                claim = it,
                                subjectEntity = entityById[it.subjectEntityId],
                                objectEntity = it.objectEntityId?.let(entityById::get),
                            ),
                        )
                    }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.NOTES)) {
                snapshot.notes
                    .filter { request.filters.noteStatuses.isEmpty() || it.status in request.filters.noteStatuses }
                    .filter { request.filters.noteTypes.isEmpty() || it.noteType in request.filters.noteTypes }
                    .filter {
                        request.filters.entityIds.isEmpty() ||
                            it.anchorEntityId in request.filters.entityIds ||
                            it.entityRefs.any { ref -> ref.entityId in request.filters.entityIds }
                    }
                    .filter { request.filters.scopes.isEmpty() || it.scope in request.filters.scopes }
                    .mapTo(this) {
                        MemoryStore.SearchHit.NoteHit(
                            it,
                            score = MemorySearchScorer.noteScore(
                                query = request.query,
                                note = it,
                                linkedEntities = it.linkedEntities(entityById),
                            ),
                        )
                    }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.TASKS)) {
                snapshot.tasks
                    .filter { request.filters.taskStatuses.isEmpty() || it.status in request.filters.taskStatuses }
                    .filter {
                        request.filters.entityIds.isEmpty() ||
                            it.ownerEntityId in request.filters.entityIds ||
                            it.assigneeEntityId in request.filters.entityIds ||
                            it.relatedEntityIds.any { entityId -> entityId in request.filters.entityIds }
                    }
                    .filter { request.filters.scopes.isEmpty() || it.scope in request.filters.scopes }
                    .mapTo(this) {
                        MemoryStore.SearchHit.TaskHit(
                            it,
                            score = MemorySearchScorer.taskScore(
                                query = request.query,
                                task = it,
                                linkedEntities = it.linkedEntities(entityById),
                            ),
                        )
                    }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.PROFILES)) {
                snapshot.profiles
                    .filter { request.filters.entityIds.isEmpty() || it.ownerEntityId in request.filters.entityIds }
                    .mapTo(this) {
                        MemoryStore.SearchHit.ProfileHit(
                            it,
                            score = MemorySearchScorer.profileScore(
                                query = request.query,
                                profile = it,
                                ownerEntity = entityById[it.ownerEntityId],
                            ),
                        )
                    }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.EPISODES)) {
                snapshot.episodes
                    .filter { request.filters.entityIds.isEmpty() || it.ownerEntityId in request.filters.entityIds }
                    .mapTo(this) {
                        MemoryStore.SearchHit.EpisodeHit(
                            it,
                            score = MemorySearchScorer.episodeScore(
                                query = request.query,
                                episode = it,
                                ownerEntity = it.ownerEntityId?.let(entityById::get),
                            ),
                        )
                    }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.RUNS)) {
                snapshot.runs.mapTo(this) { MemoryStore.SearchHit.RunHit(it, score = MemorySearchScorer.runScore(request.query, it)) }
            }
        }

        val positiveCandidates = candidates.filter { it.score > 0.0 }
        val eligibleCandidates = when {
            request.query.isBlank() -> candidates
            positiveCandidates.isNotEmpty() -> positiveCandidates
            else -> candidates.filter { it.allowsLowScoreFallback() }
        }

        val result = eligibleCandidates
            .sortedWith(compareByDescending<MemoryStore.SearchHit> { it.score }.thenByDescending { it.sortInstant() })
            .take(request.limit)

        log.info {
            "Mongo memory search: namespace=${request.namespace?.value ?: "all"} scopes=${request.scopes.joinToString { it.name }} " +
                "query=${request.query.oneLineForMemorySearchLog(120)} filters=${request.filters.filtersForLog()} " +
                "snapshot=${snapshot.countsForLog()} candidates=${candidates.size} eligible=${eligibleCandidates.size} result=${result.size} " +
                "top=${result.joinToString("|") { it.hitForLog() }.ifBlank { "none" }}"
        }

        return result
    }

    override suspend fun loadNamespaceSnapshot(
        namespace: MemoryNamespace,
        includeArchived: Boolean,
    ): MemoryNamespaceSnapshot {
        indexes.ensure()
        return loadSnapshot(namespace, includeArchived)
    }

    override suspend fun listNamespaceSummaries(): List<MemoryNamespaceSummary> {
        indexes.ensure()
        val allMemory = loadSnapshot(namespace = null, includeArchived = false)
        val namespaces = allMemory.namespaces()
        return namespaces
            .sortedBy { it.value }
            .map { namespace ->
                MemoryNamespaceSummary.fromSnapshot(
                    namespace = namespace,
                    snapshot = allMemory.filterNamespace(namespace),
                )
            }
    }

    override suspend fun loadPredicateCatalog(namespace: MemoryNamespace): MemoryPredicateCatalog {
        indexes.ensure()
        ensureDefaultPredicateCatalog(namespace)
        return predicateDefinitions.find(Filters.eq("namespace", namespace.value))
            .toList()
            .activeDefinitions()
    }

    override suspend fun findEntitiesByNormalizedNames(
        namespace: MemoryNamespace,
        normalizedNames: Set<String>,
    ): List<MemoryEntity> {
        indexes.ensure()
        if (normalizedNames.isEmpty()) return emptyList()
        return entities.find(
            Filters.and(
                Filters.eq("namespace", namespace.value),
                Filters.or(
                    Filters.`in`("normalizedName", normalizedNames),
                    Filters.`in`("aliases.normalizedText", normalizedNames),
                ),
            ),
        ).toList()
    }

    override suspend fun findSourcesByIds(sourceIds: List<MemorySource.Id>): List<MemorySource> {
        indexes.ensure()
        if (sourceIds.isEmpty()) return emptyList()
        return sources.find(Filters.`in`("id", sourceIds.map { it.value })).toList()
    }

    override suspend fun findRunById(runId: MemoryRun.Id): MemoryRun? {
        indexes.ensure()
        return runs.findByDomainId(runId.value)
    }

    override suspend fun findRunsByParentRunId(parentRunId: MemoryRun.Id): List<MemoryRun> {
        indexes.ensure()
        return runs.find(Filters.eq("parentRunId", parentRunId.value)).toList()
    }

    override suspend fun findProfile(
        namespace: MemoryNamespace,
        ownerEntityId: MemoryEntity.Id?,
    ): MemoryProfile? {
        indexes.ensure()
        val filters = listOfNotNull(
            Filters.eq("namespace", namespace.value),
            ownerEntityId?.let { Filters.eq("ownerEntityId", it.value) },
        )
        return profiles.find(Filters.and(filters)).firstOrNull()
    }

    override suspend fun findConversationSources(conversationId: Conversation.Id): List<MemorySource> {
        indexes.ensure()
        return sources.find(Filters.eq("conversationId", conversationId.value))
            .toList()
            .filterIsInstance<MemorySource.ChatTurn>()
    }

    override suspend fun touchReferences(
        references: List<MemoryItemRef>,
        usedAt: Instant,
    ) {
        indexes.ensure()
        references.forEach { ref ->
            when (ref.type) {
                MemoryItemRef.Type.CLAIM -> claims.findByDomainId(ref.id)?.let {
                    claims.upsertByDomainId(ref.id, it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt))
                }

                MemoryItemRef.Type.NOTE -> notes.findByDomainId(ref.id)?.let {
                    notes.upsertByDomainId(ref.id, it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt))
                }

                MemoryItemRef.Type.TASK -> tasks.findByDomainId(ref.id)?.let {
                    tasks.upsertByDomainId(ref.id, it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt))
                }

                MemoryItemRef.Type.EPISODE -> episodes.findByDomainId(ref.id)?.let {
                    episodes.upsertByDomainId(ref.id, it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt))
                }

                MemoryItemRef.Type.SOURCE,
                MemoryItemRef.Type.ENTITY,
                MemoryItemRef.Type.PROFILE,
                MemoryItemRef.Type.RUN,
                -> Unit
            }
        }
    }

    private suspend fun loadSnapshot(
        namespace: MemoryNamespace?,
        includeArchived: Boolean,
    ): MemoryNamespaceSnapshot {
        val loadedPredicateDefinitions = if (namespace != null) {
            loadPredicateCatalog(namespace)
        } else {
            predicateDefinitions.find().toList().activeDefinitions()
        }

        return MemoryNamespaceSnapshot(
            predicateDefinitions = loadedPredicateDefinitions,
            sources = sources.find(namespaceFilter(namespace)).toList()
                .filter { includeArchived || it.deletedAt == null },
            runs = runs.find(namespaceFilter(namespace)).toList(),
            entities = entities.find(namespaceFilter(namespace)).toList(),
            claims = claims.find(namespaceFilter(namespace)).toList()
                .filter { includeArchived || it.archivedAt == null },
            notes = notes.find(namespaceFilter(namespace)).toList()
                .filter { includeArchived || it.archivedAt == null },
            tasks = tasks.find(namespaceFilter(namespace)).toList()
                .filter { includeArchived || it.archivedAt == null },
            profiles = profiles.find(namespaceFilter(namespace)).toList(),
            episodes = episodes.find(namespaceFilter(namespace)).toList()
                .filter { includeArchived || it.archivedAt == null },
        )
    }

    private suspend fun ensureDefaultPredicateCatalog(namespace: MemoryNamespace) {
        val existingPredicates = predicateDefinitions.find(Filters.eq("namespace", namespace.value))
            .toList()
            .mapTo(mutableSetOf()) { it.predicate.lowercase() }
        val missingDefaults = MemoryPredicateCatalogDefaults.forNamespace(namespace)
            .filter { it.predicate.lowercase() !in existingPredicates }

        missingDefaults.forEach { predicateDefinitions.upsertByDomainId(it.id.value, it) }
    }

    private fun namespaceFilter(namespace: MemoryNamespace?): Bson =
        namespace?.let { Filters.eq("namespace", it.value) } ?: Filters.empty()

    private suspend fun <T : Any> createDomainIndexes(
        collection: MongoCollection<T>,
        fields: List<String>,
    ) {
        collection.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        fields.forEach { field ->
            collection.createIndex(Indexes.ascending(field))
        }
    }
}

private fun MemoryNote.linkedEntities(entityById: Map<MemoryEntity.Id, MemoryEntity>): List<MemoryEntity> =
    (listOfNotNull(anchorEntityId) + entityRefs.map { it.entityId })
        .distinct()
        .mapNotNull(entityById::get)

private fun MemoryClaim.matchesEntityFilter(
    entityIds: Set<MemoryEntity.Id>,
    entityById: Map<MemoryEntity.Id, MemoryEntity>,
): Boolean {
    if (entityIds.isEmpty()) return true
    if (subjectEntityId in entityIds || objectEntityId in entityIds) return true

    val text = listOf(
        normalizedText,
        contextText.orEmpty(),
        scope.text,
        qualifiers.toString(),
    ).joinToString(" ").normalizedEntityFilterText()

    return entityIds
        .mapNotNull(entityById::get)
        .any { entity -> text.containsEntityFilterName(entity) }
}

private fun MemoryTask.linkedEntities(entityById: Map<MemoryEntity.Id, MemoryEntity>): List<MemoryEntity> =
    (listOfNotNull(ownerEntityId, assigneeEntityId) + relatedEntityIds)
        .distinct()
        .mapNotNull(entityById::get)

private fun MemoryStore.SearchHit.allowsLowScoreFallback(): Boolean =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit,
        is MemoryStore.SearchHit.NoteHit,
        is MemoryStore.SearchHit.TaskHit,
        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.EpisodeHit,
        -> true

        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.RunHit,
        -> false
    }

private fun MemoryStore.SearchHit.sortInstant(): Instant =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> source.createdAt
        is MemoryStore.SearchHit.EntityHit -> entity.updatedAt
        is MemoryStore.SearchHit.ClaimHit -> claim.updatedAt
        is MemoryStore.SearchHit.NoteHit -> note.updatedAt
        is MemoryStore.SearchHit.TaskHit -> task.updatedAt
        is MemoryStore.SearchHit.ProfileHit -> profile.updatedAt
        is MemoryStore.SearchHit.EpisodeHit -> episode.updatedAt
        is MemoryStore.SearchHit.RunHit -> run.createdAt
    }

private fun String.containsEntityFilterName(entity: MemoryEntity): Boolean {
    val names = (listOf(entity.canonicalName, entity.normalizedName) + entity.aliases.flatMap { listOf(it.text, it.normalizedText) })
        .map { it.normalizedEntityFilterText() }
        .filter { it.length >= 2 }
        .distinct()
    return names.any { name -> " $this ".contains(" $name ") }
}

private fun String.normalizedEntityFilterText(): String =
    replace(Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})"), " ")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun MemoryStore.SearchHit.hitForLog(): String =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> "source:${source.id.value}:${score.scoreForLog()}:${source.contentText.oneLineForMemorySearchLog(80)} search=${source.searchText.orEmpty().oneLineForMemorySearchLog(80)}"
        is MemoryStore.SearchHit.EntityHit -> "entity:${entity.id.value}:${score.scoreForLog()}:${entity.entityType.name}:${entity.canonicalName.oneLineForMemorySearchLog(60)}"
        is MemoryStore.SearchHit.ClaimHit -> "claim:${claim.id.value}:${score.scoreForLog()}:${claim.predicate}:${claim.normalizedText.oneLineForMemorySearchLog(100)}"
        is MemoryStore.SearchHit.NoteHit -> "note:${note.id.value}:${score.scoreForLog()}:${note.noteType.name}:${note.title.oneLineForMemorySearchLog(80)}"
        is MemoryStore.SearchHit.TaskHit -> "task:${task.id.value}:${score.scoreForLog()}:${task.status.name}:${task.title.oneLineForMemorySearchLog(80)}"
        is MemoryStore.SearchHit.ProfileHit -> "profile:${profile.id.value}:${score.scoreForLog()}:${profile.profileText.oneLineForMemorySearchLog(100)}"
        is MemoryStore.SearchHit.EpisodeHit -> "episode:${episode.id.value}:${score.scoreForLog()}:${episode.lesson.oneLineForMemorySearchLog(100)}"
        is MemoryStore.SearchHit.RunHit -> "run:${run.id.value}:${score.scoreForLog()}:${run.runType.name}:${run.summary.oneLineForMemorySearchLog(80)}"
    }

private fun MemoryStore.SearchFilters.filtersForLog(): String =
    buildList {
        if (claimStatuses.isNotEmpty()) add("claimStatuses=${claimStatuses.joinToString { it.name }}")
        if (noteStatuses.isNotEmpty()) add("noteStatuses=${noteStatuses.joinToString { it.name }}")
        if (entityIds.isNotEmpty()) add("entityIds=${entityIds.joinToString { it.value }}")
        if (claimPredicates.isNotEmpty()) add("claimPredicates=${claimPredicates.joinToString()}")
        if (noteTypes.isNotEmpty()) add("noteTypes=${noteTypes.joinToString { it.name }}")
        if (taskStatuses.isNotEmpty()) add("taskStatuses=${taskStatuses.joinToString { it.name }}")
        if (scopes.isNotEmpty()) add("scopes=${scopes.joinToString { it.text.oneLineForMemorySearchLog(60) }}")
    }.joinToString(";").ifBlank { "none" }

private fun MemoryNamespaceSnapshot.countsForLog(): String =
    "sources=${sources.size},runs=${runs.size},entities=${entities.size},claims=${claims.size},notes=${notes.size},tasks=${tasks.size},profiles=${profiles.size},episodes=${episodes.size}"

private fun MemoryNamespaceSnapshot.namespaces(): Set<MemoryNamespace> =
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

private fun MemoryNamespaceSnapshot.filterNamespace(namespace: MemoryNamespace): MemoryNamespaceSnapshot =
    MemoryNamespaceSnapshot(
        predicateDefinitions = predicateDefinitions.filter { it.namespace == namespace },
        sources = sources.filter { it.namespace == namespace },
        runs = runs.filter { it.namespace == namespace },
        entities = entities.filter { it.namespace == namespace },
        claims = claims.filter { it.namespace == namespace },
        notes = notes.filter { it.namespace == namespace },
        tasks = tasks.filter { it.namespace == namespace },
        profiles = profiles.filter { it.namespace == namespace },
        episodes = episodes.filter { it.namespace == namespace },
    )

private fun Double.scoreForLog(): String =
    "%.3f".format(this)

private fun String.oneLineForMemorySearchLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
