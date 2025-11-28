package com.gromozeka.domain.service

/**
 * [SPECIFICATION] File discovery and search operations
 * 
 * Provides file lookup capabilities when exact path resolution fails.
 * Used by filesystem tools (grz_read_file, grz_write_file, grz_edit_file) 
 * to suggest alternative paths when requested file doesn't exist.
 * 
 * ## Purpose
 * 
 * When LLM requests file that doesn't exist at specified path, this service
 * helps find similar files by name (case-insensitive) across project tree.
 * Improves UX by suggesting "Did you mean: ..." alternatives.
 * 
 * ## Use Cases
 * 
 * 1. **Typo correction:** User requests "Main.kt" → suggests "main.kt"
 * 2. **Path confusion:** User requests "src/App.kt" → suggests "app/src/App.kt"
 * 3. **Case sensitivity:** User requests "README.md" → suggests "readme.md"
 * 4. **Moved files:** User requests old path → suggests current location
 * 
 * ## Search Algorithm (Infrastructure Implements)
 * 
 * Infrastructure should implement case-insensitive filename matching:
 * 1. Extract filename from target path (ignore directory components)
 * 2. Walk project tree recursively
 * 3. Match filenames case-insensitively
 * 4. Rank results by relevance (exact > prefix > contains)
 * 5. Return top N relative paths
 * 
 * **Suggested ranking strategy:**
 * - Priority 1: Exact match, different path ("src/Main.kt" vs "app/Main.kt")
 * - Priority 2: Case-only difference ("Main.kt" vs "main.kt")
 * - Priority 3: Prefix match ("Main" matches "MainTest.kt")
 * - Priority 4: Contains match ("Main" matches "MyMainFile.kt")
 * 
 * ## Implementation Requirements
 * 
 * Infrastructure implementation should:
 * - ✅ Respect `.gitignore` patterns (don't suggest build artifacts)
 * - ✅ Limit search depth (prevent traversing `node_modules`, `.git`, etc.)
 * - ✅ Handle symlinks safely (avoid infinite loops)
 * - ✅ Complete within 1-2 seconds for typical projects
 * - ⚡ Optional: Cache results for large projects (10k+ files)
 * 
 * ## Performance Characteristics
 * 
 * - **Small projects (<1k files):** Instant (<100ms)
 * - **Medium projects (1k-10k files):** Fast (<500ms)
 * - **Large projects (10k+ files):** May take 1-2 seconds
 * - **Huge monorepos (100k+ files):** Consider caching/indexing
 * 
 * @see com.gromozeka.domain.tool.filesystem.GrzReadFileTool Uses this for file-not-found suggestions
 * @see com.gromozeka.domain.tool.filesystem.GrzWriteFileTool May use for parent directory validation
 * @see com.gromozeka.domain.tool.filesystem.GrzEditFileTool Uses this for file-not-found suggestions
 */
interface FileSearchService {
    
    /**
     * Find files with names similar to target filename.
     * 
     * Extracts filename from `targetPath`, searches project tree case-insensitively,
     * returns relative paths ordered by relevance.
     * 
     * **Search behavior:**
     * - Recursive search from `projectPath` root
     * - Filename-only matching (directory segments ignored for matching)
     * - Case-insensitive comparison
     * - Results ordered by similarity/relevance
     * 
     * **Empty result cases:**
     * - No files with similar names exist in project
     * - All matches are excluded by .gitignore
     * - Search depth limit reached before finding matches
     * 
     * **Performance:**
     * - Should complete within 1-2 seconds for typical projects
     * - May be slow for huge monorepos (100k+ files)
     * - Infrastructure may implement caching/indexing for optimization
     * 
     * @param targetPath Original path that failed (e.g., "src/Missing.kt").
     *                   Filename is extracted from this path for searching.
     * @param projectPath Root directory to search within (absolute path).
     *                    Must be existing directory.
     * @param limit Maximum number of results to return (default: 5).
     *              Use higher values for broader suggestions, lower for precision.
     * @return List of relative paths (from projectPath) to similar files,
     *         ordered by relevance (most similar first).
     *         Empty list if no matches found.
     * 
     * @throws IllegalArgumentException if projectPath doesn't exist or isn't directory
     * 
     * ## Example 1: Exact match in different location
     * 
     * ```kotlin
     * val suggestions = fileSearchService.findSimilarFiles(
     *     targetPath = "src/Missing.kt",
     *     projectPath = "/Users/dev/myproject",
     *     limit = 5
     * )
     * 
     * // Returns:
     * // [
     * //   "src/main/kotlin/Missing.kt",     // Exact name, different path
     * //   "src/test/kotlin/Missing.kt"      // Exact name, test directory
     * // ]
     * ```
     * 
     * ## Example 2: Case sensitivity issue
     * 
     * ```kotlin
     * val suggestions = fileSearchService.findSimilarFiles(
     *     targetPath = "README.MD",  // Wrong case
     *     projectPath = "/Users/dev/myproject",
     *     limit = 5
     * )
     * 
     * // Returns:
     * // [
     * //   "README.md",              // Correct case
     * //   "docs/README.md"          // Additional matches
     * // ]
     * ```
     * 
     * ## Example 3: Partial match
     * 
     * ```kotlin
     * val suggestions = fileSearchService.findSimilarFiles(
     *     targetPath = "Service.kt",
     *     projectPath = "/Users/dev/myproject",
     *     limit = 3
     * )
     * 
     * // Returns:
     * // [
     * //   "UserService.kt",         // Contains "Service"
     * //   "ProductService.kt",      // Contains "Service"
     * //   "ServiceImpl.kt"          // Starts with "Service"
     * // ]
     * ```
     * 
     * ## Example 4: No matches found
     * 
     * ```kotlin
     * val suggestions = fileSearchService.findSimilarFiles(
     *     targetPath = "NonExistent.xyz",
     *     projectPath = "/Users/dev/myproject",
     *     limit = 5
     * )
     * 
     * // Returns: []  (empty list)
     * ```
     * 
     * ## Usage in Tools (Integration Pattern)
     * 
     * **GrzReadFileToolImpl example:**
     * 
     * ```kotlin
     * @Service
     * class GrzReadFileToolImpl(
     *     private val fileSearchService: FileSearchService
     * ) : GrzReadFileTool {
     *     
     *     override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
     *         val projectPath = context?.getContext()?.get("projectPath") as String
     *         val file = resolveFile(request.file_path, projectPath)
     *         
     *         if (!file.exists()) {
     *             val suggestions = fileSearchService.findSimilarFiles(
     *                 targetPath = request.file_path,
     *                 projectPath = projectPath,
     *                 limit = 5
     *             )
     *             
     *             return if (suggestions.isNotEmpty()) {
     *                 mapOf(
     *                     "error" to "File not found: ${request.file_path}",
     *                     "suggestions" to suggestions
     *                 )
     *             } else {
     *                 mapOf("error" to "File not found: ${request.file_path}")
     *             }
     *         }
     *         
     *         // ... read file normally ...
     *     }
     * }
     * ```
     * 
     * ## Related Services
     * 
     * This service is complementary to filesystem operations but doesn't replace them:
     * - Use this ONLY when file operation fails (file not found)
     * - Don't use for general file listing (use grz_execute_command with ls/find)
     * - Don't use for content search (use grz_execute_command with rg/grep)
     */
    fun findSimilarFiles(
        targetPath: String,
        projectPath: String,
        limit: Int = 5
    ): List<String>
}
