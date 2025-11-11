# Claude Code MCP Tools

This document describes all 16 tools available from Claude Code MCP server (`claude mcp serve`).

---

## Task

Launch a new agent to handle complex, multi-step tasks autonomously.

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "description": {
      "type": "string",
      "description": "A short (3-5 word) description of the task"
    },
    "prompt": {
      "type": "string",
      "description": "The task for the agent to perform"
    },
    "subagent_type": {
      "type": "string",
      "description": "The type of specialized agent to use for this task"
    },
    "model": {
      "type": "string",
      "enum": ["sonnet", "opus", "haiku"],
      "description": "Optional model to use for this agent. If not specified, inherits from parent. Prefer haiku for quick, straightforward tasks to minimize cost and latency."
    },
    "resume": {
      "type": "string",
      "description": "Optional agent ID to resume from. If provided, the agent will continue from the previous execution transcript."
    }
  },
  "required": ["description", "prompt", "subagent_type"],
  "type": "object"
}
```

---

## Bash

Executes a given bash command in a persistent shell session with optional timeout, ensuring proper handling and security measures.

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "command": {
      "type": "string",
      "description": "The command to execute"
    },
    "timeout": {
      "type": "number",
      "description": "Optional timeout in milliseconds (max 600000)"
    },
    "description": {
      "type": "string",
      "description": "Clear, concise description of what this command does in 5-10 words, in active voice. Examples:\nInput: ls\nOutput: List files in current directory\n\nInput: git status\nOutput: Show working tree status\n\nInput: npm install\nOutput: Install package dependencies\n\nInput: mkdir foo\nOutput: Create directory 'foo'"
    },
    "run_in_background": {
      "type": "boolean",
      "description": "Set to true to run this command in the background. Use BashOutput to read the output later."
    },
    "dangerouslyDisableSandbox": {
      "type": "boolean",
      "description": "Set this to true to dangerously override sandbox mode and run commands without sandboxing."
    }
  },
  "required": ["command"],
  "type": "object"
}
```

---

## Glob

Fast file pattern matching tool that works with any codebase size

