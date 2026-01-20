package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.repository.ProjectConfigRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.springframework.stereotype.Service
import java.io.File

/**
 * File system implementation of ProjectConfigRepository.
 * 
 * Reads/writes .gromozeka/project.json in project directory.
 * Creates file with defaults if doesn't exist.
 */
@Service
class FileSystemProjectConfigRepository(
    private val json: Json
) : ProjectConfigRepository {

    @Serializable
    private data class ProjectConfigDTO(
        val name: String,
        val description: String? = null,
        val domain_patterns: List<String> = listOf("domain/src/**/*.kt")
    )

    override suspend fun getDomainPatterns(projectPath: String): List<String> {
        val config = readOrCreateConfig(projectPath)
        return config.domain_patterns
    }

    override suspend fun getProjectName(projectPath: String): String {
        val config = readOrCreateConfig(projectPath)
        return config.name
    }

    override suspend fun getProjectDescription(projectPath: String): String? {
        val config = readOrCreateConfig(projectPath)
        return config.description
    }

    private fun readOrCreateConfig(projectPath: String): ProjectConfigDTO {
        val configFile = File(projectPath, ".gromozeka/project.json")
        
        if (!configFile.exists()) {
            return createDefaultConfig(projectPath, configFile)
        }
        
        return try {
            json.decodeFromString<ProjectConfigDTO>(configFile.readText())
        } catch (e: Exception) {
            // If file is corrupted, recreate with defaults
            createDefaultConfig(projectPath, configFile)
        }
    }

    private fun createDefaultConfig(projectPath: String, configFile: File): ProjectConfigDTO {
        // Create .gromozeka directory if doesn't exist
        configFile.parentFile?.mkdirs()
        
        // Default name from last path segment
        val defaultName = File(projectPath).name
        
        val defaultConfig = ProjectConfigDTO(
            name = defaultName,
            description = null,
            domain_patterns = listOf("domain/src/**/*.kt")
        )
        
        // Write to file
        configFile.writeText(json.encodeToString(defaultConfig))
        
        return defaultConfig
    }
}
