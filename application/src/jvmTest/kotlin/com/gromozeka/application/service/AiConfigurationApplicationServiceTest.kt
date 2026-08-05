package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSecretSlot
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiWebToolConfiguration
import com.gromozeka.domain.model.ai.redactInlineSecrets
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AiCatalogRepository
import com.gromozeka.domain.service.SettingsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AiConfigurationApplicationServiceTest {
    private val seed = RuntimeCatalogTemplateApplicationService().createSeed()

    @Test
    fun `connection upsert preserves configured secret and enforces catalog revision`() = runBlocking {
        val repository = TestAiCatalogRepository(seed.aiCatalog, revision = 7)
        val service = service(repository)
        service.initialize()
        val original = assertIs<AiConnection.OpenAiApi>(
            service.snapshot.catalog.connections.single { it.id.value == "openai-api" }
        )

        val updated = service.upsertConnection(
            connection = original.copy(displayName = "OpenAI API renamed", apiKey = null),
            expectedRevision = 7,
            preserveExistingSecret = true,
        )

        assertEquals(8, updated.revision)
        val stored = assertIs<AiConnection.OpenAiApi>(
            updated.catalog.connections.single { it.id == original.id }
        )
        assertEquals("OpenAI API renamed", stored.displayName)
        assertEquals(original.apiKey, stored.apiKey)

        val error = assertFailsWith<IllegalArgumentException> {
            service.upsertConnection(
                connection = stored,
                expectedRevision = 7,
                preserveExistingSecret = true,
            )
        }
        assertTrue(error.message.orEmpty().contains("revision conflict"))
        assertEquals(8, repository.findRevision())
    }

    @Test
    fun `invalid catalog mutation is rejected without partial persistence`() = runBlocking {
        val repository = TestAiCatalogRepository(seed.aiCatalog, revision = 11)
        val service = service(repository)
        service.initialize()

        assertFailsWith<IllegalArgumentException> {
            service.deleteConnection(
                connectionId = AiConnection.Id("openai-api"),
                expectedRevision = 11,
            )
        }

        assertEquals(11, repository.findRevision())
        assertTrue(repository.find()!!.catalog.connections.any { it.id.value == "openai-api" })
    }

    @Test
    fun `runtime assignment mutation rejects an unavailable connection`() = runBlocking {
        val repository = TestAiCatalogRepository(seed.aiCatalog, revision = 5)
        val service = service(repository, runtimeEnabledConnectionIds = emptySet())
        service.initialize()
        val assignment = seed.aiCatalog.runtimeAssignments.single {
            it.purpose == AiRuntimeAssignment.Purpose.MEMORY_READ
        }

        val error = assertFailsWith<IllegalArgumentException> {
            service.setRuntimeAssignment(assignment, expectedRevision = 5)
        }

        assertTrue(error.message.orEmpty().contains("requires an enabled compatible model"))
        assertEquals(5, repository.findRevision())
    }

    @Test
    fun `web tool update preserves configured secrets when requested`() = runBlocking {
        val braveKey = SecretRef.Inline("brave-secret")
        val jinaKey = SecretRef.Inline("jina-secret")
        val initialCatalog = seed.aiCatalog.copy(
            webTools = AiWebToolConfiguration(
                braveSearch = AiWebToolConfiguration.BraveSearch(apiKey = braveKey),
                jinaReader = AiWebToolConfiguration.JinaReader(apiKey = jinaKey),
            )
        )
        val repository = TestAiCatalogRepository(initialCatalog, revision = 3)
        val service = service(repository)
        service.initialize()

        val updated = service.setWebToolConfiguration(
            configuration = AiWebToolConfiguration(
                braveSearch = AiWebToolConfiguration.BraveSearch(enabled = true),
                jinaReader = AiWebToolConfiguration.JinaReader(enabled = true),
            ),
            expectedRevision = 3,
            preserveExistingSecrets = true,
        )

        assertEquals(braveKey, updated.catalog.webTools.braveSearch.apiKey)
        assertEquals(jinaKey, updated.catalog.webTools.jinaReader.apiKey)
        assertTrue(updated.catalog.webTools.braveSearch.enabled)
        assertTrue(updated.catalog.webTools.jinaReader.enabled)
    }

    @Test
    fun `redacted catalog replacement preserves secrets unless explicitly mutated`() = runBlocking {
        val connectionKey = SecretRef.Inline("connection-secret")
        val braveKey = SecretRef.Inline("brave-secret")
        val initialCatalog = seed.aiCatalog.copy(
            connections = seed.aiCatalog.connections.map { connection ->
                if (connection is AiConnection.OpenAiApi) {
                    connection.copy(apiKey = connectionKey)
                } else {
                    connection
                }
            },
            webTools = AiWebToolConfiguration(
                braveSearch = AiWebToolConfiguration.BraveSearch(
                    enabled = true,
                    apiKey = braveKey,
                )
            ),
        )
        val repository = TestAiCatalogRepository(initialCatalog, revision = 4)
        val service = service(repository)
        service.initialize()
        val redacted = service.snapshot.redactInlineSecrets()
        val openAiConnection = redacted.catalog.connections
            .filterIsInstance<AiConnection.OpenAiApi>()
            .single()

        val preserved = service.replaceCatalog(
            catalog = redacted.catalog.copy(
                connections = redacted.catalog.connections.map { connection ->
                    if (connection.id == openAiConnection.id) {
                        openAiConnection.copy(displayName = "Renamed")
                    } else {
                        connection
                    }
                }
            ),
            expectedRevision = 4,
        )

        assertEquals(
            connectionKey,
            assertIs<AiConnection.OpenAiApi>(
                preserved.catalog.connections.single { it.id == openAiConnection.id }
            ).apiKey,
        )
        assertEquals(braveKey, preserved.catalog.webTools.braveSearch.apiKey)

        val removed = service.replaceCatalog(
            catalog = preserved.catalog.redactInlineSecrets(),
            expectedRevision = 5,
            secretMutations = listOf(
                AiCatalogSecretMutation.Remove(
                    AiCatalogSecretSlot.ConnectionApiKey(openAiConnection.id)
                )
            ),
        )

        assertNull(
            assertIs<AiConnection.OpenAiApi>(
                removed.catalog.connections.single { it.id == openAiConnection.id }
            ).apiKey
        )
        assertEquals(braveKey, removed.catalog.webTools.braveSearch.apiKey)
    }

    private fun service(
        repository: AiCatalogRepository,
        runtimeEnabledConnectionIds: Set<AiConnection.Id> =
            seed.aiCatalog.connections.mapTo(mutableSetOf(), AiConnection::id),
    ) =
        AiConfigurationApplicationService(
            repository = repository,
            agentRepository = TestAgentRepository(seed.agents),
            settingsProvider = TestSettingsProvider(runtimeEnabledConnectionIds),
        )
}

