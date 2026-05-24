package com.gromozeka.application.service.memory

internal object MemoryPipelineParallelism {
    fun writeBranchParallelism(): Int =
        (System.getProperty(WRITE_BRANCH_PARALLELISM_PROPERTY)
            ?: System.getenv(WRITE_BRANCH_PARALLELISM_ENV))
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(1, 3)
            ?: 1

    private const val WRITE_BRANCH_PARALLELISM_PROPERTY = "gromozeka.memory.write.parallelism"
    private const val WRITE_BRANCH_PARALLELISM_ENV = "GROMOZEKA_MEMORY_WRITE_PARALLELISM"
}
