package com.gromozeka.domain.service.treesitter

import com.gromozeka.domain.model.treesitter.ProjectInfo

/**
 * Registry for managing registered projects for Tree-sitter analysis
 */
interface ProjectRegistry {
    
    /**
     * Register a new project for analysis
     * 
     * @param name Unique project name
     * @param path Absolute path to project root directory
     * @param description Optional project description
     * @return Registered project info
     * @throws IllegalArgumentException if path doesn't exist or not a directory
     */
    fun registerProject(name: String, path: String, description: String? = null): ProjectInfo
    
    /**
     * Get project by name
     * 
     * @param name Project name
     * @return Project info
     * @throws IllegalArgumentException if project not found
     */
    fun getProject(name: String): ProjectInfo
    
    /**
     * List all registered projects
     * 
     * @return List of all registered projects
     */
    fun listProjects(): List<ProjectInfo>
    
    /**
     * Remove project from registry
     * 
     * @param name Project name
     * @throws IllegalArgumentException if project not found
     */
    fun removeProject(name: String)
    
    /**
     * Check if project exists
     * 
     * @param name Project name
     * @return true if project is registered
     */
    fun hasProject(name: String): Boolean
    
    /**
     * Update project languages after scanning
     * 
     * @param name Project name
     * @param languages Detected languages
     */
    fun updateProjectLanguages(name: String, languages: Set<String>)
}
