package com.gromozeka.worker

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import kotlinx.datetime.Instant
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationRuntimeWorkerConfigurationTest {
    private val projectService = object : ProjectDomainService {
        override suspend fun getOrCreate(path: String): Project =
            Project(
                id = Project.Id("project-1"),
                path = path,
                name = "project",
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                lastUsedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

        override suspend fun findById(id: Project.Id): Project? = null

        override suspend fun findByPath(path: String): Project? = null

        override suspend fun findRecent(limit: Int): List<Project> = emptyList()

        override suspend fun updateLastUsed(id: Project.Id): Project? = null
    }
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ConversationRuntimeWorkerConfiguration::class.java)
        .withBean("database", Any::class.java, { Any() })
        .withBean(ProjectDomainService::class.java, { projectService })

    @Test
    fun `binds worker capabilities and affinities from structured properties`() {
        contextRunner
            .withPropertyValues(
                "gromozeka.runtime.worker.id=macbook-primary",
                "gromozeka.runtime.worker.capabilities[0]=TOOL_EXECUTION",
                "gromozeka.runtime.worker.capabilities[1]=LOCAL_AGENT_TOOL",
                "gromozeka.runtime.worker.project-paths[0]=/workspace/project",
                "gromozeka.runtime.worker.affinities[0].kind=MACHINE",
                "gromozeka.runtime.worker.affinities[0].value=macbook",
            )
            .run { context ->
                val descriptor = context.getBean(ConversationRuntimeWorkerDescriptor::class.java)
                assertEquals("macbook-primary", descriptor.id.value)
                assertEquals(
                    setOf(
                        ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                        ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                    ),
                    descriptor.capabilities,
                )
                assertEquals(
                    setOf(
                        ConversationRuntimeWorkerAffinity(
                            kind = ConversationRuntimeWorkerAffinity.Kind.PROJECT,
                            value = "project-1",
                        ),
                        ConversationRuntimeWorkerAffinity(
                            kind = ConversationRuntimeWorkerAffinity.Kind.MACHINE,
                            value = "macbook",
                        )
                    ),
                    descriptor.affinities,
                )
            }
    }

    @Test
    fun `fails fast without capabilities`() {
        contextRunner
            .withPropertyValues("gromozeka.runtime.worker.id=worker-1")
            .run { context ->
                assertNotNull(context.startupFailure)
                assertTrue(
                    context.startupFailure
                        ?.causeChain()
                        ?.any { it.message?.contains("must declare at least one capability") == true } == true
                )
            }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }
}
