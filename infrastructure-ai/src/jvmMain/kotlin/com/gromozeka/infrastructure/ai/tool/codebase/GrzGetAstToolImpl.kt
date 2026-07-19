package com.gromozeka.infrastructure.ai.tool.codebase

import com.gromozeka.domain.service.treesitter.TreeSitterService
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.codebase.GetAstRequest
import com.gromozeka.domain.tool.codebase.GrzGetAstTool
import com.gromozeka.domain.tool.requiredWorkspaceRootPath
import org.springframework.stereotype.Service

@Service
class GrzGetAstToolImpl(
    private val treeSitterService: TreeSitterService,
) : GrzGetAstTool {
    override fun execute(request: GetAstRequest, context: ToolExecutionContext?): Map<String, Any> =
        runCatching {
            val ast = treeSitterService.getAst(
                workspaceRootPath = context.requiredWorkspaceRootPath(),
                filePath = request.file_path,
                maxDepth = request.max_depth,
                includeText = request.include_text,
            )
            mapOf(
                "file" to ast.file,
                "language" to ast.language,
                "tree" to ast.tree,
            )
        }.getOrElse { error ->
            mapOf("error" to (error.message ?: "Failed to parse file"))
        }
}
