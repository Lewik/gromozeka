package com.gromozeka.domain.visual

import kotlinx.serialization.json.*

/** Replace supplied root sections in full; never combine their nested members. */
fun replaceVisualStateSections(current: JsonObject, sections: JsonObject): JsonObject {
    require(sections.isNotEmpty() && sections.keys.all { it == "form" || it == "data" }) {
        "Supply one or both complete state sections: form and data"
    }
    require(sections.values.all { it is JsonObject }) { "form and data sections must be objects" }
    return JsonObject(current + sections).also(::validateVisualStateShape)
}

fun validateVisualStateShape(state: JsonObject) {
    require(state.keys == setOf("form", "data") && state["form"] is JsonObject && state["data"] is JsonObject) {
        "State must contain exactly two objects: form and data"
    }
    require(state.toString().encodeToByteArray().size <= VisualLimits.STATE_BYTES) {
        "Full visual state exceeds ${VisualLimits.STATE_BYTES} UTF-8 bytes"
    }
    fun checkDepth(value: JsonElement, depth: Int) {
        require(depth <= VisualLimits.DEPTH) { "State nesting is too deep" }
        when (value) {
            is JsonObject -> value.values.forEach { checkDepth(it, depth + 1) }
            is JsonArray -> value.forEach { checkDepth(it, depth + 1) }
            else -> Unit
        }
    }
    checkDepth(state, 0)
}

object VisualPath {
    private val pathPattern = Regex("""[A-Za-z_][A-Za-z0-9_-]*(?:\.[A-Za-z_][A-Za-z0-9_-]*|\[(?:0|[1-9][0-9]*)\])*""")
    private val segments = Regex("""[A-Za-z_][A-Za-z0-9_-]*|\[([0-9]+)\]""")

    fun validate(path: String) {
        require(path.length <= 256 && pathPattern.matches(path)) { "Invalid binding path: ${path.take(80)}" }
    }

    fun get(path: String, state: JsonObject, locals: Map<String, JsonElement> = emptyMap()): JsonElement {
        validate(path)
        val parts = segments.findAll(path).toList()
        val first = parts.first().value
        var value = locals[first] ?: state[first] ?: error("Missing binding path: $path")
        parts.drop(1).forEach { part ->
            val next = if (part.value.startsWith('[')) {
                (value as? JsonArray)?.getOrNull(part.groupValues[1].toIntOrNull() ?: -1)
            } else (value as? JsonObject)?.get(part.value)
            value = next ?: error("Missing binding path: $path")
        }
        return value
    }

    fun setForm(state: JsonObject, path: String, value: JsonElement): JsonObject {
        validate(path)
        require(path.startsWith("form.")) { "Editable fields must bind to form.*" }
        val parts = segments.findAll(path).map { it.value }.toList()
        fun replace(current: JsonElement, index: Int): JsonElement {
            if (index == parts.size) return value
            val part = parts[index]
            return if (part.startsWith('[')) {
                val array = current as? JsonArray ?: error("Not an array: $path")
                val at = part.substring(1, part.length - 1).toInt()
                require(at in array.indices) { "Array index is out of range: $path" }
                JsonArray(array.mapIndexed { i, child -> if (i == at) replace(child, index + 1) else child })
            } else {
                val obj = current as? JsonObject ?: error("Not an object: $path")
                val child = obj[part] ?: error("Missing form field: $path")
                JsonObject(obj + (part to replace(child, index + 1)))
            }
        }
        return replace(state, 0) as JsonObject
    }
}

/** A deliberately bounded template language: paths only, no expressions or recursive evaluation. */
object VisualBinding {
    private sealed interface Part {
        data class Literal(val text: String) : Part
        data class Path(val path: String) : Part
    }

    fun validate(template: String) { parse(template) }

    fun value(template: String, state: JsonObject, locals: Map<String, JsonElement> = emptyMap()): JsonElement {
        val parts = parse(template)
        if (parts.size == 1 && parts[0] is Part.Path) return VisualPath.get((parts[0] as Part.Path).path, state, locals)
        return JsonPrimitive(buildString {
            parts.forEach { part ->
                when (part) {
                    is Part.Literal -> append(part.text)
                    is Part.Path -> append(text(VisualPath.get(part.path, state, locals)))
                }
            }
        })
    }

    fun text(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> error("Objects and arrays cannot be interpolated into text")
    }

    fun string(template: String, state: JsonObject, locals: Map<String, JsonElement> = emptyMap()): String =
        text(value(template, state, locals))

