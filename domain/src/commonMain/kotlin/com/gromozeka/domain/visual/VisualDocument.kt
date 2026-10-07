package com.gromozeka.domain.visual

import kxml.Kxml
import kotlinx.serialization.json.*

data class VisualDocument(
    val title: String,
    val schema: JsonObject,
    val palette: Map<String, String>,
    val body: List<VisualNode>,
)

data class VisualNode(
    val tag: String,
    val attributes: Map<String, String>,
    val children: List<VisualNode>,
    val key: String,
    val text: String? = null,
)

/** Evaluated nodes contain data, never new markup. Keys are stable across state-only updates. */
data class VisualRenderNode(
    val tag: String,
    val attributes: JsonObject,
    val children: List<VisualRenderNode>,
    val key: String,
    val text: String? = null,
) {
    fun string(name: String): String? = (attributes[name] as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
    fun bool(name: String): Boolean = (attributes[name] as? JsonPrimitive)?.booleanOrNull ?: false
    fun number(name: String): Double? = (attributes[name] as? JsonPrimitive)?.doubleOrNull
}

object VisualDocumentCompiler {
    val defaultPalette = linkedMapOf(
        "red" to "#DC2626", "green" to "#15803D", "blue" to "#2563EB",
        "orange" to "#B45309", "gray" to "#6B7280",
    )
    private val common = setOf("id", "hidden", "title", "color")
    private val allowed = mapOf(
        "div" to setOf("layout", "columns", "border", "background"),
        "p" to emptySet(), "span" to setOf("format"),
        "h1" to emptySet(), "h2" to emptySet(), "h3" to emptySet(),
        "small" to emptySet(), "pre" to emptySet(), "br" to emptySet(), "hr" to emptySet(),
        "label" to setOf("for"),
        "input" to setOf("type", "name", "placeholder", "maxlength", "readonly", "disabled", "min", "max", "step"),
        "textarea" to setOf("name", "rows", "maxlength", "placeholder", "readonly", "disabled"),
        "select" to setOf("name", "disabled"), "option" to setOf("value", "disabled"),
        "button" to setOf("disabled"),
        "progress" to setOf("value", "max"),
        "ul" to emptySet(), "ol" to setOf("start"), "li" to emptySet(),
        "a" to setOf("href"),
        "if" to setOf("test"), "else" to emptySet(),
        "for-each" to setOf("items", "as", "key"),
    )
    private val booleanAttributes = setOf("hidden", "border", "readonly", "disabled", "test")
    private val voidTags = setOf("input", "br", "hr")
    private val namePattern = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }

    fun compile(source: String): VisualDocument {
        require(source.encodeToByteArray().size <= VisualLimits.DOCUMENT_BYTES) { "Visual document exceeds ${VisualLimits.DOCUMENT_BYTES} bytes" }
        val root = Kxml.parseDocument(source, Kxml.Limits(
            maxInputLength = VisualLimits.DOCUMENT_BYTES, maxDepth = VisualLimits.DEPTH,
            maxNodes = VisualLimits.RENDER_NODES, maxAttributesPerElement = 16,
        ))
        require(root.name == "html" && root.namespace == null && root.attrs.isEmpty()) { "Document root must be <html> without attributes" }
        val rootChildren = elementChildren(root)
        require(rootChildren.map { it.name } == listOf("head", "body")) { "html must contain exactly head followed by body" }
        val head = rootChildren[0]
        val body = rootChildren[1]
        require(head.attrs.isEmpty() && body.attrs.isEmpty() && head.namespace == null && body.namespace == null) { "head and body do not accept attributes or namespaces" }
        val definitions = elementChildren(head)
        require(definitions.all { it.namespace == null && it.name in setOf("title", "state-schema", "palette") }) { "Unsupported head element" }
        require(definitions.groupBy { it.name }.values.all { it.size == 1 }) { "Duplicate head definition" }
        val titleNode = definitions.singleOrNull { it.name == "title" } ?: error("head requires title")
        require(titleNode.attrs.isEmpty()) { "title does not accept attributes" }
        val title = plainText(titleNode).trim()
        require(title.isNotEmpty() && title.length <= 100) { "title must contain 1..100 characters" }
        val schemaNode = definitions.singleOrNull { it.name == "state-schema" } ?: error("head requires state-schema")
        require(schemaNode.attrs.isEmpty()) { "state-schema does not accept attributes" }
        val schema = json.parseToJsonElement(plainText(schemaNode)) as? JsonObject ?: error("state-schema must contain a JSON object")
        VisualSchema.check(schema)
        val palette = definitions.singleOrNull { it.name == "palette" }?.let(::palette) ?: defaultPalette
        val nodes = body.content.mapIndexedNotNull { index, entry -> node(entry, "body/$index", parent = "body") }
        require(nodes.isNotEmpty()) { "Visual body must not be empty" }
        return VisualDocument(title, schema, palette, nodes)
    }

    fun validateState(document: VisualDocument, state: JsonObject) {
        validateVisualStateShape(state)
        VisualSchema.validate(document.schema, state)
        render(document, state)
    }

    fun render(document: VisualDocument, state: JsonObject): List<VisualRenderNode> {
        var count = 0
        var textBytes = 0
        fun budget(text: String) {
            textBytes += text.encodeToByteArray().size
            require(textBytes <= 65_536) { "Expanded visual text exceeds 64 KiB; reduce or paginate the content" }
        }
        val ids = mutableSetOf<String>()
        fun expand(nodes: List<VisualNode>, locals: Map<String, JsonElement>, prefix: String, depth: Int): List<VisualRenderNode> {
            require(depth <= VisualLimits.DEPTH) { "Rendered tree is too deep" }
            return buildList {
                for (node in nodes) {
                    require(++count <= VisualLimits.RENDER_NODES) { "Rendered node limit exceeded" }
                    if (node.tag == "#text") {
                        val text = VisualBinding.string(node.text.orEmpty(), state, locals)
                        budget(text)
                        add(VisualRenderNode("#text", JsonObject(emptyMap()), emptyList(), prefix + node.key, text))
                        continue
                    }
                    fun eval(name: String): JsonElement? = node.attributes[name]?.let { raw ->
                        val value = VisualBinding.value(raw, state, locals)
                        if (name in booleanAttributes) {
                            val literal = !raw.contains('{')
                            val p = value as? JsonPrimitive
                            val bool = p?.takeIf { literal || !it.isString }?.booleanOrNull
                            require(bool != null) { "${node.tag}.$name requires a boolean" }
                            JsonPrimitive(bool)
                        } else value
                    }
                    if ((eval("hidden") as? JsonPrimitive)?.booleanOrNull == true) continue
                    if (node.tag == "if") {
                        val test = eval("test")?.jsonPrimitive?.booleanOrNull ?: error("if requires boolean test")
                        val children = if (test) node.children.filter { it.tag != "else" }
                            else node.children.singleOrNull { it.tag == "else" }?.children.orEmpty()
                        addAll(expand(children, locals, prefix + node.key + "/", depth + 1))
                        continue
                    }
                    if (node.tag == "for-each") {
                        val items = eval("items") as? JsonArray ?: error("for-each.items requires an array")
                        val alias = node.attributes.getValue("as")
                        val keys = mutableSetOf<String>()
                        require(count + items.size <= VisualLimits.RENDER_NODES) { "Loop expansion limit exceeded" }
                        items.forEachIndexed { index, item ->
                            val context = locals + (alias to item) + ("index" to JsonPrimitive(index))
                            val key = VisualBinding.value(node.attributes.getValue("key"), state, context)
                            require(key is JsonPrimitive && key != JsonNull && key.content.length <= 128) { "Loop keys must be bounded scalar values" }
                            require(keys.add(key.toString())) { "Duplicate loop key" }
                            val stablePrefix = prefix + node.key + "/" + key.toString().length + ":" + key.toString() + "/"
                            addAll(expand(node.children, context, stablePrefix, depth + 1))
                        }
                        continue
                    }
                    val attributes = JsonObject(node.attributes.mapValues { (name, _) -> requireNotNull(eval(name)) })
                    val id = (attributes["id"] as? JsonPrimitive)?.content
                    if (id != null) {
                        require(id.isNotBlank() && id.length <= 128) { "Element id must contain 1..128 characters" }
                        require(ids.add(id)) { "Duplicate element id: $id" }
                    }
                    if (node.tag == "button") require(id != null) { "button requires id" }
                    for (name in listOf("color", "background")) attributes[name]?.let {
                        require(it is JsonPrimitive && it.content in document.palette) { "Unknown palette color for $name" }
                    }
                    val rendered = VisualRenderNode(node.tag, attributes,
                        expand(node.children, locals, prefix, depth + 1), id?.let { "id:$it" } ?: prefix + node.key)
                    validateAttributes(rendered, state)
                    if (rendered.tag in setOf("input", "textarea")) {
                        budget(VisualBinding.text(VisualPath.get(rendered.string("name")!!, state)))
                    }
                    add(rendered)
                }
            }
        }
        val expanded = expand(document.body, emptyMap(), "", 0)
        // Validate the effective tree, after loops/conditions have emitted their children.
        // The native renderer must never silently drop an input nested in a text container.
        val inline = setOf("#text", "span", "small", "a", "br")
        val textContainers = setOf("p", "span", "small", "pre", "label", "h1", "h2", "h3", "a", "button", "option")
        fun structure(nodes: List<VisualRenderNode>, parent: String) {
            for (node in nodes) {
                if (parent in textContainers) require(node.tag in inline) { "$parent accepts inline text only, not ${node.tag}" }
                if (parent == "select") require(node.tag == "option") { "select accepts option children only" }
                if (parent in setOf("ul", "ol")) require(node.tag == "li") { "$parent accepts li children only" }
                if (node.tag == "option") require(parent == "select") { "option must be inside select" }
                if (node.tag == "li") require(parent in setOf("ul", "ol")) { "li must be inside ul or ol" }
                if (node.tag in setOf("input", "textarea", "progress", "br", "hr")) {
                    require(node.children.isEmpty()) { "${node.tag} does not accept children; values come from state or attributes" }
                }
                structure(node.children, node.tag)
            }
        }
        structure(expanded, "body")
        return expanded
    }

    fun buttons(nodes: List<VisualRenderNode>): List<VisualRenderNode> = buildList {
        fun visit(node: VisualRenderNode) { if (node.tag == "button") add(node); node.children.forEach(::visit) }
        nodes.forEach(::visit)
    }

    /** Convert number editor drafts at submission, not on each keystroke. */
    fun submittedState(document: VisualDocument, draft: JsonObject): JsonObject {
        var result = draft
        fun visit(node: VisualRenderNode) {
            if (node.tag == "input" && node.string("type") in setOf("number", "range")) {
                val path = node.string("name")!!
                val current = VisualPath.get(path, result)
                if (current is JsonPrimitive && current.isString) {
                    val text = current.content.trim()
                    val value = if (text.isEmpty()) JsonNull else {
                        val number = text.toDoubleOrNull()
                        require(number != null && number.isFinite()) { "Invalid number in $path" }
                        current.content.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(number)
                    }
                    result = VisualPath.setForm(result, path, value)
                }
            }
            node.children.forEach(::visit)
        }
        render(document, draft).forEach(::visit)
        validateState(document, result)
        return result
    }

    fun gridColumns(value: String): List<String> {
        val tracks = value.trim().split(Regex("\\s+"))
        require(tracks.size in 1..12 && tracks.all { it == "auto" || it.endsWith("fr") && it.removeSuffix("fr").toFloatOrNull()?.let { n -> n.isFinite() && n > 0f && n <= 100f } == true }) {
            "columns must contain 1..12 tracks: auto or positive fractions such as 1fr"
        }
        return tracks
    }

    private fun validateAttributes(node: VisualRenderNode, state: JsonObject) {
        fun choice(name: String, values: Set<String>) { node.string(name)?.let { require(it in values) { "Invalid ${node.tag}.$name: $it" } } }
        fun number(name: String, positive: Boolean = false): Double? = node.attributes[name]?.let {
            val value = (it as? JsonPrimitive)?.doubleOrNull
            require(value != null && value.isFinite() && (!positive || value > 0)) { "${node.tag}.$name requires a ${if (positive) "positive " else ""}number" }
            value
        }
        if (node.tag == "div") {
            choice("layout", setOf("column", "row", "grid"))
            if (node.string("layout") == "grid") gridColumns(node.string("columns") ?: error("grid requires columns"))
            else require(node.string("columns") == null) { "columns requires layout=grid" }
        }
        if (node.tag == "span") node.string("format")?.let {
            require(it.split(Regex("\\s+")).all { flag -> flag in setOf("bold", "italic", "mono") }) { "span.format accepts bold, italic, mono" }
        }
        if (node.tag in setOf("input", "textarea", "select")) {
            val path = node.string("name") ?: error("${node.tag} requires name")
            require(path.startsWith("form.")) { "Input name must refer to form.*" }
            val value = VisualPath.get(path, state)
            require(value is JsonPrimitive) { "Editable values must be scalar: $path" }
            if (node.tag == "textarea" || node.tag == "input" && node.string("type") in setOf(null, "text")) {
                require(value == JsonNull || (value as JsonPrimitive).isString) { "Text fields require a string or null form value" }
            }
            if (node.tag == "input") {
                choice("type", setOf("text", "number", "checkbox", "range"))
                val extras = when (node.string("type") ?: "text") {
                    "text" -> setOf("placeholder", "maxlength", "readonly", "disabled")
                    "number" -> setOf("placeholder", "readonly", "disabled", "min", "max", "step")
                    "checkbox" -> setOf("disabled")
                    else -> setOf("disabled", "min", "max", "step")
                }
                require(node.attributes.keys.all { it in common || it in extras || it in setOf("name", "type") }) { "Unsupported attributes for this input type" }
                if (node.string("type") == "checkbox") require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "Checkbox requires boolean form value" }
                if (node.string("type") in setOf("number", "range")) {
                    val min = number("min"); val max = number("max"); number("step", positive = true)
                    require(min == null || max == null || max >= min) { "max must not be smaller than min" }
                    if (node.string("type") == "range") {
                        val lower = (min ?: 0.0).toFloat()
                        val upper = (max ?: 100.0).toFloat()
                        require(lower.isFinite() && upper.isFinite() && upper > lower && (upper - lower).isFinite()) { "Slider requires a finite increasing range (defaults 0..100)" }
                        node.number("step")?.let { require(it.toFloat().isFinite() && it.toFloat() > 0f) { "Slider step must fit a positive finite floating point number" } }
                        require(value == JsonNull || value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { it.isFinite() && it >= (min ?: 0.0) && it <= (max ?: 100.0) } == true) { "Slider form value must be numeric and inside its range" }
                    }
                    if (value != JsonNull && value is JsonPrimitive && !value.isString) {
                        val n = value.doubleOrNull
                        require(n != null && n.isFinite() && (min == null || n >= min) && (max == null || n <= max)) { "Number outside allowed range: $path" }
                    }
                }
            }
            number("maxlength", positive = true)?.let { require(it % 1.0 == 0.0 && it <= 8192) { "maxlength must be an integer up to 8192" } }
            number("rows", positive = true)?.let { require(it % 1.0 == 0.0 && it <= 30) { "rows must be an integer up to 30" } }
        }
        if (node.tag == "progress") {
            val max = number("max", positive = true) ?: 1.0
            node.attributes["value"]?.takeUnless { it == JsonNull }?.let {
                val value = number("value")!!
                require(value in 0.0..max) { "progress value is outside 0..max" }
            }
        }
        if (node.tag == "ol") number("start")?.let { require(it % 1.0 == 0.0) { "ol.start requires an integer" } }
        if (node.tag == "option") require(node.attributes["value"] is JsonPrimitive && node.attributes["value"] != JsonNull) { "option requires scalar value" }
        if (node.tag == "a") {
            val href = node.string("href") ?: error("a requires href")
            require(href.length <= 2048 && (href.startsWith("https://") || href.startsWith("http://")) && href.none { it.isWhitespace() || it.code < 32 }) { "Links must use HTTP or HTTPS" }
        }
    }

    private fun node(entry: Pair<Kxml.Element?, String?>, key: String, parent: String): VisualNode? {
        entry.second?.let { text ->
            if (text.isBlank() && parent !in setOf("p", "span", "label", "button", "option", "pre", "h1", "h2", "h3", "small", "a")) return null
            VisualBinding.validate(text)
            return VisualNode("#text", emptyMap(), emptyList(), key, text)
        }
        val element = requireNotNull(entry.first)
        require(element.namespace == null && element.name in allowed) { "Unsupported Visual tag: ${element.name}" }
        require(element.attrs.all { it.namespace == null }) { "Namespaced attributes are not supported" }
        val attrs = element.attrs.associate { it.name to it.value }
        require(attrs.keys.all { it in common || it in allowed.getValue(element.name) }) { "Unsupported attributes on ${element.name}: ${attrs.keys - common - allowed.getValue(element.name)}" }
        attrs.values.forEach(VisualBinding::validate)
        if (element.name in voidTags) require(element.content.isEmpty()) { "${element.name} must not have children" }
        if (element.name == "option") require(parent == "select" || parent == "for-each") { "option must be inside select" }
        if (element.name == "else") require(parent == "if") { "else must be inside if" }
        if (element.name == "for-each") {
            require(attrs.keys.containsAll(listOf("items", "as", "key"))) { "for-each requires items, as, key" }
            require(namePattern.matches(attrs.getValue("as")) && attrs["as"] !in setOf("form", "data", "index")) { "Invalid loop alias" }
        }
        if (element.name == "if") require("test" in attrs) { "if requires test" }
        val children = element.content.mapIndexedNotNull { index, child -> node(child, "$key/$index", element.name) }
        if (element.name == "if") {
            require(children.count { it.tag == "else" } <= 1) { "if may contain only one else" }
            val at = children.indexOfFirst { it.tag == "else" }
            require(at < 0 || children.drop(at + 1).all { it.tag == "#text" && it.text.isNullOrBlank() }) { "else must be last" }
        }
        return VisualNode(element.name, attrs, children, key)
    }

    private fun palette(element: Kxml.Element): Map<String, String> {
        require(element.attrs.isEmpty()) { "palette does not accept attributes" }
        val colors = elementChildren(element)
        require(colors.size in 1..5) { "palette must contain 1..5 colors" }
        val result = linkedMapOf<String, String>()
        colors.forEach {
            require(it.name == "color" && it.namespace == null && it.content.isEmpty()) { "palette may only contain empty color elements" }
            require(it.attrs.all { a -> a.namespace == null } && it.attrs.map { a -> a.name }.toSet() == setOf("name", "value")) { "color requires name and value only" }
            val attrs = it.attrs.associate { a -> a.name to a.value }
            val name = attrs.getValue("name")
            val color = attrs.getValue("value")
            require(namePattern.matches(name) && name.length <= 32) { "Invalid palette name" }
            require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "Palette colors must use #RRGGBB" }
            require(result.put(name, color) == null) { "Duplicate palette color" }
        }
        return result
    }

    private fun elementChildren(element: Kxml.Element): List<Kxml.Element> {
        require(element.content.all { it.second.isNullOrBlank() }) { "Unexpected text inside ${element.name}" }
        return element.content.mapNotNull { it.first }
    }

    private fun plainText(element: Kxml.Element): String {
        require(element.content.all { it.first == null }) { "${element.name} must contain text only (use CDATA for JSON)" }
        return element.getInnerText()
    }
}
