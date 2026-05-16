package com.gromozeka.domain.repository

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiModelSpec

/**
 * Repository for optional static knowledge about provider models.
 *
 * Model specs are not the source of truth for whether a model may be called.
 * User profile model configurations decide that. Missing specs are normal for
 * custom or newly released models; callers must treat `null` as "unknown
 * limits" and disable spec-dependent behavior instead of guessing.
 */
interface AiModelSpecRepository {
    /**
     * Finds known limits and static behavior for one provider model.
     *
     * @param provider provider/model family.
     * @param modelId exact provider model id from an `AiModelConfiguration`.
     * @return configured model spec, or null when this model is callable but its
     * limits are not known to Gromozeka.
     */
    suspend fun find(provider: AiProvider, modelId: String): AiModelSpec?

    /**
     * Returns all configured model specs.
     *
     * Implementations may load these from resources, disk, database, or remote
     * discovery. The returned list must be unique by provider and model id.
     */
    suspend fun findAll(): List<AiModelSpec>
}
