package com.gromozeka.shared.json

import kotlinx.serialization.json.*
import kotlin.test.*

class JsonPointerTest {
    private val document = Json.parseToJsonElement("""{"foo":["bar","baz"],"":0,"a/b":1,"c%d":2,"e^f":3,"g|h":4,"i\\j":5,"k\"l":6," ":7,"m~n":8,"null":null,"01":"object key"}""")
    @Test fun `RFC 6901 examples and escaping`() {
        assertEquals(document, JsonPointer("").resolve(document))
        assertEquals(JsonPrimitive("bar"), JsonPointer("/foo/0").resolve(document))
        mapOf("/" to 0, "/a~1b" to 1, "/c%d" to 2, "/e^f" to 3, "/g|h" to 4, "/i\\j" to 5,
            "/k\"l" to 6, "/ " to 7, "/m~0n" to 8).forEach { (pointer, expected) -> assertEquals(JsonPrimitive(expected), JsonPointer(pointer).resolve(document)) }
        assertEquals(JsonPrimitive("object key"), JsonPointer("/01").resolve(document))
        assertEquals(JsonPrimitive(4), JsonPointer("/~01").resolve(Json.parseToJsonElement("""{"~1":4}""")))
    }
    @Test fun `missing and null differ and array indices are strict`() {
        assertEquals(JsonNull, JsonPointer("/null").resolve(document))
        for (path in listOf("/missing", "/foo/01", "/foo/-", "/foo/+1", "/foo/-1", "/foo/999999999999999", "/null/value")) assertNull(JsonPointer(path).resolve(document))
    }
    @Test fun `invalid syntax is rejected`() {
        for (path in listOf("foo", "#/foo", "/bad~", "/bad~2")) assertFailsWith<IllegalArgumentException> { JsonPointer(path) }
    }
}
