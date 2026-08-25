package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.ui.icons.Icons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolSemanticIconTest {
    @Test
    fun resolvesFileRead() {
        val spec = toolIconSpec("grz_read_file")

        assertEquals(Icons.Default.Description, spec.domain)
        assertEquals(Icons.Default.Visibility, spec.action)
        assertNull(spec.modifier)
    }

    @Test
    fun resolvesSkillDirectoryImport() {
        val spec = toolIconSpec("grz_skill_import_from_directory")

        assertEquals(Icons.Default.Extension, spec.domain)
        assertEquals(Icons.Default.ArrowDownward, spec.action)
        assertEquals(Icons.Default.Folder, spec.modifier)
    }

    @Test
    fun resolvesVersionedExternalWebSearch() {
        val spec = toolIconSpec("external-provider__web_search__v3")

        assertEquals(Icons.Default.Public, spec.domain)
        assertEquals(Icons.Default.Search, spec.action)
        assertNull(spec.modifier)
    }
}
