package com.gromozeka.domain.repository

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiModelSpec

/**
 * Repository for static knowledge about provider models.
 *
 * Runtime configuration decides which model is selected, but a selected model
 * should have an explicit spec so Gromozeka can validate capabilities and avoid
 * guessing limits.
 */
interface AiModelSpecRepository {
    /**
     * Finds known limits and static behavior for one provider model.
     *
     * @param provider provider/model family.
     * @param modelId exact provider model id from an `AiModelConfiguration`.
     * @return configured model spec, or null when this model is not described.
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
