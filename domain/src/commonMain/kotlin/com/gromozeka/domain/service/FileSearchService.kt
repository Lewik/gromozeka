package com.gromozeka.domain.service

/**
 * [SPECIFICATION] Finds alternative files when an exact filesystem tool path is missing.
 *
 * Matching is case-insensitive and filename-based. Results are relative to the selected
 * filesystem workspace and ordered by exact, prefix, suffix, then contains matches.
 * Implementations must avoid symlink traversal and common generated directories.
 */
interface FileSearchService {
    fun findSimilarFiles(
        targetPath: String,
        workspaceRootPath: String,
        limit: Int = 5,
    ): List<String>
}
