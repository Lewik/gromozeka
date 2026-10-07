package com.gromozeka.domain.visual

import kotlinx.serialization.json.*
import kotlin.test.*

class VisualDocumentTest {
    private val state = obj("""{"form":{"query":"kxml","count":1,"enabled":true},"data":{"busy":false,"rows":[{"id":"one","label":"First"},{"id":"two","label":"Second"}]}}""")

    @Test fun compilesSchemaPaletteAndTypedBindings() {
        val document = VisualDocumentCompiler.compile(document("""<div layout="grid" columns="auto 1fr"><span format="bold italic mono">{form.query}</span><button id="search" disabled="{data.busy}">Search</button></div>"""))
        VisualDocumentCompiler.validateState(document, state)
        val grid = VisualDocumentCompiler.render(document, state).single()
        assertEquals("kxml", grid.children[0].children.single().text)
        assertFalse(grid.children[1].bool("disabled"))
        assertEquals("#DC2626", document.palette["red"])
    }

    @Test fun conditionsAreLazyAndLoopsHaveStableKeys() {
        val doc = VisualDocumentCompiler.compile(document("""<if test="{data.busy}"><span>{data.missing}</span><else><for-each items="{data.rows}" as="row" key="{row.id}"><span>{row.label}</span></for-each></else></if>"""))
        val before = VisualDocumentCompiler.render(doc, state)
        val after = VisualDocumentCompiler.render(doc, replaceVisualStateSections(state, obj("""{"data":{"busy":false,"rows":[{"id":"two","label":"Changed"},{"id":"one","label":"First"}]}}""")))
        assertEquals(before[1].key, after[0].key)
        assertEquals(before[0].key, after[1].key)
        assertEquals("Changed", after[0].children.single().text)
    }

    @Test fun suppliedSectionsReplaceTheirWholeContentsAndOmittedSectionsStay() {
        val data = obj("""{"data":{"nested":{"keep":1,"drop":2},"rows":[1,2]}}""")
        val before = replaceVisualStateSections(state, data)
        val after = replaceVisualStateSections(before, obj("""{"data":{"nested":{"keep":3},"value":null}}"""))
        assertEquals(state.getValue("form"), after.getValue("form"))
        assertEquals(obj("""{"nested":{"keep":3},"value":null}"""), after.getValue("data"))
        val both = replaceVisualStateSections(after, obj("""{"form":{"query":"new"},"data":{"rows":[]}}"""))
        assertEquals(obj("""{"query":"new"}"""), both.getValue("form"))
        assertEquals(obj("""{"rows":[]}"""), both.getValue("data"))
        val cleared = replaceVisualStateSections(both, obj("""{"form":{},"data":{}}"""))
        assertEquals(obj("{}"), cleared.getValue("form"))
        assertEquals(obj("{}"), cleared.getValue("data"))
        for (invalid in listOf("{}", "{\"other\":{}}", "{\"form\":null}", "{\"data\":[]}")) {
            assertFailsWith<IllegalArgumentException>(invalid) { replaceVisualStateSections(state, obj(invalid)) }
        }
    }

    @Test fun pathsSupportKebabCaseAndArrayIndexesWithoutExpressions() {
        val sample = obj("""{"form":{"long-name":[{"value":3}]},"data":{}}""")
        assertEquals(JsonPrimitive(3), VisualPath.get("form.long-name[0].value", sample))
        assertEquals(JsonPrimitive(4), VisualPath.get("form.long-name[0].value", VisualPath.setForm(sample,"form.long-name[0].value",JsonPrimitive(4))))
        for (path in listOf("form.long-name[-1]", "form[x]", "form..query", "form.query()", "form.query + 1", "form.rows[01]")) {
            assertFailsWith<IllegalArgumentException>(path) { VisualPath.validate(path) }
        }
        assertFails { VisualPath.get("form.long-name[9]", sample) }
        assertFailsWith<IllegalArgumentException> { VisualPath.setForm(sample, "data.foo", JsonNull) }
    }

    @Test fun interpolationIsTypedAndDoesNotReinterpretDataAsMarkup() {
        assertEquals(JsonPrimitive(false), VisualBinding.value("{data.busy}", state))
        assertEquals("{{x}}", VisualBinding.string("{form.query}", replaceVisualStateSections(state,obj("""{"form":{"query":"{{x}}"}}"""))))
        assertEquals("{literal} kxml", VisualBinding.string("{{literal}} {form.query}",state))
        assertFails { VisualBinding.string("{data.rows}",state) }
        assertFails { VisualBinding.string("bad {",state) }
        assertFails { VisualBinding.string("bad }",state) }
    }

    @Test fun unsupportedMarkupAndAttributesAreRejected() {
        for (body in listOf("<script/>", "<span onclick='x'/>", "<span style='color:red'/>", "<table/>", "<component/>", "<output/>", "<strong/>", "<em/>", "<code/>", "<input value='x'/>", "<span xmlns='x'/>")) {
            assertFails(body) { VisualDocumentCompiler.compile(document(body)) }
        }
        assertFails { VisualDocumentCompiler.compile("<!DOCTYPE html><html><head/><body/></html>") }
    }

    @Test fun invalidDynamicAttributesAndDuplicateIdsAreRejected() {
        for (body in listOf(
            "<button>Missing id</button>", "<button id='x'/><button id='x'/>",
            "<input name='data.busy'/>", "<input name='form.missing'/>",
            "<input name='form.query' type='file'/>", "<input name='form.query' type='checkbox'/>",
            "<span color='unknown'/>", "<span format='bold underline'/>",
            "<a href='javascript:alert(1)'>Bad</a>", "<a href='file:///tmp/x'>Bad</a>",
            "<div layout='grid' columns='1px 1fr'/>", "<div layout='grid' columns='0fr'/>",
            "<button id='x' disabled='{form.query}'/>",
        )) {
            assertFails(body) { VisualDocumentCompiler.validateState(VisualDocumentCompiler.compile(document(body)), state) }
        }
    }

