package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryActionItem

internal object MemorySearchScorer {
    fun hybridScore(
        lexicalOrTypedScore: Double,
        vectorSimilarity: Double?,
    ): Double =
        lexicalOrTypedScore + vectorBoost(vectorSimilarity)

    fun vectorBoost(vectorSimilarity: Double?): Double =
        (vectorSimilarity?.coerceIn(0.0, 1.0) ?: 0.0) * 1.25

    fun sourceScore(
        query: String,
        source: MemorySource,
    ): Double =
        lexicalScore(
            query,
            weighted(source.contentText, 1.0),
            weighted(source.searchText.orEmpty(), 0.9),
        )

    fun entityScore(
        query: String,
        entity: MemoryEntity,
    ): Double =
        lexicalScore(
            query,
            weighted(entity.canonicalName, 1.5),
            weighted(entity.normalizedName, 1.3),
            weighted(entity.entityType.name, 0.4),
            weighted(entity.summary.orEmpty(), 0.8),
            weighted(entity.aliases.joinToString(" ") { "${it.text} ${it.normalizedText}" }, 1.1),
        )

    fun claimScore(
        query: String,
        claim: MemoryClaim,
        subjectEntity: MemoryEntity?,
        objectEntity: MemoryEntity?,
    ): Double =
        typedScore(
            query = query,
            base = 0.15,
            importance = claim.importance,
            confidence = claim.confidence,
            weighted(claim.normalizedText, 1.5),
            weighted(claim.contextText.orEmpty(), 0.9),
            weighted(claim.predicate, 1.0),
            weighted(claim.predicateFamily.orEmpty(), 0.7),
            weighted(subjectEntity?.searchText().orEmpty(), 1.2),
            weighted(objectEntity?.searchText().orEmpty(), 1.2),
            weighted(claim.objectValue?.toString().orEmpty(), 0.9),
            weighted(claim.scope.text, 0.4),
            weighted(claim.qualifiers.toString(), 0.3),
        )

    fun noteScore(
        query: String,
        note: MemoryNote,
        linkedEntities: List<MemoryEntity>,
    ): Double =
        typedScore(
            query = query,
            base = 0.10,
            importance = note.importance,
            confidence = note.confidence,
            weighted(note.title, 1.5),
            weighted(note.summary, 1.4),
            weighted(note.noteType.name, 0.7),
            weighted(note.keywords.joinToString(" "), 1.1),
            weighted(note.tags.joinToString(" "), 1.0),
            weighted(linkedEntities.joinToString(" ") { it.searchText() }, 1.2),
            weighted(note.scope.text, 0.4),
            weighted(note.candidateClaimHints.toString(), 0.4),
        )

    fun taskScore(
        query: String,
        actionItem: MemoryActionItem,
        linkedEntities: List<MemoryEntity>,
    ): Double =
        typedScore(
            query = query,
            base = 0.10,
            importance = when (actionItem.priority) {
                MemoryActionItem.Priority.HIGH -> 8
                MemoryActionItem.Priority.NORMAL -> 5
                MemoryActionItem.Priority.LOW -> 3
            },
            confidence = actionItem.confidence,
            weighted(actionItem.title, 1.5),
            weighted(actionItem.description.orEmpty(), 1.2),
            weighted(actionItem.status.name, 0.5),
            weighted(actionItem.priority.name, 0.4),
            weighted(actionItem.acceptanceCriteria.joinToString(" "), 1.0),
            weighted(actionItem.blockers.joinToString(" "), 0.9),
            weighted(linkedEntities.joinToString(" ") { it.searchText() }, 1.2),
            weighted(actionItem.scope.text, 0.4),
        )

    fun profileScore(
        query: String,
        profile: MemoryProfile,
        ownerEntity: MemoryEntity?,
    ): Double =
        typedScore(
            query = query,
            base = 0.20,
            importance = 8,
            confidence = 1.0,
            weighted(profile.profileText, 1.4),
            weighted(profile.profileJson.toString(), 0.8),
            weighted(ownerEntity?.searchText().orEmpty(), 1.2),
        )

    fun episodeScore(
        query: String,
        episode: MemoryEpisode,
        ownerEntity: MemoryEntity?,
    ): Double =
        typedScore(
            query = query,
            base = 0.08,
            importance = ((episode.successScore ?: 0.5) * 10).toInt(),
            confidence = episode.successScore ?: 0.5,
            weighted(episode.lesson, 1.6),
            weighted(episode.situation, 1.4),
            weighted(episode.action, 1.2),
            weighted(episode.result, 1.0),
            weighted(episode.tags.joinToString(" "), 0.9),
            weighted(ownerEntity?.searchText().orEmpty(), 1.2),
        )

    fun runScore(
        query: String,
        run: MemoryRun,
    ): Double =
        lexicalScore(
            query,
            weighted(run.summary, 1.0),
            weighted(run.runType.name, 0.7),
            weighted(run.status.name, 0.4),
        )