**Description:**
- Supports glob patterns like "**/*.js" or "src/**/*.ts"
- Returns matching file paths sorted by modification time
- Use this tool when you need to find files by name patterns
- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead
- You can call multiple tools in a single response. It is always better to speculatively perform multiple searches in parallel if they are potentially useful.

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "pattern": {
      "type": "string",
      "description": "The glob pattern to match files against"
    },
    "path": {
      "type": "string",
      "description": "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided."
    }
  },
  "required": ["pattern"],
  "type": "object"
}
```

---

## Grep

A powerful search tool built on ripgrep

**Description:**
- ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command
- Supports full regex syntax (e.g., "log.*Error", "function\\s+\\w+")
- Filter files with glob parameter (e.g., "*.js", "**/*.tsx") or type parameter (e.g., "js", "py", "rust")
- Output modes: "content" shows matching lines, "files_with_matches" shows only file paths (default), "count" shows match counts
- Use Task tool for open-ended searches requiring multiple rounds
- Pattern syntax: Uses ripgrep (not grep) - literal braces need escaping (use `interface\\{\\}` to find `interface{}` in Go code)
- Multiline matching: By default patterns match within single lines only. For cross-line patterns like `struct \\{[\\s\\S]*?field`, use `multiline: true`

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "pattern": {
      "type": "string",
      "description": "The regular expression pattern to search for in file contents"
    },
    "path": {
      "type": "string",
      "description": "File or directory to search in (rg PATH). Defaults to current working directory."
    },
    "glob": {
      "type": "string",
      "description": "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob"
    },
    "output_mode": {
      "type": "string",
      "enum": ["content", "files_with_matches", "count"],
      "description": "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\"."
    },
    "-B": {
      "type": "number",
      "description": "Number of lines to show before each match (rg -B). Requires output_mode: \"content\", ignored otherwise."
    },
    "-A": {
      "type": "number",
      "description": "Number of lines to show after each match (rg -A). Requires output_mode: \"content\", ignored otherwise."
    },
    "-C": {
      "type": "number",
      "description": "Number of lines to show before and after each match (rg -C). Requires output_mode: \"content\", ignored otherwise."
    },
    "-n": {
      "type": "boolean",
      "description": "Show line numbers in output (rg -n). Requires output_mode: \"content\", ignored otherwise. Defaults to true."
    },
    "-i": {
      "type": "boolean",
      "description": "Case insensitive search (rg -i)"
    },
    "type": {
      "type": "string",
      "description": "File type to search (rg --type). Common types: js, py, rust, go, java, etc. More efficient than include for standard file types."
    },
    "head_limit": {
      "type": "number",
      "description": "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults based on \"cap\" experiment value: 0 (unlimited), 20, or 100."
    },
    "offset": {
      "type": "number",
      "description": "Skip first N lines/entries before applying head_limit, equivalent to \"| tail -n +N | head -N\". Works across all output modes. Defaults to 0."
    },
    "multiline": {
      "type": "boolean",
      "description": "Enable multiline mode where . matches newlines and patterns can span lines (rg -U --multiline-dotall). Default: false."
    }
  },
  "required": ["pattern"],
  "type": "object"
}
```

---

## ExitPlanMode

Use this tool when you are in plan mode and have finished presenting your plan and are ready to code. This will prompt the user to exit plan mode.

**Important Notes:**
- Only use this tool when the task requires planning the implementation steps of a task that requires writing code
- For research tasks where you're gathering information, searching files, reading files or in general trying to understand the codebase - do NOT use this tool

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "plan": {
      "type": "string",
      "description": "The plan you came up with, that you want to run by the user for approval. Supports markdown. The plan should be pretty concise."
    }
  },
  "required": ["plan"],
  "type": "object"
}
```

---

## Read

Reads a file from the local filesystem. You can access any file directly by using this tool.

**Description:**
- Assume this tool is able to read all files on the machine
- The file_path parameter must be an absolute path, not a relative path
- By default, it reads up to 2000 lines starting from the beginning of the file
- You can optionally specify a line offset and limit (especially handy for long files), but it's recommended to read the whole file by not providing these parameters
- Any lines longer than 2000 characters will be truncated
- Results are returned using cat -n format, with line numbers starting at 1
- This tool allows Claude Code to read images (eg PNG, JPG, etc). When reading an image file the contents are presented visually as Claude Code is a multimodal LLM
- This tool can read PDF files (.pdf). PDFs are processed page by page, extracting both text and visual content for analysis
- This tool can read Jupyter notebooks (.ipynb files) and returns all cells with their outputs, combining code, text, and visualizations
- This tool can only read files, not directories. To read a directory, use an ls command via the Bash tool
- You can call multiple tools in a single response. It is always better to speculatively read multiple potentially useful files in parallel
- You will regularly be asked to read screenshots. If the user provides a path to a screenshot, ALWAYS use this tool to view the file at the path. This tool will work with all temporary file paths
- If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "file_path": {
      "type": "string",
      "description": "The absolute path to the file to read"
    },
    "offset": {
      "type": "number",
      "description": "The line number to start reading from. Only provide if the file is too large to read at once"
    },
    "limit": {
      "type": "number",
      "description": "The number of lines to read. Only provide if the file is too large to read at once."
    }
  },
  "required": ["file_path"],
  "type": "object"
}
```

---

## Edit

Performs exact string replacements in files.

**Description:**
- You must use your `Read` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file
- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix
- The line number prefix format is: spaces + line number + tab. Everything after that tab is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string
- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required
- Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked
- The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`
- Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "file_path": {
      "type": "string",
      "description": "The absolute path to the file to modify"
    },
    "old_string": {
      "type": "string",
      "description": "The text to replace"
    },
    "new_string": {
      "type": "string",
      "description": "The text to replace it with (must be different from old_string)"
    },
    "replace_all": {
      "type": "boolean",
      "default": false,
      "description": "Replace all occurences of old_string (default false)"
    }
  },
  "required": ["file_path", "old_string", "new_string"],
  "type": "object"
}
```

---

## Write

Writes a file to the local filesystem.

