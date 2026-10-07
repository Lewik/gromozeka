package com.gromozeka.domain.visual

import kotlin.test.*

class VisualSpacingContractTest {
    @Test fun markupDescribesStructureAndDecorationButCannotSpecifySpacing() {
        fun document(attributes: String) = """<html><head><title>Test</title><state-schema><![CDATA[{"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"]}]]></state-schema></head><body><div $attributes>Content</div></body></html>"""
        VisualDocumentCompiler.compile(document("layout='row' border='true' background='blue'"))
        for (attribute in listOf("gap", "padding", "margin", "spacing")) {
            assertFailsWith<IllegalArgumentException>(attribute) {
                VisualDocumentCompiler.compile(document("$attribute='small'"))
            }
        }
    }
}
