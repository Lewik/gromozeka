package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.infrastructure.ai.memory.KnowledgeGraphServiceFacade
import com.gromozeka.domain.tool.memory.BuildMemoryFromTextRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of BuildMemoryFromTextTool.
 * 
 * Delegates to KnowledgeGraphServiceFacade for LLM-based entity extraction.
 * 
 * @see com.gromozeka.domain.tool.memory.BuildMemoryFromTextTool Domain specification
 */
@Service
class BuildMemoryFromTextTool(
    private val knowledgeGraphServiceFacade: KnowledgeGraphServiceFacade
) : com.gromozeka.domain.tool.memory.BuildMemoryFromTextTool {
    
    private val logger = LoggerFactory.getLogger(BuildMemoryFromTextTool::class.java)
    
    override fun execute(request: BuildMemoryFromTextRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val result = runBlocking {
                knowledgeGraphServiceFacade.extractAndSaveToGraph(
                    content = request.content,
                    previousMessages = request.previousMessages ?: ""
                )
            }

            logger.debug("Added to graph: $result")

            mapOf(
                "type" to "text",
                "text" to result
            )
        } catch (e: Exception) {
            logger.error("Error adding to graph: ${e.message}", e)
            mapOf(
                "type" to "text",
                "text" to "Error adding to knowledge graph: ${e.message}"
            )
        }
    }
}
