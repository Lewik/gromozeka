package com.gromozeka.application.service.memory

internal object MemoryPipelineParallelism {
    const val MAX_WRITE_BRANCH_PARALLELISM = 20

    fun writeBranchParallelism(): Int =
        (System.getProperty(WRITE_BRANCH_PARALLELISM_PROPERTY)
            ?: System.getenv(WRITE_BRANCH_PARALLELISM_ENV))
            ?.trim()
            ?.toIntOrNull()
            ?.let(::normalizeWriteBranchParallelism)
            ?: 1

    fun normalizeWriteBranchParallelism(parallelism: Int): Int =
        parallelism.coerceIn(1, MAX_WRITE_BRANCH_PARALLELISM)

    private const val WRITE_BRANCH_PARALLELISM_PROPERTY = "gromozeka.memory.write.parallelism"
    private const val WRITE_BRANCH_PARALLELISM_ENV = "GROMOZEKA_MEMORY_WRITE_PARALLELISM"
}
