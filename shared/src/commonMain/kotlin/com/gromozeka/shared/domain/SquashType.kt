package com.gromozeka.shared.domain

import kotlinx.serialization.Serializable

/**
 * Type of squash operation for message consolidation.
 *
 * Defines different strategies for combining multiple messages:
 *
 * - CONCATENATE: Simple joining without AI processing (preserves exact content)
 * - DISTILL: AI extracts key points and decisions, removing redundancy
 * - SUMMARIZE: AI creates concise summary maintaining essential information
 *
 * Each type trades off between fidelity and compression:
 * - CONCATENATE: 100% fidelity, minimal compression
 * - DISTILL: High fidelity for key information, removes noise
 * - SUMMARIZE: Lower fidelity, maximum compression
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
     * AI-powered summarization.
     *
     * Creates concise summary capturing main points and outcomes.
     * More aggressive compression than DISTILL.
     */
    SUMMARIZE
}
