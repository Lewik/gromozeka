package com.gromozeka.infrastructure.ai

import io.github.treesitter.ktreesitter.kotlin.TreeSitterKotlin
import kotlin.test.Test
import kotlin.test.assertNotNull

class TreeSitterTest {
    
    @Test
    fun `should load Tree-sitter Kotlin language`() {
        val language = TreeSitterKotlin.language()
        assertNotNull(language, "Tree-sitter Kotlin language should be loaded")
    }
}
