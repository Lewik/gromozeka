package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiResponseFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object MemoryStructuredResponseFormats {
    val WriteRouter = AiResponseFormat.JsonSchema(
        name = "memory_write_router",
        description = "Route one target conversation message into a memory write decision.",
        schema = objectSchema(
            "decision" to stringEnumSchema("noop", "direct_structured_write", "note_write", "mixed", "forget_request"),
            "memory_types" to arraySchema(stringEnumSchema("claim", "note", "action_item", "profile", "source")),
            "salience" to numberSchema(),
            "source_policy" to objectSchema(
                "allow_structured_extraction" to booleanSchema(),
                "allow_recall" to booleanSchema(),
                "allow_evidence_hydration" to booleanSchema(),
                "reason" to stringSchema(),
            ),
            "source_search_text" to stringSchema(),
            "reason" to stringSchema(),
        ),
    )

    val ForgetPlanner = AiResponseFormat.JsonSchema(
        name = "memory_forget_planner",
        description = "Plan safe effects of an explicit user forget request.",
        schema = objectSchema(
            "forget_actions" to arraySchema(
                objectSchema(
                    "action" to stringEnumSchema("archive_item", "soft_delete_source", "noop"),
                    "target_type" to stringEnumSchema("source", "claim", "note", "action_item", "episode"),
                    "target_ids" to arraySchema(stringSchema()),
                    "reason" to stringSchema(),
                )
            ),
            "summary" to stringSchema(),
        ),
    )

    val WriteRetrievalPlanner = AiResponseFormat.JsonSchema(
        name = "memory_write_retrieval_plan",
        description = "Plan memory retrieval before updating structured memory.",
        schema = objectSchema(
            "need_retrieval" to booleanSchema(),
            "entity_queries" to arraySchema(stringSchema()),
            "text_queries" to arraySchema(stringSchema()),
            "predicate_hints" to arraySchema(stringSchema()),
            "memory_types" to arraySchema(stringEnumSchema("profile", "claim", "note", "action_item", "source", "entity")),
            "time_filters" to objectSchema(
                "from_iso" to nullableStringSchema(),
                "to_iso" to nullableStringSchema(),
            ),
            "limits" to objectSchema(
                "claims" to integerSchema(),
                "notes" to integerSchema(),
                "action_items" to integerSchema(),
                "sources" to integerSchema(),
            ),
        ),
    )

    val EntityCanonicalizer = AiResponseFormat.JsonSchema(
        name = "memory_entity_canonicalizer",
        description = "Resolve target message mentions into canonical memory entities.",
        schema = objectSchema(
            "operations" to arraySchema(
                objectSchema(
                    "mention" to stringSchema(),
                    "action" to stringEnumSchema("link_existing", "create_new", "add_alias", "noop"),
                    "entity_id" to nullableStringSchema(),
                    "new_entity" to nullableObjectSchema(
                        "entity_type" to stringEnumSchema(
                            "user",
                            "person",
                            "agent",
                            "organization",
                            "project",
                            "repo",
                            "file",
                            "technology",
                            "product",
                            "location",
                            "concept",
                            "document",
                            "conversation",
                            "service",
                            "environment",
                            "other",
                        ),
                        "canonical_name" to stringSchema(),
                        "summary" to nullableStringSchema(),
                    ),
                    "about_file_assertion" to booleanSchema(),
                    "alias_text" to nullableStringSchema(),
                    "confidence" to numberSchema(),
                    "reason" to stringSchema(),
                )
            ),
        ),
    )

    val ClaimExtractor = AiResponseFormat.JsonSchema(
        name = "memory_claim_extractor",
        description = "Extract atomic structured memory claims from one target message.",
        schema = objectSchema(
            "claims" to arraySchema(
                objectSchema(
                    "subject_entity_id" to stringSchema(),
                    "predicate" to stringSchema(),
                    "object_entity_id" to nullableStringSchema(),
                    "object_value_json" to nullableScalarValueSchema(),
                    "normalized_text" to stringSchema(),
                    "context_text" to nullableStringSchema(),
                    "scope_json" to objectSchema(
                        "kind" to stringEnumSchema("global", "conversation", "entity", "environment", "document", "project"),
                        "text" to stringSchema(),
                        "basis" to stringEnumSchema("explicit", "inferred", "summarized", "imported"),
                    ),
                    "qualifiers_json" to objectSchema(),
                    "confidence" to numberSchema(),
                    "importance" to integerSchema(),
                    "valid_from" to nullableStringSchema(),
                    "valid_to" to nullableStringSchema(),
                    "evidence_quote" to nullableStringSchema(),
                    "evidence_kind" to stringEnumSchema("direct", "summarized", "imported", "inferred", "derived_from_note"),
                    "evidence_reason" to stringSchema(),
                    "reason" to stringSchema(),
                )
            ),
        ),
    )

    val NoteConstructor = AiResponseFormat.JsonSchema(
        name = "memory_note_constructor",
        description = "Construct contextual memory note candidates from one target message.",
        schema = objectSchema(
            "notes" to arraySchema(
                objectSchema(
                    "title" to stringSchema(),
                    "summary" to stringSchema(),
                    "note_type" to stringEnumSchema("decision", "direction", "hypothesis", "plan", "lesson", "doc_digest", "context"),
                    "scope_json" to objectSchema(
                        "kind" to stringEnumSchema("global", "conversation", "entity", "environment", "document", "project"),
                        "text" to stringSchema(),
                        "basis" to stringEnumSchema("explicit", "inferred", "summarized", "imported"),
                    ),
                    "entity_refs" to arraySchema(
                        objectSchema(
                            "entity_id" to stringSchema(),
                            "role" to stringEnumSchema("primary", "secondary", "mentioned", "owner", "subject"),
                        )
                    ),
                    "keywords" to arraySchema(stringSchema()),
                    "tags" to arraySchema(stringSchema()),
                    "candidate_claim_hints" to arraySchema(nullableScalarValueSchema()),
                    "confidence" to numberSchema(),
                    "importance" to integerSchema(),
                    "valid_from" to nullableStringSchema(),
                    "valid_to" to nullableStringSchema(),
                    "evidence_quote" to nullableStringSchema(),
                    "evidence_kind" to stringEnumSchema("direct", "summarized", "imported", "inferred", "derived_from_note"),
                    "evidence_reason" to stringSchema(),
                    "rationale" to stringSchema(),
                )
            ),
        ),
    )

    val ClaimReconciler = AiResponseFormat.JsonSchema(
        name = "memory_claim_reconciler",
        description = "Reconcile claim candidates against active memory claims.",
        schema = objectSchema(
            "operations" to arraySchema(
                objectSchema(
                    "action" to stringEnumSchema("insert", "noop", "supersede", "retract", "update"),
                    "candidate_index" to nullableIntegerSchema(),
                    "target_claim_id" to nullableStringSchema(),
                    "canonical_predicate" to nullableStringSchema(),
                    "predicate_family" to nullableStringSchema(),
                    "predicate_description" to nullableStringSchema(),
                    "object_kind" to nullableStringEnumSchema("entity", "string", "number", "boolean", "json"),
                    "cardinality" to nullableStringEnumSchema("single", "multi"),
                    "temporal_policy" to nullableStringEnumSchema("atemporal", "time_scoped", "status_like"),
                    "conflict_policy" to nullableStringEnumSchema("replace", "coexist", "range_split"),
                    "reason" to stringSchema(),
                )
            ),
        ),
    )

    val NoteReconciler = AiResponseFormat.JsonSchema(
        name = "memory_note_reconciler",
        description = "Reconcile note candidates against active memory notes.",
        schema = objectSchema(
            "operations" to arraySchema(
                objectSchema(
                    "action" to stringEnumSchema("insert", "noop", "supersede", "retract", "update"),
                    "candidate_index" to nullableIntegerSchema(),
                    "target_note_id" to nullableStringSchema(),
                    "patch" to nullableObjectSchema(
                        "title" to nullableStringSchema(),
                        "summary" to nullableStringSchema(),
                        "scope_json" to nullableObjectSchema(
                            "kind" to stringEnumSchema("global", "conversation", "entity", "environment", "document", "project"),
                            "text" to stringSchema(),
                            "basis" to stringEnumSchema("explicit", "inferred", "summarized", "imported"),
                        ),
                        "status" to nullableStringEnumSchema("active", "superseded", "retracted", "resolved", "stale", "candidate"),
                        "maturity" to nullableStringEnumSchema("fresh", "stabilizing", "mature", "consolidated"),
                        "maturity_score" to numberSchema(),
                    ),
                    "links_to_create" to arraySchema(
                        objectSchema(
                            "to_note_id" to stringSchema(),
                            "link_type" to stringEnumSchema("supports", "contradicts", "refines", "related", "supersedes", "derived_from"),
                            "link_weight" to numberSchema(),
                        )
                    ),
                    "reason" to stringSchema(),
                )
            ),
        ),
    )

    val ActionItemUpdater = AiResponseFormat.JsonSchema(
        name = "memory_action_item_updater",
        description = "Create or update internal memory action items from explicit commitments.",
        schema = objectSchema(
            "operations" to arraySchema(
                objectSchema(
                    "action" to stringEnumSchema("insert", "update", "close", "cancel", "noop"),
                    "target_action_item_id" to nullableStringSchema(),
                    "action_item" to nullableObjectSchema(
                        "title" to stringSchema(),
                        "description" to nullableStringSchema(),
                        "status" to stringEnumSchema("open", "in_progress", "blocked", "done", "cancelled"),
                        "priority" to stringEnumSchema("low", "normal", "high"),
                        "due_at" to nullableStringSchema(),
                        "scope_json" to objectSchema(
                            "kind" to stringEnumSchema("global", "conversation", "entity", "environment", "document", "project"),
                            "text" to stringSchema(),
                            "basis" to stringEnumSchema("explicit", "inferred", "summarized", "imported"),
                        ),
                        "owner_entity_id" to nullableStringSchema(),
                        "assignee_entity_id" to nullableStringSchema(),
                        "acceptance_criteria" to arraySchema(stringSchema()),
                        "blockers" to arraySchema(stringSchema()),
                        "related_entity_ids" to arraySchema(stringSchema()),
                        "confidence" to numberSchema(),
                        "evidence_quote" to nullableStringSchema(),
                        "evidence_kind" to stringEnumSchema("direct", "summarized", "imported", "inferred", "derived_from_note"),
                        "evidence_reason" to stringSchema(),
                    ),
                    "reason" to stringSchema(),
                )
            ),
        ),
    )

    val NoteConsolidator = AiResponseFormat.JsonSchema(
        name = "memory_note_consolidator",
        description = "Consolidate mature notes into durable memory objects and note lifecycle actions.",
        schema = objectSchema(
            "claim_candidates" to arraySchema(
                objectSchema(
                    "origin_note_id" to stringSchema(),
                    "subject_entity_id" to stringSchema(),
                    "predicate" to stringSchema(),
                    "object_entity_id" to nullableStringSchema(),
                    "object_value_json" to nullableScalarValueSchema(),
                    "normalized_text" to stringSchema(),
                    "context_text" to nullableStringSchema(),
                    "scope_json" to objectSchema(
                        "kind" to stringEnumSchema("global", "conversation", "entity", "environment", "document", "project"),
                        "text" to stringSchema(),
                        "basis" to stringEnumSchema("explicit", "inferred", "summarized", "imported"),
                    ),
                    "qualifiers_json" to objectSchema(),
                    "confidence" to numberSchema(),
                    "importance" to integerSchema(),
                    "valid_from" to nullableStringSchema(),
                    "valid_to" to nullableStringSchema(),
                    "evidence_quote" to nullableStringSchema(),
                    "evidence_kind" to stringEnumSchema("direct", "summarized", "imported", "inferred", "derived_from_note"),
                    "evidence_reason" to stringSchema(),
                    "reason" to stringSchema(),
                )
            ),
            "action_item_actions" to arraySchema(
                objectSchema(
                    "action" to stringEnumSchema("insert", "update", "close", "cancel", "noop"),
                    "target_action_item_id" to nullableStringSchema(),
                    "origin_note_id" to nullableStringSchema(),
                    "action_item" to nullableObjectSchema(
                        "title" to stringSchema(),
                        "description" to nullableStringSchema(),
                        "status" to stringEnumSchema("open", "in_progress", "blocked", "done", "cancelled"),
                        "priority" to stringEnumSchema("low", "normal", "high"),
                        "due_at" to nullableStringSchema(),
                        "scope_json" to objectSchema(
                            "kind" to stringEnumSchema("global", "conversation", "entity", "environment", "document", "project"),
                            "text" to stringSchema(),
                            "basis" to stringEnumSchema("explicit", "inferred", "summarized", "imported"),
                        ),
                        "owner_entity_id" to nullableStringSchema(),
                        "assignee_entity_id" to nullableStringSchema(),
                        "acceptance_criteria" to arraySchema(stringSchema()),
                        "blockers" to arraySchema(stringSchema()),
                        "related_entity_ids" to arraySchema(stringSchema()),
                        "confidence" to numberSchema(),
                        "evidence_quote" to nullableStringSchema(),
                        "evidence_kind" to stringEnumSchema("direct", "summarized", "imported", "inferred", "derived_from_note"),
                        "evidence_reason" to stringSchema(),
                    ),
                    "reason" to stringSchema(),
                )
            ),
            "profile_patch" to nullableObjectSchema(
                "profile_text" to stringSchema(),
                "reason" to stringSchema(),
            ),
            "episode_candidates" to arraySchema(
                objectSchema(
                    "origin_note_id" to stringSchema(),
                    "owner_entity_id" to nullableStringSchema(),
                    "situation" to stringSchema(),
                    "action" to stringSchema(),
                    "result" to stringSchema(),
                    "lesson" to stringSchema(),
                    "tags" to arraySchema(stringSchema()),
                    "success_score" to numberSchema(),
                    "reason" to stringSchema(),
                )
            ),
            "note_actions" to arraySchema(
                objectSchema(
                    "note_id" to stringSchema(),
                    "action" to stringEnumSchema("keep_active", "mark_resolved", "mark_stale", "supersede", "mark_consolidated"),
                    "reason" to stringSchema(),
                )
            ),
            "summary" to stringSchema(),
        ),
    )

    val MemoryRepairPlanner = AiResponseFormat.JsonSchema(
        name = "memory_repair_planner",
        description = "Plan conservative repairs for suspicious memory clusters.",
        schema = objectSchema(
            "repair_actions" to arraySchema(
                objectSchema(
                    "action" to stringEnumSchema("merge_duplicates", "supersede_item", "archive_item", "refresh_profile", "noop"),
                    "target_type" to stringEnumSchema("note", "claim", "action_item", "profile", "entity", "episode"),
                    "target_ids" to arraySchema(stringSchema()),
                    "reason" to stringSchema(),
                )
            ),
            "summary" to stringSchema(),
        ),
    )

    val EntityMaintenancePlanner = AiResponseFormat.JsonSchema(
        name = "memory_entity_maintenance_planner",
        description = "Plan conservative canonical entity merge, alias, and summary maintenance.",
        schema = objectSchema(
            "actions" to arraySchema(
                objectSchema(
                    "action" to stringEnumSchema("merge", "add_alias", "update_summary", "keep_separate", "noop"),
                    "winner_entity_id" to nullableStringSchema(),
                    "loser_entity_ids" to arraySchema(stringSchema()),
                    "target_entity_ids" to arraySchema(stringSchema()),
                    "alias_texts" to arraySchema(stringSchema()),
                    "summary_text" to nullableStringSchema(),
                    "reason" to stringSchema(),
                )
            ),
            "summary" to stringSchema(),
        ),
    )

    val ReadRetrievalPlanner = AiResponseFormat.JsonSchema(
        name = "memory_read_retrieval_plan",
        description = "Plan runtime memory retrieval for the current target user request.",
        schema = objectSchema(
            "need_memory" to booleanSchema(),
            "answer_mode" to stringEnumSchema("factual", "rationale", "action_item", "mixed"),
            "coverage_mode" to stringEnumSchema("minimal", "complete_set"),
            "core_blocks" to arraySchema(stringEnumSchema("profile", "action_items", "session_summary")),
            "retrieval_budget" to objectSchema(
                "claims" to integerSchema(),
                "notes" to integerSchema(),
                "action_items" to integerSchema(),
                "sources" to integerSchema(),
                "episodes" to integerSchema(),
            ),
            "retrieval_requests" to arraySchema(
                objectSchema(
                    "memory_type" to stringEnumSchema("claim", "note", "action_item", "source", "profile", "episode"),
                    "why" to stringSchema(),
                    "query" to stringSchema(),
                    "top_k" to integerSchema(),
                    "filters" to objectSchema(),
                    "preferred_claim_predicates" to arraySchema(stringSchema()),
                    "deprioritized_claim_predicates" to arraySchema(stringSchema()),
                )
            ),
            "require_evidence_fallback" to booleanSchema(),
        ),
    )

    val ReadNeedVerifier = AiResponseFormat.JsonSchema(
        name = "memory_read_need_verifier",
        description = "Verify a no-memory read plan and decide whether recall is actually needed.",
        schema = objectSchema(
            "needs_memory" to booleanSchema(),
            "answer_mode" to stringEnumSchema("factual", "rationale", "action_item", "mixed"),
            "needs_source" to booleanSchema(),
            "query" to stringSchema(),
            "reason" to stringSchema(),
        ),
    )

    val ReadSelector = AiResponseFormat.JsonSchema(
        name = "memory_read_selector",
        description = "Select and rerank retrieved memory candidates for the current target request.",
        schema = objectSchema(
            "selected_items" to arraySchema(
                objectSchema(
                    "item_type" to stringEnumSchema("source", "entity", "claim", "note", "action_item", "profile", "episode", "run"),
                    "item_id" to stringSchema(),
                    "rank" to integerSchema(),
                    "relevance" to stringEnumSchema("direct_answer", "supporting_context", "required_evidence"),
                    "reason" to stringSchema(),
                )
            ),
            "rejected_items" to arraySchema(
                objectSchema(
                    "item_type" to stringEnumSchema("source", "entity", "claim", "note", "action_item", "profile", "episode", "run"),
                    "item_id" to stringSchema(),
                    "reason" to stringSchema(),
                )
            ),
            "summary" to stringSchema(),
        ),
    )
}

