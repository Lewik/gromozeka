package com.gromozeka.application.service.memory

internal object MemoryPipelineParallelism {
    const val MAX_PARALLELISM = 20

    fun configuredParallelism(): Int =
        (System.getProperty(PARALLELISM_PROPERTY)
            ?: System.getenv(PARALLELISM_ENV))
            ?.trim()
            ?.toIntOrNull()
            ?.let(::normalizeParallelism)
            ?: 1

    fun normalizeParallelism(parallelism: Int): Int =
        parallelism.coerceIn(1, MAX_PARALLELISM)

    private const val PARALLELISM_PROPERTY = "gromozeka.memory.parallelism"
    private const val PARALLELISM_ENV = "GROMOZEKA_MEMORY_PARALLELISM"
}
