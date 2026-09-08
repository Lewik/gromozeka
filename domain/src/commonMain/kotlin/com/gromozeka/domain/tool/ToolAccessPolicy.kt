package com.gromozeka.domain.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class ToolContractFingerprint(val value: String) {
    init {
        require(value.matches(Regex("[a-f0-9]{64}"))) { "Tool contract fingerprint must be a SHA-256 hex digest" }
    }
}

@Serializable
data class QualifiedToolName(val source: String, val name: String) {
    init {
        require(source.isNotBlank() && name.isNotBlank()) { "Tool source and logical name must not be blank" }
    }
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ToolSelector {
    @Serializable
    @SerialName("exact_revision")
    data class ExactRevision(val fingerprint: ToolContractFingerprint) : ToolSelector

    @Serializable
    @SerialName("by_name")
    data class ByName(val tool: QualifiedToolName) : ToolSelector

    fun matches(name: QualifiedToolName, fingerprint: String): Boolean = when (this) {
        is ExactRevision -> this.fingerprint.value == fingerprint
        is ByName -> tool == name
    }
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ToolAccessPolicy {
    val entries: Set<ToolSelector>

    @Serializable
    @SerialName("allow_only")
    data class AllowOnly(override val entries: Set<ToolSelector> = emptySet()) : ToolAccessPolicy

    @Serializable
    @SerialName("deny_listed")
    data class DenyListed(override val entries: Set<ToolSelector> = emptySet()) : ToolAccessPolicy

    fun allows(name: QualifiedToolName, fingerprint: String): Boolean {
        val matches = entries.any { it.matches(name, fingerprint) }
        return when (this) {
            is AllowOnly -> matches
            is DenyListed -> !matches
        }
    }

    fun allows(contract: AiToolContract): Boolean = allows(
        QualifiedToolName(contract.descriptor.definition.source, contract.logicalName), contract.fingerprint,
    )
}

@Serializable
data class AgentPreloadedTools(val names: List<String> = emptyList()) {
    init {
        require(names.none(String::isBlank)) { "Preloaded tool names must not be blank" }
        require(names.distinct().size == names.size) { "Preloaded tool names must be unique" }
    }
}

fun ToolAccessPolicy.isNoBroaderThan(parent: ToolAccessPolicy, known: List<AgentToolCatalogEntry>): Boolean {
    if (this == parent) return true
    fun ToolSelector.nameOrNull(): QualifiedToolName? = when (this) {
        is ToolSelector.ByName -> tool
        is ToolSelector.ExactRevision -> known.firstOrNull { it.fingerprint == fingerprint }?.name
    }
    fun ToolSelector.covers(other: ToolSelector): Boolean = this == other ||
        (this is ToolSelector.ByName && tool == other.nameOrNull())
    fun ToolSelector.mayOverlap(other: ToolSelector): Boolean {
        if (this == other) return true
        if (this is ToolSelector.ExactRevision && other is ToolSelector.ExactRevision) return false
        val first = nameOrNull() ?: return true
        val second = other.nameOrNull() ?: return true
        return first == second
    }
    return when {
        this is ToolAccessPolicy.AllowOnly && parent is ToolAccessPolicy.AllowOnly ->
            entries.all { child -> parent.entries.any { it.covers(child) } }
        this is ToolAccessPolicy.AllowOnly && parent is ToolAccessPolicy.DenyListed ->
            entries.none { child -> parent.entries.any { it.mayOverlap(child) } }
        this is ToolAccessPolicy.DenyListed && parent is ToolAccessPolicy.DenyListed ->
            parent.entries.all { denied -> entries.any { it.covers(denied) } }
        else -> false
    }
}
