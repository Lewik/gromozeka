package com.gromozeka.application.service.memory

internal object MemoryPipelineParallelism {
    const val MAX_PARALLELISM = 20

    fun configuredParallelism(): Int {
        val configuredValue = System.getProperty(PARALLELISM_PROPERTY)
            ?: System.getenv(PARALLELISM_ENV)
            ?: return 1
        val parallelism = configuredValue.trim().toIntOrNull()
            ?: error("$PARALLELISM_PROPERTY must be an integer, but was '$configuredValue'")
        return normalizeParallelism(parallelism)
    }

    fun normalizeParallelism(parallelism: Int): Int {
        require(parallelism in 1..MAX_PARALLELISM) {
            "Memory pipeline parallelism must be between 1 and $MAX_PARALLELISM, but was $parallelism"
        }
        return parallelism
    }

    private const val PARALLELISM_PROPERTY = "gromozeka.memory.parallelism"
    private const val PARALLELISM_ENV = "GROMOZEKA_MEMORY_PARALLELISM"
}
