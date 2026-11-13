package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.infrastructure.ai.runtime.SdkHttpTestServer
import com.gromozeka.infrastructure.ai.runtime.SdkTlsTestIdentity
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalScriptSdkTlsTest {
    @Test
    fun `Python SDK inherits additional CA trust but still checks hostname and rejects bad bundles`() {
        val home = Files.createTempDirectory("grz-script-tls-").toFile()
        try {
            SdkTlsTestIdentity().use { identity ->
                val replies = listOf(
                    SdkHttpTestServer.Reply("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25"}}"""),
                    SdkHttpTestServer.Reply("{}", 202),
                    SdkHttpTestServer.Reply("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""),
                )
                SdkHttpTestServer(replies, sslContext = identity.context, expectedTarget = "/mcp").use { server ->
                    val sdk = LocalScriptSdk.materialize(home)
                    val script = File(home, "probe.py").apply { writeText("""
                        import os, ssl, grz
                        expected = os.environ.get("EXPECTED_FAILURE")
                        try:
                            with grz.Client.from_env(timeout=5) as client:
                                assert client.list_tools() == []
                                context = client._ssl_context()
                                assert context.check_hostname
                                assert context.verify_mode == ssl.CERT_REQUIRED
                                assert len(context.get_ca_certs()) > len(ssl.create_default_context().get_ca_certs())
                        except grz.Error as error:
                            assert type(error).__name__ == expected, (type(error).__name__, expected)
                            assert not error.outcome_unknown
                            assert os.environ["GRZ_MCP_TOKEN"] not in str(error)
                            print("EXPECTED:" + expected)
                        else:
                            assert expected is None
                            print("SUCCESS")
                    """.trimIndent()) }
                    fun probe(url: String, caBundle: String?, expected: String?) {
                        val python = System.getenv("GRZ_TEST_PYTHON")
                            ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
                        val output = File(home, "probe.log")
                        val process = ProcessBuilder(python, script.absolutePath).directory(home)
                            .redirectErrorStream(true).redirectOutput(output).apply {
                                environment()["PYTHONPATH"] = sdk.absolutePath
                                environment()["PYTHONIOENCODING"] = "utf-8"
                                environment()["GRZ_MCP_URL"] = url
                                environment()["GRZ_MCP_TOKEN"] = "synthetic-mcp-token"
                                environment()["GRZ_MCP_CONTEXT"] = "{}"
                                environment().remove("GRZ_MCP_CA_BUNDLE")
                                environment().remove("EXPECTED_FAILURE")
                                caBundle?.let { environment()["GRZ_MCP_CA_BUNDLE"] = it }
                                expected?.let { environment()["EXPECTED_FAILURE"] = it }
                            }.start()
                        try {
                            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "SDK TLS probe timed out")
                            val log = output.readText()
                            assertEquals(0, process.exitValue(), log)
                            assertTrue(log.contains(expected?.let { "EXPECTED:$it" } ?: "SUCCESS"), log)
                        } finally {
                            process.descendants().forEach { it.destroyForcibly() }
                            if (process.isAlive) process.destroyForcibly()
                            process.waitFor(5, TimeUnit.SECONDS)
                        }
                    }
                    probe("${server.origin}/mcp", null, "TransportError")
                    assertTrue(server.requests.isEmpty())
                    probe("${server.origin}/mcp", identity.caBundle.toString(), null)
                    assertEquals(3, server.requests.size)
                    probe("${server.origin.replace("127.0.0.1", "localhost")}/mcp", identity.caBundle.toString(), "TransportError")
                    val invalid = File(home, "synthetic-mcp-token.pem").apply { writeText("not a certificate") }
                    probe("${server.origin}/mcp", invalid.absolutePath, "ConfigurationError")
                    probe("${server.origin}/mcp", File(home, "missing.pem").absolutePath, "ConfigurationError")
                    assertEquals(3, server.requests.size, "Untrusted TLS and invalid trust must not send authenticated MCP requests")
                }
            }
        } finally {
            assertTrue(home.deleteRecursively())
        }
    }
}
