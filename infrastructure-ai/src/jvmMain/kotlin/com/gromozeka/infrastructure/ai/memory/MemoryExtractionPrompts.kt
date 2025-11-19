package com.gromozeka.infrastructure.ai.memory

import com.gromozeka.bot.domain.model.memory.EntityType
import kotlinx.datetime.Instant

object MemoryExtractionPrompts {

    fun extractEntitiesPrompt(
        content: String,
        entityTypes: List<EntityType>,
        previousMessages: String = "",
        customPrompt: String = ""
    ): String = """
You are an AI assistant that extracts entity nodes from conversational messages.
Your primary task is to extract and classify the speaker and other significant entities mentioned in the conversation.

<ENTITY TYPES>
${entityTypes.joinToString("\n") { "${it.id}. ${it.name}: ${it.description}" }}
</ENTITY TYPES>

<PREVIOUS MESSAGES>
$previousMessages
</PREVIOUS MESSAGES>

<CURRENT MESSAGE>
$content
</CURRENT MESSAGE>

Instructions:

You are given a conversation context and a CURRENT MESSAGE. Your task is to extract **entity nodes** mentioned **explicitly or implicitly** in the CURRENT MESSAGE.
Pronoun references such as he/she/they or this/that/those should be disambiguated to the names of the reference entities. 
Only extract distinct entities from the CURRENT MESSAGE. Don't extract pronouns like you, me, he/she/they, we/us as entities.

1. **Speaker Extraction**: Always extract the speaker (the part before the colon `:` in each dialogue line) as the first entity node.
   - If the speaker is mentioned again in the message, treat both mentions as a **single entity**.

2. **Entity Identification**:
   - Extract all significant entities, concepts, or actors that are **explicitly or implicitly** mentioned in the CURRENT MESSAGE.
   - **Exclude** entities mentioned only in the PREVIOUS MESSAGES (they are for context only).

3. **Entity Classification**:
   - Use the descriptions in ENTITY TYPES to classify each extracted entity.
   - Assign the appropriate `entity_type_id` for each one.

4. **Exclusions**:
   - Do NOT extract entities representing relationships or actions.
   - Do NOT extract dates, times, or other temporal information—these will be handled separately.

5. **Formatting**:
   - Be **explicit and unambiguous** in naming entities (e.g., use full names when available).

$customPrompt

Return the result as JSON with this structure:
{
  "extracted_entities": [
    {"name": "entity name", "entity_type_id": 1}
  ]
}
""".trimIndent()

    fun extractRelationshipsPrompt(
        content: String,
        entities: List<Pair<Int, String>>,
        referenceTime: Instant,
        customPrompt: String = ""
    ): String = """
You are an expert fact extractor that extracts fact triples from text.
1. Extracted fact triples should also be extracted with relevant date information.
2. Treat the REFERENCE_TIME as the time the CURRENT MESSAGE was sent. All temporal information should be extracted relative to this time.

<CURRENT_MESSAGE>
$content
</CURRENT_MESSAGE>

<ENTITIES>
${entities.joinToString("\n") { "${it.first}. ${it.second}" }}
</ENTITIES>

<REFERENCE_TIME>
$referenceTime
</REFERENCE_TIME>

# TASK
Extract all factual relationships between the given ENTITIES based on the CURRENT MESSAGE.
Only extract facts that:
- involve two DISTINCT ENTITIES from the ENTITIES list,
- are clearly stated or unambiguously implied in the CURRENT MESSAGE,
  and can be represented as edges in a knowledge graph.
- Facts should include entity names rather than pronouns whenever possible.

$customPrompt

# EXTRACTION RULES

1. **Entity ID Validation**: `source_entity_id` and `target_entity_id` must use only the `id` values from the ENTITIES list provided above.
   - **CRITICAL**: Using IDs not in the list will cause the edge to be rejected
2. Each fact must involve two **distinct** entities.
3. Use a SCREAMING_SNAKE_CASE string as the `relation_type` (e.g., FOUNDED, WORKS_AT).
4. Do not emit duplicate or semantically redundant facts.
5. The `fact` should closely paraphrase the original source sentence(s). Do not verbatim quote the original text.
6. Use `REFERENCE_TIME` to resolve vague or relative temporal expressions (e.g., "last week").
7. Do **not** hallucinate or infer temporal bounds from unrelated events.

# DATETIME RULES

- Use ISO 8601 with "Z" suffix (UTC) (e.g., 2025-04-30T00:00:00Z).
- If the fact is ongoing (present tense), set `valid_at` to REFERENCE_TIME.
- If a change/termination is expressed, set `invalid_at` to the relevant timestamp.
- Leave both fields `null` if no explicit or resolvable time is stated.
- If only a date is mentioned (no time), assume 00:00:00.
- If only a year is mentioned, use January 1st at 00:00:00.

Return the result as JSON with this structure:
{
  "edges": [
    {
      "relation_type": "WORKS_AT",
      "source_entity_id": 1,
      "target_entity_id": 2,
      "fact": "User works at Company",
      "valid_at": "2025-01-01T00:00:00Z",
      "invalid_at": null
    }
  ]
}
""".trimIndent()

