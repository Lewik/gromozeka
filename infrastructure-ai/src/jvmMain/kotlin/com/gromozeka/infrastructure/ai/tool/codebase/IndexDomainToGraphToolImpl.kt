package com.gromozeka.infrastructure.ai.tool.codebase

import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphRequest
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphTool
import org.springframework.stereotype.Service

@Service
class IndexDomainToGraphToolImpl : IndexDomainToGraphTool {
    override fun execute(
        request: IndexDomainToGraphRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> = mapOf(
        "success" to false,
        "disabled" to true,
        "project_id" to request.project_id,
        "project_path" to request.project_path,
        "message" to "Code graph indexing is currently disabled.",
    )
}
