package com.gromozeka.infrastructure.ai.treesitter

import com.gromozeka.domain.model.treesitter.ProjectInfo
import com.gromozeka.domain.service.treesitter.ProjectRegistry
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of ProjectRegistry for Tree-sitter
 */
@Service
class ProjectRegistryImpl : ProjectRegistry {
    
    private val projects = ConcurrentHashMap<String, ProjectInfo>()
    
    override fun registerProject(name: String, path: String, description: String?): ProjectInfo {
        val projectPath = Paths.get(path).toAbsolutePath().normalize()
        
        // Validate path exists
        if (!Files.exists(projectPath)) {
            throw IllegalArgumentException("Project path does not exist: $path")
        }
        
        if (!Files.isDirectory(projectPath)) {
            throw IllegalArgumentException("Project path is not a directory: $path")
        }
        
        // Create project info
        val projectInfo = ProjectInfo(
            name = name,
            rootPath = projectPath.toString(),
            description = description,
            lastScanTime = LocalDateTime.now().toString()
        )
        
        // Store in registry
        projects[name] = projectInfo
        
        println("[ProjectRegistry] Registered project '$name' at path: $projectPath")
        return projectInfo
    }
    
    override fun getProject(name: String): ProjectInfo {
        return projects[name] ?: throw IllegalArgumentException("Project '$name' not found")
    }
    
    override fun listProjects(): List<ProjectInfo> {
        return projects.values.toList()
    }
    
    override fun removeProject(name: String) {
        projects.remove(name) ?: throw IllegalArgumentException("Project '$name' not found")
        println("[ProjectRegistry] Removed project '$name'")
    }
    
    override fun hasProject(name: String): Boolean {
        return projects.containsKey(name)
    }
    
    override fun updateProjectLanguages(name: String, languages: Set<String>) {
        val project = getProject(name)
        val updated = project.copy(languages = languages)
        projects[name] = updated
    }
    
    /**
     * Get project root path as Path object (internal use)
     */
    internal fun getProjectPath(name: String): Path {
        val project = getProject(name)
        return Paths.get(project.rootPath)
    }
    
    /**
     * Get file path within project (internal use)
     */
    internal fun getFilePath(projectName: String, relativePath: String): Path {
        val projectPath = getProjectPath(projectName)
        val filePath = projectPath.resolve(relativePath).normalize()
        
        // Security: ensure file is within project directory
        if (!filePath.startsWith(projectPath)) {
            throw IllegalArgumentException("File path is outside project directory: $relativePath")
        }
        
        return filePath
    }
}
