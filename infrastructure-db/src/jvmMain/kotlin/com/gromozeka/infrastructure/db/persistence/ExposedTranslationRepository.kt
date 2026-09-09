package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.TranslationState
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.TranslationRepository
import com.gromozeka.domain.service.TranslationRevisionConflictException
import com.gromozeka.infrastructure.db.persistence.tables.TranslationStates
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedTranslationRepository : TranslationRepository {
    private val json = Json { encodeDefaults = true }

    override suspend fun load(userId: User.Id): TranslationState = dbQuery {
        TranslationStates.selectAll()
            .where { TranslationStates.userId eq userId.value }
            .singleOrNull()
            ?.toState()
            ?: TranslationState()
    }

    override suspend fun save(
        userId: User.Id,
        state: TranslationState,
        expectedRevision: Long,
    ): TranslationState = dbQuery {
        require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE) {
            "Expected translation revision must allow a non-negative successor"
        }
        require(state.revision == expectedRevision + 1) {
            "Replacement translation revision ${state.revision} does not follow $expectedRevision"
        }
        if (expectedRevision == 0L) {
            TranslationStates.insertIgnore {
                it[TranslationStates.userId] = userId.value
                it[revision] = 0
                it[payloadJson] = json.encodeToString(TranslationState())
            }
        }
        val updated = TranslationStates.update({
            (TranslationStates.userId eq userId.value) and
                (TranslationStates.revision eq expectedRevision)
        }) {
            it[revision] = state.revision
            it[payloadJson] = json.encodeToString(state)
        }
        if (updated != 1) {
            val actualRevision = TranslationStates.selectAll()
                .where { TranslationStates.userId eq userId.value }
                .singleOrNull()
                ?.get(TranslationStates.revision)
                ?: 0L
            throw TranslationRevisionConflictException(expectedRevision, actualRevision)
        }
        state
    }

    private fun ResultRow.toState(): TranslationState =
        json.decodeFromString<TranslationState>(this[TranslationStates.payloadJson]).also { state ->
            check(state.revision == this[TranslationStates.revision]) {
                "Translation state payload revision does not match the stored revision"
            }
        }
}
