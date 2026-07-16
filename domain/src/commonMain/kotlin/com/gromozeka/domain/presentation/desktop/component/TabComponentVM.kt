package com.gromozeka.domain.presentation.desktop.component

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.TokenUsageStatistics
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for a single conversation tab component.
 *
 * ## UI Layout (Conversation Tab)
 * ```
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ Tab Header: [Project Name] | Agent: [Name] | 1.2K               │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │ ↑
 * │  ┌──────────────────────────────────────────────────────────┐  │ │
 * │  │ [☑] [USER] Message 1                              [⚙]    │  │ │
 * │  └──────────────────────────────────────────────────────────┘  │ │
 * │                                                                 │ │
 * │  ┌──────────────────────────────────────────────────────────┐  │ │
 * │  │ [☐] [ASSISTANT] Response 1                        [⚙]    │  │ │ Scrollable
 * │  │ <Markdown rendered content>                              │  │ │ Message
 * │  │ ┌──────────────────────────────────────────────────────┐ │  │ │ List
 * │  │ │ [Thinking ▼] (click to expand)                       │ │  │ │
 * │  │ └──────────────────────────────────────────────────────┘ │  │ │
 * │  │ [Tool Call: grz_read_file] → [Tool Result ▼]            │  │ │
 * │  └──────────────────────────────────────────────────────────┘  │ ↓
 * │                                                                 │
 * │  ┌──────────────────────────────────────────────────────────┐  │
 * │  │ [ASSISTANT] Streaming response...                        │  │
 * │  │ Partial text appears here █                              │  │
 * │  └──────────────────────────────────────────────────────────┘  │
 * │                                                                 │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ Edit Mode Toolbar (when editMode = true):                      │
 * │ [Select All] [User] [Assistant] [Thinking] [Tools] [Plain]     │
 * │ Selected: 3 | [Squash] [Distill] [Summarize] [Delete]          │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ Message Instructions: [R]                                       │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ ┌─────────────────────────────────────────────────────────────┐ │
 * │ │ Type your message here...                                   │ │
 * │ └─────────────────────────────────────────────────────────────┘ │
 * │ [📸 Capture] [Cancel] (when streaming)              [Send ➤]   │
 * └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ## Message Rendering
 * - **USER messages**: Left-aligned, blue background, plain text
 * - **ASSISTANT messages**: Right-aligned, gray background, markdown rendering
 * - **Thinking blocks**: Collapsible, monospace font, initially collapsed
 * - **Tool calls**: Blue badge with tool name, click to show result
 * - **Tool results**: Collapsed by default, expandable panel
 * - **Streaming messages**: Typing indicator, partial content updates
 *
 * ## Behavior
 *
 * ### Sending Messages
 * 1. User types in input field ([userInput])
 * 2. Optionally selects a message instruction (Readonly/Writable)
 * 3. Clicks [Send] or presses Ctrl+Enter → [sendMessage]
 * 4. User message added to [allMessages] immediately
 * 5. Input field cleared
 * 6. AI response streams in real-time
 * 7. Each chunk updates message in [allMessages]
 * 8. [isWaitingForResponse] = true during streaming
 * 9. Sound notification on completion
 * 10. [tokenStats] updated
 *
 * ### Interrupting Streaming
 * - [Cancel] button appears when [isWaitingForResponse] = true
 * - Click [Cancel] or ESC → [interrupt]
 * - Streaming stops, partial response saved
 * - [isWaitingForResponse] = false
 *
 * ### Message Selection & Editing
 * - Toggle [editMode] to enable selection checkboxes
 * - Click message checkbox → [toggleMessageSelection]
 * - Shift+Click → [toggleMessageSelectionRange] (select range)
 * - Selected messages highlighted, count shown in toolbar
 * - Bulk operations: Squash, Distill, Summarize, Delete
 *
 * ### Message Instructions
 * - Groups defined in [messageInstructionGroups]
 * - Active instructions in [activeMessageInstructionIds]
 * - Instructions are mutually exclusive within the same group
 * - Example groups:
 *   - Mode: [Readonly, Writable]
 * - Click a control → [selectMessageInstruction]
 * - Selected controls marked with `includeInMessage` are included in the message
 *
 * ### Message Squashing
 * - **Manual Squash**: Concatenate selected messages as-is
 * - **Distill**: Use AI to extract key decisions and context
 * - **Summarize**: Use AI to create brief summary
 * - Squashing is TRANSACTIONAL - replaces multiple messages with one atomically
 *
 * ## State Management
 * - [allMessages] - complete message history (unfiltered)
 * - [filteredMessages] - messages after applying filters (hide system, etc.)
 * - [userInput] - current text in input field (reactive)
 * - [isWaitingForResponse] - whether AI is currently responding
 * - [tokenStats] - token usage statistics for this conversation
 * - [activeMessageInstructionIds] - currently selected message instructions
 * - [editMode] - whether bulk editing UI is visible
 * - [selectedMessageIds] - IDs of selected messages for bulk operations
 * - [collapsedMessageIds] - IDs of messages with collapsed thinking blocks
 *
 * ## Performance Considerations
 * - Messages loaded on tab creation (not paginated in current impl)
 * - Streaming chunks throttled by AI provider
 * - Markdown rendering cached per message (Compose remember)
 * - Tool results lazy-loaded on expand
 *
 * @property conversationId ID of conversation this tab displays
 * @property projectPath Absolute path to project directory
 * @property uiState Complete UI state (includes all tab state)
 * @property allMessages All messages in conversation (unfiltered)
 * @property filteredMessages Messages after applying display filters
 * @property isWaitingForResponse Whether AI is currently generating response
 * @property tokenStats Token usage statistics (input/output/total tokens)
 * @property toolResultsMap Map of tool call IDs to their results (for lookup)
 * @property messageInstructionGroups Settings-defined message instruction groups
 * @property activeMessageInstructionIds Set of currently selected instruction IDs
 */
interface TabComponentVM {
    // Immutable properties
    val conversationId: Conversation.Id
    val projectPath: String
    
    // State (survives recomposition)
    val uiState: StateFlow<TabUIState>
    val allMessages: StateFlow<List<Conversation.Message>>
    val filteredMessages: StateFlow<List<Conversation.Message>>
    val isWaitingForResponse: StateFlow<Boolean>
    val tokenStats: StateFlow<TokenUsageStatistics.ThreadTotals?>
    val toolResultsMap: StateFlow<Map<String, Conversation.Message.ContentItem.ToolResult>>
    val messageInstructionGroups: List<MessageInstructionGroup>
    val activeMessageInstructionIds: Set<String>
    
    // Actions - Message Input
    /**
     * Update text in input field.
     * Updates [userInput] in [uiState] reactively.
     *
     * @param input new input text
     */
    fun updateUserInput(input: String)
    
    /**
     * Send user message to AI.
     * This is a TRANSACTIONAL operation - saves user message AND streams AI response atomically.
     *
     * Message sending flow:
     * 1. Collect selected message instructions from [activeMessageInstructionIds]
     * 2. Combine with additionalInstructions
     * 3. Create user Message with content + instructions
     * 4. Add to [allMessages] immediately
     * 5. Clear [userInput]
     * 6. Set [isWaitingForResponse] = true
     * 7. Stream AI response chunks
     * 8. Update message in [allMessages] on each chunk
     * 9. Auto-collapse thinking blocks
     * 10. Play sound on completion
     * 11. Update [tokenStats]
     *
     * @param message message text to send
     * @param additionalInstructions extra instructions beyond selected composer controls
     */
    suspend fun sendMessageToSession(
        message: String,
        additionalInstructions: List<Conversation.Message.Instruction> = emptyList()
    )
    
    /**
     * Cancel ongoing AI response streaming.
     * Cancels streaming job, sets [isWaitingForResponse] = false.
     * Partial response remains in [allMessages] as-is.
     */
    fun interrupt()
    
    /**
     * Capture screenshot and add file path to input field.
     * Opens screen capture UI, saves screenshot, appends path to [userInput].
     * If [userInput] is blank, sets path directly. Otherwise appends with space.
     */
    suspend fun captureAndAddToInput()
    
    // Actions - Message Instructions
    /**
     * Select a message instruction within a group.
     * Controls are mutually exclusive within the same group.
     *
     * @param group instruction group definition
     * @param controlIndex index of control to activate in group
     */
    fun selectMessageInstruction(group: MessageInstructionGroup, controlIndex: Int)
    
    // Actions - Agent Management
    /**
     * Change agent for this conversation.
     * NOT TRANSACTIONAL - only updates UI state, doesn't save to repository.
     *
     * @param agent new agent to handle this conversation
     */
    fun updateAgent(agent: AgentDefinition)
    
    /**
     * Update custom tab display name.
     * NOT TRANSACTIONAL - only updates UI state.
     *
     * @param customName new custom name (null = use default)
     */
    fun updateCustomName(customName: String?)
    
    // Actions - Edit Mode & Selection
    /**
     * Toggle edit mode (show/hide message selection checkboxes).
     * When enabled, shows selection UI and bulk operation toolbar.
     */
    fun toggleEditMode()
    
    /**
     * Toggle selection state of single message.
     * Updates [selectedMessageIds] in [uiState].
     *
     * @param messageId message to toggle selection
     */
    fun toggleMessageSelection(messageId: Conversation.Message.Id)
    
    /**
     * Toggle selection range between last toggled message and current.
     * If shift not pressed or no previous toggle, behaves like [toggleMessageSelection].
     * Range includes all messages between last and current (inclusive).
     *
     * @param messageId message at range end
     * @param isShiftPressed whether Shift key is pressed
     */
    fun toggleMessageSelectionRange(messageId: Conversation.Message.Id, isShiftPressed: Boolean)
    
    /**
     * Clear all message selections.
     * Sets [selectedMessageIds] to empty set.
     */
    fun clearMessageSelection()
    
    /**
     * Toggle select/deselect all messages.
     * If all selected → deselect all
     * If some/none selected → select all
     *
     * @param allMessageIds IDs of all messages in current view
     */
    fun toggleSelectAll(allMessageIds: Set<Conversation.Message.Id>)
    
    /**
     * Toggle selection of all USER messages.
     * If all user messages selected → deselect them
     * If some/none selected → select all user messages
     */
    fun toggleSelectUserMessages()
    
    /**
     * Toggle selection of all ASSISTANT messages.
     */
    fun toggleSelectAssistantMessages()
    
    /**
     * Toggle selection of all messages containing thinking blocks.
     */
    fun toggleSelectThinkingMessages()
    
    /**
     * Toggle selection of all messages containing tool calls.
     */
    fun toggleSelectToolMessages()
    
    /**
     * Toggle selection of all plain messages (no thinking, no tools).
     */
    fun toggleSelectPlainMessages()
    
    // Actions - Message Collapse
    /**
     * Toggle collapse state of message's thinking block.
     * Updates [collapsedMessageIds] in [uiState].
     *
     * @param messageId message to toggle collapse
     */
    fun toggleMessageCollapse(messageId: Conversation.Message.Id)
    
    // Actions - Message Editing
    /**
     * Start editing a message.
     * Loads message content into editing state.
     * NOT TRANSACTIONAL - only updates UI state.
     *
     * @param messageId message to edit
     */
    fun startEditMessage(messageId: Conversation.Message.Id)
    
    /**
     * Update text of message being edited.
     * Updates [editingMessageText] in [uiState].
     *
     * @param text new editing text
     */
    fun updateEditingMessageText(text: String)
    
    /**
     * Cancel message editing.
     * Clears [editingMessageId] and [editingMessageText] in [uiState].
     */
    fun cancelEditMessage()
    
    /**
     * Save edited message to repository.
     * This is a TRANSACTIONAL operation - updates message content atomically.
     * Reloads messages after save.
     */
    suspend fun confirmEditMessage()
    
    /**
     * Delete all selected messages.
     * This is a TRANSACTIONAL operation - removes all messages atomically.
     * Clears selection and reloads messages.
     */
    suspend fun deleteSelectedMessages()
    
    // Actions - Message Squashing
    /**
     * Squash selected messages by simple concatenation.
     * This is a TRANSACTIONAL operation - replaces multiple messages with one atomically.
     *
     * Squashing logic:
     * 1. Concatenate text content of selected messages with \\n\\n
     * 2. Create new user message with combined text
     * 3. Replace selected messages with squashed message
     * 4. Clear selection and reload messages
     *
     * @throws IllegalStateException if less than 2 messages selected
     */
    suspend fun squashSelectedMessages()
    
    /**
     * Squash selected messages using AI distillation.
     * This is a TRANSACTIONAL operation - uses AI to extract key context, then replaces messages atomically.
     *
     * Distillation:
     * - Extracts architectural decisions
     * - Preserves critical facts and context
     * - Removes debug sessions and failed attempts
     * - High signal-to-noise ratio
     *
     * @throws IllegalStateException if less than 2 messages selected
     */
    suspend fun distillSelectedMessages()
    
    /**
     * Squash selected messages using AI summarization.
     * This is a TRANSACTIONAL operation - uses AI to create brief summary, then replaces messages atomically.
     *
     * Summarization:
     * - Creates concise overview
     * - Preserves main points
     * - Shorter than distillation
     *
     * @throws IllegalStateException if less than 2 messages selected
     */
    suspend fun summarizeSelectedMessages()
    
    /**
     * Complete UI state for a conversation tab.
     * Includes all transient UI state that should be persisted.
     *
     * @property projectPath absolute path to project directory
     * @property conversationId ID of conversation being displayed
     * @property activeMessageInstructionIds set of selected message instruction IDs
     * @property userInput current text in input field (unsent)
     * @property isWaitingForResponse whether AI is currently responding
     * @property customName custom tab display name (null = use default)
     * @property tabId stable tab identifier for MCP
     * @property parentTabId ID of tab that spawned this tab (null if user-created)
     * @property agent agent handling this conversation
     * @property editMode whether bulk editing UI is visible
     * @property selectedMessageIds IDs of messages selected for bulk operations
     * @property collapsedMessageIds IDs of messages with collapsed thinking blocks
     * @property editingMessageId ID of message currently being edited (null = not editing)
     * @property editingMessageText text content of message being edited
     */
    data class TabUIState(
        val projectPath: String,
        val conversationId: Conversation.Id,
        val activeMessageInstructionIds: Set<String>,
        val userInput: String,
        val isWaitingForResponse: Boolean,
        val customName: String?,
        val tabId: String,
        val parentTabId: String?,
        val agent: AgentDefinition,
        val editMode: Boolean,
        val selectedMessageIds: Set<Conversation.Message.Id>,
        val collapsedMessageIds: Set<Conversation.Message.Id>,
        val editingMessageId: Conversation.Message.Id?,
        val editingMessageText: String
    )
}
