package com.gromozeka.domain.repository

import com.gromozeka.domain.tool.AiToolCapabilityCatalog

interface AiToolCapabilityCatalogRepository {
    suspend fun find(
        sourceId: String,
        fingerprint: String,
    ): AiToolCapabilityCatalog?

    suspend fun saveIfAbsent(catalog: AiToolCapabilityCatalog): AiToolCapabilityCatalog
}
