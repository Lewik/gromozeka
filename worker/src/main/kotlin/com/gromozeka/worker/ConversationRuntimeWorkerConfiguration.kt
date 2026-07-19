package com.gromozeka.worker

import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.supportedBy
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn

@Configuration
@EnableConfigurationProperties(ConversationRuntimeWorkerProperties::class)
class ConversationRuntimeWorkerConfiguration {

    @Bean
    @DependsOn("database")
    fun conversationRuntimeWorkerDescriptor(
        properties: ConversationRuntimeWorkerProperties,
        projectService: ProjectDomainService,
        workspaceService: WorkspaceDomainService,
        aiToolProvider: AiToolProvider,
    ): ConversationRuntimeWorkerDescriptor {
        val workerId = properties.id
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: error("gromozeka.runtime.worker.id is required")
        require(properties.capabilities.isNotEmpty()) {
            "gromozeka.runtime.worker.capabilities must declare at least one capability"
        }
        runBlocking {
            properties.workspaces.forEach { configured ->
                val projectId = Project.Id(configured.projectId.trim())
                val project = projectService.findById(projectId)
                    ?: projectService.create(
                        name = configured.projectName,
                        id = projectId,
                    )
                val workspaceId = Workspace.Id(configured.id.trim())
                val workspace = workspaceService.findById(workspaceId)
                val context = if (workspace == null) {
                    workspaceService.createFilesystem(
                        projectId = project.id,
                        name = configured.name,
                        workerId = workerId,
                        rootPath = configured.rootPath,
                        id = workspaceId,
                    )
                } else {
                    require(workspace.projectId == project.id) {
                        "Workspace ${workspace.id.value} belongs to project ${workspace.projectId.value}, " +
                            "not ${project.id.value}"
                    }
                    workspaceService.attachFilesystem(
                        workspaceId = workspace.id,
                        workerId = workerId,
                        rootPath = configured.rootPath,
                    )
                }
            }
        }
        val tools = if (ConversationRuntimeWorkerCapability.TOOL_EXECUTION in properties.capabilities) {
            aiToolProvider.getTools()
        } else {
            emptyList()
        }
            .supportedBy(properties.capabilities)
            .map { AiToolDescriptor(it.definition, it.metadata) }
            .sortedBy { it.definition.name }
        return ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId(workerId),
            capabilities = properties.capabilities,
            tools = tools,
        )
    }
}

@ConfigurationProperties("gromozeka.runtime.worker")
data class ConversationRuntimeWorkerProperties(
    val id: String = "",
    val capabilities: Set<ConversationRuntimeWorkerCapability> = emptySet(),
    val workspaces: List<FilesystemWorkspace> = emptyList(),
) {
    data class FilesystemWorkspace(
        val id: String,
        val projectId: String,
        val projectName: String,
        val name: String,
        val rootPath: String,
    )
}
