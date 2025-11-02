package com.gromozeka.shared.domain

import com.gromozeka.shared.domain.Project
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val id: Id,
    val projectId: Project.Id,
    val name: String,
    val content: String,
    val files: List<File>,
    val links: List<String>,
    val tags: Set<String>,
    val extractedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    data class File(
        val path: String,
        val spec: FileSpec
    )

    @Serializable
    sealed class FileSpec {
        @Serializable
        data object ReadFull : FileSpec()

        @Serializable
        data class SpecificItems(val items: List<String>) : FileSpec()
    }
}

fun String.toContextId(): Context.Id = Context.Id(this)
