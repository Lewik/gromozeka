import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.io.File
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MCP Server that proxies tool calls to Gromozeka TCP server
 * 
 * Architecture:
 * Claude Code CLI ↔ MCP Protocol ↔ [This Server] ↔ TCP Socket ↔ Gromozeka Main Process
 * 
 * This server handles MCP protocol properly and forwards tool calls to Gromozeka via TCP.
 */

// Logger setup  
val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
val logFile = File("mcp-proxy.log").apply { 
    if (exists()) delete() // Clear previous log
}

fun log(message: String) {
    val timestamp = LocalDateTime.now().format(timeFormatter)
    val logLine = "[$timestamp] $message"
    System.err.println(logLine)
    logFile.appendText("$logLine\n")
}

/**
 * TCP client that forwards tool calls to Gromozeka
 */
class GromozekaTcpClient(private val port: Int) {
    
    suspend fun callTool(toolName: String, arguments: JsonObject): CallToolResult {
        log("[TCP Client] Calling tool: $toolName with args: $arguments")
        
        return try {
            Socket("localhost", port).use { socket ->
                val socketOut = socket.getOutputStream().bufferedWriter()
                val socketIn = socket.getInputStream().bufferedReader()
                
                // Create JSON-RPC request
                val request = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "tools/call")
                    put("params", buildJsonObject {
                        put("name", toolName)
                        put("arguments", arguments)
                    })
                }
                
                val requestJson = Json.encodeToString(JsonObject.serializer(), request)
                log("[TCP Client] Sending: $requestJson")
                
                socketOut.write(requestJson)
                socketOut.newLine()
                socketOut.flush()
                
                // Read response
                val responseJson = socketIn.readLine()
                log("[TCP Client] Received: $responseJson")
                
                val response = Json.parseToJsonElement(responseJson).jsonObject
                val result = response["result"]?.jsonObject
                val error = response["error"]?.jsonObject
                
                if (error != null) {
                    val errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    throw RuntimeException("Tool execution error: $errorMessage")
                }
                
                // Extract content from result
                val content = result?.get("content")?.jsonArray?.map { contentElement ->
                    val contentObj = contentElement.jsonObject
                    val text = contentObj["text"]?.jsonPrimitive?.content ?: ""
                    TextContent(text)
                } ?: listOf(TextContent("Tool executed successfully"))
                
                CallToolResult(content = content)
            }
        } catch (e: Exception) {
            log("[TCP Client] Error calling tool: ${e.message}")
            CallToolResult(
                content = listOf(TextContent("Error: ${e.message}")),
                isError = true
            )
        }
    }
}

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 42777
    
    log("[MCP Proxy] Starting MCP server, will connect to Gromozeka on port $port")
    
    val tcpClient = GromozekaTcpClient(port)
    
    val server = Server(
        Implementation(
            name = "gromozeka-self-control",
            version = "1.0.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
        )
    )
    
    // Add tools that will be proxied to Gromozeka
    server.addTool(
        name = "hello_world",
        description = "Test tool that returns a greeting from Gromozeka",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                // Empty properties for hello_world
            },
            required = emptyList()
        )
    ) { request ->
        tcpClient.callTool("hello_world", request.arguments)
    }
    
    server.addTool(
        name = "open_tab", 
        description = "Open a new tab with Claude session for specified project",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("project_path") {
                    put("type", "string")
                    put("description", "Path to the project directory")
                }
                putJsonObject("resume_session_id") {
                    put("type", "string")
                    put("description", "Optional Claude session ID to resume")
                }
                putJsonObject("initial_message") {
                    put("type", "string") 
                    put("description", "Optional initial message to send after creating session")
                }
            },
            required = listOf("project_path")
        )
    ) { request ->
        tcpClient.callTool("open_tab", request.arguments)
    }
    
    server.addTool(
        name = "switch_tab",
        description = "Switch to specified tab by index", 
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("tab_index") {
                    put("type", "integer")
                    put("description", "Index of the tab to switch to (0-based)")
                }
            },
            required = listOf("tab_index")
        )
    ) { request ->
        tcpClient.callTool("switch_tab", request.arguments)
    }
    
    server.addTool(
        name = "list_tabs",
        description = "Get list of all open tabs with their information",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                // Empty properties for list_tabs
            },
            required = emptyList()
        )
    ) { request ->
        tcpClient.callTool("list_tabs", request.arguments)
    }
    
    log("[MCP Proxy] Server configured, starting STDIO transport...")
    
    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )
    
    runBlocking {
        server.connect(transport)
        val done = Job()
        server.onClose {
            done.complete()
        }
        done.join()
    }
}