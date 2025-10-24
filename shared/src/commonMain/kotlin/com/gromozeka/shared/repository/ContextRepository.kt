package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.context.Context
import com.gromozeka.shared.domain.project.Project

interface ContextRepository {
    suspend fun save(context: Context): Context
    suspend fun findById(id: Context.Id): Context?
    suspend fun delete(id: Context.Id)

    // Query by relationships
    suspend fun findByProject(projectId: Project.Id): List<Context>

    // Query by attributes
    suspend fun findByTags(tags: Set<String>): List<Context>
    suspend fun findRecent(limit: Int): List<Context>

    // Search
    suspend fun search(query: String): List<Context>

    // Statistics
    suspend fun count(): Int
    suspend fun countByProject(projectId: Project.Id): Int
}
