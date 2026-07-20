package com.gromozeka.application.service.memory

internal object MemoryPipelineParallelism {
    const val MAX_PARALLELISM = 20

    fun configuredReadParallelism(): Int =
        configuredParallelism(
            property = READ_PARALLELISM_PROPERTY,
            environmentVariable = READ_PARALLELISM_ENV,
        )

    fun configuredWriteParallelism(): Int =
        configuredParallelism(
            property = WRITE_PARALLELISM_PROPERTY,
            environmentVariable = WRITE_PARALLELISM_ENV,
        )

    private fun configuredParallelism(
        property: String,
        environmentVariable: String,
    ): Int {
        val configuredValue = System.getProperty(property)
            ?: System.getenv(environmentVariable)
            ?: return 1
        val parallelism = configuredValue.trim().toIntOrNull()
            ?: error("$property must be an integer, but was '$configuredValue'")
        return normalizeParallelism(parallelism)
    }

    fun normalizeParallelism(parallelism: Int): Int {
        require(parallelism in 1..MAX_PARALLELISM) {
            "Memory pipeline parallelism must be between 1 and $MAX_PARALLELISM, but was $parallelism"
        }
        return parallelism
    }

    private const val READ_PARALLELISM_PROPERTY = "gromozeka.memory.read.parallelism"
    private const val READ_PARALLELISM_ENV = "GROMOZEKA_MEMORY_READ_PARALLELISM"
    private const val WRITE_PARALLELISM_PROPERTY = "gromozeka.memory.write.parallelism"
    private const val WRITE_PARALLELISM_ENV = "GROMOZEKA_MEMORY_WRITE_PARALLELISM"
}
