package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import com.gromozeka.domain.tool.ToolExecutionContext

/**
 * Domain specification for informational notification tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `notify`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Send informational message to user without interrupting execution.
 * Continues stride mode after notification.
 *
 * ## Core Features
 *
 * **Non-blocking notification:**
 * - Displays message to user
 * - Does NOT pause execution
 * - Does NOT exit stride mode
 * - Does NOT wait for user response
 *
 * **Progress updates:**
 * - Step progress ("Processing 5/10 files...")
 * - Intermediate findings ("Found 15 TODO items")
 * - Context gathering ("Searching codebase...")
 * - Verification results ("Fact confirmed from graph")
 *
 * **Flow control:**
 * - Execution continues immediately
 * - Message appears in UI (toast, status bar, thread)
 * - No user action required
 *
 * # When to Use
 *
 * **Call when:**
 * - Long-running step needs progress updates
 * - Intermediate findings worth showing
 * - Verification status information
 * - Context gathering notifications
 *
 * **Examples:**
 * - "Searching 347 Kotlin files for TODO patterns..."
 * - "Found 15 matches. Analyzing priority levels..."
 * - "Verified: project uses PostgreSQL 16 (from graph)"
 * - "Gathering context: reading 3 related files"
 *
 * **Do NOT call if:**
 * - Need user decision → use `request_user_input`
 * - Step completed → use `step_complete`
 * - Step failed → use `step_complete` with status="fail"
 * - Message is critical error
 *
 * # Parameters
 *
 * ## stepId: String
 * UUID of current step (provided by application at step start).
 *
 * ## message: String
 * Informational message for user (1-2 sentences).
 * Should be concise and relevant to current step.
 *
 * Example:
 * - "Searching 347 files for TODO patterns..."
 * - "Found 15 TODO items. Grouping by priority..."
 * - "Context gathered: 3 files, 2 graph entities"
 *
 * ## level: String (default: "info")
 * Notification importance level.
 *
 * - **"info"** - neutral information (default)
 * - **"success"** - positive progress (milestone reached)
 * - **"warning"** - concerning but non-critical (unusual pattern found)
 *
 * # Response
 *
 * Returns acknowledgment:
 * ```json
 * {
 *   "status": "notified",
 *   "message": "Notification sent to user"
 * }
 * ```
 *
 * # UI Presentation
 *
 * **Display options:**
 * 1. **Toast notification** - brief popup (2-3 seconds)
 * 2. **Status bar** - persistent until step completes
 * 3. **Thread message** - System role message in thread
 * 4. **Progress indicator** - if message contains progress info
 *
 * **Formatting:**
 * - Info: blue/neutral color
 * - Success: green checkmark
 * - Warning: yellow/orange indicator
 *
 * # Tool Behavior
 *
 * **Message delivery:**
 * - Notification is added to conversation thread (role: SYSTEM)
 * - UI displays notification (transient or persistent based on level)
 * - Step execution continues without interruption
 *
 * **State preservation:**
 * - Step.status remains unchanged (typically IN_PROGRESS)
 * - No Neo4j state modification occurs
 * - Tool call completes synchronously
 *
 * **Distillation exclusion:**
 * - Notify messages are excluded from distillation
 * - They represent transient progress, not results
 * - Only step_complete result appears in distillate
 *
 * **Rate limiting:**
 * - Notifications may be throttled to prevent UI spam
 * - Maximum rate typically 1 per second
 * - Excessive notifications are buffered or dropped
 *
 * ## Usage Patterns
 *
 * **Progress tracking:**
 * - Long-running operations emit periodic status updates
 * - Each phase of multi-step operation is announced
 * - Completion uses step_complete, not notify
 *
 * **Context gathering:**
 * - Each data source query is announced
 * - Summary notification after gathering completes
 * - Actual execution continues in same turn
 *
 * **Verification:**
 * - Verification status is communicated
 * - Success/warning levels indicate outcome
 * - Critical issues escalate to request_user_input
 */
interface NotifyTool : Tool<NotifyRequest, NotifyResponse> {
    override val name: String get() = "notify"
    override val description: String get() = "Send informational notification without interrupting execution"
    override val requestType: Class<NotifyRequest> get() = NotifyRequest::class.java

    override fun execute(request: NotifyRequest, context: ToolExecutionContext?): NotifyResponse
}

@Serializable
data class NotifyRequest(
    val stepId: String,
    val message: String,
    val level: String = "info"  // "info" | "success" | "warning"
)

@Serializable
data class NotifyResponse(
    val status: String,  // "notified"
    val message: String
)
