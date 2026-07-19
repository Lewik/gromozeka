package com.gromozeka.domain.tool.codebase

import com.gromozeka.domain.tool.LocalAgentToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext

data class GetAstRequest(
    val file_path: String,
    val max_depth: Int = 5,
    val include_text: Boolean = true,
) {
    init {
        require(file_path.isNotBlank()) { "file_path must not be blank" }
        require(max_depth in 0..50) { "max_depth must be between 0 and 50" }
    }
}

interface GrzGetAstTool : Tool<GetAstRequest, Map<String, Any>> {
    override val name: String
        get() = "grz_get_ast"

    override val metadata
        get() = LocalAgentToolMetadata

    override val description: String
        get() = """
            Parse a Kotlin source file in the selected filesystem workspace and return its Tree-sitter AST.
            file_path must be relative to the workspace root. Use max_depth to limit the returned tree and
            include_text=false when source snippets are unnecessary.
        """.trimIndent()

    override val requestType: Class<GetAstRequest>
        get() = GetAstRequest::class.java

    override fun execute(request: GetAstRequest, context: ToolExecutionContext?): Map<String, Any>
}