    private fun typedScore(
        query: String,
        base: Double,
        importance: Int,
        confidence: Double,
        vararg fields: WeightedText,
    ): Double {
        if (query.isBlank()) {
            return base + importance.coerceIn(0, 10) / 100.0 + confidence.coerceIn(0.0, 1.0) / 20.0
        }

        val relevance = lexicalScore(query, *fields)
        if (relevance <= 0.0) return 0.0

        return base + relevance + importance.coerceIn(0, 10) / 100.0 + confidence.coerceIn(0.0, 1.0) / 20.0
    }

    private fun lexicalScore(
        query: String,
        vararg fields: WeightedText,
    ): Double {
        val queryTokens = query.memorySearchTokens(removeStopWords = true).distinct()
        if (queryTokens.isEmpty()) return 0.0

        val searchableFields = fields
            .filter { it.text.isNotBlank() && it.weight > 0.0 }
            .map { field ->
                IndexedField(
                    text = field.text.normalizedSearchText(),
                    tokens = field.text.memorySearchTokens(removeStopWords = false),
                    weight = field.weight,
                )
            }
            .filter { it.tokens.isNotEmpty() }
        if (searchableFields.isEmpty()) return 0.0

        val tokenMatches = queryTokens.map { queryToken ->
            searchableFields.maxOf { field ->
                field.bestTokenMatch(queryToken) * field.weight
            }.coerceAtMost(1.6)
        }
        val matchedTokenCount = tokenMatches.count { it > 0.0 }
        val coverage = tokenMatches.sum() / queryTokens.size

        val phraseBonus = searchableFields.maxOf { field ->
            query.normalizedSearchText().takeIf { it.length >= 8 && it in field.text }?.let { 0.30 * field.weight } ?: 0.0
        }.coerceAtMost(0.30)

        val adjacencyBonus = searchableFields.maxOf { field ->
            queryTokens.windowed(2)
                .count { (first, second) -> field.containsAdjacent(first, second) }
                .toDouble()
                .let { matches -> if (queryTokens.size > 1) matches / (queryTokens.size - 1) else 0.0 } * 0.25 * field.weight
        }.coerceAtMost(0.25)

        if (queryTokens.size >= 3 && matchedTokenCount < 2 && phraseBonus == 0.0 && adjacencyBonus == 0.0) {
            return 0.0
        }

        return coverage + phraseBonus + adjacencyBonus
    }

    private fun IndexedField.bestTokenMatch(queryToken: String): Double {
        if (tokens.any { it.matchesSearchToken(queryToken) }) return 1.0
        if (queryToken.length >= 4 && tokens.any { it.hasPrefixAffinity(queryToken) }) return 0.65
        return 0.0
    }

    private fun IndexedField.containsAdjacent(
        first: String,
        second: String,
    ): Boolean =
        tokens.windowed(2).any { (left, right) ->
            left.matchesSearchToken(first) && right.matchesSearchToken(second)
        }

    private fun MemoryEntity.searchText(): String =
        buildString {
            append(canonicalName)
            append(' ')
            append(normalizedName)
            append(' ')
            append(entityType.name)
            summary?.let {
                append(' ')
                append(it)
            }
            aliases.forEach {
                append(' ')
                append(it.text)
                append(' ')
                append(it.normalizedText)
            }
        }

    private fun String.matchesSearchToken(other: String): Boolean {
        if (this == other) return true
        return tokenVariants().any { it in other.tokenVariants() }
    }

    private fun String.hasPrefixAffinity(other: String): Boolean =
        startsWith(other) || other.startsWith(this)

    private fun String.tokenVariants(): Set<String> =
        buildSet {
            add(this@tokenVariants)
            if (length > 4 && endsWith("s")) add(dropLast(1))
            if (length > 5 && endsWith("ies")) add(dropLast(3) + "y")
            if (length > 5 && endsWith("ing")) add(dropLast(3))
            if (length > 4 && endsWith("ed")) add(dropLast(2))
        }

    private fun String.memorySearchTokens(removeStopWords: Boolean): List<String> {
        val expanded = expandCamelCase()
        val tokens = "$this $expanded"
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toList()
        if (!removeStopWords) return tokens

        val filtered = tokens.filterNot { it in stopWords }
        return filtered.ifEmpty { tokens }
    }

    private fun String.normalizedSearchText(): String =
        expandCamelCase()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.expandCamelCase(): String =
        replace(Regex("([\\p{Ll}\\p{N}])([\\p{Lu}])"), "$1 $2")

    private fun weighted(
        text: String,
        weight: Double,
    ): WeightedText = WeightedText(text, weight)

    private data class WeightedText(
        val text: String,
        val weight: Double,
    )

    private data class IndexedField(
        val text: String,
        val tokens: List<String>,
        val weight: Double,
    )

    private val stopWords = setOf(
        "about",
        "after",
        "all",
        "and",
        "are",
        "before",
        "did",
        "does",
        "for",
        "from",
        "how",
        "into",
        "its",
        "our",
        "should",
        "that",
        "the",
        "this",
        "was",
        "were",
        "what",
        "when",
        "where",
        "which",
        "with",
        "без",
        "был",
        "была",
        "были",
        "где",
        "для",
        "его",
        "еще",
        "как",
        "когда",
        "мне",
        "нам",
        "нас",
        "наш",
        "она",
        "они",
        "про",
        "что",
        "чем",
        "это",
    )
}
