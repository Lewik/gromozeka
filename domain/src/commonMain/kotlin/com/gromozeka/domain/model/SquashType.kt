package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

/**
 * Type of squash operation for message consolidation.
 *
 * Defines different strategies for combining multiple messages:
 *
 * - CONCATENATE: Simple joining without AI processing (preserves exact content)
 * - DISTILL: AI extracts key points and decisions, removing redundancy
 * - COMPACT: AI creates a continuation checkpoint for selected history
 *
 * Each type trades off between fidelity and compression:
 * - CONCATENATE: 100% fidelity, minimal compression
 * - DISTILL: High fidelity for key information, removes noise
 * - COMPACT: Continuation-oriented history compression
 */
@Serializable
enum class SquashType {
    /**
     * Simple concatenation without AI processing.
     *
     * Joins messages with separators, preserving exact original content.
     * No information loss but minimal compression.
     */
    CONCATENATE,

    /**
     * AI-powered distillation of key information.
     *
     * Extracts decisions, facts, and essential details while removing
     * redundant discussion, failed attempts, and noise.
     */
    DISTILL,

    /**
     * Conversation compaction.
     *
     * A text model creates a continuation checkpoint for the selected history.
     */
    COMPACT
}