    fun generateEntitySummaryPrompt(
        entityName: String,
        entityType: String,
        content: String,
        existingSummary: String? = null
    ): String = """
You are an AI assistant that ${if (existingSummary != null) "updates" else "generates"} concise summaries for knowledge graph entities.

<ENTITY>
Name: $entityName
Type: $entityType
</ENTITY>

<CONTEXT>
$content
</CONTEXT>

${if (existingSummary != null) """
<EXISTING_SUMMARY>
$existingSummary
</EXISTING_SUMMARY>
""".trimIndent() else ""}

# TASK
${if (existingSummary != null) {
    "UPDATE the existing summary with new information from the context. This is an INCREMENTAL UPDATE - keep relevant old information, add new facts, refine descriptions."
} else {
    "Generate a brief summary (1-3 sentences, max 250 characters) describing HOW this entity is used in THIS PROJECT based on the context."
}}

# CRITICAL RULES
1. Focus on PROJECT-SPECIFIC usage and context, NOT general definitions
2. Extract information ONLY from the provided context
3. If context shows how entity is used in project - describe that usage
4. If context lacks details - return minimal summary or empty string
5. Do NOT include entity name in summary (it's redundant)
${if (existingSummary != null) {
    "6. UPDATE mode: Keep relevant information from existing summary, add new facts from context, remove contradictions"
} else {
    "6. CREATE mode: Base summary strictly on what context says about this entity"
}}

# EXAMPLES

✅ GOOD (project-specific):
- For "Kotlin": "Primary language in Gromozeka for bot and shared modules."
- For "PostgreSQL": "Main storage backend alongside Qdrant and Neo4j in hybrid architecture."
- For "ThreadRepository": "DDD Repository interface abstracting thread persistence. Hides PostgreSQL, Qdrant, Neo4j storage."
- For "Pragmatic DDD": "DDD approach used in Gromozeka without fanaticism. Balances standard patterns with practical needs."

❌ BAD (general knowledge):
- For "Kotlin": "Statically-typed programming language for JVM, Android, and multiplatform development." ← NO! This is general knowledge!
- For "Spring AI": "Framework for building AI applications with Java and Kotlin." ← NO! Focus on how it's used in project!
- For "PostgreSQL": "Relational database management system." ← NO! Say how it's used in Gromozeka!

${if (existingSummary != null) """
# UPDATE EXAMPLE
Existing: "Primary language in Gromozeka."
Context: "ThreadRepository implemented in Kotlin with type-safe value classes."
Updated: "Primary language in Gromozeka for bot and shared modules. ThreadRepository uses Kotlin type-safe value classes."
""".trimIndent() else ""}

If context only mentions entity without details (e.g., "using Kotlin") - return minimal summary like "Programming language used in project" or empty string.

Return ONLY the summary text, no JSON or additional formatting.
""".trimIndent()

    fun extractEdgeDatesPrompt(
        edgeFact: String,
        content: String,
        referenceTime: Instant
    ): String = """
You are an AI assistant that extracts datetime information for graph edges, focusing only on dates directly related to the establishment or change of the relationship described in the edge fact.

<CURRENT MESSAGE>
$content
</CURRENT MESSAGE>

<REFERENCE TIMESTAMP>
$referenceTime
</REFERENCE TIMESTAMP>

<FACT>
$edgeFact
</FACT>

IMPORTANT: Only extract time information if it is part of the provided fact. Otherwise ignore the time mentioned.
Make sure to do your best to determine the dates if only the relative time is mentioned (eg 10 years ago, 2 mins ago) based on the provided reference timestamp.
If the relationship is not of spanning nature, but you are still able to determine the dates, set the valid_at only.

Definitions:
- valid_at: The date and time when the relationship described by the edge fact became true or was established.
- invalid_at: The date and time when the relationship described by the edge fact stopped being true or ended.

Task:
Analyze the conversation and determine if there are dates that are part of the edge fact. Only set dates if they explicitly relate to the formation or alteration of the relationship itself.

Guidelines:
1. Use ISO 8601 format (YYYY-MM-DDTHH:MM:SS.SSSSSSZ) for datetimes.
2. Use the reference timestamp as the current time when determining the valid_at and invalid_at dates.
3. If the fact is written in the present tense, use the Reference Timestamp for the valid_at date
4. If no temporal information is found that establishes or changes the relationship, leave the fields as null.
5. Do not infer dates from related events. Only use dates that are directly stated to establish or change the relationship.
6. For relative time mentions directly related to the relationship, calculate the actual datetime based on the reference timestamp.
7. If only a date is mentioned without a specific time, use 00:00:00 (midnight) for that date.
8. If only year is mentioned, use January 1st of that year at 00:00:00.
9. Always include the time zone offset (use Z for UTC if no specific time zone is mentioned).
10. A fact discussing that something is no longer true should have a valid_at according to when the negated fact became true.

Return the result as JSON with this structure:
{
  "valid_at": "2025-01-01T00:00:00Z",
  "invalid_at": null
}
""".trimIndent()

