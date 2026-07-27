package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.repository.AiToolCapabilityCatalogRepository
import com.gromozeka.domain.tool.AiToolCapabilityCatalog
import com.gromozeka.infrastructure.db.persistence.tables.AiToolCapabilityCatalogs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service

@Service
class ExposedAiToolCapabilityCatalogRepository(
    private val json: Json,
) : AiToolCapabilityCatalogRepository {
    override suspend fun find(
        sourceId: String,
        fingerprint: String,
    ): AiToolCapabilityCatalog? = dbQuery {
        AiToolCapabilityCatalogs.selectAll()
            .where {
                (AiToolCapabilityCatalogs.sourceId eq sourceId) and
                    (AiToolCapabilityCatalogs.fingerprint eq fingerprint)
            }
            .singleOrNull()
            ?.let { row -> decodeCatalog(sourceId, fingerprint, row[AiToolCapabilityCatalogs.payloadJson]) }
    }

    override suspend fun saveIfAbsent(catalog: AiToolCapabilityCatalog): AiToolCapabilityCatalog = dbQuery {
        val payload = json.encodeToString(catalog)

        AiToolCapabilityCatalogs.insertIgnore {
            it[sourceId] = catalog.sourceId
            it[fingerprint] = catalog.fingerprint
            it[modelConfigurationId] = catalog.generatedByModelConfigurationId.value
            it[payloadJson] = payload
            it[generatedAt] = catalog.generatedAt.toKotlin()
        }
        val storedPayload = AiToolCapabilityCatalogs.selectAll()
            .where {
                (AiToolCapabilityCatalogs.sourceId eq catalog.sourceId) and
                    (AiToolCapabilityCatalogs.fingerprint eq catalog.fingerprint)
            }
            .single()[AiToolCapabilityCatalogs.payloadJson]
        decodeCatalog(catalog.sourceId, catalog.fingerprint, storedPayload)
    }

    private fun decodeCatalog(
        expectedSourceId: String,
        expectedFingerprint: String,
        payload: String,
    ): AiToolCapabilityCatalog =
        json.decodeFromString<AiToolCapabilityCatalog>(payload).also { catalog ->
            check(catalog.sourceId == expectedSourceId) {
                "Stored AI tool capability catalog source does not match its cache key"
            }
            check(catalog.fingerprint == expectedFingerprint) {
                "Stored AI tool capability catalog fingerprint does not match its cache key"
            }
        }
}
