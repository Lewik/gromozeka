package com.gromozeka.domain.service.plan

/**
 * MCP tool interfaces for working with plans.
 *
 * Each interface is a specification of one MCP tool.
 * Infrastructure layer implements these interfaces and registers them as MCP tools.
 *
 * Pattern: Domain spec -> Infrastructure implementation -> Compiler enforcement
 */

/**
 * [MCP TOOL] Create new plan.
 *
 * Tool name: `create_plan`
 *
 * Usage example:
 * ```json
 * {
 *   "name": "Implement authentication feature",
 *   "description": "Add JWT-based authentication to the API",
 *   "isTemplate": false
 * }
 * ```
 *
 * Returns:
 * ```json
 * {
 *   "planId": "uuid",
 *   "name": "...",
 *   "description": "...",
 *   "isTemplate": false,
 *   "createdAt": "2025-01-15T10:30:00Z"
 * }
 * ```
 */
interface CreatePlanTool {
    suspend fun execute(
        name: String,
        description: String,
        isTemplate: Boolean = false
    ): Map<String, Any>
}

/**
 * [MCP TOOL] Update plan.
 *
 * Tool name: `update_plan`
 *
 * Only passed parameters are updated.
 */
interface UpdatePlanTool {
    suspend fun execute(
        planId: String,
        name: String? = null,
        description: String? = null,
        isTemplate: Boolean? = null
    ): Map<String, Any>
}

/**
 * [MCP TOOL] Delete plan.
 *
 * Tool name: `delete_plan`
 *
 * Cascades to all steps.
 */
interface DeletePlanTool {
    suspend fun execute(planId: String): Map<String, Any>
}

/**
 * [MCP TOOL] Get plan with all steps.
 *
 * Tool name: `get_plan`
 *
 * Returns plan and all its steps in flat list.
 */
interface GetPlanTool {
    suspend fun execute(planId: String): Map<String, Any>
}

/**
 * [MCP TOOL] Search plans.
 *
 * Tool name: `search_plans`
 *
 * Semantic search over description and name.
 *
 * Example:
 * ```json
 * {
 *   "query": "authentication implementation",
 *   "limit": 5
 * }
 * ```
 */
interface SearchPlansTool {
    suspend fun execute(
        query: String,
        limit: Int = 10
    ): Map<String, Any>
}

/**
 * [MCP TOOL] Clone plan.
 *
 * Tool name: `clone_plan`
 *
 * Creates a copy of plan with all steps.
 */
interface ClonePlanTool {
    suspend fun execute(
        planId: String,
        newName: String? = null
    ): Map<String, Any>
}

/**
 * [MCP TOOL] Add text step.
 *
 * Tool name: `add_text_step`
 *
 * Example:
 * ```json
 * {
 *   "planId": "uuid",
 *   "instruction": "Create User entity with fields: id, email, passwordHash",
 *   "parentId": null
 * }
 * ```
 */
interface AddTextStepTool {
    suspend fun execute(
        planId: String,
        instruction: String,
        parentId: String? = null
    ): Map<String, Any>
}

/**
 * [MCP TOOL] Update step.
 *
 * Tool name: `update_step`
 *
 * Only passed parameters are updated.
 */
interface UpdateStepTool {
    suspend fun execute(
        stepId: String,
        instruction: String? = null,
        result: String? = null,
        status: String? = null,
        parentId: String? = null
    ): Map<String, Any>
}

/**
 * [MCP TOOL] Delete step.
 *
 * Tool name: `delete_step`
 *
 * Cascades to child steps.
 */
interface DeleteStepTool {
    suspend fun execute(stepId: String): Map<String, Any>
}

/**
 * [MCP TOOL] Update step status.
 *
 * Tool name: `update_step_status`
 *
 * Quick status change (for UI checkbox).
 *
 * Example:
 * ```json
 * {
 *   "stepId": "uuid",
 *   "status": "DONE"
 * }
 * ```
 */
interface UpdateStepStatusTool {
    suspend fun execute(
        stepId: String,
        status: String
    ): Map<String, Any>
}
