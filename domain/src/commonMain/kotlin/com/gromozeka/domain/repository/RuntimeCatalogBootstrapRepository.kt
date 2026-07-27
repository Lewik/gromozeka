package com.gromozeka.domain.repository

import com.gromozeka.domain.model.RuntimeCatalogSeed

interface RuntimeCatalogBootstrapRepository {
    suspend fun initializeIfEmpty(seed: RuntimeCatalogSeed): Boolean
}