    private fun parse(template: String): List<Part> {
        val parts = mutableListOf<Part>()
        val literal = StringBuilder()
        fun flush() { if (literal.isNotEmpty()) { parts.add(Part.Literal(literal.toString())); literal.clear() } }
        var index = 0
        while (index < template.length) {
            when {
                template.startsWith("{{", index) -> { literal.append('{'); index += 2 }
                template.startsWith("}}", index) -> { literal.append('}'); index += 2 }
                template[index] == '{' -> {
                    flush()
                    val end = template.indexOf('}', index + 1)
                    require(end >= 0) { "Unterminated binding" }
                    val path = template.substring(index + 1, end)
                    VisualPath.validate(path)
                    parts.add(Part.Path(path)); index = end + 1
                }
                template[index] == '}' -> error("Literal braces must be escaped as {{ and }}")
                else -> literal.append(template[index++])
            }
        }
        flush()
        return parts
    }
}

/** Explicit JSON Schema subset; unsupported keywords fail rather than silently weakening validation. */
object VisualSchema {
    private val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val keywords = setOf("type", "properties", "required", "items", "enum", "additionalProperties", "title", "description")

    fun check(schema: JsonObject) {
        var count = 0
        fun visit(node: JsonObject, path: String, depth: Int) {
            require(depth <= VisualLimits.DEPTH && ++count <= 1024) { "Schema is too complex" }
            require(node.keys.all { it in keywords }) { "Unsupported schema keyword at $path: ${node.keys - keywords}" }
            node["type"]?.let { type ->
                val values = if (type is JsonArray) type else JsonArray(listOf(type))
                require(values.isNotEmpty() && values.all { it is JsonPrimitive && it.isString && it.content in types }) {
                    "Invalid schema type at $path"
                }
            }
            node["properties"]?.let { properties ->
                require(properties is JsonObject) { "properties must be an object at $path" }
                properties.forEach { (name, child) ->
                    require(child is JsonObject) { "Property schema must be an object at $path.$name" }
                    visit(child, "$path.$name", depth + 1)
                }
            }
            node["required"]?.let { required ->
                require(required is JsonArray && required.all { it is JsonPrimitive && it.isString }) { "required must be a string array at $path" }
                require(required.distinct().size == required.size) { "Duplicate required property at $path" }
            }
            node["items"]?.let { items ->
                require(items is JsonObject) { "items must be a schema object at $path" }
                visit(items, "$path[]", depth + 1)
            }
            node["additionalProperties"]?.let {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "additionalProperties must be boolean at $path" }
            }
            node["enum"]?.let { require(it is JsonArray && it.isNotEmpty() && it.distinct().size == it.size) { "enum must contain unique values at $path" } }
            for (name in listOf("title", "description")) node[name]?.let { require(it is JsonPrimitive && it.isString) { "$name must be a string" } }
        }
        visit(schema, "$", 0)
        require((schema["type"] as? JsonPrimitive)?.content == "object") { "State schema must have type object" }
        val props = schema["properties"] as? JsonObject ?: error("State schema must declare form and data properties")
        require(props.keys == setOf("form", "data")) { "State schema must declare exactly form and data" }
        for (name in listOf("form", "data")) require(((props[name] as JsonObject)["type"] as? JsonPrimitive)?.content == "object") { "$name schema must have type object" }
        val required = schema["required"] as? JsonArray ?: error("State schema must require form and data")
        require(required.map { it.jsonPrimitive.content }.toSet() == setOf("form", "data")) { "State schema must require form and data" }
    }

    fun validate(schema: JsonObject, value: JsonElement) {
        fun typeMatches(type: String, v: JsonElement): Boolean = when (type) {
            "null" -> v == JsonNull
            "object" -> v is JsonObject
            "array" -> v is JsonArray
            "string" -> v is JsonPrimitive && v.isString
            "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
            "number", "integer" -> v is JsonPrimitive && !v.isString && v.doubleOrNull?.let { n -> n.isFinite() && (type == "number" || n % 1.0 == 0.0) } == true
            else -> false
        }
        fun visit(s: JsonObject, v: JsonElement, path: String, depth: Int) {
            require(depth <= VisualLimits.DEPTH) { "State nesting is too deep" }
            s["type"]?.let { t ->
                val allowed = if (t is JsonArray) t else JsonArray(listOf(t))
                require(allowed.any { typeMatches(it.jsonPrimitive.content, v) }) { "Value at $path does not match schema type $t" }
            }
            s["enum"]?.let { require(v in (it as JsonArray)) { "Value at $path is not in enum" } }
            if (v is JsonObject) {
                (s["required"] as? JsonArray)?.forEach { require(it.jsonPrimitive.content in v) { "Missing required property $path.${it.jsonPrimitive.content}" } }
                val props = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
                if ((s["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) require(v.keys.all { it in props }) { "Unknown properties at $path: ${v.keys - props.keys}" }
                v.forEach { (name, child) -> (props[name] as? JsonObject)?.let { visit(it, child, "$path.$name", depth + 1) } }
            }
            if (v is JsonArray) (s["items"] as? JsonObject)?.let { item -> v.forEachIndexed { index, child -> visit(item, child, "$path[$index]", depth + 1) } }
        }
        visit(schema, value, "$", 0)
    }
}
