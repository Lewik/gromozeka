package com.gromozeka.bot.utils

import com.gromozeka.bot.model.ClaudeLogEntry

/**
 * Utility for deduplicating session messages caused by Claude Code CLI bug
 * where stream-json input format creates duplicate entries in session files.
 */
object SessionDeduplicator {

    /**
     * Remove duplicate entries using chain-based deduplication.
     *
     * Strategy:
     * 1. Build conversation chains using parentUuid links
     * 2. Identify duplicate chains by finding assistant messages with same message.id
     * 3. Keep the longest chain (most complete)
     * 4. Reconnect orphaned messages to canonical chain
     */
    fun deduplicate(entries: List<ClaudeLogEntry>): List<ClaudeLogEntry> {
        // Always keep non-BaseEntry items
        val nonBaseEntries = entries.filterNot { it is ClaudeLogEntry.BaseEntry }
        val baseEntries = entries.filterIsInstance<ClaudeLogEntry.BaseEntry>()

        // Build conversation chains
        val chains = buildConversationChains(baseEntries)

        // Find and remove duplicate chains
        val canonicalChains = removeDuplicateChains(chains)

        // Flatten back to list and sort by timestamp
        val deduplicatedEntries = (nonBaseEntries + canonicalChains.flatten())
            .sortedBy { extractTimestamp(it) }

        return deduplicatedEntries
    }

    /**
     * Build conversation chains by following parentUuid links
     */
    private fun buildConversationChains(entries: List<ClaudeLogEntry.BaseEntry>): List<List<ClaudeLogEntry.BaseEntry>> {
        val entryByUuid = entries.associateBy { it.uuid }
        val visited = mutableSetOf<String>()
        val chains = mutableListOf<List<ClaudeLogEntry.BaseEntry>>()

        // Find chain starting points (entries with parentUuid=null or non-existent parent)
        val chainStarts = entries.filter { entry ->
            entry.parentUuid == null || entryByUuid[entry.parentUuid] == null
        }

        // Build each chain
        for (start in chainStarts) {
            if (start.uuid in visited) continue

            val chain = buildChainFromStart(start, entryByUuid, visited)
            if (chain.isNotEmpty()) {
                chains.add(chain)
            }
        }

        return chains
    }

    /**
     * Build a single chain starting from given entry
     */
    private fun buildChainFromStart(
        start: ClaudeLogEntry.BaseEntry,
        entryByUuid: Map<String, ClaudeLogEntry.BaseEntry>,
        visited: MutableSet<String>,
    ): List<ClaudeLogEntry.BaseEntry> {
        val chain = mutableListOf<ClaudeLogEntry.BaseEntry>()
        var current: ClaudeLogEntry.BaseEntry? = start

        while (current != null && current.uuid !in visited) {
            visited.add(current.uuid)
            chain.add(current)

            // Find next entry in chain (entry that has current as parent)
            current = entryByUuid.values.find { it.parentUuid == current!!.uuid }
        }

        return chain
    }

    /**
     * Remove duplicate chains by keeping the longest one for each duplicate group
     */
    private fun removeDuplicateChains(chains: List<List<ClaudeLogEntry.BaseEntry>>): List<List<ClaudeLogEntry.BaseEntry>> {
        // Group chains by assistant message IDs they contain
        val chainsByAssistantIds = mutableMapOf<Set<String>, MutableList<List<ClaudeLogEntry.BaseEntry>>>()

        for (chain in chains) {
            val assistantIds = chain
                .filterIsInstance<ClaudeLogEntry.AssistantEntry>()
                .mapNotNull { extractAssistantMessageId(it) }
                .toSet()

            if (assistantIds.isNotEmpty()) {
                // Check if this chain overlaps with existing groups
                val overlappingGroups = chainsByAssistantIds.keys.filter { existingIds ->
                    assistantIds.intersect(existingIds).isNotEmpty()
                }

                if (overlappingGroups.isEmpty()) {
                    // New unique chain
                    chainsByAssistantIds[assistantIds] = mutableListOf(chain)
                } else {
                    // Merge with existing group(s)
                    val mergedIds = overlappingGroups.fold(assistantIds) { acc, ids -> acc + ids }
                    val mergedChains = overlappingGroups.fold(mutableListOf(chain)) { acc, ids ->
                        acc.addAll(chainsByAssistantIds[ids]!!)
                        chainsByAssistantIds.remove(ids)
                        acc
                    }
                    chainsByAssistantIds[mergedIds] = mergedChains
                }
            } else {
                // Chain with no assistant messages - keep as is
                chainsByAssistantIds[emptySet()] = mutableListOf(chain)
            }
        }

        // For each group, keep the longest chain
        return chainsByAssistantIds.values.map { duplicateChains ->
            duplicateChains.maxByOrNull { it.size } ?: duplicateChains.first()
        }
    }


    /**
     * Extract assistant message ID for deduplication comparison
     */
    private fun extractAssistantMessageId(entry: ClaudeLogEntry.AssistantEntry): String? {
        return try {
            (entry.message as? ClaudeLogEntry.Message.AssistantMessage)?.id
        } catch (e: Exception) {
            null
        }
    }


    /**
     * Extract timestamp from any log entry type
     */
    private fun extractTimestamp(entry: ClaudeLogEntry): String {
        return when (entry) {
            is ClaudeLogEntry.SummaryEntry -> "0000-00-00T00:00:00.000Z" // Sort summaries first
            is ClaudeLogEntry.FileHistorySnapshotEntry -> entry.snapshot.timestamp
            is ClaudeLogEntry.QueueOperationEntry -> entry.timestamp
            is ClaudeLogEntry.UserEntry -> entry.timestamp
            is ClaudeLogEntry.AssistantEntry -> entry.timestamp
            is ClaudeLogEntry.SystemEntry -> entry.timestamp
        }
    }

    /**
     * Statistics about deduplication results
     */
    data class DeduplicationStats(
        val originalCount: Int,
        val deduplicatedCount: Int,
        val duplicatesRemoved: Int,
        val userDuplicates: Int,
        val assistantDuplicates: Int,
    )

    /**
     * Get statistics about the deduplication operation
     */
    fun getDeduplicationStats(
        original: List<ClaudeLogEntry>,
        deduplicated: List<ClaudeLogEntry>,
    ): DeduplicationStats {
        val originalBaseEntries = original.filterIsInstance<ClaudeLogEntry.BaseEntry>()
        val deduplicatedBaseEntries = deduplicated.filterIsInstance<ClaudeLogEntry.BaseEntry>()

        val originalUserEntries = originalBaseEntries.filterIsInstance<ClaudeLogEntry.UserEntry>()
        val originalAssistantEntries = originalBaseEntries.filterIsInstance<ClaudeLogEntry.AssistantEntry>()

        val deduplicatedUserEntries = deduplicatedBaseEntries.filterIsInstance<ClaudeLogEntry.UserEntry>()
        val deduplicatedAssistantEntries = deduplicatedBaseEntries.filterIsInstance<ClaudeLogEntry.AssistantEntry>()

        val userDuplicates = originalUserEntries.size - deduplicatedUserEntries.size
        val assistantDuplicates = originalAssistantEntries.size - deduplicatedAssistantEntries.size

        return DeduplicationStats(
            originalCount = original.size,
            deduplicatedCount = deduplicated.size,
            duplicatesRemoved = original.size - deduplicated.size,
            userDuplicates = userDuplicates,
            assistantDuplicates = assistantDuplicates
        )
    }
}