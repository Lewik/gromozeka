package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.service.PromptDomainService

class TabPromptService(
    private val promptService: PromptDomainService,
) {
    suspend fun listAvailablePrompts(): List<TabPromptOption> =
        promptService.findAll()
            .filterNot { it.type is Prompt.Type.Environment }
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
            is Prompt.Type.Global -> "Global"
            is Prompt.Type.Workspace -> "Workspace"
            is Prompt.Type.Environment -> "Environment"
        }

    private val Prompt.Type.order: Int
        get() = when (this) {
            is Prompt.Type.Workspace -> 0
            is Prompt.Type.Global -> 1
            is Prompt.Type.Builtin -> 2
            is Prompt.Type.Environment -> 3
        }
}
