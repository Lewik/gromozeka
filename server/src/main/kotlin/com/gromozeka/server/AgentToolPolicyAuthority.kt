package com.gromozeka.server

import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.tool.*
import org.springframework.stereotype.Service

@Service
internal class AgentToolPolicyAuthority(
    private val agents: AgentDomainService,
    private val contracts: AiToolContractRepository,
) {
    suspend fun requireWithinCaller(context: ControlMcpCallContext, proposed: ToolAccessPolicy) {
        val id = context.callingAgentId ?: return
        val caller = agents.findById(id) ?: throw ControlMcpToolException("forbidden", "Calling Agent no longer exists")
        val known = contracts.findAll().map {
            AgentToolCatalogEntry(QualifiedToolName(it.descriptor.definition.source, it.logicalName),
                ToolContractFingerprint(it.fingerprint), it.modelName, it.variant, true)
        } + ProviderNativeTool.entries.map { it.catalogEntry() }
        if (!proposed.isNoBroaderThan(caller.toolAccess, known)) {
            throw ControlMcpToolException("forbidden", "An Agent cannot grant broader tool access than its own. Change permissions from the user interface or an authorized independent control client.")
        }
    }
}
