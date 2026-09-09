package com.gromozeka.domain.tool

import kotlinx.serialization.json.Json
import kotlin.test.*

class ToolAccessPolicyTest {
    private val name = QualifiedToolName("mcp:public_web", "mcp__public_web__search")
    private val first = ToolContractFingerprint("1".repeat(64))
    private val next = ToolContractFingerprint("2".repeat(64))
    private val exact = ToolSelector.ExactRevision(first)
    private val all = ToolSelector.ByName(name)

    @Test fun emptyPolicies() {
        assertFalse(ToolAccessPolicy.AllowOnly().allows(name, first.value))
        assertTrue(ToolAccessPolicy.DenyListed().allows(name, first.value))
    }

    @Test fun exactRevisionInBothModes() {
        assertTrue(ToolAccessPolicy.AllowOnly(setOf(exact)).allows(name, first.value))
        assertFalse(ToolAccessPolicy.AllowOnly(setOf(exact)).allows(name, next.value))
        assertFalse(ToolAccessPolicy.DenyListed(setOf(exact)).allows(name, first.value))
        assertTrue(ToolAccessPolicy.DenyListed(setOf(exact)).allows(name, next.value))
    }

    @Test fun fullNameInBothModes() {
        for (hash in listOf(first, next)) {
            assertTrue(ToolAccessPolicy.AllowOnly(setOf(all)).allows(name, hash.value))
            assertFalse(ToolAccessPolicy.DenyListed(setOf(all)).allows(name, hash.value))
        }
        val unrelated = name.copy(source = "mcp:private_web")
        assertFalse(ToolAccessPolicy.AllowOnly(setOf(all)).allows(unrelated, next.value))
        assertTrue(ToolAccessPolicy.DenyListed(setOf(all)).allows(unrelated, next.value))
        assertFalse(ToolAccessPolicy.AllowOnly(setOf(all)).allows(name.copy(name = "read_file"), next.value))
    }

    @Test fun overlappingEntriesHaveUnionSemantics() {
        assertTrue(ToolAccessPolicy.AllowOnly(setOf(exact, all)).allows(name, next.value))
        assertFalse(ToolAccessPolicy.DenyListed(setOf(exact, all)).allows(name, next.value))
    }

    @Test fun typedPoliciesRoundTripIndependentlyOfOuterDiscriminator() {
        val json = Json { classDiscriminator = "memoryType"; encodeDefaults = true }
        for (policy in listOf(ToolAccessPolicy.AllowOnly(setOf(exact, all)), ToolAccessPolicy.DenyListed(setOf(all)))) {
            val serialized = json.encodeToString<ToolAccessPolicy>(policy)
            assertEquals(policy, json.decodeFromString<ToolAccessPolicy>(serialized))
            assertTrue("\"type\"" in serialized)
        }
        assertFailsWith<IllegalArgumentException> { ToolContractFingerprint("not-a-hash") }
    }

    @Test fun preloadConflictDoesNotMutateSavedConfiguration() {
        val preload = AgentPreloadedTools(listOf(name.name))
        val catalog = listOf(AgentToolCatalogEntry(name, first, name.name, 1, true))
        assertEquals(listOf(name.name), preload.blockedBy(ToolAccessPolicy.AllowOnly(), catalog))
        assertEquals(listOf(name.name), preload.names)
        assertTrue(preload.blockedBy(ToolAccessPolicy.AllowOnly(setOf(all)), catalog).isEmpty())
        assertEquals(preload, Json.decodeFromString<AgentPreloadedTools>(Json.encodeToString(preload)))
    }

    @Test fun nativeProviderToolsRequireTheirOwnPermission() {
        val api = ProviderNativeTool.OPENAI_API_WEB_SEARCH
        val subscription = ProviderNativeTool.OPENAI_SUBSCRIPTION_WEB_SEARCH
        val policy = ToolAccessPolicy.AllowOnly(setOf(api.catalogEntry().selector(false)))
        assertTrue(api.isAllowed(policy))
        assertFalse(subscription.isAllowed(policy))
        assertFalse(api.isAllowed(ToolAccessPolicy.AllowOnly()))
    }

    @Test fun childPoliciesCannotExpandCallerAuthority() {
        val known = listOf(AgentToolCatalogEntry(name, first, name.name, 1, true))
        val narrow = ToolAccessPolicy.AllowOnly(setOf(exact))
        val broad = ToolAccessPolicy.AllowOnly(setOf(all))
        assertTrue(narrow.isNoBroaderThan(broad, known))
        assertFalse(broad.isNoBroaderThan(narrow, known))
        assertFalse(ToolAccessPolicy.DenyListed().isNoBroaderThan(narrow, known))
        assertTrue(ToolAccessPolicy.AllowOnly().isNoBroaderThan(narrow, known))
        assertFalse(narrow.isNoBroaderThan(ToolAccessPolicy.DenyListed(setOf(all)), known))
        assertTrue(ToolAccessPolicy.DenyListed(setOf(all)).isNoBroaderThan(ToolAccessPolicy.DenyListed(setOf(exact)), known))
        assertFalse(ToolAccessPolicy.DenyListed(setOf(exact)).isNoBroaderThan(ToolAccessPolicy.DenyListed(setOf(all)), known))
        assertFalse(narrow.isNoBroaderThan(broad, emptyList()))
    }
}
