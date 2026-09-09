package com.gromozeka.shared.localization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CardinalPluralRules {
    private val relationPattern = Regex("([nivwftec])(?: % (\\d+))? (!=|=) ([0-9.,]+)")
    private val rules by lazy {
        val data = Json.parseToJsonElement(BundledLocalizationData.cardinalRules()).jsonObject
            .getValue("supplemental").jsonObject.getValue("plurals-type-cardinal").jsonObject
        val parsedExpressions = mutableMapOf<String, Rule>()
        data.mapKeys { it.key.lowercase() }.mapValues { (_, categories) ->
            categories.jsonObject.map { (key, value) ->
                val category = PluralCategory.valueOf(key.removePrefix("pluralRule-count-"))
                val expression = value.jsonPrimitive.content.substringBefore('@').trim()
                category to parsedExpressions.getOrPut(expression) { parseRule(expression) }
            }.toMap()
        }
    }

    fun categories(locale: String): Set<PluralCategory> = forLocale(locale).keys

    fun select(locale: String, count: Long): PluralCategory {
        require(count >= 0) { "Plural count must not be negative" }
        return forLocale(locale).entries.firstOrNull { (category, rule) ->
            category != PluralCategory.other && rule.matches(count)
        }?.key ?: PluralCategory.other
    }

    fun allowsImplicitCount(locale: String, category: PluralCategory): Boolean {
        val count = when (category) {
            PluralCategory.zero -> 0L
            PluralCategory.one -> 1L
            PluralCategory.two -> 2L
            else -> return false
        }
        val rule = forLocale(locale)[category] ?: return false
        val possible = rule.branches.filter { branch ->
            branch.none { !it.variable && !it.matches(count) }
        }
        return possible.isNotEmpty() && possible.all { branch ->
            branch.any { it.variable && it.modulo == null && it.equal && it.ranges == listOf(count..count) }
        } && possible.any { branch -> branch.all { it.matches(count) } }
    }

    private fun forLocale(locale: String): Map<PluralCategory, Rule> {
        val normalized = locale.replace('_', '-').lowercase()
        return rules[normalized] ?: rules[normalized.substringBefore('-')]
            ?: mapOf(PluralCategory.other to Rule(emptyList()))
    }

    private fun parseRule(expression: String): Rule {
        if (expression.isEmpty()) return Rule(emptyList())
        return Rule(expression.split(" or ").map { branch ->
            branch.split(" and ").map { text ->
                val match = requireNotNull(relationPattern.matchEntire(text)) { "Unsupported CLDR rule: $text" }
                val (operand, modulo, operator, intervals) = match.destructured
                Relation(
                    variable = operand == "n" || operand == "i",
                    modulo = modulo.toLongOrNull(),
                    equal = operator == "=",
                    ranges = intervals.split(',').map {
                        val bounds = it.split("..")
                        bounds.first().toLong()..bounds.last().toLong()
                    },
                )
            }
        })
    }

    private data class Rule(val branches: List<List<Relation>>) {
        fun matches(count: Long): Boolean = branches.any { branch -> branch.all { it.matches(count) } }
    }

    private data class Relation(
        val variable: Boolean,
        val modulo: Long?,
        val equal: Boolean,
        val ranges: List<LongRange>,
    ) {
        fun matches(count: Long): Boolean {
            val operand = if (variable) count else 0L
            val value = modulo?.let { operand % it } ?: operand
            return ranges.any { value in it } == equal
        }
    }
}
