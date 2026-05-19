package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.memory.MemoryNamespace

internal const val PROJECT_MEMORY_NAMESPACE_PREFIX = "project:"

private val memoryNamespacePattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._:@/-]{0,127}")

internal fun Project.defaultMemoryNamespace(): MemoryNamespace =
    MemoryNamespace("$PROJECT_MEMORY_NAMESPACE_PREFIX${id.value}")

internal fun String?.toConfiguredMemoryNamespace(): MemoryNamespace? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    require(memoryNamespacePattern.matches(value)) {
        "Memory namespace must be a readable slug up to 128 chars. Use letters, numbers, '.', '_', '-', ':', '@', or '/'. Examples: global, user:lewik, work:hebrew, project:<project-id>."
    }
    return MemoryNamespace(value)
}

internal fun UserProfile.MemorySettings.defaultMemoryNamespace(): MemoryNamespace? =
    defaultNamespace.toConfiguredMemoryNamespace()
