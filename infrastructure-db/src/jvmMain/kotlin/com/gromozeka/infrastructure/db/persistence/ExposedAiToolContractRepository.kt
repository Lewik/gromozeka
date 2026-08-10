package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.tool.AI_TOOL_MODEL_NAME_MAX_LENGTH
import com.gromozeka.domain.tool.AiToolContract
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.contractFingerprint
import com.gromozeka.infrastructure.db.persistence.tables.AiToolContracts
import kotlin.time.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.stereotype.Service

@Service
class ExposedAiToolContractRepository(
    private val json: Json,
) : AiToolContractRepository {
    override suspend fun resolveAll(descriptors: Collection<AiToolDescriptor>): List<AiToolContract> {
        val requested = descriptors
            .groupBy(AiToolDescriptor::contractFingerprint)
            .mapValues { (fingerprint, matchingDescriptors) ->
                matchingDescriptors.first().also { descriptor ->
                    check(matchingDescriptors.all { it == descriptor }) {
                        "AI tool contract fingerprint collision: $fingerprint"
                    }
                }
            }
            .toSortedMap()
        if (requested.isEmpty()) return emptyList()

        val existing = dbQuery {
            loadContracts().associateBy(AiToolContract::fingerprint)
        }
        if (requested.keys.all(existing::containsKey)) {
            return requested.map { (fingerprint, descriptor) ->
                existing.getValue(fingerprint).also { it.requireDescriptor(descriptor) }
            }
        }

        return dbQuery {
            TransactionManager.current().exec(
                "LOCK TABLE ai_tool_contracts IN SHARE ROW EXCLUSIVE MODE"
            )
            val known = loadContracts().associateByTo(linkedMapOf(), AiToolContract::fingerprint)
            requested.forEach { (fingerprint, descriptor) ->
                val existingContract = known[fingerprint]
                if (existingContract != null) {
                    existingContract.requireDescriptor(descriptor)
                    return@forEach
                }

                val logicalName = descriptor.definition.name
                val variant = known.values
                    .asSequence()
                    .filter { it.logicalName == logicalName }
                    .maxOfOrNull(AiToolContract::variant)
                    ?.plus(1)
                    ?: 1
                val candidate = AiToolContract(
                    fingerprint = fingerprint,
                    logicalName = logicalName,
                    modelName = allocateModelName(
                        logicalName = logicalName,
                        variant = variant,
                        fingerprint = fingerprint,
                        usedNames = known.values.mapTo(mutableSetOf(), AiToolContract::modelName),
                    ),
                    variant = variant,
                    descriptor = descriptor,
                    createdAt = Clock.System.now(),
                )
                check(AiToolContracts.insertIgnore {
                    it[AiToolContracts.fingerprint] = candidate.fingerprint
                    it[AiToolContracts.logicalName] = candidate.logicalName
                    it[AiToolContracts.modelName] = candidate.modelName
                    it[AiToolContracts.variant] = candidate.variant
                    it[AiToolContracts.sourceId] = candidate.descriptor.definition.source
                    it[AiToolContracts.payloadJson] = json.encodeToString(candidate)
                    it[AiToolContracts.createdAt] = candidate.createdAt
                }.insertedCount == 1) {
                    "AI tool contract allocation conflicted while holding the registry lock"
                }
                known[fingerprint] = candidate
            }
            requested.keys.map { fingerprint -> known.getValue(fingerprint) }
        }
    }

    private fun loadContracts(): List<AiToolContract> =
        AiToolContracts.selectAll()
            .orderBy(
                AiToolContracts.logicalName to SortOrder.ASC,
                AiToolContracts.variant to SortOrder.ASC,
            )
            .map { row -> row.toAiToolContract() }

    private fun ResultRow.toAiToolContract(): AiToolContract =
        json.decodeFromString<AiToolContract>(this[AiToolContracts.payloadJson]).also { contract ->
            check(contract.fingerprint == this[AiToolContracts.fingerprint])
            check(contract.logicalName == this[AiToolContracts.logicalName])
            check(contract.modelName == this[AiToolContracts.modelName])
            check(contract.variant == this[AiToolContracts.variant])
            check(contract.descriptor.definition.source == this[AiToolContracts.sourceId])
        }

    private fun AiToolContract.requireDescriptor(expected: AiToolDescriptor) {
        check(descriptor == expected) {
            "AI tool contract fingerprint collision for '$logicalName'"
        }
    }

    private fun allocateModelName(
        logicalName: String,
        variant: Int,
        fingerprint: String,
        usedNames: Set<String>,
    ): String {
        val preferredSuffix = if (variant == 1) "" else "__v$variant"
        val preferred = logicalName.take(AI_TOOL_MODEL_NAME_MAX_LENGTH - preferredSuffix.length) + preferredSuffix
        if (logicalName.length + preferredSuffix.length <= AI_TOOL_MODEL_NAME_MAX_LENGTH && preferred !in usedNames) {
            return preferred
        }

        val markerLength = "__v${variant}_".length
        val maxHashLength = minOf(
            fingerprint.length,
            AI_TOOL_MODEL_NAME_MAX_LENGTH - markerLength - 1,
        )
        for (hashLength in 8..maxHashLength) {
            val suffix = "__v${variant}_${fingerprint.take(hashLength)}"
            val candidate = logicalName.take(AI_TOOL_MODEL_NAME_MAX_LENGTH - suffix.length) + suffix
            if (candidate !in usedNames) return candidate
        }
        error("Could not allocate a unique model name for AI tool '$logicalName'")
    }
}
