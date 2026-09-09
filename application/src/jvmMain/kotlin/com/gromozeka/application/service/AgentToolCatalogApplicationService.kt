package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.tool.*
import org.springframework.stereotype.Service

@Service
class AgentToolCatalogApplicationService(
    private val contracts: AiToolContractRepository,
    private val catalog: DistributedAiToolCatalog,
    private val projects: ProjectDomainService,
) {
    suspend fun load(projectId: Project.Id?): List<AgentToolCatalogEntry> {
        val current = projectId?.let { id ->
            catalog.snapshot(projects.findById(id) ?: error("Project not found: ${id.value}"))
        }
        val available = current?.entries?.values?.mapTo(mutableSetOf()) { it.contractFingerprint }
            ?: catalog.serverContracts().mapTo(mutableSetOf()) { it.fingerprint }
        val sources = current?.entries?.values?.mapTo(mutableSetOf()) { it.descriptor.definition.source }
        val entries = contracts.findAll().filter { sources == null || it.descriptor.definition.source in sources }.map { contract ->
            AgentToolCatalogEntry(
                QualifiedToolName(contract.descriptor.definition.source, contract.logicalName),
                ToolContractFingerprint(contract.fingerprint), contract.modelName, contract.variant,
                contract.fingerprint in available,
            )
        }.sortedWith(compareBy({ it.name.source }, { it.name.name }, { it.variant }))
        return entries + ProviderNativeTool.entries.map { it.catalogEntry() }
    }
}