private fun objectSchema(vararg properties: Pair<String, JsonElement>): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (name, schema) -> put(name, schema) }
        }
        putJsonArray("required") {
            properties.forEach { (name, _) -> add(JsonPrimitive(name)) }
        }
        put("additionalProperties", false)
    }

private fun nullableObjectSchema(vararg properties: Pair<String, JsonElement>): JsonObject =
    anyOfSchema(objectSchema(*properties), typeSchema("null"))

private fun arraySchema(items: JsonElement): JsonObject =
    buildJsonObject {
        put("type", "array")
        put("items", items)
    }

private fun stringSchema(): JsonObject = typeSchema("string")

private fun numberSchema(): JsonObject = typeSchema("number")

private fun integerSchema(): JsonObject = typeSchema("integer")

private fun booleanSchema(): JsonObject = typeSchema("boolean")

private fun nullableIntegerSchema(): JsonObject =
    buildJsonObject {
        putJsonArray("type") {
            add(JsonPrimitive("integer"))
            add(JsonPrimitive("null"))
        }
    }

private fun nullableStringSchema(): JsonObject =
    buildJsonObject {
        putJsonArray("type") {
            add(JsonPrimitive("string"))
            add(JsonPrimitive("null"))
        }
    }

private fun nullableStringEnumSchema(vararg values: String): JsonObject =
    anyOfSchema(
        stringEnumSchema(*values),
        typeSchema("null"),
    )

private fun nullableScalarValueSchema(): JsonObject =
    anyOfSchema(
        typeSchema("string"),
        typeSchema("number"),
        typeSchema("integer"),
        typeSchema("boolean"),
        typeSchema("null"),
    )

private fun stringEnumSchema(vararg values: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        putJsonArray("enum") {
            values.forEach { add(JsonPrimitive(it)) }
        }
    }

private fun anyOfSchema(vararg schemas: JsonObject): JsonObject =
    buildJsonObject {
        putJsonArray("anyOf") {
            schemas.forEach { add(it) }
        }
    }

private fun typeSchema(type: String): JsonObject =
    buildJsonObject {
        put("type", type)
    }
