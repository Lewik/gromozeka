package com.gromozeka.bot.services.memory.graph

import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import java.security.MessageDigest
import kotlin.math.min

data class EntityCandidate(
    val uuid: String,
    val name: String,
    val normalizedName: String,
    val summary: String,
    val entityType: String,
    val shingles: Set<String>,
    val signature: List<Int>,
    val bands: List<List<Int>>
)

data class DedupMatch(
    val extractedIndex: Int,
    val candidateUuid: String,
    val matchType: MatchType,
    val confidence: Double
)

enum class MatchType {
    EXACT,
    FUZZY,
    LLM
}

object DedupHelpers {
    private const val NAME_ENTROPY_THRESHOLD = 1.5
    private const val MIN_NAME_LENGTH = 6
    private const val MIN_TOKEN_COUNT = 2
    private const val FUZZY_JACCARD_THRESHOLD = 0.9
    private const val MINHASH_PERMUTATIONS = 32
    private const val MINHASH_BAND_SIZE = 4

    fun normalize(name: String): String {
        return name.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    fun shingles(normalizedName: String): Set<String> {
        val cleaned = normalizedName.replace(" ", "")
        if (cleaned.length < 2) {
            return if (cleaned.isEmpty()) emptySet() else setOf(cleaned)
        }
        return (0..cleaned.length - 3).map { i ->
            cleaned.substring(i, i + 3)
        }.toSet()
    }

    fun minhashSignature(shingles: Set<String>): List<Int> {
        if (shingles.isEmpty()) return emptyList()

        val signature = mutableListOf<Int>()
        for (seed in 0 until MINHASH_PERMUTATIONS) {
            val minHash = shingles.minOf { shingle ->
                hashShingle(shingle, seed)
            }
            signature.add(minHash)
        }
        return signature
    }

    fun lshBands(signature: List<Int>): List<List<Int>> {
        val bands = mutableListOf<List<Int>>()
        for (start in signature.indices step MINHASH_BAND_SIZE) {
            val band = signature.subList(
                start,
                min(start + MINHASH_BAND_SIZE, signature.size)
            )
            if (band.size == MINHASH_BAND_SIZE) {
                bands.add(band)
            }
        }
        return bands
    }

    fun jaccardSimilarity(set1: Set<String>, set2: Set<String>): Double {
        if (set1.isEmpty() && set2.isEmpty()) return 1.0
        if (set1.isEmpty() || set2.isEmpty()) return 0.0

        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size

        return intersection.toDouble() / union.toDouble()
    }

    suspend fun collectCandidates(
        neo4jDriver: Driver,
        groupId: String,
        extractedNames: List<String>
    ): List<EntityCandidate> {
        val candidates = mutableListOf<EntityCandidate>()

        neo4jDriver.session().use { session ->
            for (name in extractedNames) {
                val normalized = normalize(name)

                val result = session.run(
                    """
                    CALL db.index.fulltext.queryNodes('memory_object_index', ${'$'}query)
                    YIELD node, score
                    WHERE node.group_id = ${'$'}groupId
                    RETURN node.uuid as uuid,
                           node.name as name,
                           node.summary as summary,
                           node.entity_type as entityType
                    LIMIT 20
                    """.trimIndent(),
                    Values.parameters(
                        "query", "$normalized~",
                        "groupId", groupId
                    )
                )

                while (result.hasNext()) {
                    val record = result.next()
                    val candidateName = record["name"].asString()
                    val candidateNormalized = normalize(candidateName)
                    val candidateShingles = shingles(candidateNormalized)
                    val candidateSignature = minhashSignature(candidateShingles)
                    val candidateBands = lshBands(candidateSignature)

                    candidates.add(
                        EntityCandidate(
                            uuid = record["uuid"].asString(),
                            name = candidateName,
                            normalizedName = candidateNormalized,
                            summary = record["summary"].asString(""),
                            entityType = record["entityType"].asString(""),
                            shingles = candidateShingles,
                            signature = candidateSignature,
                            bands = candidateBands
                        )
                    )
                }
            }
        }

        return candidates
    }

    fun deterministicMatching(
        extractedNames: List<String>,
        candidates: List<EntityCandidate>
    ): Map<Int, DedupMatch> {
        val matches = mutableMapOf<Int, DedupMatch>()

        extractedNames.forEachIndexed { index, extractedName ->
            val normalized = normalize(extractedName)
            val extractedShingles = shingles(normalized)
            val extractedSignature = minhashSignature(extractedShingles)
            val extractedBands = lshBands(extractedSignature)

            for (candidate in candidates) {
                if (candidate.normalizedName == normalized) {
                    matches[index] = DedupMatch(
                        extractedIndex = index,
                        candidateUuid = candidate.uuid,
                        matchType = MatchType.EXACT,
                        confidence = 1.0
                    )
                    break
                }

                val hasMatchingBand = extractedBands.any { extractedBand ->
                    candidate.bands.any { candidateBand ->
                        extractedBand == candidateBand
                    }
                }

                if (hasMatchingBand) {
                    val jaccard = jaccardSimilarity(extractedShingles, candidate.shingles)
                    if (jaccard >= FUZZY_JACCARD_THRESHOLD) {
                        if (!matches.containsKey(index) || matches[index]!!.confidence < jaccard) {
                            matches[index] = DedupMatch(
                                extractedIndex = index,
                                candidateUuid = candidate.uuid,
                                matchType = MatchType.FUZZY,
                                confidence = jaccard
                            )
                        }
                    }
                }
            }
        }

        return matches
    }

    private fun hashShingle(shingle: String, seed: Int): Int {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$shingle:$seed".toByteArray()
        val hash = md.digest(input)

        return ((hash[0].toInt() and 0xFF) shl 24) or
               ((hash[1].toInt() and 0xFF) shl 16) or
               ((hash[2].toInt() and 0xFF) shl 8) or
               (hash[3].toInt() and 0xFF)
    }
}
