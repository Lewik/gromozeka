package com.gromozeka.application.service

import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.tool.AiToolContract
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.contractFingerprint
import kotlinx.datetime.Clock

internal class InMemoryAiToolContractRepository : AiToolContractRepository {
    private val contracts = linkedMapOf<String, AiToolContract>()

    override suspend fun resolveAll(descriptors: Collection<AiToolDescriptor>): List<AiToolContract> =
        descriptors
            .associateBy(AiToolDescriptor::contractFingerprint)
            .map { (fingerprint, descriptor) ->
                contracts.getOrPut(fingerprint) {
                    val variant = contracts.values.count { it.logicalName == descriptor.definition.name } + 1
                    AiToolContract(
                        fingerprint = fingerprint,
                        logicalName = descriptor.definition.name,
                        modelName = if (variant == 1) {
                            descriptor.definition.name
                        } else {
                            "${descriptor.definition.name}__v$variant"
                        },
                        variant = variant,
                        descriptor = descriptor,
                        createdAt = Clock.System.now(),
                    )
                }.also { contract ->
                    check(contract.descriptor == descriptor)
                }
            }
}
