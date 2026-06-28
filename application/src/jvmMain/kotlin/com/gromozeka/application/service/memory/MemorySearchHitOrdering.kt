package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore

internal fun MemoryStore.SearchHit.contentStableSortKey(): String =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> source.contentStableSortKey()
        is MemoryStore.SearchHit.EntityHit -> listOf(
            entity.entityType.name,
            entity.normalizedName,
            entity.canonicalName,
            entity.summary.orEmpty(),
            entity.aliases.joinToString(" ") { it.normalizedText },
        ).joinToString("|")
        is MemoryStore.SearchHit.ClaimHit -> listOf(
            claim.predicate,
            claim.predicateFamily.orEmpty(),
            claim.normalizedText,
            claim.contextText.orEmpty(),
            claim.scope.contentStableSortKey(),
            claim.objectValue?.toString().orEmpty(),
        ).joinToString("|")
        is MemoryStore.SearchHit.NoteHit -> listOf(
            note.noteType.name,
            note.title,
            note.summary,
            note.scope.contentStableSortKey(),
            note.keywords.joinToString(" "),
            note.tags.joinToString(" "),
        ).joinToString("|")
        is MemoryStore.SearchHit.ActionItemHit -> listOf(
            actionItem.status.name,
            actionItem.priority.name,
            actionItem.title,
            actionItem.description.orEmpty(),
            actionItem.scope.contentStableSortKey(),
            actionItem.acceptanceCriteria.joinToString(" "),
            actionItem.blockers.joinToString(" "),
        ).joinToString("|")
        is MemoryStore.SearchHit.ProfileHit -> listOf(
            profile.profileText,
            profile.profileJson.toString(),
        ).joinToString("|")
        is MemoryStore.SearchHit.EpisodeHit -> listOf(
            episode.situation,
            episode.action,
            episode.result,
            episode.lesson,
            episode.tags.joinToString(" "),
        ).joinToString("|")
        is MemoryStore.SearchHit.RunHit -> listOf(
            run.runType.name,
            run.status.name,
            run.summary,
            run.inputHash.orEmpty(),
            run.promptName.orEmpty(),
            run.promptVersion.orEmpty(),
        ).joinToString("|")
    }.lowercase()

private fun MemorySource.contentStableSortKey(): String =
    when (this) {
        is MemorySource.ChatTurn -> listOf(
            "chat",
            speakerRole.name,
            authorLabel.orEmpty(),
            contentHash,
            searchText.orEmpty(),
            contentText,
        ).joinToString("|")
        is MemorySource.ToolOutput -> listOf(
            "tool",
            toolName.orEmpty(),
            contentHash,
            searchText.orEmpty(),
            contentText,
        ).joinToString("|")
        is MemorySource.ImportedNote -> listOf(
            "imported",
            importRef.orEmpty(),
            authorLabel.orEmpty(),
            contentHash,
            searchText.orEmpty(),
            contentText,
        ).joinToString("|")
        is MemorySource.ExternalRecord -> listOf(
            "external",
            recordRef,
            authorLabel.orEmpty(),
            contentHash,
            searchText.orEmpty(),
            contentText,
        ).joinToString("|")
    }

private fun MemoryScope.contentStableSortKey(): String =
    when (this) {
        is MemoryScope.Global -> listOf("global", text, basis.name)
        is MemoryScope.Project -> listOf("project", text, basis.name)
        is MemoryScope.Conversation -> listOf("conversation", text, basis.name)
        is MemoryScope.Entity -> listOf("entity", text, basis.name)
        is MemoryScope.Environment -> listOf("environment", environment, text, basis.name)
        is MemoryScope.Document -> listOf("document", documentRef, text, basis.name)
    }.joinToString("|")
