package com.gromozeka.domain.tool

import com.gromozeka.domain.model.ai.AiModelConfiguration
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class AiToolCapabilityCatalog(
    val sourceId: String,
    val fingerprint: String,
    val overview: String,
    val categories: List<Category>,
    val generatedByModelConfigurationId: AiModelConfiguration.Id,
    val generatedAt: Instant,
) {
    init {
        require(sourceId.isNotBlank()) { "AI tool capability catalog source id must not be blank" }
        require(fingerprint.matches(SHA_256_PATTERN)) {
            "AI tool capability catalog fingerprint must be a lowercase SHA-256 hash"
        }
        require(overview.isNotBlank()) { "AI tool capability catalog overview must not be blank" }
        require(categories.isNotEmpty()) { "AI tool capability catalog must contain categories" }
        require(categories.map(Category::id).distinct().size == categories.size) {
            "AI tool capability catalog category ids must be unique"
        }
        val toolNames = categories.flatMap(Category::toolNames)
        require(toolNames.distinct().size == toolNames.size) {
            "AI tool capability catalog tools must belong to exactly one category"
        }
    }

    @Serializable
    data class Category(
        val id: String,
        val label: String,
        val summary: String,
        val toolNames: List<String>,
    ) {
        init {
            require(id.matches(CATEGORY_ID_PATTERN)) {
                "AI tool capability category id must be stable snake_case: $id"
            }
            require(label.isNotBlank()) { "AI tool capability category label must not be blank" }
            require(summary.isNotBlank()) { "AI tool capability category summary must not be blank" }
            require(toolNames.isNotEmpty()) { "AI tool capability category must contain tools" }
            require(toolNames.all(String::isNotBlank)) {
                "AI tool capability category tool names must not be blank"
            }
            require(toolNames.distinct().size == toolNames.size) {
                "AI tool capability category tool names must be unique"
            }
        }
    }
}

private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
private val CATEGORY_ID_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
