package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.infrastructure.db.persistence.tables.Artifacts
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service
import kotlinx.serialization.json.Json

@Service
class ExposedArtifactRepository : ArtifactRepository {
    private val metadataColumns = Artifacts.columns - Artifacts.searchText

    override suspend fun save(artifact: Artifact, searchableText: String?): Artifact = dbQuery {
        Artifacts.insert {
            it[id] = artifact.id.value
            it[projectId] = artifact.projectId.value
            it[conversationId] = artifact.conversationId.value
            it[createdByUserId] = artifact.createdByUserId?.value
            it[searchText] = searchableText
            it[fileName] = artifact.fileName
            it[mediaType] = artifact.mediaType
            it[sizeBytes] = artifact.sizeBytes
            it[contentSource] = Json.encodeToString(artifact.source)
            it[purpose] = artifact.purpose.name
            it[state] = artifact.state.name
            it[createdAt] = artifact.createdAt
            it[committedAt] = artifact.committedAt
        }
        artifact
    }

    override suspend fun cloneBatch(
        sourceConversationId: Conversation.Id,
        targetConversation: Conversation,
        sourceToTargetIds: Map<Artifact.Id, Artifact.Id>,
        createdAt: Instant,
    ): List<Artifact> {
        require(sourceConversationId != targetConversation.id) { "Artifact clones require a different conversation" }
        require(sourceToTargetIds.values.toSet().size == sourceToTargetIds.size &&
            sourceToTargetIds.values.none { it in sourceToTargetIds }) { "Artifact clone IDs must be new and distinct" }
        if (sourceToTargetIds.isEmpty()) return emptyList()
        return dbQuery {
            sourceToTargetIds.entries.toList().chunked(BATCH_SIZE).flatMap { batch ->
                val rows = Artifacts.selectAll().where { Artifacts.id inList batch.map { it.key.value } }
                    .associateBy { Artifact.Id(it[Artifacts.id]) }
                require(rows.size == batch.size) { "Some artifact clone sources are missing" }
                val copies = batch.map { (sourceId, targetId) ->
                    val source = rows.getValue(sourceId).toArtifact()
                    require(source.conversationId == sourceConversationId && source.projectId == targetConversation.projectId &&
                        source.state == Artifact.State.COMMITTED) { "Artifact clone source must be committed and owned by the source conversation and project" }
                    source.copy(id = targetId, conversationId = targetConversation.id, projectId = targetConversation.projectId,
                        state = Artifact.State.DRAFT, createdAt = createdAt, committedAt = null) to rows.getValue(sourceId)[Artifacts.searchText]
                }
                Artifacts.batchInsert(copies, shouldReturnGeneratedValues = false) { (artifact, text) ->
                    this[Artifacts.id] = artifact.id.value
                    this[Artifacts.projectId] = artifact.projectId.value
                    this[Artifacts.conversationId] = artifact.conversationId.value
                    this[Artifacts.createdByUserId] = artifact.createdByUserId?.value
                    this[Artifacts.searchText] = text
                    this[Artifacts.fileName] = artifact.fileName
                    this[Artifacts.mediaType] = artifact.mediaType
                    this[Artifacts.sizeBytes] = artifact.sizeBytes
                    this[Artifacts.contentSource] = Json.encodeToString(artifact.source)
                    this[Artifacts.purpose] = artifact.purpose.name
                    this[Artifacts.state] = artifact.state.name
                    this[Artifacts.createdAt] = artifact.createdAt
                    this[Artifacts.committedAt] = artifact.committedAt
                }
                copies.map { it.first }
            }
        }
    }

    override suspend fun findById(id: Artifact.Id): Artifact? = dbQuery {
        Artifacts.select(metadataColumns)
            .where { Artifacts.id eq id.value }
            .singleOrNull()
            ?.toArtifact()
    }

    override suspend fun findByIds(ids: List<Artifact.Id>): List<Artifact> {
        if (ids.isEmpty()) return emptyList()
        return dbQuery {
            ids.distinct().chunked(BATCH_SIZE).flatMap { batch ->
                Artifacts.select(metadataColumns)
                    .where { Artifacts.id inList batch.map(Artifact.Id::value) }
                    .map { it.toArtifact() }
            }
        }
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Artifact> = dbQuery {
        Artifacts.select(metadataColumns)
            .where { Artifacts.conversationId eq conversationId.value }
            .map { it.toArtifact() }
    }

    override suspend fun commit(ids: List<Artifact.Id>, committedAt: Instant) {
        if (ids.isEmpty()) return
        dbQuery {
            ids.distinct().chunked(BATCH_SIZE).forEach { batch ->
                val values = batch.map(Artifact.Id::value)
                Artifacts.update({ (Artifacts.id inList values) and (Artifacts.state eq Artifact.State.DRAFT.name) }) {
                    it[state] = Artifact.State.COMMITTED.name
                    it[Artifacts.committedAt] = committedAt
                }
                val committedIds = Artifacts.select(Artifacts.id)
                    .where { (Artifacts.id inList values) and (Artifacts.state eq Artifact.State.COMMITTED.name) }
                    .mapTo(mutableSetOf()) { Artifact.Id(it[Artifacts.id]) }
                require(committedIds.size == batch.size) { "Cannot commit missing artifacts: expected=${batch.size} committed=${committedIds.size}" }
            }
        }
    }

    override suspend fun deleteDraft(id: Artifact.Id): Boolean = dbQuery {
        Artifacts.deleteWhere {
            (Artifacts.id eq id.value) and (Artifacts.state eq Artifact.State.DRAFT.name)
        } > 0
    }

    override suspend fun findDraftsCreatedBefore(createdBefore: Instant, limit: Int): List<Artifact> {
        require(limit > 0) { "Artifact draft query limit must be positive" }
        return dbQuery {
            Artifacts.select(metadataColumns)
                .where {
                    (Artifacts.state eq Artifact.State.DRAFT.name) and
                        (Artifacts.createdAt less createdBefore)
                }
                .limit(limit)
                .map { it.toArtifact() }
        }
    }

    private companion object { const val BATCH_SIZE = 256 }

    private fun ResultRow.toArtifact(): Artifact = Artifact(
        id = Artifact.Id(this[Artifacts.id]),
        projectId = Project.Id(this[Artifacts.projectId]),
        conversationId = Conversation.Id(this[Artifacts.conversationId]),
        createdByUserId = this[Artifacts.createdByUserId]?.let(User::Id),
        fileName = this[Artifacts.fileName],
        mediaType = this[Artifacts.mediaType],
        sizeBytes = this[Artifacts.sizeBytes],
        source = Json.decodeFromString(this[Artifacts.contentSource]),
        purpose = Artifact.Purpose.valueOf(this[Artifacts.purpose]),
        state = Artifact.State.valueOf(this[Artifacts.state]),
        createdAt = this[Artifacts.createdAt],
        committedAt = this[Artifacts.committedAt],
    )
}
