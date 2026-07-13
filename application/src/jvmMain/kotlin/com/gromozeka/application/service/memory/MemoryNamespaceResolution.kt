package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace

private val memoryNamespacePattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._:@/-]{0,127}")

internal fun String?.toMemoryNamespaceOverride(): MemoryNamespace? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    require(memoryNamespacePattern.matches(value)) {
        "Memory namespace must be a readable slug up to 128 chars. Use letters, numbers, '.', '_', '-', ':', '@', or '/'. Examples: global, user:lewik, work:hebrew, project:<project-id>."
    }
    return MemoryNamespace(value)
}
