package com.gromozeka.domain.tool

import kotlinx.serialization.Serializable

@Serializable
data class AgentToolCatalogEntry(
    val name: QualifiedToolName,
    val fingerprint: ToolContractFingerprint,
    val modelName: String,
    val variant: Int?,
    val available: Boolean,
    val providerNative: Boolean = false,
) {
    fun selector(allRevisions: Boolean): ToolSelector = if (allRevisions) ToolSelector.ByName(name)
        else ToolSelector.ExactRevision(fingerprint)

    fun matches(selector: ToolSelector): Boolean = selector.matches(name, fingerprint.value)
}

fun AgentPreloadedTools.blockedBy(policy: ToolAccessPolicy, catalog: List<AgentToolCatalogEntry>): List<String> =
    names.flatMap { requested ->
        val exact = catalog.firstOrNull { it.modelName == requested && it.modelName != it.name.name }
        (exact?.let(::listOf) ?: catalog.filter { it.name.name == requested }).filter {
            !policy.allows(it.name, it.fingerprint.value)
        }.map { it.modelName }
    }.distinct().sorted()
