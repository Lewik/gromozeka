package com.gromozeka.domain.repository

import com.gromozeka.domain.tool.AiToolContract
import com.gromozeka.domain.tool.AiToolDescriptor

interface AiToolContractRepository {
    suspend fun resolveAll(descriptors: Collection<AiToolDescriptor>): List<AiToolContract>
}
