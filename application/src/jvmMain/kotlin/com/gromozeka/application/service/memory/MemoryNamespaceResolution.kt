package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_NAMESPACE
import com.gromozeka.domain.tool.ToolExecutionContext

private val memoryNamespacePattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._:@/-]{0,127}")

private fun String?.toValidatedMemoryNamespaceOrNull(): MemoryNamespace? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    require(memoryNamespacePattern.matches(value)) {
        "Memory namespace must be a readable slug up to 128 chars. Use letters, numbers, '.', '_', '-', ':', '@', or '/'."
    }
    return MemoryNamespace(value)
}

internal fun ToolExecutionContext?.requiredMemoryNamespace(): MemoryNamespace =
    this?.getString(TOOL_CONTEXT_MEMORY_NAMESPACE)
        .toValidatedMemoryNamespaceOrNull()
        ?: error("Authorized memory namespace is missing from tool execution context")
