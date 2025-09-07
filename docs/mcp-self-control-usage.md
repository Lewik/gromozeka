# MCP Self-Control Usage Guide

## Overview

MCP Self-Control allows Claude Code CLI to control the Gromozeka application through the Model Context Protocol (MCP). This enables Claude to manage tabs, send messages, and interact with the Gromozeka UI directly.

## Architecture

```
Claude Code CLI
    ↓ (STDIO JSONL)
mcp-proxy.jar (STDIO-TCP Proxy)  
    ↓ (TCP Socket localhost:42777)
Gromozeka Main Process (McpSelfControlServer)
    ↓ (Direct method calls)
AppViewModel methods (createTab, selectTab, etc.)
```

## Installation

### 1. Build the MCP Proxy

The `mcp-proxy.jar` is automatically built when you compile the main project:

```bash
./gradlew :bot:build
```

The generated JAR will be located at: `mcp-proxy/build/libs/mcp-proxy.jar`

### 2. Configure Claude Code CLI

Add the MCP server to your Claude Code CLI configuration:

```bash
claude mcp add gromozeka -- java -jar /path/to/mcp-proxy.jar 42777
```

Replace `/path/to/mcp-proxy.jar` with the absolute path to your built JAR file.

### 3. Start Gromozeka

Launch the Gromozeka application. The MCP TCP server will automatically start on port 42777.

## Available Tools

### 1. hello_world

Test tool to verify MCP connectivity.

**Usage:**
```
Hello, can you test the gromozeka self-control?
```

### 2. open_tab

Opens a new tab with a Claude session for the specified project.

**Parameters:**
- `project_path` (required): Path to the project directory
- `resume_session_id` (optional): Claude session ID to resume
- `initial_message` (optional): Message to send after creating the session

**Usage:**
```
Open a new tab for project /path/to/my-project
```

```
Open a tab for /path/to/project and send the message "Hello, analyze this codebase"
```

### 3. switch_tab

Switches to a specific tab by index.

**Parameters:**
- `tab_index` (required): Index of the tab to switch to (0-based)

**Usage:**
```
Switch to tab 2
```

### 4. list_tabs

Lists all open tabs with their information.

**Usage:**
```
What tabs are currently open?
```

## Example Workflow

1. **Start Gromozeka** - The MCP server starts automatically
2. **Configure Claude Code** - Add the MCP server as shown above  
3. **Use Claude Code CLI** with MCP commands:

```bash
# Example interaction
claude -p "Open a tab for ~/code/my-project and send 'Analyze the main.kt file'"

# Or through interactive mode
claude
> Open a new tab for ~/code/gromozeka/dev1
> Switch to tab 1  
> List all open tabs
```

## Troubleshooting

### Connection Issues

If Claude Code CLI cannot connect to the MCP server:

1. **Check if Gromozeka is running** - The TCP server only starts when the main application is running
2. **Verify port 42777 is available** - Make sure no other process is using port 42777
3. **Check JAR file path** - Ensure the path to `mcp-proxy.jar` is correct in your MCP configuration

### Debug Logging

The MCP proxy writes debug information to STDERR:

```bash
# View MCP proxy logs  
claude mcp list  # To see configured servers
# MCP proxy will show connection attempts and data flow
```

The main Gromozeka application logs MCP activities to the console.

### Port Configuration

By default, the MCP server uses port 42777. To change this:

1. Modify `DEFAULT_PORT` in `McpSelfControlServer.kt`
2. Update the MCP proxy command to use the new port:
   ```bash
   claude mcp add gromozeka -- java -jar mcp-proxy.jar 43000
   ```

## Security Notes

- The MCP server only listens on `localhost` - no external network access
- Only basic tab and session management operations are exposed
- The proxy provides a secure bridge between Claude Code CLI and Gromozeka

## Technical Details

### Protocol Flow

1. Claude Code CLI sends JSON-RPC 2.0 messages via STDIO to mcp-proxy.jar
2. mcp-proxy.jar forwards messages via TCP socket to Gromozeka  
3. McpSelfControlServer in Gromozeka processes JSON-RPC requests
4. Responses flow back through the same chain

### Error Handling

The system includes comprehensive error handling:
- JSON parsing errors return proper JSON-RPC error responses
- Tool execution errors are caught and reported to Claude
- Network connection issues are logged for debugging

### Performance

- TCP socket communication provides low-latency tool calls
- Session state is maintained in memory for fast access
- No persistence layer needed - operates on live application state