package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot

interface AiCatalogRepository {
    suspend fun find(): AiCatalogSnapshot?

    suspend fun findRevision(): Long?

    suspend fun replace(
        expectedRevision: Long,
        catalog: AiCatalog,
    ): AiCatalogSnapshot
}