    @Test fun loopsRejectDuplicateKeysAndExpansionBombs() {
        val doc = VisualDocumentCompiler.compile(document("""<for-each items="{data.rows}" as="row" key="{row.id}"><span>{row.label}</span></for-each>"""))
        val duplicates = replaceVisualStateSections(state,obj("""{"data":{"rows":[{"id":"x","label":"A"},{"id":"x","label":"B"}]}}"""))
        assertFails { VisualDocumentCompiler.render(doc,duplicates) }
        val items = JsonArray((0 until 100).map(::JsonPrimitive))
        val nested = VisualDocumentCompiler.compile(document("""<for-each items="{data.items}" as="outer" key="{outer}"><for-each items="{data.items}" as="inner" key="{inner}"><span>{inner}</span></for-each></for-each>"""))
        assertFails { VisualDocumentCompiler.render(nested,replaceVisualStateSections(state, buildJsonObject { put("data",buildJsonObject { put("items",items) }) })) }
    }

    @Test fun schemaItselfAndFullStateAreChecked() {
        val doc = VisualDocumentCompiler.compile(document("<span>{form.query}</span>"))
        assertFails { VisualSchema.check(obj("""{"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"],"oneOf":[]}""")) }
        assertFails { VisualDocumentCompiler.validateState(doc,obj("""{"form":{"query":123},"data":{}}""")) }
        assertFails { VisualDocumentCompiler.validateState(doc,obj("""{"form":{},"data":{}}""")) }
        assertFails { validateVisualStateShape(obj("""{"data":{}}""")) }
        assertFails { validateVisualStateShape(buildJsonObject { put("form",buildJsonObject { put("query","x".repeat(VisualLimits.STATE_BYTES)) });put("data",JsonObject(emptyMap())) }) }
    }

    @Test fun draftsNormalizeNumbersOnlyAtSubmit() {
        val doc = VisualDocumentCompiler.compile(document("<input name='form.count' type='number'/><button id='save'>Save</button>"))
        val draft = VisualPath.setForm(state,"form.count",JsonPrimitive("12.5"))
        assertEquals(JsonPrimitive(12.5),VisualDocumentCompiler.submittedState(doc,draft)["form"]!!.jsonObject["count"])
        assertFails { VisualDocumentCompiler.submittedState(doc,VisualPath.setForm(state,"form.count",JsonPrimitive("-"))) }
    }

    @Test fun aSmallCustomPaletteIsExplicit() {
        val markup = document("<span color='warning'>Warning</span>").replace("</head>","<palette><color name='warning' value='#B45309'/></palette></head>")
        val doc = VisualDocumentCompiler.compile(markup)
        assertEquals(mapOf("warning" to "#B45309"),doc.palette)
        VisualDocumentCompiler.validateState(doc,state)
    }

    @Test fun nativeContainersRejectChildrenTheyCannotRender() {
        for (body in listOf(
            "<p><input name='form.query'/></p>", "<span><div/></span>",
            "<textarea name='form.query'>Ignored value</textarea>", "<progress><button id='hidden-button'>Bad</button></progress>",
            "<select name='form.query'><span>Not an option</span></select>",
            "<ul><span>Not a list item</span></ul>", "<li>Orphan</li>",
            "<for-each items='{data.rows}' as='row' key='{row.id}'><option value='{row.id}'/></for-each>",
        )) assertFails(body) { VisualDocumentCompiler.validateState(VisualDocumentCompiler.compile(document(body)), state) }
        VisualDocumentCompiler.validateState(VisualDocumentCompiler.compile(document("""<select name="form.query"><for-each items="{data.rows}" as="row" key="{row.id}"><option value="{row.id}">{row.label}</option></for-each></select>""")), state)
    }

    @Test fun sliderRejectsInvalidEffectiveRangesAndFloatingPointOverflow() {
        for (attributes in listOf("min='150'", "max='-1'", "min='1' max='1'", "min='-3e38' max='3e38'", "step='1e-100'")) {
            assertFails(attributes) { VisualDocumentCompiler.validateState(VisualDocumentCompiler.compile(document("<input type='range' name='form.count' $attributes/>")), state) }
        }
        val doc = VisualDocumentCompiler.compile(document("<input type='range' name='form.count' min='0.1' max='0.7' step='0.1'/>"))
        VisualDocumentCompiler.validateState(doc, VisualPath.setForm(state, "form.count", JsonPrimitive(0.7)))
        assertFails { VisualDocumentCompiler.render(doc, VisualPath.setForm(state, "form.count", JsonPrimitive("invalid"))) }
    }

    @Test fun loopBudgetCountsActualExpansionRatherThanRepeatedRemainingItems() {
        val doc = VisualDocumentCompiler.compile(document("<for-each items='{data.items}' as='item' key='{item}'><br/></for-each>"))
        val sample = replaceVisualStateSections(state, buildJsonObject { put("data", buildJsonObject { put("items", JsonArray((0 until 1500).map(::JsonPrimitive))) }) })
        assertEquals(1500, VisualDocumentCompiler.render(doc, sample).size)
    }

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun document(body: String) = """<html><head><title>Test visual</title><state-schema><![CDATA[
        {"type":"object","properties":{"form":{"type":"object","properties":{"query":{"type":"string"},"count":{"type":"number"},"enabled":{"type":"boolean"}},"required":["query"]},"data":{"type":"object"}},"required":["form","data"],"additionalProperties":false}
        ]]></state-schema></head><body>$body</body></html>"""
}
