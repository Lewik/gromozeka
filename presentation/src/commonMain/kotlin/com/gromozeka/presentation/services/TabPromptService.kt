package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.service.PromptDomainService

class TabPromptService(
    private val promptService: PromptDomainService,
) {
    suspend fun listAvailablePrompts(): List<TabPromptOption> =
        promptService.findAll()
            .map { prompt ->
                TabPromptOption(
                    id = prompt.id.value,
                    name = prompt.name,
                    type = prompt.type.label,
                    typeOrder = prompt.type.order,
                )
            }
            .sortedWith(compareBy<TabPromptOption> { it.typeOrder }.thenBy { it.name.lowercase() })

    data class TabPromptOption(
        val id: String,
        val name: String,
        val type: String,
        val typeOrder: Int,
    )

    private val Prompt.Type.label: String
        get() = when (this) {
            is Prompt.Type.Builtin -> "Builtin"
            is Prompt.Type.Project -> "Project"
        }

    private val Prompt.Type.order: Int
        get() = when (this) {
            is Prompt.Type.Project -> 0
            is Prompt.Type.Builtin -> 1
        }
}
