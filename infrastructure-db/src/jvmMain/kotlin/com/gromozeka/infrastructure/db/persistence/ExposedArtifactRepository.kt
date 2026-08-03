package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.infrastructure.db.persistence.tables.Artifacts
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedArtifactRepository : ArtifactRepository {
    override suspend fun save(artifact: Artifact): Artifact = dbQuery {
        Artifacts.insert {
            it[id] = artifact.id.value
            it[projectId] = artifact.projectId.value
            it[conversationId] = artifact.conversationId.value
            it[createdByUserId] = artifact.createdByUserId?.value
            it[fileName] = artifact.fileName
            it[mediaType] = artifact.mediaType
            it[sizeBytes] = artifact.sizeBytes
            it[sha256] = artifact.sha256
            it[purpose] = artifact.purpose.name
            it[state] = artifact.state.name
            it[createdAt] = artifact.createdAt.toKotlin()
            it[committedAt] = artifact.committedAt?.toKotlin()
        }
        artifact
    }

    override suspend fun findById(id: Artifact.Id): Artifact? = dbQuery {
        Artifacts.selectAll()
            .where { Artifacts.id eq id.value }
            .singleOrNull()
            ?.toArtifact()
    }

    override suspend fun findByIds(ids: List<Artifact.Id>): List<Artifact> {
        if (ids.isEmpty()) return emptyList()
        return dbQuery {
            Artifacts.selectAll()
                .where { Artifacts.id inList ids.map(Artifact.Id::value) }
                .map { it.toArtifact() }
        }
    }

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Artifact> = dbQuery {
        Artifacts.selectAll()
            .where { Artifacts.conversationId eq conversationId.value }
            .map { it.toArtifact() }
    }

    override suspend fun commit(ids: List<Artifact.Id>, committedAt: Instant) {
        if (ids.isEmpty()) return
        val distinctIds = ids.distinct()
        dbQuery {
            val values = distinctIds.map(Artifact.Id::value)
            Artifacts.update({
                (Artifacts.id inList values) and (Artifacts.state eq Artifact.State.DRAFT.name)
            }) {
                it[state] = Artifact.State.COMMITTED.name
                it[Artifacts.committedAt] = committedAt.toKotlin()
            }
            val committedIds = Artifacts.selectAll()
                .where {
                    (Artifacts.id inList values) and (Artifacts.state eq Artifact.State.COMMITTED.name)
                }
                .mapTo(mutableSetOf()) { Artifact.Id(it[Artifacts.id]) }
            require(committedIds.size == distinctIds.size) {
                "Cannot commit missing artifacts: expected=${distinctIds.size} committed=${committedIds.size}"
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
            Artifacts.selectAll()
                .where {
                    (Artifacts.state eq Artifact.State.DRAFT.name) and
                        (Artifacts.createdAt less createdBefore.toKotlin())
                }
                .limit(limit)
                .map { it.toArtifact() }
        }
    }

    private fun ResultRow.toArtifact(): Artifact = Artifact(
        id = Artifact.Id(this[Artifacts.id]),
        projectId = Project.Id(this[Artifacts.projectId]),
        conversationId = Conversation.Id(this[Artifacts.conversationId]),
        createdByUserId = this[Artifacts.createdByUserId]?.let(User::Id),
        fileName = this[Artifacts.fileName],
        mediaType = this[Artifacts.mediaType],
        sizeBytes = this[Artifacts.sizeBytes],
        sha256 = this[Artifacts.sha256],
        purpose = Artifact.Purpose.valueOf(this[Artifacts.purpose]),
        state = Artifact.State.valueOf(this[Artifacts.state]),
        createdAt = this[Artifacts.createdAt].toKotlinx(),
        committedAt = this[Artifacts.committedAt]?.toKotlinx(),
    )
}
