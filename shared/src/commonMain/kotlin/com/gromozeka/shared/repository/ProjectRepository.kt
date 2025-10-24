package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.project.Project

interface ProjectRepository {
    suspend fun save(project: Project): Project
    suspend fun findById(id: Project.Id): Project?
    suspend fun findByPath(path: String): Project?
    suspend fun findAll(): List<Project>
    suspend fun findRecent(limit: Int): List<Project>
    suspend fun findFavorites(): List<Project>
    suspend fun search(query: String): List<Project>
    suspend fun delete(id: Project.Id)
    suspend fun exists(id: Project.Id): Boolean
    suspend fun count(): Int
}
