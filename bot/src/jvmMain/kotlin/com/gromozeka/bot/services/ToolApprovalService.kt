package com.gromozeka.bot.services

import klog.KLoggers
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.stereotype.Service

/**
 * Service responsible for approving tool calls before execution.
 *
 * This abstraction allows for different approval strategies:
 * - AutoApproveToolApprovalService: Automatically approves all tool calls (current default)
 * - UIToolApprovalService: Shows UI dialog for user confirmation (future)
 */
interface ToolApprovalService {
    /**
     * Approve or reject the given tool calls.
     *
     * @param toolCalls List of tool calls requested by the AI model
     * @return ApprovalResult indicating whether the tool calls are approved or rejected
     */
    suspend fun approve(toolCalls: List<AssistantMessage.ToolCall>): ApprovalResult
}

/**
 * Result of tool call approval.
 */
sealed class ApprovalResult {
    /**
     * Tool calls are approved and can be executed.
     */
    data object Approved : ApprovalResult()

    /**
     * Tool calls are rejected and should not be executed.
     *
     * @param reason Human-readable reason for rejection
     */
    data class Rejected(val reason: String) : ApprovalResult()
}

/**
 * Default implementation that automatically approves all tool calls.
 *
 * This is the current behavior matching the previous framework-controlled execution.
 * Future implementations can provide UI-based approval.
 */
@Service
class AutoApproveToolApprovalService : ToolApprovalService {
    private val log = KLoggers.logger(this)

    override suspend fun approve(toolCalls: List<AssistantMessage.ToolCall>): ApprovalResult {
        val toolNames = toolCalls.joinToString(", ") { it.name() }
        log.debug { "Auto-approving ${toolCalls.size} tool calls: $toolNames" }
        return ApprovalResult.Approved
    }
}
