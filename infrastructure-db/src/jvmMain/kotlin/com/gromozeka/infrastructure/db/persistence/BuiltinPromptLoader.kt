package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

@Component
class BuiltinPromptLoader {
    private val log = KLoggers.logger("BuiltinPromptLoader")
    private val resourceResolver = PathMatchingResourcePatternResolver()

    fun loadBuiltinPrompts(): List<Prompt> {
        val resources = resourceResolver.getResources("classpath*:/prompts/*.md")
        check(resources.isNotEmpty()) {
            "No builtin prompts found in classpath:/prompts"
        }

        val prompts = resources.map { resource ->
            val fileName = checkNotNull(resource.filename) {
                "Builtin prompt resource has no filename: $resource"
            }
            val content = resource.inputStream.bufferedReader().use { it.readText() }
            val name = fileName.removeSuffix(".md")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }
            val id = Prompt.Id("builtin:$fileName")

            Prompt(
                id = id,
                name = name,
                content = content,
                type = Prompt.Type.Builtin,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now()
            ).also {
                log.debug { "Loaded builtin prompt: $name ($id)" }
            }
        }

        val duplicateIds = prompts.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicateIds.isEmpty()) {
            "Duplicate builtin prompt ids: ${duplicateIds.joinToString { it.value }}"
        }

        log.info { "Loaded ${prompts.size} builtin prompts from /prompts directory" }
        return prompts
    }
}
