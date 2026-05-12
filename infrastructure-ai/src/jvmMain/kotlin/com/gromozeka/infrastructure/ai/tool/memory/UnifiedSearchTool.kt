package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.memory.UnifiedSearchRequest
import com.gromozeka.infrastructure.db.memory.UnifiedSearchService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class UnifiedSearchTool(
    private val unifiedSearchService: UnifiedSearchService,
) : com.gromozeka.domain.tool.memory.UnifiedSearchTool {
    private val logger = LoggerFactory.getLogger(UnifiedSearchTool::class.java)

    override fun execute(request: UnifiedSearchRequest, context: ToolExecutionContext?): Map<String, Any> {
        if (request.query.isBlank()) {
            return errorResponse("Query cannot be empty")
        }

        return try {
            val results = runBlocking {
                unifiedSearchService.unifiedSearch(
                    query = request.query,
                    scopes = request.scopes.toSet(),
                    knowledgeKinds = request.knowledgeKinds.orEmpty().filter { it.isNotBlank() }.toSet(),
                    standings = request.standings.orEmpty().filter { it.isNotBlank() }.toSet(),
                    bases = request.bases.orEmpty().filter { it.isNotBlank() }.toSet(),
                    relationRoles = request.relationRoles.orEmpty().filter { it.isNotBlank() }.toSet(),
                    perspectiveKind = request.perspectiveKind?.takeIf { it.isNotBlank() },
                    perspectiveValue = request.perspectiveValue?.takeIf { it.isNotBlank() },
                    includeInvalidated = request.includeInvalidated ?: false,
                    limit = request.limit ?: 5,
                )
            }

            if (results.isEmpty()) {
                return mapOf(
                    "type" to "text",
                    "text" to "No results found for '${request.query}'"
                )
            }

            mapOf(
                "type" to "text",
                "text" to formatResults(request.query, results)
            )
        } catch (e: Exception) {
            logger.error("Error in unified search for '${request.query}': ${e.message}", e)
            errorResponse("Search failed: ${e.message}")
        }
    }

    private fun formatResults(
        query: String,
        results: List<MemoryStore.SearchHit>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} results for '$query':")
        sb.appendLine()

        results.forEach { hit ->
            when (hit) {
                is MemoryStore.SearchHit.SourceHit -> {
                    sb.appendLine("[SOURCE] ${hit.source.contentText.take(240)} (score: ${formatScore(hit.score)})")
                    sb.appendLine(
                        "  id=${hit.source.id.value} type=${hit.source.kindLabel()} speaker=${hit.source.speakerLabel()}"
                    )
                }

                is MemoryStore.SearchHit.EntityHit -> {
                    sb.appendLine("[ENTITY] ${hit.entity.canonicalName} (score: ${formatScore(hit.score)})")
                    if (!hit.entity.summary.isNullOrBlank()) {
                        sb.appendLine("  ${hit.entity.summary}")
                    }
                    sb.appendLine(
                        "  id=${hit.entity.id.value} type=${hit.entity.entityType.name} status=${hit.entity.status.name}"
                    )
                    if (hit.entity.aliases.isNotEmpty()) {
                        sb.appendLine("  aliases=${hit.entity.aliases.joinToString { it.text }}")
                    }
                }

                is MemoryStore.SearchHit.ClaimHit -> {
                    sb.appendLine("[CLAIM] ${hit.claim.normalizedText.take(240)} (score: ${formatScore(hit.score)})")
                    sb.appendLine(
                        "  id=${hit.claim.id.value} predicate=${hit.claim.predicate} status=${hit.claim.status.name} confidence=${formatScore(hit.claim.confidence)} importance=${hit.claim.importance}"
                    )
                    if (hit.claim.objectValue != null) {
                        sb.appendLine("  object=${hit.claim.objectValue}")
                    }
                    sb.appendLine("  evidence=${hit.claim.evidenceRefs.size} useCount=${hit.claim.useCount}")
                }

                is MemoryStore.SearchHit.NoteHit -> {
                    sb.appendLine("[NOTE] ${hit.note.title.ifBlank { hit.note.summary.take(80) }} (score: ${formatScore(hit.score)})")
                    sb.appendLine("  ${hit.note.summary.take(240)}")
                    sb.appendLine(
                        "  id=${hit.note.id.value} type=${hit.note.noteType.name} status=${hit.note.status.name} maturity=${hit.note.maturity.name}"
                    )
                    sb.appendLine("  evidence=${hit.note.evidenceRefs.size} useCount=${hit.note.useCount}")
                }

                is MemoryStore.SearchHit.TaskHit -> {
                    sb.appendLine("[TASK] ${hit.task.title} (score: ${formatScore(hit.score)})")
                    hit.task.description?.takeIf { it.isNotBlank() }?.let {
                        sb.appendLine("  ${it.take(240)}")
                    }
                    sb.appendLine(
                        "  id=${hit.task.id.value} status=${hit.task.status.name} priority=${hit.task.priority.name}"
                    )
                    hit.task.dueAt?.let { sb.appendLine("  dueAt=$it") }
                    sb.appendLine("  evidence=${hit.task.evidenceRefs.size} useCount=${hit.task.useCount}")
                }

                is MemoryStore.SearchHit.ProfileHit -> {
                    sb.appendLine("[PROFILE] ${hit.profile.profileText.take(240)} (score: ${formatScore(hit.score)})")
                    sb.appendLine("  id=${hit.profile.id.value} owner=${hit.profile.ownerEntityId.value} version=${hit.profile.version}")
                }

                is MemoryStore.SearchHit.EpisodeHit -> {
                    sb.appendLine("[EPISODE] ${hit.episode.situation.take(120)} (score: ${formatScore(hit.score)})")
                    sb.appendLine("  action=${hit.episode.action.take(160)}")
                    sb.appendLine("  result=${hit.episode.result.take(160)}")
                    sb.appendLine("  lesson=${hit.episode.lesson.take(160)}")
                    sb.appendLine("  id=${hit.episode.id.value} evidence=${hit.episode.evidenceRefs.size} useCount=${hit.episode.useCount}")
                }

                is MemoryStore.SearchHit.RunHit -> {
                    sb.appendLine("[RUN] ${hit.run.runType.name} ${hit.run.status.name} (score: ${formatScore(hit.score)})")
                    sb.appendLine("  ${hit.run.summary.take(240)}")
                    sb.appendLine("  id=${hit.run.id.value} prompt=${hit.run.promptName ?: "<none>"}")
                }
            }

            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    private fun formatScore(score: Double): String = String.format("%.2f", score)

    private fun MemorySource.kindLabel(): String =
        when (this) {
            is MemorySource.ChatTurn -> "chat_turn"
            is MemorySource.ToolOutput -> "tool_output"
            is MemorySource.ImportedNote -> "imported_note"
            is MemorySource.ExternalRecord -> "external_record"
        }

    private fun MemorySource.speakerLabel(): String =
        when (this) {
            is MemorySource.ChatTurn -> speakerRole.name
            is MemorySource.ImportedNote -> authorLabel ?: "<none>"
            is MemorySource.ExternalRecord -> authorLabel ?: "<none>"
            else -> "<none>"
        }

    private fun errorResponse(message: String): Map<String, Any> = mapOf(
        "type" to "text",
        "text" to "Error: $message"
    )
}
