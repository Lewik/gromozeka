package com.gromozeka.application.service

import com.gromozeka.domain.model.Context
import com.gromozeka.domain.model.toContextId
import com.gromozeka.domain.repository.ContextRepository
import com.gromozeka.domain.repository.ContextDomainService
import com.gromozeka.domain.repository.ProjectDomainService
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service for extracted context management.
 *
 * Handles context lifecycle including creation, updates, search, and deletion.
 * Coordinates with ProjectService to ensure project existence.
 *
 * Contexts are distilled knowledge extracted from conversations, containing
 * key information, file references, and searchable tags for efficient reuse.
 */
@Service
class ContextApplicationService(
    private val contextRepository: ContextRepository,
    private val projectService: ProjectDomainService,
) : ContextDomainService {
    private val log = KLoggers.logger(this)

    /**
     * Creates new context for project.
     *
     * Ensures project exists (creates if needed), then creates context
     * with specified content and metadata.
     *
     * @param projectPath absolute file system path to project
     * @param name brief topic name (e.g., "API Design", "Database Schema")
     * @param content key information and decisions (markdown-formatted)
     * @param files file references with read specifications (default: empty)
     * @param links external resources and documentation URLs (default: empty)
     * @param tags searchable keywords for categorization (default: empty)
     * @return created context
     */
    @Transactional
    override suspend fun createContext(
        projectPath: String,
        name: String,
        content: String,
        files: List<Context.File>,
        links: List<String>,
        tags: Set<String>
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

    /**
     * Finds context by unique identifier.
     *
     * @param id context identifier
     * @return context if found, null otherwise
     */
    override suspend fun findById(id: Context.Id): Context? =
        contextRepository.findById(id)

    /**
     * Finds all contexts in project.
     *
     * @param projectPath absolute file system path to project
     * @return list of contexts (empty if project doesn't exist or has no contexts)
     */
    override suspend fun findByProject(projectPath: String): List<Context> {
        val project = projectService.findByPath(projectPath) ?: return emptyList()
        return contextRepository.findByProject(project.id)
    }

    /**
     * Finds context by exact name within project.
     *
     * Searches for exact name match (case-sensitive).
     *
     * @param projectPath absolute file system path to project
     * @param name context name to search for
     * @return context if found, null if project or context doesn't exist
     */
    override suspend fun findByName(projectPath: String, name: String): Context? {
        val project = projectService.findByPath(projectPath) ?: return null
        return contextRepository.findByProject(project.id)
            .find { it.name == name }
    }

    /**
     * Finds contexts matching all specified tags.
     *
     * Returns contexts having ALL tags in the input set (AND operation).
     *
     * @param tags set of tags to match
     * @return list of matching contexts (empty if no matches)
     */
    override suspend fun findByTags(tags: Set<String>): List<Context> {
        return contextRepository.findByTags(tags)
    }

    /**
     * Finds most recently updated contexts.
     *
     * @param limit maximum number of contexts to return (default: 10)
     * @return recent contexts ordered by update time (newest first)
     */
    override suspend fun findRecent(limit: Int): List<Context> {
        return contextRepository.findRecent(limit)
    }

    /**
     * Updates context content and metadata.
     *
     * Only updates non-null parameters. Updates timestamp on any change.
     *
     * @param id context identifier
     * @param content new content text (null keeps existing)
     * @param files new file references (null keeps existing)
     * @param links new external links (null keeps existing)
     * @param tags new tag set (null keeps existing)
     * @return updated context if exists, null otherwise
     */
    @Transactional
    override suspend fun updateContent(
        id: Context.Id,
        content: String?,
        files: List<Context.File>?,
        links: List<String>?,
        tags: Set<String>?
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

    /**
     * Deletes context.
     *
     * @param id context identifier
     */
    @Transactional
    override suspend fun delete(id: Context.Id) {
        contextRepository.delete(id)
    }

    /**
     * Returns context count for project.
     *
     * @param projectPath absolute file system path to project
     * @return context count (0 if project doesn't exist or has no contexts)
     */
    override suspend fun count(projectPath: String): Int {
        val project = projectService.findByPath(projectPath) ?: return 0
        return contextRepository.countByProject(project.id)
    }
}
