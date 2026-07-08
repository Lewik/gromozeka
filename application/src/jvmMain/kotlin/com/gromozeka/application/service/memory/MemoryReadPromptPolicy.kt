package com.gromozeka.application.service.memory

internal object MemoryReadPromptPolicy {
    fun selectorCountAndCategoryRules(): String =
        """
        - For count, list, "how many", and COMPLETE_SET questions, select the complete candidate set needed to avoid an incomplete answer. Do not require exact wording when typed semantics and evidence show category fit.
        ${typedCountAndCategoryRules()}
        - When a candidate supplies only one operand for a derived answer, keep it if another selected candidate can supply the complementary operand. Rejecting a baseline, anchor date, boundary event, or source date can make the answer impossible.
        - For ambiguous imported-source dates, keep otherwise matching first-person evidence when the source/session date and local wording make it plausible and no candidate explicitly places it outside scope. Preserve the uncertainty for the answerer instead of turning it into a hard rejection.
        """.trimIndent()

    fun answerCountAndCategoryRules(): String =
        """
            For numeric count/list answers, first form the set of counted items from retrieved memory and make the final number match that set size. If a selected ranked reference is a plausible counted item, include it or explicitly exclude it; do not silently ignore it. An empty counted set is not evidence for zero by itself; answer zero only when retrieved memory explicitly states none/zero for the requested scope or provides a closed complete inventory for that exact scope. Otherwise say memory is insufficient or the requested item was not mentioned, and do not put a concrete zero count in the final answer.
            ${typedCountAndCategoryRules().prependIndent("            ")}
            For derived numeric answers, use compatible explicit operands from retrieved memory. Include baselines, later values, aggregate deltas, anchor dates, source dates, and boundary events in reasoning before computing the final answer. If any required operand is missing or contradictory, set sufficiency to insufficient or conflicting.
            For imported-source date uncertainty, count otherwise matching first-person evidence only when the selected source/session date and local wording make it plausible and no selected memory explicitly places the event outside scope. State the uncertainty in reasoning instead of hiding it.
        """.trimIndent()

    fun answerSourceEvidenceRules(): String =
        """
            Treat a selected source transcript or document as a coherent local evidence frame. If selected typed memory identifies the asked event/item but lacks one requested detail, and selected source evidence gives that detail in the same local topic or surrounding exchange, answer from that same-source frame.
            If selected typed memory gives an older value and a selected later source explicitly gives a different value for the same current/status/default/approval/limit/metric slot, treat the later source as the update evidence instead of silently preferring the older typed value.
            For source transcripts, preserve local dialogue topic continuity: when the user reports the asked action as an example while the surrounding turns are about a named store, app, service, venue, program, or source, and no competing named frame is introduced before the topic changes, that named frame can supply the missing place/source/medium for the action.
            Do not use this to stitch together different sources, different named targets, different events/items, or competing anchors/values. If the source only mentions a related topic without preserving the same asked target, answer insufficient.
        """.trimIndent()

    fun answerDerivationAndConsistencyRules(): String =
        """
            For ordering, first/second/latest/earliest, and comparison questions, compare every selected dated or relative-time candidate for the named alternatives before choosing the answer. The final answer must be the item selected by that comparison, not the first lexical match or the most recent evidence block.
            For questions asking for the order or list of a category of entities, such as providers, organizations, people, places, products, books, venues, or tools, distinguish category members from event occurrences. Unless the user explicitly asks for every occurrence, event, visit, trip, or instance, list each distinct category member once and order members by their earliest qualifying occurrence. Keep repeated occurrences only in reasoning or support.
            For latest/most-recent questions about when the user started, began, first used, acquired, or got access to something, compare actual lifecycle/use/acquisition events for that requested kind. A later recommendation, preference, choice, or access-path decision is not a later started-using event unless selected memory explicitly says the user actually started, used, acquired, or got access to that option.
            For chained relative-time, date-difference, duration, and lead-time questions, identify each operand in reasoning before answering: target event time, anchor event time, source/question date, offsets such as "three months in advance", and elapsed intervals such as "two months ago". A lead time such as "three months in advance" or "two weeks before" is not itself an "ago" answer unless its anchor is the question/current date. When the question asks how long ago the target happened and selected memory gives a lead time before or in advance of another event, first find that anchor event's timing relative to the question/current date, then add or otherwise combine the lead-time offset with the anchor elapsed time. If the anchor timing is not selected, set sufficiency to insufficient instead of returning the lead time as the elapsed answer. Put the arithmetic or combination explicitly in reasoning, then copy the final elapsed value into answer.
            If reasoning compares alternatives or computes a value, end reasoning with the computed final conclusion and copy that same value into answer. Never return an answer field that contradicts the reasoning conclusion. If selected memory does not contain every named operand needed for the comparison or calculation, set sufficiency to insufficient instead of guessing from a partial operand.
        """.trimIndent()

    private fun typedCountAndCategoryRules(): String =
        """
        - Use predicate_semantic_kinds, predicate, lifecycle_state, status, temporal fields, qualifiers, and evidence as typed meaning. Do not decide by lexical overlap alone.
        - For aggregate questions, AGGREGATE_VALUE and AGGREGATE_DELTA claims are numeric operands. Historical observations coexist unless memory says one corrects, replaces, retracts, or repeats the same measurement slot. Current values replace only same-slot current values.
        - For broad category counts, category fit is semantic, not exact-word matching. A concrete user-attributed member, copy, carrier, component, or subtype may satisfy the requested category unless memory gives an explicit conflicting category or the question asks for transaction/subtype details.
        - POSSESSION and COUNTABLE_ITEM claims are direct evidence for personal items, collections, and copies. They can answer acquisition-style questions unless the question asks specifically for transaction details such as price, store, payment, download source, or exact purchase date.
        - USAGE and LIFECYCLE_EVENT claims are direct evidence only when the question asks about actual user use, adoption, completion, attendance, or other experienced events. Assistant recommendations, examples, option lists, and hypotheticals are not evidence of user action.
        - RESPONSIBILITY and FUNCTIONAL_ROLE claims are direct evidence for leadership, ownership, responsibility, assignment, and role questions. PROJECT_ASSOCIATION alone is supporting context, not proof that the user led, owned, or was responsible for a project.
        - For project count/list questions, count standalone projects, courses with project work, jobs, initiatives, or simultaneous project boards/workstreams. Exclude tasks, datasets, techniques, papers, subtasks, deliverables, and implementation steps when memory places them inside a selected project or thesis.
        - For functional replacement or upgrade questions, count a functional slot only when selected memory connects an older same-role item leaving use/inventory with a newer same-role item entering use/inventory. The new item's source is not an exclusion reason by itself.
        - For location-scoped and functional-category questions, count concrete objects whose selected evidence places them in the location or ordinary function requested. Exclude adjacent decor, accessories, and suggestions unless they themselves satisfy the requested category.
        - For boundary questions scoped before, prior to, until, or at a commitment/decision about a named target, use the target as boundary context. Do not count the boundary target as a prior alternative unless the question explicitly includes it.
        """.trimIndent()
}
