package com.gromozeka.domain.service.treesitter

import com.gromozeka.domain.model.treesitter.AstNode
import com.gromozeka.domain.model.treesitter.FileAst

/**
 * Service for Tree-sitter AST operations
 */
interface TreeSitterService {
    
    /**
     * Get AST for a file
     * 
     * @param projectName Registered project name
     * @param filePath File path relative to project root
     * @param maxDepth Maximum AST depth to traverse (default: 5)
     * @param includeText Whether to include source text for each node (default: true)
     * @return File AST
     * @throws IllegalArgumentException if project or file not found
     */
    suspend fun getAst(
        projectName: String,
        filePath: String,
        maxDepth: Int = 5,
        includeText: Boolean = true
    ): FileAst
}