**Description:**
- This tool will overwrite the existing file if there is one at the provided path
- If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first
- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required
- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User
- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "file_path": {
      "type": "string",
      "description": "The absolute path to the file to write (must be absolute, not relative)"
    },
    "content": {
      "type": "string",
      "description": "The content to write to the file"
    }
  },
  "required": ["file_path", "content"],
  "type": "object"
}
```

---

## NotebookEdit

Completely replaces the contents of a specific cell in a Jupyter notebook (.ipynb file) with new source.

**Description:**
- Jupyter notebooks are interactive documents that combine code, text, and visualizations, commonly used for data analysis and scientific computing
- The notebook_path parameter must be an absolute path, not a relative path
- The cell_number is 0-indexed
- Use edit_mode=insert to add a new cell at the index specified by cell_number
- Use edit_mode=delete to delete the cell at the index specified by cell_number

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "notebook_path": {
      "type": "string",
      "description": "The absolute path to the Jupyter notebook file to edit (must be absolute, not relative)"
    },
    "cell_id": {
      "type": "string",
      "description": "The ID of the cell to edit. When inserting a new cell, the new cell will be inserted after the cell with this ID, or at the beginning if not specified."
    },
    "new_source": {
      "type": "string",
      "description": "The new source for the cell"
    },
    "cell_type": {
      "type": "string",
      "enum": ["code", "markdown"],
      "description": "The type of the cell (code or markdown). If not specified, it defaults to the current cell type. If using edit_mode=insert, this is required."
    },
    "edit_mode": {
      "type": "string",
      "enum": ["replace", "insert", "delete"],
      "description": "The type of edit to make (replace, insert, delete). Defaults to replace."
    }
  },
  "required": ["notebook_path", "new_source"],
  "type": "object"
}
```

---

## WebFetch

Fetches content from a specified URL and processes it using an AI model.

**Description:**
- Takes a URL and a prompt as input
- Fetches the URL content, converts HTML to markdown
- Processes the content with the prompt using a small, fast model
- Returns the model's response about the content
- Use this tool when you need to retrieve and analyze web content

**Usage notes:**
- IMPORTANT: If an MCP-provided web fetch tool is available, prefer using that tool instead of this one, as it may have fewer restrictions. All MCP-provided tools start with "mcp__"
- The URL must be a fully-formed valid URL
- HTTP URLs will be automatically upgraded to HTTPS
- The prompt should describe what information you want to extract from the page
- This tool is read-only and does not modify any files
- Results may be summarized if the content is very large
- Includes a self-cleaning 15-minute cache for faster responses when repeatedly accessing the same URL
- When a URL redirects to a different host, the tool will inform you and provide the redirect URL in a special format. You should then make a new WebFetch request with the redirect URL to fetch the content

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "url": {
      "type": "string",
      "format": "uri",
      "description": "The URL to fetch content from"
    },
    "prompt": {
      "type": "string",
      "description": "The prompt to run on the fetched content"
    }
  },
  "required": ["url", "prompt"],
  "type": "object"
}
```

---

## TodoWrite

Use this tool to create and manage a structured task list for your current coding session. This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.

**When to Use This Tool:**
Use this tool proactively in these scenarios:
1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
3. User explicitly requests todo list - When the user directly asks you to use the todo list
4. User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)
5. After receiving new instructions - Immediately capture user requirements as todos
6. When you start working on a task - Mark it as in_progress BEFORE beginning work. Ideally you should only have one todo as in_progress at a time
7. After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation

**When NOT to Use This Tool:**
Skip using this tool when:
1. There is only a single, straightforward task
2. The task is trivial and tracking it provides no organizational benefit
3. The task can be completed in less than 3 trivial steps
4. The task is purely conversational or informational

NOTE that you should not use this tool if there is only one trivial task to do. In this case you are better off just doing the task directly.

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "todos": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "content": {
            "type": "string",
            "minLength": 1
          },
          "status": {
            "type": "string",
            "enum": ["pending", "in_progress", "completed"]
          },
          "activeForm": {
            "type": "string",
            "minLength": 1
          }
        },
        "required": ["content", "status", "activeForm"],
        "additionalProperties": false
      },
      "description": "The updated todo list"
    }
  },
  "required": ["todos"],
  "type": "object"
}
```

---

## WebSearch

Allows Claude to search the web and use the results to inform responses.

**Description:**
- Provides up-to-date information for current events and recent data
- Returns search result information formatted as search result blocks
- Use this tool for accessing information beyond Claude's knowledge cutoff
- Searches are performed automatically within a single API call

