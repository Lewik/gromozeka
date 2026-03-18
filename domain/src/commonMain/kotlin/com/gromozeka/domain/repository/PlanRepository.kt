package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Plan

/**
 * Repository for execution plans.
 *
 * Plans are stored in Neo4j graph database as (Plan) nodes.
 * Each plan contains ordered steps with dependency relationships.
 *
 * Storage: Neo4j graph database
 * Relationships: (Plan)-[:HAS_STEP]->(Step)
 */
interface PlanRepository {
    /**
     * Creates new execution plan.
     *
     * Creates (Plan) node in Neo4j with initial status ACTIVE.
     *
     * This is a transactional operation.
     *
     * @param plan plan to create
     * @return created plan
     * @throws DuplicatePlanException if plan with same ID already exists
     */
    suspend fun create(plan: Plan): Plan

    /**
     * Finds plan by ID.
     *
     * @param id plan identifier
     * @return plan if found, null otherwise
     */
    suspend fun findById(id: Plan.Id): Plan?

    /**
     * Finds all plans for conversation.
     *
     * Returns plans ordered by createdAt descending (newest first).
     *
     * @param conversationId conversation identifier
     * @return list of plans (empty if none found)
     */
    suspend fun findByConversationId(conversationId: Conversation.Id): List<Plan>

    /**
     * Finds all active plans for conversation.
     *
     * Filters by status ACTIVE in database. Returns all matches
     * ordered by createdAt descending (newest first).
     *
     * Normally at most one active plan should exist per conversation.
     * Multiple results indicate data corruption — caller should guard against this.
     *
     * @param conversationId conversation identifier
     * @return list of active plans (empty if none)
     */
    suspend fun findActivePlans(conversationId: Conversation.Id): List<Plan>

    /**
     * Updates plan status.
     *
     * Updates status and sets completedAt if transitioning to terminal state
     * (COMPLETED, FAILED, CANCELLED).
     *
     * This is a transactional operation.
     *
     * @param id plan identifier
     * @param status new status
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun updateStatus(id: Plan.Id, status: Plan.Status)

    /**
     * Deletes plan and all its steps.
     *
     * Cascading delete:
     * - Deletes (Plan) node
     * - Deletes all (Step) nodes connected via HAS_STEP
     * - Deletes all DEPENDS_ON relationships between steps
     *
     * This is a transactional operation.
     *
     * @param id plan identifier
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun delete(id: Plan.Id)
}
