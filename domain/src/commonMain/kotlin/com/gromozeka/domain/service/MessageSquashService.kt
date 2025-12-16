package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType

/**
 * [SPECIFICATION] Message squashing operations for conversation history compression.
 *
 * Squashing reduces conversation history while preserving meaning and context.
 * This is a domain operation because it affects conversation semantics and user experience.
 *
 * ## Use Cases
 *
 * **Context window management:**
 * - Long conversations exceed model context limits
 * - Squashing preserves history while fitting in context
 *
 * **Performance optimization:**
 * - Reduce token usage for repeated context
 * - Speed up LLM response time
 *
 * **User experience:**
 * - Clean up verbose exchanges
 * - Focus on essential information
 *
 * ## Squashing Strategies
 *
 * **CONCATENATE:**
 * - Simple text merge without AI
 * - Fast, deterministic
 * - No semantic analysis
 * - Use case: Merging short related messages
 *
 * **SUMMARIZE:**
 * - AI-generated summary of selected messages
 * - Preserves key information
 * - Loses exact wording
 * - Use case: Long discussions, meeting notes
 *
 * **COMPRESS:**
 * - AI removes redundant information
 * - Preserves important details
 * - More conservative than SUMMARIZE
 * - Use case: Technical discussions with code snippets
 *
 * ## Implementation Requirements
 *
 * Business Logic Agent must:
 * 1. Validate message selection (sequential, same conversation)
 * 2. Choose appropriate AI model based on strategy
 * 3. Generate squashed content
 * 4. Preserve metadata (timestamps, role)
 * 5. Record squash operation for audit trail
 *
 * ## Side Effects
 *
 * - Creates SquashOperation record in database
 * - Original messages remain in database (soft delete pattern)
 * - Squashed message gets new ID
 *
 * @see SquashType for strategy definitions
 * @see Conversation.Message for message structure
 * @see SquashOperationRepository for audit trail
 */
interface MessageSquashService {

    /**
     * Squash multiple messages into single message.
     *
     * Takes selected messages and produces single message with compressed content.
     * Original messages remain in database for audit purposes.
     *
     * This is a TRANSACTIONAL operation:
     * - Creates squashed message
     * - Records squash operation
     * - Updates conversation state
     *
     * **Validation:**
     * - All messageIds must exist in conversation
     * - Messages must be sequential (no gaps)
     * - At least 2 messages required
     * - Cannot squash system messages
     *
     * **Strategy selection:**
     * - CONCATENATE: No AI, simple text merge
     * - SUMMARIZE: AI generates summary (uses project path for context)
     * - COMPRESS: AI removes redundancy (preserves technical details)
     *
     * **AI model selection:**
     * - Determined by conversation's agentDefinitionId
     * - Uses same provider/model as conversation
     * - Falls back to default if agent not found
     *
     * @param conversationId target conversation
     * @param messageIds messages to squash (must be sequential)
     * @param strategy squashing strategy (CONCATENATE, SUMMARIZE, COMPRESS)
     * @param projectPath optional project path for context (required for AI strategies)
     * @return result with squashed message or error
     * @throws IllegalArgumentException if messageIds is empty or has single element
     * @throws IllegalStateException if conversation or messages not found
     */
    suspend fun squash(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
        projectPath: String? = null
    ): SquashResult

    /**
     * Result of squash operation.
     *
     * Sealed interface for type-safe result handling.
     */
    sealed interface SquashResult {
        /**
         * Squashing succeeded.
         *
         * @property squashedContent generated content for squashed message
         * @property originalMessageCount number of messages that were squashed
         * @property strategy strategy used for squashing
         * @property tokensSaved estimated tokens saved (null for CONCATENATE)
         */
        data class Success(
            val squashedContent: String,
            val originalMessageCount: Int,
            val strategy: SquashType,
            val tokensSaved: Int? = null
        ) : SquashResult

        /**
         * Squashing failed.
         *
         * @property reason human-readable error message
         * @property errorType type of error for programmatic handling
         */
        data class Failure(
            val reason: String,
            val errorType: ErrorType
        ) : SquashResult {
            enum class ErrorType {
                /** Messages not found or don't belong to conversation */
                MESSAGES_NOT_FOUND,
                
                /** Messages are not sequential (have gaps) */
                NON_SEQUENTIAL,
                
                /** Less than 2 messages selected */
                INSUFFICIENT_MESSAGES,
                
                /** System messages cannot be squashed */
                SYSTEM_MESSAGES,
                
                /** AI model failed to generate squashed content */
                AI_GENERATION_FAILED,
                
                /** Project path required for AI strategies but not provided */
                MISSING_PROJECT_PATH
            }
        }
    }
}