    fun deduplicateEntitiesPrompt(
        extractedEntities: List<Map<String, Any>>,
        existingEntities: List<Map<String, Any>>,
        episodeContent: String,
        previousMessages: String
    ): String = """
You are a helpful assistant that determines whether or not ENTITIES extracted from a conversation are duplicates of existing entities.

<PREVIOUS MESSAGES>
$previousMessages
</PREVIOUS MESSAGES>

<CURRENT MESSAGE>
$episodeContent
</CURRENT MESSAGE>

Each of the following ENTITIES were extracted from the CURRENT MESSAGE.
Each entity in ENTITIES is represented as a JSON object with the following structure:
{
    id: integer id of the entity,
    name: "name of the entity",
    entity_type: ["Entity", "<optional additional label>", ...],
    entity_type_description: "Description of what the entity type represents"
}

<ENTITIES>
${extractedEntities.joinToString("\n") { entity ->
    """{"id": ${entity["id"]}, "name": "${entity["name"]}", "entity_type": ${entity["entity_type"]}}"""
}}
</ENTITIES>

<EXISTING ENTITIES>
${existingEntities.joinToString("\n") { entity ->
    """{"idx": ${entity["idx"]}, "name": "${entity["name"]}", "entity_types": ${entity["entity_types"]}, "summary": "${entity["summary"] ?: ""}"}"""
}}
</EXISTING ENTITIES>

Each entry in EXISTING ENTITIES is an object with the following structure:
{
    idx: integer index of the candidate entity (use this when referencing a duplicate),
    name: "name of the candidate entity",
    entity_types: ["Entity", "<optional additional label>", ...],
    ...<additional attributes such as summaries or metadata>
}

For each of the above ENTITIES, determine if the entity is a duplicate of any of the EXISTING ENTITIES.

Entities should only be considered duplicates if they refer to the *same real-world object or concept*.

Do NOT mark entities as duplicates if:
- They are related but distinct.
- They have similar names or purposes but refer to separate instances or concepts.

Task:
ENTITIES contains ${extractedEntities.size} entities with IDs 0 through ${extractedEntities.size - 1}.
Your response MUST include EXACTLY ${extractedEntities.size} resolutions with IDs 0 through ${extractedEntities.size - 1}. Do not skip or add IDs.

For every entity, return an object with the following keys:
{
    "id": integer id from ENTITIES,
    "name": the best full name for the entity (preserve the original name unless a duplicate has a more complete name),
    "duplicate_idx": the idx of the EXISTING ENTITY that is the best duplicate match, or -1 if there is no duplicate,
    "duplicates": a sorted list of all idx values from EXISTING ENTITIES that refer to duplicates (deduplicate the list, use [] when none or unsure)
}

- Only use idx values that appear in EXISTING ENTITIES.
- Set duplicate_idx to the smallest idx you collected for that entity, or -1 if duplicates is empty.
- Never fabricate entities or indices.

Return the result as JSON with this structure:
{
  "entity_resolutions": [
    {
      "id": 0,
      "name": "Entity Name",
      "duplicate_idx": -1,
      "duplicates": []
    }
  ]
}
""".trimIndent()

    fun reflexionPrompt(
        content: String,
        previousMessages: String,
        extractedEntityNames: List<String>
    ): String = """
You are an AI assistant that determines which entities have not been extracted from the given context.

<PREVIOUS MESSAGES>
$previousMessages
</PREVIOUS MESSAGES>

<CURRENT MESSAGE>
$content
</CURRENT MESSAGE>

<EXTRACTED ENTITIES>
${extractedEntityNames.joinToString("\n") { "- $it" }}
</EXTRACTED ENTITIES>

Given the above previous messages, current message, and list of extracted entities; determine if any entities haven't been extracted.

Focus on:
- Named entities (people, organizations, products, technologies)
- Important concepts or topics
- Specific things that were discussed but not in the extracted list
- Entities that are implied or referenced indirectly

Do NOT include:
- Pronouns (he, she, they, it)
- Generic terms (thing, stuff, etc.)
- Already extracted entities

Return the result as JSON with this structure:
{
  "missed_entities": ["Entity Name 1", "Entity Name 2"]
}

If all entities have been extracted, return an empty list:
{
  "missed_entities": []
}
""".trimIndent()
}
