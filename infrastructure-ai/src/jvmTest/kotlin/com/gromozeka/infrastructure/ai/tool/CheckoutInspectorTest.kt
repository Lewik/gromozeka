package com.gromozeka.infrastructure.ai.tool

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class CheckoutInspectorTest {
    @Test fun `read-only inspection does not alter index branch or working files`() = runBlocking {
        val directory = Files.createTempDirectory("slot-inspection-").toFile()
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output
        }
        try {
            git("init", "--initial-branch=main")
            File(directory, "tracked.txt").writeText("original")
            git("add", "tracked.txt")
            git("-c", "user.name=Slot Test", "-c", "user.email=slot@example.invalid", "commit", "-m", "initial")
            git("remote", "add", "origin", ".")
            git("update-ref", "refs/remotes/origin/main", "HEAD")
            git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
            git("config", "branch.main.remote", "origin")
            git("config", "branch.main.merge", "refs/heads/main")
            val index = File(directory, ".git/index").readBytes()
            val inspector = CheckoutInspector()
            val clean = inspector.inspect(directory.absolutePath)
            assertEquals(false, clean.dirty); assertEquals("main", clean.branch); assertEquals("main", clean.defaultBranch)
            assertEquals(0L, clean.aheadOfUpstream)
            assertContentEquals(index, File(directory, ".git/index").readBytes())
            File(directory, "tracked.txt").writeText("intentional unfinished work")
            File(directory, "new.txt").writeText("keep me")
            val dirty = inspector.inspect(directory.absolutePath)
            assertEquals(true, dirty.dirty)
            assertEquals("intentional unfinished work", File(directory, "tracked.txt").readText())
            assertTrue(File(directory, "new.txt").exists())
            assertContentEquals(index, File(directory, ".git/index").readBytes())
            git("checkout", "--detach")
            val detached = inspector.inspect(directory.absolutePath)
            assertNull(detached.branch); assertTrue(detached.warnings.isNotEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `non-repository is unknown not clean`() = runBlocking {
        val directory = Files.createTempDirectory("slot-not-repo-").toFile()
        try {
            val result = CheckoutInspector().inspect(directory.absolutePath)
            assertNull(result.dirty); assertTrue(result.warnings.isNotEmpty())
        } finally { directory.deleteRecursively() }
    }
}
