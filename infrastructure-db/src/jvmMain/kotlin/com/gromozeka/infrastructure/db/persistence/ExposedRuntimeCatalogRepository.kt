package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.RuntimeCatalogSeed
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiWebToolConfiguration
import com.gromozeka.domain.repository.AiCatalogRepository
import com.gromozeka.domain.repository.RuntimeCatalogBootstrapRepository
import com.gromozeka.infrastructure.db.persistence.tables.Agents
import com.gromozeka.infrastructure.db.persistence.tables.AiConnections
import com.gromozeka.infrastructure.db.persistence.tables.AiModelConfigurations
import com.gromozeka.infrastructure.db.persistence.tables.AiModelSpecs
import com.gromozeka.infrastructure.db.persistence.tables.AiRuntimeAssignments
import com.gromozeka.infrastructure.db.persistence.tables.Prompts
import com.gromozeka.infrastructure.db.persistence.tables.RuntimeCatalogConfiguration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedRuntimeCatalogRepository(
    private val json: Json,
) : AiCatalogRepository, RuntimeCatalogBootstrapRepository {
    override suspend fun find(): AiCatalogSnapshot? = dbQuery {
        loadSnapshot()
    }

    override suspend fun findRevision(): Long? = dbQuery {
        RuntimeCatalogConfiguration.selectAll()
            .where { RuntimeCatalogConfiguration.id eq CONFIGURATION_ID }
            .singleOrNull()
            ?.get(RuntimeCatalogConfiguration.revision)
    }

    override suspend fun replace(
        expectedRevision: Long,
        catalog: AiCatalog,
    ): AiCatalogSnapshot = dbQuery {
        val updated = RuntimeCatalogConfiguration.update({
            (RuntimeCatalogConfiguration.id eq CONFIGURATION_ID) and
                (RuntimeCatalogConfiguration.revision eq expectedRevision)
        }) {
            it[defaultAgentId] = catalog.defaultAgentId.value
            it[webToolsJson] = json.encodeToString(catalog.webTools)
            it[revision] = expectedRevision + 1
        }
        check(updated == 1) {
            val actualRevision = RuntimeCatalogConfiguration.selectAll()
                .where { RuntimeCatalogConfiguration.id eq CONFIGURATION_ID }
                .singleOrNull()
                ?.get(RuntimeCatalogConfiguration.revision)
            "AI catalog revision conflict: expected $expectedRevision, actual $actualRevision"
        }

        replaceAiCatalogRows(catalog)
        AiCatalogSnapshot(catalog, expectedRevision + 1)
    }

    override suspend fun initializeIfEmpty(seed: RuntimeCatalogSeed): Boolean = dbQuery {
        if (RuntimeCatalogConfiguration.selectAll().limit(1).any()) {
            return@dbQuery false
        }

        check(Agents.selectAll().limit(1).none()) {
            "Cannot initialize runtime catalog while agent rows already exist"
        }
        check(Prompts.selectAll().limit(1).none()) {
            "Cannot initialize runtime catalog while prompt rows already exist"
        }

        seed.prompts.forEach { prompt ->
            Prompts.insert {
                it[id] = prompt.id.value
                it[projectId] = prompt.projectId?.value
                it[name] = prompt.name
                it[content] = prompt.content
                it[scope] = prompt.type.databaseValue()
                it[createdAt] = prompt.createdAt
                it[updatedAt] = prompt.updatedAt
            }
        }
        seed.agents.forEach { agent ->
            Agents.insert {
                it[id] = agent.id.value
                it[projectId] = agent.projectId?.value
                it[name] = agent.name
                it[promptsJson] = json.encodeToString(agent.prompts)
                it[skillsJson] = json.encodeToString(agent.skills)
                it[runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
                it[runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
                it[toolsJson] = json.encodeToString(agent.tools)
                it[description] = agent.description
                it[type] = agent.type.databaseValue()
                it[createdAt] = agent.createdAt
                it[updatedAt] = agent.updatedAt
            }
        }
        replaceAiCatalogRows(seed.aiCatalog)
        RuntimeCatalogConfiguration.insert {
            it[id] = CONFIGURATION_ID
            it[defaultAgentId] = seed.aiCatalog.defaultAgentId.value
            it[webToolsJson] = json.encodeToString(seed.aiCatalog.webTools)
            it[revision] = 0
        }
        true
    }

    private fun loadSnapshot(): AiCatalogSnapshot? {
        val configuration = RuntimeCatalogConfiguration.selectAll()
            .where { RuntimeCatalogConfiguration.id eq CONFIGURATION_ID }
            .singleOrNull()
            ?: return null

        val catalog = AiCatalog(
            connections = AiConnections.selectAll()
                .map { json.decodeFromString<AiConnection>(it[AiConnections.payloadJson]) }
                .sortedBy { it.displayName.lowercase() },
            modelSpecs = AiModelSpecs.selectAll()
                .map { json.decodeFromString<AiModelSpec>(it[AiModelSpecs.payloadJson]) }
                .sortedWith(compareBy({ it.provider.name }, { it.id })),
            modelConfigurations = AiModelConfigurations.selectAll()
                .map { json.decodeFromString<AiModelConfiguration>(it[AiModelConfigurations.payloadJson]) }
                .sortedBy { it.displayName.lowercase() },
            runtimeAssignments = AiRuntimeAssignments.selectAll()
                .map { json.decodeFromString<AiRuntimeAssignment>(it[AiRuntimeAssignments.payloadJson]) }
                .sortedBy { it.purpose.ordinal },
            defaultAgentId = AgentDefinition.Id(configuration[RuntimeCatalogConfiguration.defaultAgentId]),
            webTools = json.decodeFromString<AiWebToolConfiguration>(
                configuration[RuntimeCatalogConfiguration.webToolsJson]
            ),
        )
        return AiCatalogSnapshot(
            catalog = catalog,
            revision = configuration[RuntimeCatalogConfiguration.revision],
        )
    }

    private fun replaceAiCatalogRows(catalog: AiCatalog) {
        AiRuntimeAssignments.deleteAll()
        AiModelConfigurations.deleteAll()
        AiModelSpecs.deleteAll()

        val existingConnectionIds = AiConnections.selectAll()
            .mapTo(mutableSetOf()) { it[AiConnections.id] }
        val desiredConnectionIds = catalog.connections.mapTo(mutableSetOf()) { it.id.value }
        catalog.connections.forEach { connection ->
            val payload = json.encodeToString<AiConnection>(connection)
            if (connection.id.value in existingConnectionIds) {
                AiConnections.update({ AiConnections.id eq connection.id.value }) {
                    it[payloadJson] = payload
                }
            } else {
                AiConnections.insert {
                    it[id] = connection.id.value
                    it[payloadJson] = payload
                }
            }
        }
        (existingConnectionIds - desiredConnectionIds).forEach { connectionId ->
            AiConnections.deleteWhere { AiConnections.id eq connectionId }
        }
        catalog.modelSpecs.forEach { spec ->
            AiModelSpecs.insert {
                it[provider] = spec.provider.name
                it[modelId] = spec.id
                it[payloadJson] = json.encodeToString(spec)
            }
        }
        catalog.modelConfigurations.forEach { configuration ->
            AiModelConfigurations.insert {
                it[id] = configuration.id.value
                it[connectionId] = configuration.connectionId.value
                it[payloadJson] = json.encodeToString(configuration)
            }
        }
        catalog.runtimeAssignments.forEach { assignment ->
            AiRuntimeAssignments.insert {
                it[purpose] = assignment.purpose.name
                it[modelConfigurationId] = assignment.selection.modelConfigurationId.value
                it[payloadJson] = json.encodeToString(assignment)
            }
        }
    }

    private fun AgentDefinition.Type.databaseValue(): String =
        when (this) {
            AgentDefinition.Type.Global -> GLOBAL_SCOPE
            AgentDefinition.Type.Project -> PROJECT_SCOPE
        }

    private fun com.gromozeka.domain.model.Prompt.Type.databaseValue(): String =
        when (this) {
            com.gromozeka.domain.model.Prompt.Type.Global -> GLOBAL_SCOPE
            com.gromozeka.domain.model.Prompt.Type.Project -> PROJECT_SCOPE
        }

    private companion object {
        const val CONFIGURATION_ID = "primary"
        const val GLOBAL_SCOPE = "global"
        const val PROJECT_SCOPE = "project"
    }
}
