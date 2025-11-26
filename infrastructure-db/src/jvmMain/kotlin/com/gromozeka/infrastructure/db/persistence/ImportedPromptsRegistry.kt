package com.gromozeka.infrastructure.db.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.io.File

/**
 * Registry of imported CLAUDE.md files from disk.
 * Stores absolute paths to files discovered via "Find all CLAUDE.md" button.
 */
@Component
class ImportedPromptsRegistry : com.gromozeka.domain.service.ImportedPromptsRegistry {
    
    private val json = Json { prettyPrint = true }
    
    @Serializable
    data class Registry(
        val importedPaths: List<String> = emptyList()
    )
    
    private fun getRegistryFile(): File {
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME") 
            ?: "${System.getProperty("user.home")}/.gromozeka"
        return File(gromozekaHome, "imported-prompts-registry.json")
    }
    
    override suspend fun load(): List<String> {
        val file = getRegistryFile()
        if (!file.exists()) return emptyList()
        
        return try {
            val registry = json.decodeFromString<Registry>(file.readText())
            registry.importedPaths
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun save(paths: List<String>) {
        val file = getRegistryFile()
        file.parentFile.mkdirs()
        
        val registry = Registry(importedPaths = paths.distinct())
        file.writeText(json.encodeToString(registry))
    }
    
    override suspend fun add(paths: List<String>) {
        val existing = load()
        val updated = (existing + paths).distinct()
        save(updated)
    }
    
    override suspend fun clear() {
        save(emptyList())
    }
}