private class TestAiCatalogRepository(
    initialCatalog: AiCatalog,
    revision: Long,
) : AiCatalogRepository {
    private var snapshot = AiCatalogSnapshot(initialCatalog, revision)

    override suspend fun find(): AiCatalogSnapshot = snapshot

    override suspend fun findRevision(): Long = snapshot.revision

    override suspend fun replace(
        expectedRevision: Long,
        catalog: AiCatalog,
    ): AiCatalogSnapshot {
        require(snapshot.revision == expectedRevision) {
            "AI catalog revision conflict: expected $expectedRevision, actual ${snapshot.revision}"
        }
        return AiCatalogSnapshot(catalog, snapshot.revision + 1).also { snapshot = it }
    }
}

private class TestAgentRepository(
    agents: List<AgentDefinition>,
) : AgentRepository {
    private val agents = agents.associateBy(AgentDefinition::id).toMutableMap()

    override suspend fun save(agent: AgentDefinition): AgentDefinition =
        agent.also { agents[it.id] = it }

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? = agents[id]

    override suspend fun findAll(): List<AgentDefinition> = agents.values.toList()

    override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> =
        agents.values.filter { it.projectId == null || it.projectId == projectId }

    override suspend fun delete(id: AgentDefinition.Id) {
        agents.remove(id)
    }

    override suspend fun count(): Int = agents.size
}

private class TestSettingsProvider(
    override val runtimeEnabledAiConnectionIds: Set<AiConnection.Id>,
) : SettingsProvider {
    override val userProfile = UserProfile()
    override val userDeviceSettings = UserDeviceSettings.Desktop()
    override val mode = AppMode.TEST
    override val homeDirectory = "/tmp/gromozeka-test"

    override fun resolveSecret(secretRef: SecretRef?): String? =
        (secretRef as? SecretRef.Inline)?.value
}
