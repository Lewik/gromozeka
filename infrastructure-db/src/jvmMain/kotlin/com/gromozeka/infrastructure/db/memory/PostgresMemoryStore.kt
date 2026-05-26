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
        val snapshot = loadSnapshot(request.namespace, request.includeArchived)
        val includeAll = request.scopes.contains(MemoryStore.SearchScope.ALL)
        val entityById = snapshot.entities.associateBy { it.id }
        val vectorScores = request.vectorScores()

        val candidates = buildList {
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.SOURCES)) {
                snapshot.sources
                    .mapTo(this) {
                        MemoryStore.SearchHit.SourceHit(
                            it,
                            score = MemorySearchScorer.sourceScore(request.query, it) +
                                vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.SOURCE, it.id.value)),
                        )
                    }
            }
            if (includeAll || request.scopes.contains(MemoryStore.SearchScope.ENTITIES)) {
                snapshot.entities
                    .filter { request.filters.entityIds.isEmpty() || it.id in request.filters.entityIds }
                    .mapTo(this) {
                        MemoryStore.SearchHit.EntityHit(
                            it,
                            score = MemorySearchScorer.entityScore(request.query, it) +
                                vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.ENTITY, it.id.value)),
                        )
                    }
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
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.CLAIM, it.id.value)),
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
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.NOTE, it.id.value)),
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
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.TASK, it.id.value)),
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
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.PROFILE, it.id.value)),
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
                            ) + vectorScores.boost(MemoryItemRef(MemoryItemRef.Type.EPISODE, it.id.value)),
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
            "Postgres memory search: namespace=${request.namespace?.value ?: "all"} scopes=${request.scopes.joinToString { it.name }} " +
                "query=${request.query.oneLineForMemorySearchLog(120)} filters=${request.filters.filtersForLog()} " +
                "embedding=${request.embedding?.let { "${it.modelConfigurationId}/${it.dimensions}" } ?: "none"} " +
                "snapshot=${snapshot.countsForLog()} candidates=${candidates.size} eligible=${eligibleCandidates.size} result=${result.size} " +
                "top=${result.joinToString("|") { it.hitForLog() }.ifBlank { "none" }}"
        }

        return result
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
                "search_text" to item.searchText,
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
                id, namespace, memory_type, memory_id, model_configuration_id, provider_model_id,
                dimensions, content_hash, $vectorColumn, $otherVectorColumn, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, NULL, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                namespace = EXCLUDED.namespace,
                memory_type = EXCLUDED.memory_type,
                memory_id = EXCLUDED.memory_id,
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
            statement.setString(5, item.modelConfigurationId)
            statement.setString(6, item.providerModelId)
            statement.setInt(7, item.dimensions)
            statement.setString(8, item.contentHash)
            statement.setString(9, item.vector.toPostgresVectorLiteral())
            statement.setTimestamp(10, Timestamp.from(java.time.Instant.parse(item.createdAt.toString())))
            statement.setTimestamp(11, Timestamp.from(java.time.Instant.parse(item.updatedAt.toString())))
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
            SELECT memory_type, memory_id, 1 - ($vectorColumn <=> ?::vector) AS score
            FROM memory_embeddings
            WHERE ${namespaceFilter}model_configuration_id = ?
              AND dimensions = ?
              AND memory_type IN ($typePlaceholders)
              AND $vectorColumn IS NOT NULL
            ORDER BY $vectorColumn <=> ?::vector
            LIMIT ?
        """.trimIndent()

        val vectorLiteral = searchEmbedding.vector.toPostgresVectorLiteral()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                statement.setString(index++, vectorLiteral)
                namespace?.let { statement.setString(index++, it.value) }
                statement.setString(index++, searchEmbedding.modelConfigurationId)
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
}

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

private fun Map<MemoryItemRef, Double>.boost(ref: MemoryItemRef): Double =
    (this[ref] ?: 0.0) * 1.25

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
