package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEmbeddingRecord
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
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.activeDefinitions
import com.gromozeka.domain.model.memory.requireValidEntityIds
import klog.KLoggers
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import javax.sql.DataSource

private const val SEARCH_PROJECTION_MAX_CHARS = 12_000
private const val SOURCE_CONTENT_SEARCH_PREVIEW_CHARS = 4_000

private val memorySearchHitComparator =
    compareByDescending<MemoryStore.SearchHit> { it.score }
        .thenBy { it.stableSearchTypeRank() }
        .thenBy { it.stableSearchSortKey() }

@Service
@Primary
class PostgresMemoryStore(
    private val dataSource: DataSource,
    private val json: Json,
) : MemoryStore {
    private val log = KLoggers.logger(this)

    override suspend fun apply(batch: MemoryUpdateBatch) {
        val validBatch = batch.requireValidEntityIds()
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                validBatch.predicateDefinitions.forEach { connection.upsertPredicateDefinition(it) }
                validBatch.sources.forEach { connection.upsertSource(it) }
                validBatch.runs.forEach { connection.upsertRun(it) }
                validBatch.entities.forEach { connection.upsertEntity(it) }
                validBatch.claims.forEach { connection.upsertClaim(it) }
                validBatch.notes.forEach { connection.upsertNote(it) }
                validBatch.tasks.forEach { connection.upsertTask(it) }
                validBatch.profiles.forEach { connection.upsertProfile(it) }
                validBatch.episodes.forEach { connection.upsertEpisode(it) }
                validBatch.embeddings.forEach { connection.upsertEmbedding(it) }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun search(request: MemoryStore.SearchRequest): List<MemoryStore.SearchHit> {
        val vectorScores = request.vectorScores()
        val vectorRefs = vectorScores.keys
        return dataSource.connection.use { connection ->
            val payloads = connection.selectSearchPayloads(request, vectorRefs)
            val sources = payloads.sources.map { json.decodeFromString<MemorySource>(it.payload) }
            val runs = payloads.runs.map { json.decodeFromString<MemoryRun>(it.payload) }
            val entities = payloads.entities.map { json.decodeFromString<MemoryEntity>(it.payload) }
            val claims = payloads.claims.map { json.decodeFromString<MemoryClaim>(it.payload) }
            val notes = payloads.notes.map { json.decodeFromString<MemoryNote>(it.payload) }
            val tasks = payloads.tasks.map { json.decodeFromString<MemoryTask>(it.payload) }
            val profiles = payloads.profiles.map { json.decodeFromString<MemoryProfile>(it.payload) }
            val episodes = payloads.episodes.map { json.decodeFromString<MemoryEpisode>(it.payload) }
            val entityById = connection.selectEntitiesForSearch(
                explicitEntities = entities,
                claims = claims,
                notes = notes,
                tasks = tasks,
                profiles = profiles,
                episodes = episodes,
                filterEntityIds = request.filters.entityIds,
            )

            val candidates = buildList {
                sources
                    .filter { it.matchesSearchRequest(request) }
                    .mapTo(this) {
                        MemoryStore.SearchHit.SourceHit(
                            it,
                            score = MemorySearchScorer.sourceScore(request.query, it) +
                                vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.SOURCE, it.id.value)),
                        )
                    }
                entities
                    .filter { it.matchesSearchRequest(request) }
                    .mapTo(this) {
                        MemoryStore.SearchHit.EntityHit(
                            it,
                            score = MemorySearchScorer.entityScore(request.query, it) +
                                vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.ENTITY, it.id.value)),
                        )
                    }
                claims
                    .filter { it.matchesSearchRequest(request, entityById) }
                    .mapTo(this) {
                        MemoryStore.SearchHit.ClaimHit(
                            it,
                            score = MemorySearchScorer.claimScore(
                                query = request.query,
                                claim = it,
                                subjectEntity = entityById[it.subjectEntityId],
                                objectEntity = it.objectEntityId?.let(entityById::get),
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.CLAIM, it.id.value)),
                        )
                    }
                notes
                    .filter { it.matchesSearchRequest(request) }
                    .mapTo(this) {
                        MemoryStore.SearchHit.NoteHit(
                            it,
                            score = MemorySearchScorer.noteScore(
                                query = request.query,
                                note = it,
                                linkedEntities = it.linkedEntities(entityById),
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.NOTE, it.id.value)),
                        )
                    }
                tasks
                    .filter { it.matchesSearchRequest(request) }
                    .mapTo(this) {
                        MemoryStore.SearchHit.TaskHit(
                            it,
                            score = MemorySearchScorer.taskScore(
                                query = request.query,
                                task = it,
                                linkedEntities = it.linkedEntities(entityById),
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.TASK, it.id.value)),
                        )
                    }
                profiles
                    .filter { it.matchesSearchRequest(request) }
                    .mapTo(this) {
                        MemoryStore.SearchHit.ProfileHit(
                            it,
                            score = MemorySearchScorer.profileScore(
                                query = request.query,
                                profile = it,
                                ownerEntity = entityById[it.ownerEntityId],
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.PROFILE, it.id.value)),
                        )
                    }
                episodes
                    .filter { it.matchesSearchRequest(request) }
                    .mapTo(this) {
                        MemoryStore.SearchHit.EpisodeHit(
                            it,
                            score = MemorySearchScorer.episodeScore(
                                query = request.query,
                                episode = it,
                                ownerEntity = it.ownerEntityId?.let(entityById::get),
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.EPISODE, it.id.value)),
                        )
                    }
                runs.mapTo(this) { MemoryStore.SearchHit.RunHit(it, score = MemorySearchScorer.runScore(request.query, it)) }
            }

            val positiveCandidates = candidates.filter { it.score > 0.0 }
            val eligibleCandidates = when {
                request.query.isBlank() -> candidates
                positiveCandidates.isNotEmpty() -> positiveCandidates
                else -> candidates.filter { it.allowsLowScoreFallback() }
            }

            val result = eligibleCandidates
                .sortedWith(memorySearchHitComparator)
                .take(request.limit)

            log.info {
                "Postgres memory search: namespace=${request.namespace?.value ?: "all"} scopes=${request.scopes.joinToString { it.name }} " +
                    "query=${request.query.oneLineForMemorySearchLog(120)} filters=${request.filters.filtersForLog()} " +
                    "embedding=${request.embedding?.let { "${it.modelConfigurationId}/${it.dimensions}" } ?: "none"} " +
                    "vectorRefs=${vectorRefs.size} payloads=${payloads.countsForLog()} candidates=${candidates.size} " +
                    "eligible=${eligibleCandidates.size} result=${result.size} " +
                    "top=${result.joinToString("|") { it.hitForLog() }.ifBlank { "none" }}"
            }

            result
        }
    }

    override suspend fun loadNamespaceSnapshot(
        namespace: MemoryNamespace,
        includeArchived: Boolean,
    ): MemoryNamespaceSnapshot = loadSnapshot(namespace, includeArchived)

    override suspend fun listNamespaceSummaries(): List<MemoryNamespaceSummary> {
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
        ensureDefaultPredicateCatalog(namespace)
        return dataSource.connection.use { connection ->
            connection.selectPayloads("memory_predicate_definitions", namespace)
                .map { json.decodeFromString<MemoryPredicateDefinition>(it) }
                .activeDefinitions()
        }
    }

    override suspend fun findEntitiesByNormalizedNames(
        namespace: MemoryNamespace,
        normalizedNames: Set<String>,
    ): List<MemoryEntity> {
        if (normalizedNames.isEmpty()) return emptyList()
        return dataSource.connection.use { connection ->
            connection.selectPayloads("memory_entities", namespace)
                .map { json.decodeFromString<MemoryEntity>(it) }
                .filter { entity ->
                    entity.normalizedName in normalizedNames ||
                        entity.aliases.any { alias -> alias.normalizedText in normalizedNames }
                }
        }
    }

    override suspend fun findSourcesByIds(sourceIds: List<MemorySource.Id>): List<MemorySource> {
        if (sourceIds.isEmpty()) return emptyList()
        return dataSource.connection.use { connection ->
            connection.selectPayloadsByIds("memory_sources", sourceIds.map { it.value })
                .map { json.decodeFromString<MemorySource>(it) }
        }
    }

    override suspend fun findTypedMemoryByEvidenceSourceIds(
        namespace: MemoryNamespace,
        sourceIds: Set<MemorySource.Id>,
    ): List<MemoryStore.SearchHit> {
        if (sourceIds.isEmpty()) return emptyList()
        return dataSource.connection.use { connection ->
            val directClaims = connection.selectPayloadsByEvidenceSourceIds("memory_claims", namespace, sourceIds)
                .map { json.decodeFromString<MemoryClaim>(it) }
            val replacedClaimIds = directClaims
                .filter { it.status != MemoryClaim.Status.ACTIVE }
                .mapTo(mutableSetOf()) { it.id.value }
            val activeClaimIdsByRetraction = directClaims
                .mapNotNullTo(mutableSetOf()) { it.retractedByClaimId?.value }
            val replacementClaims = connection.selectActiveReplacementClaims(namespace, replacedClaimIds, activeClaimIdsByRetraction)

            val directNotes = connection.selectPayloadsByEvidenceSourceIds("memory_notes", namespace, sourceIds)
                .map { json.decodeFromString<MemoryNote>(it) }
            val replacedNoteIds = directNotes
                .filter { it.status != MemoryNote.Status.ACTIVE }
                .mapTo(mutableSetOf()) { it.id.value }
            val replacementNotes = connection.selectActiveReplacementNotes(namespace, replacedNoteIds)

            val directTasks = connection.selectPayloadsByEvidenceSourceIds("memory_tasks", namespace, sourceIds)
                .map { json.decodeFromString<MemoryTask>(it) }
                .filter { it.archivedAt == null }
            val directEpisodes = connection.selectPayloadsByEvidenceSourceIds("memory_episodes", namespace, sourceIds)
                .map { json.decodeFromString<MemoryEpisode>(it) }
                .filter { it.archivedAt == null }

            buildList {
                (directClaims + replacementClaims)
                    .distinctBy { it.id }
                    .mapTo(this) { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) }
                (directNotes + replacementNotes)
                    .distinctBy { it.id }
                    .mapTo(this) { MemoryStore.SearchHit.NoteHit(it, score = 1.0) }
                directTasks
                    .distinctBy { it.id }
                    .mapTo(this) { MemoryStore.SearchHit.TaskHit(it, score = 1.0) }
                directEpisodes
                    .distinctBy { it.id }
                    .mapTo(this) { MemoryStore.SearchHit.EpisodeHit(it, score = 1.0) }
            }.distinctBy { it.toPostgresMemoryStoreItemRef() }
        }
    }

    override suspend fun replaceEmbeddings(
        namespace: MemoryNamespace,
        embeddings: List<MemoryEmbeddingRecord>,
    ): Int {
        require(embeddings.all { it.namespace == namespace }) {
            "Replacement memory embeddings must all belong to namespace ${namespace.value}"
        }
        return dataSource.connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            try {
                connection.autoCommit = false
                val deleted = connection.deleteEmbeddings(namespace)
                embeddings.forEach { connection.upsertEmbedding(it) }
                connection.commit()
                deleted
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }
    }

    override suspend fun findRunById(runId: MemoryRun.Id): MemoryRun? =
        dataSource.connection.use { connection ->
            connection.selectPayloadById("memory_runs", runId.value)
                ?.let { json.decodeFromString<MemoryRun>(it) }
        }

    override suspend fun findRunsByParentRunId(parentRunId: MemoryRun.Id): List<MemoryRun> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT payload::text FROM memory_runs WHERE parent_run_id = ?").use { statement ->
                statement.setString(1, parentRunId.value)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(json.decodeFromString<MemoryRun>(resultSet.getString(1)))
                        }
                    }
                }
            }
        }

    override suspend fun findProfile(
        namespace: MemoryNamespace,
        ownerEntityId: MemoryEntity.Id?,
    ): MemoryProfile? =
        dataSource.connection.use { connection ->
            val sql = if (ownerEntityId == null) {
                "SELECT payload::text FROM memory_profiles WHERE namespace = ? ORDER BY updated_at DESC LIMIT 1"
            } else {
                "SELECT payload::text FROM memory_profiles WHERE namespace = ? AND owner_entity_id = ? ORDER BY updated_at DESC LIMIT 1"
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, namespace.value)
                ownerEntityId?.let { statement.setString(2, it.value) }
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) json.decodeFromString<MemoryProfile>(resultSet.getString(1)) else null
                }
            }
        }

    override suspend fun findConversationSources(conversationId: Conversation.Id): List<MemorySource> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT payload::text FROM memory_sources WHERE conversation_id = ?").use { statement ->
                statement.setString(1, conversationId.value)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(json.decodeFromString<MemorySource>(resultSet.getString(1)))
                        }
                    }.filterIsInstance<MemorySource.ChatTurn>()
                }
            }
        }

    override suspend fun touchReferences(
        references: List<MemoryItemRef>,
        usedAt: Instant,
    ) {
        dataSource.connection.use { connection ->
            references.forEach { ref ->
                when (ref.type) {
                    MemoryItemRef.Type.CLAIM -> connection.selectPayloadById("memory_claims", ref.id)
                        ?.let { json.decodeFromString<MemoryClaim>(it) }
                        ?.let { connection.upsertClaim(it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt)) }

                    MemoryItemRef.Type.NOTE -> connection.selectPayloadById("memory_notes", ref.id)
                        ?.let { json.decodeFromString<MemoryNote>(it) }
                        ?.let { connection.upsertNote(it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt)) }

                    MemoryItemRef.Type.TASK -> connection.selectPayloadById("memory_tasks", ref.id)
                        ?.let { json.decodeFromString<MemoryTask>(it) }
                        ?.let { connection.upsertTask(it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt)) }

                    MemoryItemRef.Type.EPISODE -> connection.selectPayloadById("memory_episodes", ref.id)
                        ?.let { json.decodeFromString<MemoryEpisode>(it) }
                        ?.let { connection.upsertEpisode(it.copy(useCount = it.useCount + 1, lastUsedAt = usedAt)) }

                    MemoryItemRef.Type.SOURCE,
                    MemoryItemRef.Type.ENTITY,
                    MemoryItemRef.Type.PROFILE,
                    MemoryItemRef.Type.RUN,
                    -> Unit
                }
            }
        }
    }

    private suspend fun loadSnapshot(
        namespace: MemoryNamespace?,
        includeArchived: Boolean,
    ): MemoryNamespaceSnapshot =
        dataSource.connection.use { connection ->
            val loadedPredicateDefinitions = if (namespace != null) {
                loadPredicateCatalog(namespace)
            } else {
                connection.selectPayloads("memory_predicate_definitions", namespace = null)
                    .map { json.decodeFromString<MemoryPredicateDefinition>(it) }
                    .activeDefinitions()
            }

            MemoryNamespaceSnapshot(
                predicateDefinitions = loadedPredicateDefinitions,
                sources = connection.selectPayloads("memory_sources", namespace).map { json.decodeFromString<MemorySource>(it) }
                    .filter { includeArchived || it.deletedAt == null },
                runs = connection.selectPayloads("memory_runs", namespace).map { json.decodeFromString<MemoryRun>(it) },
                entities = connection.selectPayloads("memory_entities", namespace).map { json.decodeFromString<MemoryEntity>(it) },
                claims = connection.selectPayloads("memory_claims", namespace).map { json.decodeFromString<MemoryClaim>(it) }
                    .filter { includeArchived || it.archivedAt == null },
                notes = connection.selectPayloads("memory_notes", namespace).map { json.decodeFromString<MemoryNote>(it) }
                    .filter { includeArchived || it.archivedAt == null },
                tasks = connection.selectPayloads("memory_tasks", namespace).map { json.decodeFromString<MemoryTask>(it) }
                    .filter { includeArchived || it.archivedAt == null },
                profiles = connection.selectPayloads("memory_profiles", namespace).map { json.decodeFromString<MemoryProfile>(it) },
                episodes = connection.selectPayloads("memory_episodes", namespace).map { json.decodeFromString<MemoryEpisode>(it) }
                    .filter { includeArchived || it.archivedAt == null },
            )
        }

    private suspend fun ensureDefaultPredicateCatalog(namespace: MemoryNamespace) {
        dataSource.connection.use { connection ->
            val existingPredicates = connection.selectPayloads("memory_predicate_definitions", namespace)
                .map { json.decodeFromString<MemoryPredicateDefinition>(it).predicate.lowercase() }
                .toSet()
            val missingDefaults = MemoryPredicateCatalogDefaults.forNamespace(namespace)
                .filter { it.predicate.lowercase() !in existingPredicates }

            missingDefaults.forEach { connection.upsertPredicateDefinition(it) }
        }
    }

    private fun Connection.upsertPredicateDefinition(item: MemoryPredicateDefinition) =
        upsertJson(
            tableName = "memory_predicate_definitions",
            id = item.id.value,
            namespace = item.namespace?.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "predicate" to item.predicate,
                "active" to item.active,
            ),
        )

    private fun Connection.upsertSource(item: MemorySource) =
        upsertJson(
            tableName = "memory_sources",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "content_hash" to item.contentHash,
                "conversation_id" to item.conversationIdValue(),
                "search_text" to item.searchTextForStore(),
                "created_at" to item.createdAt,
            ),
        )

    private fun Connection.upsertRun(item: MemoryRun) =
        upsertJson(
            tableName = "memory_runs",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "parent_run_id" to item.parentRunId?.value,
                "status" to item.status.name,
                "run_type" to item.runType.name,
                "search_text" to item.searchTextForStore(),
                "created_at" to item.createdAt,
            ),
        )

    private fun Connection.upsertEntity(item: MemoryEntity) =
        upsertJson(
            tableName = "memory_entities",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "normalized_name" to item.normalizedName,
                "entity_type" to item.entityType.name,
                "status" to item.status.name,
                "search_text" to item.searchTextForStore(),
                "updated_at" to item.updatedAt,
            ),
        )

    private fun Connection.upsertClaim(item: MemoryClaim) =
        upsertJson(
            tableName = "memory_claims",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "subject_entity_id" to item.subjectEntityId.value,
                "object_entity_id" to item.objectEntityId?.value,
                "predicate" to item.predicate,
                "status" to item.status.name,
                "scope_text" to item.scope.text,
                "search_text" to item.searchTextForStore(),
                "evidence_source_ids" to item.evidenceSourceIdValues(),
                "supersedes_claim_id" to item.supersedesClaimId?.value,
                "retracted_by_claim_id" to item.retractedByClaimId?.value,
                "updated_at" to item.updatedAt,
            ),
        )

    private fun Connection.upsertNote(item: MemoryNote) =
        upsertJson(
            tableName = "memory_notes",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "anchor_entity_id" to item.anchorEntityId?.value,
                "status" to item.status.name,
                "note_type" to item.noteType.name,
                "search_text" to item.searchTextForStore(),
                "evidence_source_ids" to item.evidenceSourceIdValues(),
                "entity_ids" to item.linkedEntityIdValues(),
                "supersedes_note_id" to item.supersedesNoteId?.value,
                "updated_at" to item.updatedAt,
            ),
        )

    private fun Connection.upsertTask(item: MemoryTask) =
        upsertJson(
            tableName = "memory_tasks",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "owner_entity_id" to item.ownerEntityId?.value,
                "assignee_entity_id" to item.assigneeEntityId?.value,
                "status" to item.status.name,
                "search_text" to item.searchTextForStore(),
                "evidence_source_ids" to item.evidenceSourceIdValues(),
                "entity_ids" to item.linkedEntityIdValues(),
                "updated_at" to item.updatedAt,
            ),
        )

    private fun Connection.upsertProfile(item: MemoryProfile) =
        upsertJson(
            tableName = "memory_profiles",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "owner_entity_id" to item.ownerEntityId.value,
                "search_text" to item.searchTextForStore(),
                "updated_at" to item.updatedAt,
            ),
        )

    private fun Connection.upsertEpisode(item: MemoryEpisode) =
        upsertJson(
            tableName = "memory_episodes",
            id = item.id.value,
            namespace = item.namespace.value,
            payload = json.encodeToString(item),
            columns = mapOf(
                "owner_entity_id" to item.ownerEntityId?.value,
                "search_text" to item.searchTextForStore(),
                "evidence_source_ids" to item.evidenceSourceIdValues(),
                "updated_at" to item.updatedAt,
            ),
        )

    private fun Connection.upsertEmbedding(item: MemoryEmbeddingRecord) {
        val vectorColumn = when (item.dimensions) {
            1_536 -> "embedding_1536"
            3_072 -> "embedding_3072"
            else -> error("Unsupported memory embedding dimensions: ${item.dimensions}")
        }
        val otherVectorColumn = if (vectorColumn == "embedding_1536") "embedding_3072" else "embedding_1536"
        val sql = """
            INSERT INTO memory_embeddings (
                id, namespace, memory_type, memory_id, embedding_kind, model_configuration_id, provider_model_id,
                dimensions, content_hash, $vectorColumn, $otherVectorColumn, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::halfvec, NULL, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                namespace = EXCLUDED.namespace,
                memory_type = EXCLUDED.memory_type,
                memory_id = EXCLUDED.memory_id,
                embedding_kind = EXCLUDED.embedding_kind,
                model_configuration_id = EXCLUDED.model_configuration_id,
                provider_model_id = EXCLUDED.provider_model_id,
                dimensions = EXCLUDED.dimensions,
                content_hash = EXCLUDED.content_hash,
                $vectorColumn = EXCLUDED.$vectorColumn,
                $otherVectorColumn = NULL,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        prepareStatement(sql).use { statement ->
            statement.setString(1, item.id.value)
            statement.setString(2, item.namespace.value)
            statement.setString(3, item.itemRef.type.name)
            statement.setString(4, item.itemRef.id)
            statement.setString(5, item.kind.name)
            statement.setString(6, item.modelConfigurationId)
            statement.setString(7, item.providerModelId)
            statement.setInt(8, item.dimensions)
            statement.setString(9, item.contentHash)
            statement.setString(10, item.vector.toPostgresVectorLiteral())
            statement.setTimestamp(11, Timestamp.from(java.time.Instant.parse(item.createdAt.toString())))
            statement.setTimestamp(12, Timestamp.from(java.time.Instant.parse(item.updatedAt.toString())))
            statement.executeUpdate()
        }
    }

    private fun MemoryStore.SearchRequest.vectorScores(): Map<MemoryItemRef, Double> {
        val searchEmbedding = embedding ?: return emptyMap()
        if (query.isBlank()) return emptyMap()
        val vectorColumn = when (searchEmbedding.dimensions) {
            1_536 -> "embedding_1536"
            3_072 -> "embedding_3072"
            else -> return emptyMap()
        }
        val memoryTypes = scopes.toEmbeddableMemoryTypes()
        if (memoryTypes.isEmpty()) return emptyMap()

        val namespaceFilter = namespace?.let { "namespace = ? AND " }.orEmpty()
        val typePlaceholders = memoryTypes.joinToString(",") { "?" }
        val vectorLimit = (limit * 10).coerceIn(50, 500)
        val sql = """
            SELECT memory_type, memory_id, 1 - ($vectorColumn <=> ?::halfvec) AS score
            FROM memory_embeddings
            WHERE ${namespaceFilter}model_configuration_id = ?
              AND embedding_kind = ?
              AND dimensions = ?
              AND memory_type IN ($typePlaceholders)
              AND $vectorColumn IS NOT NULL
            ORDER BY $vectorColumn <=> ?::halfvec
            LIMIT ?
        """.trimIndent()

        val vectorLiteral = searchEmbedding.vector.toPostgresVectorLiteral()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                statement.setString(index++, vectorLiteral)
                namespace?.let { statement.setString(index++, it.value) }
                statement.setString(index++, searchEmbedding.modelConfigurationId)
                statement.setString(index++, MemoryEmbeddingRecord.Kind.PRIMARY.name)
                statement.setInt(index++, searchEmbedding.dimensions)
                memoryTypes.forEach { statement.setString(index++, it.name) }
                statement.setString(index++, vectorLiteral)
                statement.setInt(index, vectorLimit)
                statement.executeQuery().use { resultSet ->
                    buildMap {
                        while (resultSet.next()) {
                            val ref = MemoryItemRef(
                                type = MemoryItemRef.Type.valueOf(resultSet.getString("memory_type")),
                                id = resultSet.getString("memory_id"),
                            )
                            val score = resultSet.getDouble("score").coerceAtLeast(0.0)
                            put(ref, score)
                        }
                    }
                }
            }
        }
    }

    private fun Connection.selectSearchPayloads(
        request: MemoryStore.SearchRequest,
        vectorRefs: Set<MemoryItemRef>,
    ): MemorySearchPayloads =
        MemorySearchPayloads(
            sources = selectSearchPayloadsFor(MemorySearchTable.SOURCE, request, vectorRefs),
            runs = selectSearchPayloadsFor(MemorySearchTable.RUN, request, vectorRefs),
            entities = selectSearchPayloadsFor(MemorySearchTable.ENTITY, request, vectorRefs),
            claims = selectSearchPayloadsFor(MemorySearchTable.CLAIM, request, vectorRefs),
            notes = selectSearchPayloadsFor(MemorySearchTable.NOTE, request, vectorRefs),
            tasks = selectSearchPayloadsFor(MemorySearchTable.TASK, request, vectorRefs),
            profiles = selectSearchPayloadsFor(MemorySearchTable.PROFILE, request, vectorRefs),
            episodes = selectSearchPayloadsFor(MemorySearchTable.EPISODE, request, vectorRefs),
        )

    private fun Connection.selectSearchPayloadsFor(
        table: MemorySearchTable,
        request: MemoryStore.SearchRequest,
        vectorRefs: Set<MemoryItemRef>,
    ): List<MemorySearchPayload> {
        if (!request.scopes.includes(table.searchScope)) return emptyList()

        val conditions = mutableListOf<SearchSqlCondition>()
        request.namespace?.let { conditions += SearchSqlCondition("namespace = ?", listOf(it.value)) }
        conditions += table.strictFilterConditions(request)

        val candidateConditions = mutableListOf<SearchSqlCondition>()
        if (request.query.isNotBlank()) {
            candidateConditions += request.query.searchTextSqlCondition()
        }
        val vectorIds = vectorRefs
            .filter { it.type == table.itemRefType }
            .map { it.id }
        if (vectorIds.isNotEmpty()) {
            candidateConditions += idsSqlCondition(vectorIds)
        }
        table.entityCandidateCondition(request.filters.entityIds)?.let {
            candidateConditions += it
        }
        if (candidateConditions.isNotEmpty()) {
            conditions += candidateConditions.joinWithOr()
        }

        val whereSql = conditions.joinToString(separator = " AND ", prefix = "WHERE ") { "(${it.sql})" }
            .takeIf { conditions.isNotEmpty() }
            .orEmpty()
        val orderSql = if (request.query.isBlank()) {
            "ORDER BY ${table.orderColumn} DESC"
        } else {
            "ORDER BY GREATEST(similarity(search_text, ?), CASE WHEN search_text ILIKE ? ESCAPE '\\' THEN 1.0 ELSE 0.0 END) DESC, ${table.orderColumn} DESC"
        }
        val sql = """
            SELECT id, payload::text
            FROM ${table.tableName}
            $whereSql
            $orderSql
            LIMIT ?
        """.trimIndent()

        val orderParams = if (request.query.isBlank()) {
            emptyList()
        } else {
            listOf(request.query.sqlSearchText(), request.query.sqlLikePattern())
        }
        val params = conditions.flatMap { it.params } + orderParams + request.sqlCandidateLimit()
        val searchPayloads = prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setPostgresValue(index + 1, value) }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(MemorySearchPayload(resultSet.getString(1), resultSet.getString(2)))
                    }
                }
            }
        }
        val loadedIds = searchPayloads.mapTo(mutableSetOf()) { it.id }
        val missingVectorIds = vectorIds.filterNot { it in loadedIds }
        if (missingVectorIds.isEmpty()) return searchPayloads

        return (searchPayloads + selectSearchPayloadsByIds(table, missingVectorIds))
            .distinctBy { it.id }
    }

    private fun Connection.selectEntitiesForSearch(
        explicitEntities: List<MemoryEntity>,
        claims: List<MemoryClaim>,
        notes: List<MemoryNote>,
        tasks: List<MemoryTask>,
        profiles: List<MemoryProfile>,
        episodes: List<MemoryEpisode>,
        filterEntityIds: Set<MemoryEntity.Id>,
    ): Map<MemoryEntity.Id, MemoryEntity> {
        val explicitById = explicitEntities.associateBy { it.id }
        val entityIds = buildSet {
            addAll(explicitById.keys)
            addAll(filterEntityIds)
            claims.forEach {
                add(it.subjectEntityId)
                it.objectEntityId?.let(::add)
            }
            notes.forEach {
                it.anchorEntityId?.let(::add)
                it.entityRefs.mapTo(this) { ref -> ref.entityId }
            }
            tasks.forEach {
                it.ownerEntityId?.let(::add)
                it.assigneeEntityId?.let(::add)
                addAll(it.relatedEntityIds)
            }
            profiles.mapTo(this) { it.ownerEntityId }
            episodes.mapNotNullTo(this) { it.ownerEntityId }
        }
        val missingIds = entityIds
            .filterNot { it in explicitById }
            .map { it.value }
        val loadedById = if (missingIds.isEmpty()) {
            emptyMap()
        } else {
            selectPayloadsByIds("memory_entities", missingIds)
                .map { json.decodeFromString<MemoryEntity>(it) }
                .associateBy { it.id }
        }
        return explicitById + loadedById
    }

    private fun Connection.upsertJson(
        tableName: String,
        id: String,
        namespace: String?,
        payload: String,
        columns: Map<String, Any?>,
    ) {
        val columnNames = listOf("id", "namespace", "payload") + columns.keys
        val placeholders = listOf("?", "?", "CAST(? AS jsonb)") + columns.keys.map { "?" }
        val updates = (listOf("namespace", "payload") + columns.keys)
            .joinToString(", ") { "$it = EXCLUDED.$it" }
        val sql = """
            INSERT INTO $tableName (${columnNames.joinToString(", ")})
            VALUES (${placeholders.joinToString(", ")})
            ON CONFLICT (id) DO UPDATE SET $updates
        """.trimIndent()

        prepareStatement(sql).use { statement ->
            statement.setString(1, id)
            statement.setNullableString(2, namespace)
            statement.setString(3, payload)
            columns.values.forEachIndexed { index, value ->
                statement.setPostgresValue(index + 4, value)
            }
            statement.executeUpdate()
        }
    }

    private fun Connection.selectPayloadById(tableName: String, id: String): String? =
        prepareStatement("SELECT payload::text FROM $tableName WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    private fun Connection.selectPayloadsByIds(tableName: String, ids: List<String>): List<String> {
        val placeholders = ids.joinToString(",") { "?" }
        return prepareStatement("SELECT payload::text FROM $tableName WHERE id IN ($placeholders)").use { statement ->
            ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.getString(1))
                    }
                }
            }
        }
    }

    private fun Connection.selectSearchPayloadsByIds(table: MemorySearchTable, ids: List<String>): List<MemorySearchPayload> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return prepareStatement("SELECT id, payload::text FROM ${table.tableName} WHERE id IN ($placeholders)").use { statement ->
            ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(MemorySearchPayload(resultSet.getString(1), resultSet.getString(2)))
                    }
                }
            }
        }
    }

    private fun Connection.selectPayloads(tableName: String, namespace: MemoryNamespace?): List<String> {
        val sql = if (namespace == null) {
            "SELECT payload::text FROM $tableName"
        } else {
            "SELECT payload::text FROM $tableName WHERE namespace = ?"
        }
        return prepareStatement(sql).use { statement ->
            namespace?.let { statement.setString(1, it.value) }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.getString(1))
                    }
                }
            }
        }
    }

    private fun Connection.selectPayloadsByEvidenceSourceIds(
        tableName: String,
        namespace: MemoryNamespace,
        sourceIds: Set<MemorySource.Id>,
    ): List<String> {
        if (sourceIds.isEmpty()) return emptyList()
        val sql = """
            SELECT payload::text
            FROM $tableName
            WHERE namespace = ?
              AND evidence_source_ids && ?::text[]
        """.trimIndent()
        return prepareStatement(sql).use { statement ->
            statement.setString(1, namespace.value)
            statement.setArray(2, createArrayOf("text", sourceIds.map { it.value }.toTypedArray()))
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.getString(1))
                    }
                }
            }
        }
    }

    private fun Connection.selectActiveReplacementClaims(
        namespace: MemoryNamespace,
        replacedClaimIds: Set<String>,
        activeClaimIdsByRetraction: Set<String>,
    ): List<MemoryClaim> {
        if (replacedClaimIds.isEmpty() && activeClaimIdsByRetraction.isEmpty()) return emptyList()
        val conditions = buildList {
            if (replacedClaimIds.isNotEmpty()) add(inSqlCondition("supersedes_claim_id", replacedClaimIds.toList()))
            if (activeClaimIdsByRetraction.isNotEmpty()) add(idsSqlCondition(activeClaimIdsByRetraction.toList()))
        }.joinWithOr()
        val sql = """
            SELECT payload::text
            FROM memory_claims
            WHERE namespace = ?
              AND status = ?
              AND (${conditions.sql})
        """.trimIndent()
        return prepareStatement(sql).use { statement ->
            statement.setString(1, namespace.value)
            statement.setString(2, MemoryClaim.Status.ACTIVE.name)
            conditions.params.forEachIndexed { index, value -> statement.setPostgresValue(index + 3, value) }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(json.decodeFromString<MemoryClaim>(resultSet.getString(1)))
                    }
                }
            }
        }
    }

    private fun Connection.selectActiveReplacementNotes(
        namespace: MemoryNamespace,
        replacedNoteIds: Set<String>,
    ): List<MemoryNote> {
        if (replacedNoteIds.isEmpty()) return emptyList()
        val condition = inSqlCondition("supersedes_note_id", replacedNoteIds.toList())
        val sql = """
            SELECT payload::text
            FROM memory_notes
            WHERE namespace = ?
              AND status = ?
              AND (${condition.sql})
        """.trimIndent()
        return prepareStatement(sql).use { statement ->
            statement.setString(1, namespace.value)
            statement.setString(2, MemoryNote.Status.ACTIVE.name)
            condition.params.forEachIndexed { index, value -> statement.setPostgresValue(index + 3, value) }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(json.decodeFromString<MemoryNote>(resultSet.getString(1)))
                    }
                }
            }
        }
    }

    private fun Connection.deleteEmbeddings(namespace: MemoryNamespace): Int =
        prepareStatement("DELETE FROM memory_embeddings WHERE namespace = ?").use { statement ->
            statement.setString(1, namespace.value)
            statement.executeUpdate()
        }
}

private data class MemorySearchPayload(
    val id: String,
    val payload: String,
)

private data class MemorySearchPayloads(
    val sources: List<MemorySearchPayload>,
    val runs: List<MemorySearchPayload>,
    val entities: List<MemorySearchPayload>,
    val claims: List<MemorySearchPayload>,
    val notes: List<MemorySearchPayload>,
    val tasks: List<MemorySearchPayload>,
    val profiles: List<MemorySearchPayload>,
    val episodes: List<MemorySearchPayload>,
) {
    fun countsForLog(): String =
        "sources=${sources.size},runs=${runs.size},entities=${entities.size},claims=${claims.size},notes=${notes.size},tasks=${tasks.size},profiles=${profiles.size},episodes=${episodes.size}"
}

private fun MemoryStore.SearchHit.toPostgresMemoryStoreItemRef(): MemoryItemRef =
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

private enum class MemorySearchTable(
    val itemRefType: MemoryItemRef.Type,
    val searchScope: MemoryStore.SearchScope,
    val tableName: String,
    val orderColumn: String,
) {
    SOURCE(MemoryItemRef.Type.SOURCE, MemoryStore.SearchScope.SOURCES, "memory_sources", "created_at"),
    RUN(MemoryItemRef.Type.RUN, MemoryStore.SearchScope.RUNS, "memory_runs", "created_at"),
    ENTITY(MemoryItemRef.Type.ENTITY, MemoryStore.SearchScope.ENTITIES, "memory_entities", "updated_at"),
    CLAIM(MemoryItemRef.Type.CLAIM, MemoryStore.SearchScope.CLAIMS, "memory_claims", "updated_at"),
    NOTE(MemoryItemRef.Type.NOTE, MemoryStore.SearchScope.NOTES, "memory_notes", "updated_at"),
    TASK(MemoryItemRef.Type.TASK, MemoryStore.SearchScope.TASKS, "memory_tasks", "updated_at"),
    PROFILE(MemoryItemRef.Type.PROFILE, MemoryStore.SearchScope.PROFILES, "memory_profiles", "updated_at"),
    EPISODE(MemoryItemRef.Type.EPISODE, MemoryStore.SearchScope.EPISODES, "memory_episodes", "updated_at"),
}

private data class SearchSqlCondition(
    val sql: String,
    val params: List<Any?> = emptyList(),
)

private fun MemorySearchTable.strictFilterConditions(request: MemoryStore.SearchRequest): List<SearchSqlCondition> =
    buildList {
        when (this@strictFilterConditions) {
            MemorySearchTable.CLAIM -> {
                if (request.filters.claimStatuses.isNotEmpty()) {
                    add(inSqlCondition("status", request.filters.claimStatuses.map { it.name }))
                }
                if (request.filters.claimPredicates.isNotEmpty()) {
                    add(inSqlCondition("predicate", request.filters.claimPredicates.toList()))
                }
            }

            MemorySearchTable.NOTE -> {
                if (request.filters.noteStatuses.isNotEmpty()) {
                    add(inSqlCondition("status", request.filters.noteStatuses.map { it.name }))
                }
                if (request.filters.noteTypes.isNotEmpty()) {
                    add(inSqlCondition("note_type", request.filters.noteTypes.map { it.name }))
                }
            }

            MemorySearchTable.TASK -> {
                if (request.filters.taskStatuses.isNotEmpty()) {
                    add(inSqlCondition("status", request.filters.taskStatuses.map { it.name }))
                }
            }

            MemorySearchTable.SOURCE,
            MemorySearchTable.RUN,
            MemorySearchTable.ENTITY,
            MemorySearchTable.PROFILE,
            MemorySearchTable.EPISODE,
            -> Unit
        }
    }

private fun MemorySearchTable.entityCandidateCondition(entityIds: Set<MemoryEntity.Id>): SearchSqlCondition? {
    if (entityIds.isEmpty()) return null
    val ids = entityIds.map { it.value }
    return when (this) {
        MemorySearchTable.ENTITY -> idsSqlCondition(ids)
        MemorySearchTable.CLAIM -> {
            val placeholders = ids.joinToString(",") { "?" }
            SearchSqlCondition(
                sql = "subject_entity_id IN ($placeholders) OR object_entity_id IN ($placeholders)",
                params = ids + ids,
            )
        }

        MemorySearchTable.NOTE -> arrayOverlapSqlCondition("entity_ids", ids)
        MemorySearchTable.TASK -> arrayOverlapSqlCondition("entity_ids", ids)

        MemorySearchTable.PROFILE -> inSqlCondition("owner_entity_id", ids)
        MemorySearchTable.EPISODE -> inSqlCondition("owner_entity_id", ids)
        MemorySearchTable.SOURCE,
        MemorySearchTable.RUN,
        -> null
    }
}

private fun inSqlCondition(
    columnName: String,
    values: List<String>,
): SearchSqlCondition {
    require(values.isNotEmpty()) { "SQL IN condition must have at least one value" }
    val placeholders = values.joinToString(",") { "?" }
    return SearchSqlCondition("$columnName IN ($placeholders)", values)
}

private fun idsSqlCondition(ids: List<String>): SearchSqlCondition = inSqlCondition("id", ids)

private fun arrayOverlapSqlCondition(
    columnName: String,
    values: List<String>,
): SearchSqlCondition {
    require(values.isNotEmpty()) { "SQL array overlap condition must have at least one value" }
    return SearchSqlCondition("$columnName && ?::text[]", listOf(values))
}

private fun SearchSqlCondition.joinWithOr(other: SearchSqlCondition): SearchSqlCondition =
    SearchSqlCondition(
        sql = "$sql OR ${other.sql}",
        params = params + other.params,
    )

private fun List<SearchSqlCondition>.joinWithOr(): SearchSqlCondition {
    require(isNotEmpty()) { "Cannot join empty SQL condition list" }
    return reduce { left, right -> left.joinWithOr(right) }
}

private fun String.searchTextSqlCondition(): SearchSqlCondition {
    val queryText = sqlSearchText()
    val tokenPatterns = sqlSearchTokens()
        .take(8)
        .map { it.sqlLikePattern() }
    val parts = mutableListOf("search_text % ?", "search_text ILIKE ? ESCAPE '\\'")
    val params = mutableListOf<Any?>(queryText, sqlLikePattern())
    tokenPatterns.forEach {
        parts += "search_text ILIKE ? ESCAPE '\\'"
        params += it
    }
    return SearchSqlCondition(parts.joinToString(" OR "), params)
}

private fun String.sqlSearchText(): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .take(240)

private fun String.sqlSearchTokens(): List<String> =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 3 }
        .distinct()

private fun String.sqlLikePattern(): String = "%${escapeSqlLike()}%"

private fun String.escapeSqlLike(): String =
    replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

private fun MemoryStore.SearchRequest.sqlCandidateLimit(): Int =
    when {
        query.isBlank() -> (limit * 4).coerceIn(20, 200)
        else -> (limit * 25).coerceIn(80, 1_000)
    }

private fun Set<MemoryStore.SearchScope>.includes(scope: MemoryStore.SearchScope): Boolean =
    contains(MemoryStore.SearchScope.ALL) || contains(scope)

private fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) {
        setNull(index, java.sql.Types.VARCHAR)
    } else {
        setString(index, value)
    }
}

private fun PreparedStatement.setPostgresValue(index: Int, value: Any?) {
    when (value) {
        null -> setObject(index, null)
        is String -> setString(index, value)
        is Boolean -> setBoolean(index, value)
        is Int -> setInt(index, value)
        is Long -> setLong(index, value)
        is Instant -> setTimestamp(index, Timestamp.from(java.time.Instant.parse(value.toString())))
        is List<*> -> setArray(index, connection.createArrayOf("text", value.map { it.toString() }.toTypedArray()))
        else -> setObject(index, value)
    }
}

private fun MemorySource.conversationIdValue(): String? =
    when (this) {
        is MemorySource.ChatTurn -> conversationId.value
        is MemorySource.ToolOutput -> conversationId?.value
        is MemorySource.ImportedNote,
        is MemorySource.ExternalRecord,
        -> null
    }

private fun MemorySource.searchTextForStore(): String =
    searchProjection(searchText.orEmpty(), contentText.take(SOURCE_CONTENT_SEARCH_PREVIEW_CHARS))

private fun MemoryRun.searchTextForStore(): String =
    searchProjection(runType.name, status.name, summary)

private fun MemoryEntity.searchTextForStore(): String =
    searchProjection(
        entityType.name,
        observedTypes.joinToString(" ") { it.name },
        canonicalName,
        normalizedName,
        summary.orEmpty(),
        aliases.joinToString(" ") { "${it.text} ${it.normalizedText}" },
        attributes.toString(),
    )

private fun MemoryClaim.searchTextForStore(): String =
    searchProjection(
        normalizedText,
        contextText.orEmpty(),
        predicate,
        predicateFamily.orEmpty(),
        objectValue?.toString().orEmpty(),
        scope.text,
        qualifiers.toString(),
    )

private fun MemoryClaim.evidenceSourceIdValues(): List<String> =
    evidenceRefs.map { it.sourceId.value }.distinct()

private fun MemoryNote.searchTextForStore(): String =
    searchProjection(
        noteType.name,
        title,
        summary,
        scope.text,
        keywords.joinToString(" "),
        tags.joinToString(" "),
        candidateClaimHints.toString(),
        metadata.toString(),
    )

private fun MemoryNote.evidenceSourceIdValues(): List<String> =
    evidenceRefs.map { it.sourceId.value }.distinct()

private fun MemoryNote.linkedEntityIdValues(): List<String> =
    (listOfNotNull(anchorEntityId) + entityRefs.map { it.entityId })
        .map { it.value }
        .distinct()

private fun MemoryTask.searchTextForStore(): String =
    searchProjection(
        status.name,
        priority.name,
        title,
        description.orEmpty(),
        scope.text,
        acceptanceCriteria.joinToString(" "),
        blockers.joinToString(" "),
    )

private fun MemoryTask.evidenceSourceIdValues(): List<String> =
    evidenceRefs.map { it.sourceId.value }.distinct()

private fun MemoryTask.linkedEntityIdValues(): List<String> =
    (listOfNotNull(ownerEntityId, assigneeEntityId) + relatedEntityIds)
        .map { it.value }
        .distinct()

private fun MemoryProfile.searchTextForStore(): String =
    searchProjection(profileText, profileJson.toString())

private fun MemoryEpisode.searchTextForStore(): String =
    searchProjection(situation, action, result, lesson, tags.joinToString(" "))

private fun MemoryEpisode.evidenceSourceIdValues(): List<String> =
    evidenceRefs.map { it.sourceId.value }.distinct()

private fun searchProjection(vararg parts: String): String =
    parts.asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .take(SEARCH_PROJECTION_MAX_CHARS)

private fun MemoryNote.linkedEntities(entityById: Map<MemoryEntity.Id, MemoryEntity>): List<MemoryEntity> =
    (listOfNotNull(anchorEntityId) + entityRefs.map { it.entityId })
        .distinct()
        .mapNotNull(entityById::get)

private fun MemorySource.matchesSearchRequest(request: MemoryStore.SearchRequest): Boolean =
    request.includeArchived || (deletedAt == null && usagePolicy.allowRecall)

private fun MemoryEntity.matchesSearchRequest(request: MemoryStore.SearchRequest): Boolean =
    request.filters.entityIds.isEmpty() || id in request.filters.entityIds

private fun MemoryClaim.matchesSearchRequest(
    request: MemoryStore.SearchRequest,
    entityById: Map<MemoryEntity.Id, MemoryEntity>,
): Boolean =
    (request.includeArchived || archivedAt == null) &&
        (request.filters.claimStatuses.isEmpty() || status in request.filters.claimStatuses) &&
        (request.filters.claimPredicates.isEmpty() || predicate in request.filters.claimPredicates) &&
        (request.filters.scopes.isEmpty() || scope in request.filters.scopes) &&
        matchesEntityFilter(request.filters.entityIds, entityById)

private fun MemoryNote.matchesSearchRequest(request: MemoryStore.SearchRequest): Boolean =
    (request.includeArchived || archivedAt == null) &&
        (request.filters.noteStatuses.isEmpty() || status in request.filters.noteStatuses) &&
        (request.filters.noteTypes.isEmpty() || noteType in request.filters.noteTypes) &&
        (request.filters.scopes.isEmpty() || scope in request.filters.scopes) &&
        (
            request.filters.entityIds.isEmpty() ||
                anchorEntityId in request.filters.entityIds ||
                entityRefs.any { it.entityId in request.filters.entityIds }
            )

private fun MemoryTask.matchesSearchRequest(request: MemoryStore.SearchRequest): Boolean =
    (request.includeArchived || archivedAt == null) &&
        (request.filters.taskStatuses.isEmpty() || status in request.filters.taskStatuses) &&
        (request.filters.scopes.isEmpty() || scope in request.filters.scopes) &&
        (
            request.filters.entityIds.isEmpty() ||
                ownerEntityId in request.filters.entityIds ||
                assigneeEntityId in request.filters.entityIds ||
                relatedEntityIds.any { it in request.filters.entityIds }
            )

private fun MemoryProfile.matchesSearchRequest(request: MemoryStore.SearchRequest): Boolean =
    request.filters.entityIds.isEmpty() || ownerEntityId in request.filters.entityIds

private fun MemoryEpisode.matchesSearchRequest(request: MemoryStore.SearchRequest): Boolean =
    (request.includeArchived || archivedAt == null) &&
        (request.filters.entityIds.isEmpty() || ownerEntityId in request.filters.entityIds)

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

private fun MemoryStore.SearchHit.stableSearchTypeRank(): Int =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> 0
        is MemoryStore.SearchHit.NoteHit -> 1
        is MemoryStore.SearchHit.TaskHit -> 2
        is MemoryStore.SearchHit.ProfileHit -> 3
        is MemoryStore.SearchHit.EpisodeHit -> 4
        is MemoryStore.SearchHit.EntityHit -> 5
        is MemoryStore.SearchHit.SourceHit -> 6
        is MemoryStore.SearchHit.RunHit -> 7
    }

private fun MemoryStore.SearchHit.stableSearchSortKey(): String =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> listOf(
            source.contentHash,
            source.searchText.orEmpty(),
            source.contentText,
        )

        is MemoryStore.SearchHit.EntityHit -> listOf(
            entity.entityType.name,
            entity.canonicalName,
            entity.normalizedName,
            entity.aliases.joinToString(" ") { it.normalizedText },
        )

        is MemoryStore.SearchHit.ClaimHit -> listOf(
            claim.predicate,
            claim.normalizedText,
            claim.contextText.orEmpty(),
            claim.scope.text,
            claim.objectValue?.toString().orEmpty(),
        )

        is MemoryStore.SearchHit.NoteHit -> listOf(
            note.noteType.name,
            note.title,
            note.summary,
            note.scope.text,
            note.keywords.joinToString(" "),
            note.tags.joinToString(" "),
        )

        is MemoryStore.SearchHit.TaskHit -> listOf(
            task.status.name,
            task.priority.name,
            task.title,
            task.description.orEmpty(),
            task.scope.text,
        )

        is MemoryStore.SearchHit.ProfileHit -> listOf(
            profile.profileText,
            profile.profileJson.toString(),
        )

        is MemoryStore.SearchHit.EpisodeHit -> listOf(
            episode.situation,
            episode.action,
            episode.result,
            episode.lesson,
            episode.tags.joinToString(" "),
        )

        is MemoryStore.SearchHit.RunHit -> listOf(
            run.runType.name,
            run.summary,
            run.inputHash.orEmpty(),
            run.promptName.orEmpty(),
            run.promptVersion.orEmpty(),
        )
    }.joinToString("\u001f").stableMemorySearchSortText()

private fun String.stableMemorySearchSortText(): String =
    lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

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

private fun Map<MemoryItemRef, Double>.boost(ref: MemoryItemRef): Double =
    MemorySearchScorer.vectorBoost(this[ref])

private fun Set<MemoryStore.SearchScope>.toEmbeddableMemoryTypes(): Set<MemoryItemRef.Type> {
    val requestedScopes = this
    val includeAll = requestedScopes.contains(MemoryStore.SearchScope.ALL)
    return buildSet<MemoryItemRef.Type> {
        if (includeAll || requestedScopes.contains(MemoryStore.SearchScope.SOURCES)) add(MemoryItemRef.Type.SOURCE)
        if (includeAll || requestedScopes.contains(MemoryStore.SearchScope.ENTITIES)) add(MemoryItemRef.Type.ENTITY)
        if (includeAll || requestedScopes.contains(MemoryStore.SearchScope.CLAIMS)) add(MemoryItemRef.Type.CLAIM)
        if (includeAll || requestedScopes.contains(MemoryStore.SearchScope.NOTES)) add(MemoryItemRef.Type.NOTE)
        if (includeAll || requestedScopes.contains(MemoryStore.SearchScope.TASKS)) add(MemoryItemRef.Type.TASK)
        if (includeAll || requestedScopes.contains(MemoryStore.SearchScope.PROFILES)) add(MemoryItemRef.Type.PROFILE)
        if (includeAll || requestedScopes.contains(MemoryStore.SearchScope.EPISODES)) add(MemoryItemRef.Type.EPISODE)
    }
}

private fun List<Float>.toPostgresVectorLiteral(): String =
    joinToString(prefix = "[", postfix = "]") { value ->
        require(value.isFinite()) { "Memory embedding vector must contain only finite values" }
        value.toString()
    }

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
