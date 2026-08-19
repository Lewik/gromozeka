package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaObservation

interface AiSubscriptionQuotaService {
    suspend fun read(
        modelConfigurationId: AiModelConfiguration.Id,
        forceRefresh: Boolean = false,
    ): AiSubscriptionQuotaObservation
}
