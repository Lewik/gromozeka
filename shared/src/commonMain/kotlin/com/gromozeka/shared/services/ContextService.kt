package com.gromozeka.shared.services

import com.gromozeka.shared.domain.Context
import com.gromozeka.shared.domain.toContextId
import com.gromozeka.shared.repository.ContextRepository
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Instant

class ContextService(
    private val contextRepository: ContextRepository,
    private val projectService: ProjectService,
) {
    private val log = KLoggers.logger(this)

    suspend fun createContext(
        projectPath: String,
        name: String,
        content: String,
        files: List<Context.File> = emptyList(),
        links: List<String> = emptyList(),
        tags: Set<String> = emptySet()
    ): Context {
        val project = projectService.getOrCreate(projectPath)
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val context = Context(
            id = uuid7().toContextId(),
            projectId = project.id,
            name = name,
            content = content,
            files = files,
            links = links,
            tags = tags,
            extractedAt = now,
            createdAt = now,
            updatedAt = now
        )

        return contextRepository.save(context)
    }

    suspend fun findById(id: Context.Id): Context? =
        contextRepository.findById(id)

    suspend fun findByProject(projectPath: String): List<Context> {
        val project = projectService.findByPath(projectPath) ?: return emptyList()
        return contextRepository.findByProject(project.id)
    }

    suspend fun findByName(projectPath: String, name: String): Context? {
        val project = projectService.findByPath(projectPath) ?: return null
        return contextRepository.findByProject(project.id)
            .find { it.name == name }
    }

    suspend fun findByTags(tags: Set<String>): List<Context> {
        return contextRepository.findByTags(tags)
    }

    suspend fun findRecent(limit: Int = 10): List<Context> {
        return contextRepository.findRecent(limit)
    }

    suspend fun updateContent(
        id: Context.Id,
        content: String? = null,
        files: List<Context.File>? = null,
        links: List<String>? = null,
        tags: Set<String>? = null
    ): Context? {
        val context = contextRepository.findById(id) ?: return null
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val updated = context.copy(
            content = content ?: context.content,
            files = files ?: context.files,
            links = links ?: context.links,
            tags = tags ?: context.tags,
            updatedAt = now
        )

        return contextRepository.save(updated)
    }

    suspend fun delete(id: Context.Id) {
        contextRepository.delete(id)
    }

    suspend fun count(projectPath: String): Int {
        val project = projectService.findByPath(projectPath) ?: return 0
        return contextRepository.countByProject(project.id)
    }
}