**Usage notes:**
- Domain filtering is supported to include or block specific websites
- Web search is only available in the US
- Account for "Today's date" in <env>. For example, if <env> says "Today's date: 2025-07-01", and the user wants the latest docs, do not use 2024 in the search query. Use 2025

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "query": {
      "type": "string",
      "minLength": 2,
      "description": "The search query to use"
    },
    "allowed_domains": {
      "type": "array",
      "items": {
        "type": "string"
      },
      "description": "Only include search results from these domains"
    },
    "blocked_domains": {
      "type": "array",
      "items": {
        "type": "string"
      },
      "description": "Never include search results from these domains"
    }
  },
  "required": ["query"],
  "type": "object"
}
```

---

## BashOutput

Retrieves output from a running or completed background bash shell.

**Description:**
- Takes a shell_id parameter identifying the shell
- Always returns only new output since the last check
- Returns stdout and stderr output along with shell status
- Supports optional regex filtering to show only lines matching a pattern
- Use this tool when you need to monitor or check the output of a long-running shell
- Shell IDs can be found using the /bashes command

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "bash_id": {
      "type": "string",
      "description": "The ID of the background shell to retrieve output from"
    },
    "filter": {
      "type": "string",
      "description": "Optional regular expression to filter the output lines. Only lines matching this regex will be included in the result. Any lines that do not match will no longer be available to read."
    }
  },
  "required": ["bash_id"],
  "type": "object"
}
```

---

## KillShell

Kills a running background bash shell by its ID.

**Description:**
- Takes a shell_id parameter identifying the shell to kill
- Returns a success or failure status
- Use this tool when you need to terminate a long-running shell
- Shell IDs can be found using the /bashes command

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "shell_id": {
      "type": "string",
      "description": "The ID of the background shell to kill"
    }
  },
  "required": ["shell_id"],
  "type": "object"
}
```

---

## Skill

Execute a skill within the main conversation.

**Description:**
- When users ask you to perform tasks, check if any of the available skills can help complete the task more effectively
- Skills provide specialized capabilities and domain knowledge
- Only use skills listed in <available_skills>
- Do not invoke a skill that is already running
- Do not use this tool for built-in CLI commands (like /help, /clear, etc.)

**How to use skills:**
- Invoke skills using this tool with the skill name only (no arguments)
- When you invoke a skill, you will see <command-message>The "{name}" skill is loading</command-message>
- The skill's prompt will expand and provide detailed instructions on how to complete the task

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "skill": {
      "type": "string",
      "description": "The skill name (no arguments). E.g., \"pdf\" or \"xlsx\""
    }
  },
  "required": ["skill"],
  "type": "object"
}
```

---

## SlashCommand

Execute a slash command within the main conversation.

**Description:**
- When you use this tool or when a user types a slash command, you will see <command-message>{name} is running…</command-message> followed by the expanded prompt
- For example, if .claude/commands/foo.md contains "Print today's date", then /foo expands to that prompt in the next message

**Usage:**
- `command` (required): The slash command to execute, including any arguments
- Example: `command: "/review-pr 123"`

**Important:**
- Only use this tool for custom slash commands that appear in the Available Commands list
- Do NOT use for built-in CLI commands (like /help, /clear, etc.)
- Do NOT use for commands not shown in the list
- Do NOT use for commands you think might exist but aren't listed
- Do not invoke a command that is already running

**Parameters (JSON Schema):**
```json
{
  "properties": {
    "command": {
      "type": "string",
      "description": "The slash command to execute with its arguments, e.g., \"/review-pr 123\""
    }
  },
  "required": ["command"],
  "type": "object"
}
```

---

## Summary

Total: **16 tools** available from Claude Code MCP server

### File Operations (4)
- Read - Read files (supports images, PDFs, notebooks)
- Edit - Exact string replacements
- Write - Create/overwrite files
- NotebookEdit - Edit Jupyter notebook cells

### Search & Discovery (2)
- Glob - File pattern matching
- Grep - Content search with ripgrep

### Execution (3)
- Bash - Execute shell commands
- BashOutput - Read background process output
- KillShell - Terminate background processes

### Web & Network (2)
- WebFetch - Fetch and analyze web content
- WebSearch - Search the web

### Task Management (2)
- Task - Launch specialized sub-agents
- TodoWrite - Manage task lists

### Workflow (3)
- ExitPlanMode - Exit planning mode
- Skill - Execute skills
- SlashCommand - Execute slash commands
