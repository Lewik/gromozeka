package com.gromozeka.infrastructure.ai.service

import com.gromozeka.domain.service.FileSearchService
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Infrastructure implementation of FileSearchService.
 * 
 * Uses Java NIO file walking to find similar files across a workspace tree.
 * 
 * @see com.gromozeka.domain.service.FileSearchService Domain specification
 */
@Service
class FileSearchServiceImpl : FileSearchService {
    
    companion object {
        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".idea", ".gradle", "build", "target",
            "node_modules", ".next", "dist", "out",
            ".kotlin", ".cache", ".vscode", ".settings"
        )
        
        private const val MAX_SEARCH_DEPTH = 15
    }
    
    override fun findSimilarFiles(
        targetPath: String,
        workspaceRootPath: String,
        limit: Int
    ): List<String> {
        val workspaceDirectory = File(workspaceRootPath)
        require(workspaceDirectory.exists() && workspaceDirectory.isDirectory) {
            "Workspace root must be an existing directory: $workspaceRootPath"
        }
        
        val targetFilename = File(targetPath).name
        if (targetFilename.isBlank()) return emptyList()
        
        val matches = mutableListOf<FileMatch>()
        val workspaceRoot = workspaceDirectory.toPath()
        
        try {
            walkDirectory(
                dir = workspaceRoot,
                workspaceRoot = workspaceRoot,
                targetFilename = targetFilename,
                matches = matches,
                depth = 0
            )
        } catch (e: Exception) {
            return emptyList()
        }
        
        return matches
            .sortedBy { it.rank }
            .take(limit)
            .map { it.relativePath }
    }
    
    private fun walkDirectory(
        dir: Path,
        workspaceRoot: Path,
        targetFilename: String,
        matches: MutableList<FileMatch>,
        depth: Int
    ) {
        if (depth > MAX_SEARCH_DEPTH) return
        if (dir.name in IGNORED_DIRECTORIES) return
        
        try {
            Files.list(dir).use { stream ->
                stream.forEach { path ->
                    try {
                        when {
                            Files.isSymbolicLink(path) -> return@forEach
                            
                            Files.isDirectory(path) -> {
                                walkDirectory(path, workspaceRoot, targetFilename, matches, depth + 1)
                            }
                            
                            Files.isRegularFile(path) -> {
                                val filename = path.name
                                val rank = calculateRank(filename, targetFilename)
                                
                                if (rank != null) {
                                    val relativePath = workspaceRoot.relativize(path).toString()
                                    matches.add(FileMatch(relativePath, rank))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip files/directories with access errors
                    }
                }
            }
        } catch (e: Exception) {
            // Skip inaccessible directories
        }
    }
    
    /**
     * Ranking: exact (1) > prefix (2) > suffix (3) > contains (4)
     */
    private fun calculateRank(filename: String, targetFilename: String): Int? {
        val filenameLower = filename.lowercase()
        val targetLower = targetFilename.lowercase()
        
        return when {
            filenameLower == targetLower -> 1
            filenameLower.startsWith(targetLower) -> 2
            filenameLower.endsWith(targetLower) -> 3
            filenameLower.contains(targetLower) -> 4
            else -> null
        }
    }
    
    private data class FileMatch(
        val relativePath: String,
        val rank: Int
    )
}
