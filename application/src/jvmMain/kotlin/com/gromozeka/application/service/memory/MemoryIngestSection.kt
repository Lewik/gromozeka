package com.gromozeka.application.service.memory

internal data class MemoryIngestSection(
    val index: Int,
    val headingPath: List<String>,
    val startLine: Int,
    val endLine: Int,
    val text: String,
) {
    val headingLabel: String =
        headingPath.joinToString(" / ").ifBlank { "Source preamble" }
}
