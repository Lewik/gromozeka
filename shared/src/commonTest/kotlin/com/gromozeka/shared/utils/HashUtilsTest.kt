package com.gromozeka.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class HashUtilsTest {
    @Test
    fun `matches known SHA-256 vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "".sha256(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".sha256(),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".sha256(),
        )
    }
}
