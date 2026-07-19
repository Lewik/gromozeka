package com.gromozeka.worker

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolMetadata
import kotlinx.datetime.Instant
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationRuntimeWorkerConfigurationTest {
    private val timestamp = Instant.parse("2026-01-01T00:00:00Z")
    private val projectService = RecordingProjectService(timestamp)
    private val workspaceService = RecordingWorkspaceService(projectService, timestamp)
    private fun contextRunner(tools: List<AiToolCallback> = emptyList()) =
        ApplicationContextRunner()
            .withUserConfiguration(ConversationRuntimeWorkerConfiguration::class.java)
            .withBean("database", Any::class.java, { Any() })
            .withBean(ProjectDomainService::class.java, { projectService })
            .withBean(WorkspaceDomainService::class.java, { workspaceService })
            .withBean(AiToolProvider::class.java, {
                object : AiToolProvider {
                    override fun getTools(): List<AiToolCallback> = tools
                }
            })

    @Test
    fun `binds worker capabilities and bootstraps configured workspace`() {
        contextRunner()
            .withPropertyValues(
                "gromozeka.runtime.worker.id=macbook-primary",
                "gromozeka.runtime.worker.capabilities[0]=TOOL_EXECUTION",
                "gromozeka.runtime.worker.capabilities[1]=LOCAL_AGENT_TOOL",
                "gromozeka.runtime.worker.workspaces[0].id=workspace-1",
                "gromozeka.runtime.worker.workspaces[0].project-id=project-1",
                "gromozeka.runtime.worker.workspaces[0].project-name=Project",
                "gromozeka.runtime.worker.workspaces[0].name=Local checkout",
                "gromozeka.runtime.worker.workspaces[0].root-path=/workspace/project",
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
                assertEquals(emptyList(), descriptor.tools)
                assertEquals(
                    RecordingWorkspaceService.CreatedWorkspace(
                        projectId = Project.Id("project-1"),
                        workspaceId = Workspace.Id("workspace-1"),
                        name = "Local checkout",
                        workerId = "macbook-primary",
                        rootPath = "/workspace/project",
                    ),
                    workspaceService.createdWorkspace,
                )
            }
    }

    @Test
    fun `fails fast without capabilities`() {
        contextRunner()
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

    @Test
    fun `llm worker does not advertise executable tools`() {
        contextRunner(
            listOf(
                TestTool("generic"),
                TestTool(
                    "local",
                    setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
                ),
            )
        )
            .withPropertyValues(
                "gromozeka.runtime.worker.id=llm-worker",
                "gromozeka.runtime.worker.capabilities[0]=LLM_RUNTIME",
                "gromozeka.runtime.worker.capabilities[1]=MEMORY_PIPELINE",
            )
            .run { context ->
                val descriptor = context.getBean(ConversationRuntimeWorkerDescriptor::class.java)
                assertTrue(descriptor.tools.isEmpty())
            }
    }

    @Test
    fun `tool worker advertises only tools supported by its capabilities`() {
        contextRunner(
            listOf(
                TestTool("generic"),
                TestTool(
                    "local",
                    setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
                ),
            )
        )
            .withPropertyValues(
                "gromozeka.runtime.worker.id=tool-worker",
                "gromozeka.runtime.worker.capabilities[0]=TOOL_EXECUTION",
            )
            .run { context ->
                val descriptor = context.getBean(ConversationRuntimeWorkerDescriptor::class.java)
                assertEquals(listOf("generic"), descriptor.tools.map { it.definition.name })
            }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }
}

private class RecordingProjectService(
    private val timestamp: Instant,
) : ProjectDomainService {
    private val projects = mutableMapOf<Project.Id, Project>()

    override suspend fun create(
        name: String,
        description: String?,
        id: Project.Id?,
    ): Project {
        val project = Project(
            id = checkNotNull(id),
            name = name,
            description = description,
            createdAt = timestamp,
            lastUsedAt = timestamp,
        )
        projects[project.id] = project
        return project
    }

    override suspend fun findById(id: Project.Id): Project? = projects[id]

    override suspend fun findRecent(limit: Int): List<Project> = projects.values.take(limit)

    override suspend fun updateLastUsed(id: Project.Id): Project? = projects[id]
}

private class RecordingWorkspaceService(
    private val projectService: ProjectDomainService,
    private val timestamp: Instant,
) : WorkspaceDomainService {
    var createdWorkspace: CreatedWorkspace? = null

    override suspend fun createFilesystem(
        projectId: Project.Id,
        name: String,
        workerId: String,
        rootPath: String,
        id: Workspace.Id?,
    ): WorkspaceExecutionContext {
        val workspaceId = checkNotNull(id)
        createdWorkspace = CreatedWorkspace(projectId, workspaceId, name, workerId, rootPath)
        return WorkspaceExecutionContext(
            project = checkNotNull(projectService.findById(projectId)),
            workspace = Workspace(
                id = workspaceId,
                projectId = projectId,
                name = name,
                kind = Workspace.Kind.FILESYSTEM,
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
            mount = WorkspaceMount(
                workspaceId = workspaceId,
                workerId = workerId,
                rootPath = rootPath,
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
    }

    override suspend fun attachFilesystem(
        workspaceId: Workspace.Id,
        workerId: String,
        rootPath: String,
    ): WorkspaceExecutionContext = error("Not used")

    override suspend fun findById(id: Workspace.Id): Workspace? = null

    override suspend fun findByProject(projectId: Project.Id): List<Workspace> = emptyList()

    override suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount? = null

    override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> = emptyList()

    override suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount> = emptyList()

    override suspend fun findByWorkerPath(workerId: String, rootPath: String): WorkspaceExecutionContext? = null

    override suspend fun resolveExecution(
        workspaceId: Workspace.Id,
        workerId: String,
    ): WorkspaceExecutionContext =
        error("Not used")

    override suspend fun resolveRuntime(
        workspaceId: Workspace.Id,
        workerId: String,
    ): RuntimeEnvironmentContext.WorkspaceBound = error("Not used")

    data class CreatedWorkspace(
        val projectId: Project.Id,
        val workspaceId: Workspace.Id,
        val name: String,
        val workerId: String,
        val rootPath: String,
    )
}

private class TestTool(
    name: String,
    requiredCapabilities: Set<ConversationRuntimeWorkerCapability> = emptySet(),
) : AiToolCallback {
    override val definition = AiToolDefinition(
        name = name,
        description = name,
        inputSchema = """{"type":"object"}""",
    )
    override val metadata = AiToolMetadata(requiredRuntimeCapabilities = requiredCapabilities)

    override fun call(toolInput: String, context: com.gromozeka.domain.tool.ToolExecutionContext?): String =
        error("Not used")
}
