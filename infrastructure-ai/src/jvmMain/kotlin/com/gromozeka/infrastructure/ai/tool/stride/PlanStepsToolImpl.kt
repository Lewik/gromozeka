package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.domain.service.StepDefinition
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.domain.tool.stride.*
import com.gromozeka.domain.model.Step
import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of PlanStepsTool.
 * 
 * This tool uses LLM for semantic decomposition of user message into steps.
 * After decomposition, it creates Plan via StrideEngineService.createPlan().
 * 
 * Uses ChatModelProvider to get appropriate ChatModel for decomposition
 * based on agent configuration from ToolContext.
 * 
 * @see com.gromozeka.domain.tool.stride.PlanStepsTool Domain specification
 */
@Service
class PlanStepsToolImpl(
    private val chatModelProvider: ChatModelProvider,
    private val strideEngineService: StrideEngineService
) : PlanStepsTool {
    
    private val logger = LoggerFactory.getLogger(PlanStepsToolImpl::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    override fun execute(request: PlanStepsRequest, context: ToolContext?): PlanStepsResponse {
        return try {
            // Get required context parameters
            val contextMap = context?.getContext() ?: throw IllegalArgumentException("ToolContext is required")
            
            val conversationId = contextMap["conversationId"] as? String
                ?: throw IllegalArgumentException("conversationId not found in ToolContext")
            
            val aiProviderStr = contextMap["aiProvider"] as? String
                ?: throw IllegalArgumentException("aiProvider not found in ToolContext")
            val aiProvider = AIProvider.valueOf(aiProviderStr)
            
            val modelName = contextMap["modelName"] as? String
                ?: throw IllegalArgumentException("modelName not found in ToolContext")
            
            val projectPath = contextMap["projectPath"] as? String
            
            logger.debug("Decomposing message into steps: ${request.message.take(100)}...")
            logger.debug("Using AI provider: $aiProvider, model: $modelName")
            
            // Get ChatModel for this agent configuration
            val chatModel = chatModelProvider.getChatModel(aiProvider, modelName, projectPath)
            
            // Call LLM for semantic decomposition
            val stepsJson = decomposeMessageWithLLM(chatModel, request.message, request.threadContext)
            
            logger.debug("LLM decomposition result: $stepsJson")
            
            // Parse JSON response
            val stepDefinitions = json.decodeFromString<List<StepDefinitionJson>>(stepsJson)
            
            // Convert to domain StepDefinition
            val domainSteps = stepDefinitions.map { stepJson ->
                StepDefinition(
                    text = stepJson.text,
                    type = Step.Type.valueOf(stepJson.type.uppercase()),
                    certainty = stepJson.certainty,
                    entities = stepJson.entities,
                    dependsOn = stepJson.dependsOn,
                    meta = convertJsonElementMapToAny(stepJson.meta)
                )
            }
            
            // Create plan via StrideEngineService
            runBlocking {
                strideEngineService.createPlan(
                    conversationId = Conversation.Id(conversationId),
                    stepDefinitions = domainSteps
                )
            }
            
            logger.info("Created plan with ${stepDefinitions.size} steps")
            
            PlanStepsResponse(steps = stepDefinitions)
            
        } catch (e: Exception) {
            logger.error("Error in plan_steps tool: ${e.message}", e)
            // Return error as valid response with empty steps
            PlanStepsResponse(
                steps = listOf(
                    StepDefinitionJson(
                        text = "Error decomposing message: ${e.message}",
                        type = "INFORM",
                        certainty = 1.0f,
                        entities = emptyList(),
                        dependsOn = emptyList(),
                        meta = mapOf("error" to kotlinx.serialization.json.JsonPrimitive("true"))
                    )
                )
            )
        }
    }
    
    /**
     * Uses LLM to decompose user message into semantic steps.
     * 
     * @param chatModel ChatModel instance to use for decomposition
     * @param message User message to decompose
     * @param threadContext Optional conversation context
     * @return JSON array of step definitions
     */
    private fun decomposeMessageWithLLM(
        chatModel: org.springframework.ai.chat.model.ChatModel,
        message: String,
        threadContext: String?
    ): String {
        val systemPrompt = buildDecompositionPrompt()
        val userPrompt = buildUserPrompt(message, threadContext)
        
        val prompt = Prompt(
            """
            $systemPrompt
            
            $userPrompt
            """.trimIndent()
        )
        
        val response = chatModel.call(prompt)
        val responseText = response.result?.output?.text ?: ""
        
        // Extract JSON from response (handle cases where LLM adds explanation text)
        return extractJson(responseText)
    }
    
    /**
     * Builds system prompt for step decomposition.
     */
    private fun buildDecompositionPrompt(): String {
        return """
        You are a semantic message decomposition expert. Your task is to split user messages into minimal semantic units (Discourse Units) for execution.
        
        # Step Types
        
        - **COMMAND** - direct action instruction (e.g., "find all TODO", "run tests", "create file")
        - **QUERY** - request for information/analysis (e.g., "what's better?", "should we use X?", "explain why")
        - **INFORM** - statement of fact/context (e.g., "we use PostgreSQL", "bug was in listener")
        - **COMMIT** - speaker's promise/obligation (e.g., "I'll fix this tomorrow", "will write tests by Friday")
        - **CORRECT** - correction of known fact (e.g., "no, it's MySQL not PostgreSQL", "not a bug, it's a feature")
        - **CONDITION** - constraint for other steps (e.g., "if project is Kotlin", "only for Linux")
        - **EVALUATE** - opinion/assessment (e.g., "this approach is better", "Swing is terrible")
        
        # Certainty Levels
        
        - **1.0** - definite fact ("we use PostgreSQL")
        - **0.8** - high confidence ("probably the issue")
        - **0.5** - guess ("maybe we should try X")
        - **0.2** - doubt ("not sure if this helps")
        - **0.0** - speculation ("could it be Y?")
        
        # Dependency Rules
        
        Add dependency (dependsOn) when:
        - Step uses result from previous step
        - Step references fact from previous step
        - Anaphora resolution (pronoun refers to earlier entity)
        - Temporal sequence ("after that", "then")
        
        # Response Format
        
        Return ONLY a JSON array of step definitions. No explanatory text.
        
        Example:
        ```json
        [
          {
            "text": "Find all TODO in project gromozeka",
            "type": "COMMAND",
            "certainty": 1.0,
            "entities": ["TODO", "gromozeka"],
            "dependsOn": [],
            "meta": {}
          },
          {
            "text": "Group them by priority",
            "type": "COMMAND",
            "certainty": 1.0,
            "entities": ["priority"],
            "dependsOn": [0],
            "meta": {}
          }
        ]
        ```
        """.trimIndent()
    }
    
    /**
     * Builds user prompt with message and optional context.
     */
    private fun buildUserPrompt(message: String, threadContext: String?): String {
        val contextSection = if (threadContext != null) {
            """
            
            # Thread Context (for anaphora resolution)
            
            $threadContext
            """.trimIndent()
        } else ""
        
        return """
        # User Message
        
        $message
        $contextSection
        
        Decompose this message into semantic steps. Return JSON array only.
        """.trimIndent()
    }
    
    /**
     * Extracts JSON array from LLM response.
     * 
     * Handles cases where LLM wraps JSON in markdown code blocks or adds explanation text.
     */
    private fun extractJson(content: String): String {
        // Try to find JSON array in content
        val jsonStart = content.indexOf('[')
        val jsonEnd = content.lastIndexOf(']')
        
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            return content.substring(jsonStart, jsonEnd + 1)
        }
        
        // If no JSON found, return empty array
        logger.warn("Could not extract JSON from LLM response: $content")
        return "[]"
    }
    
    /**
     * Converts Map<String, JsonElement> to Map<String, Any> for domain layer.
     */
    private fun convertJsonElementMapToAny(meta: Map<String, kotlinx.serialization.json.JsonElement>): Map<String, Any> {
        return meta.mapValues { (_, value) ->
            when (value) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    when {
                        value.isString -> value.content
                        value.content == "true" -> true
                        value.content == "false" -> false
                        value.content.toIntOrNull() != null -> value.content.toInt()
                        value.content.toDoubleOrNull() != null -> value.content.toDouble()
                        else -> value.content
                    }
                }
                is kotlinx.serialization.json.JsonNull -> null as Any
                is kotlinx.serialization.json.JsonObject -> value.toString() // Convert complex objects to string
                is kotlinx.serialization.json.JsonArray -> value.toString()
            }
        }
    }
}
