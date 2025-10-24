-- Initial database schema for Gromozeka
-- Unified schema (previously V1-V6 migrations)

-- Projects table
CREATE TABLE projects (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    path VARCHAR(500) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    favorite BOOLEAN NOT NULL,
    archived BOOLEAN NOT NULL,
    tags TEXT NOT NULL,
    metadata_json TEXT NULL,
    settings_json TEXT NULL,
    statistics_json TEXT NULL,
    created_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL
);

-- Agents table
CREATE TABLE agents (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    system_prompt TEXT NOT NULL,
    description TEXT NULL,
    is_builtin BOOLEAN NOT NULL,
    usage_count INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- Contexts table
CREATE TABLE contexts (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    files_json TEXT NOT NULL,
    links_json TEXT NOT NULL,
    tags TEXT NOT NULL,
    extracted_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- Conversation Trees table (DAG-based conversation storage)
CREATE TABLE conversation_trees (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NULL,

    -- Fork support (self-reference)
    parent_conversation_id VARCHAR(255) NULL,
    branch_from_message_id VARCHAR(255) NULL,

    -- Navigation
    head_message_id VARCHAR(255) NULL,
    branch_selections_json TEXT DEFAULT '[]',

    tags TEXT DEFAULT '[]',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,

    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_conversation_id) REFERENCES conversation_trees(id) ON DELETE SET NULL
);

CREATE INDEX idx_conversation_trees_project ON conversation_trees(project_id);
CREATE INDEX idx_conversation_trees_updated ON conversation_trees(updated_at);
CREATE INDEX idx_conversation_parent ON conversation_trees(parent_conversation_id);
CREATE INDEX idx_conversation_head ON conversation_trees(head_message_id);

-- Conversation Messages table (DAG nodes)
CREATE TABLE conversation_messages (
    id VARCHAR(255) PRIMARY KEY,
    tree_id VARCHAR(255) NOT NULL,

    -- DAG structure
    parent_ids_json TEXT DEFAULT '[]',

    -- Content
    role VARCHAR(50) NOT NULL,
    timestamp_ms BIGINT NOT NULL,
    message_json TEXT NOT NULL,

    FOREIGN KEY (tree_id) REFERENCES conversation_trees(id) ON DELETE CASCADE
);

CREATE INDEX idx_conversation_messages_tree ON conversation_messages(tree_id);
CREATE INDEX idx_conversation_messages_timestamp ON conversation_messages(tree_id, timestamp_ms);

-- Tool Executions table
CREATE TABLE tool_executions (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    input TEXT NOT NULL,
    output TEXT NULL,
    executed_at BIGINT NOT NULL,
    completed_at BIGINT NULL,
    duration_ms BIGINT NULL,
    status VARCHAR(50) NOT NULL,
    error TEXT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversation_trees(id) ON DELETE CASCADE
);

-- Default builtin agent
INSERT INTO agents (id, name, system_prompt, description, is_builtin, usage_count, created_at, updated_at)
VALUES (
    'default-gromozeka',
    'Gromozeka',
    '# Agent Identity

**You''re Gromozeka** - Multi-armed AI buddy, chill tech friend who helps with coding. Be direct, casual, and real with the user.

**Your Configuration:**
- **Name:** Gromozeka
- **Role Description:** Multi-armed AI assistant specializing in software development, system architecture, and technical problem-solving
- **System Prompt:** You are a direct, casual, and helpful coding companion. You use multiple specialized agents (arms) to tackle complex tasks through collaboration.

# Inter-Agent Communication Rules

**PRIORITY 1: Check for Response Expected Instruction**
When you see `<instruction>response_expected:Response Expected:...` instruction:
- Extract the MCP command from the instruction description
- Follow that exact MCP instruction after completing the main task
- This overrides all other response rules

**PRIORITY 2: Check Message Source**
If no `response_expected` instruction:
- **From User** (`<message_source>user</message_source>`) → Respond directly to user
- **From Agent** (`<message_source>tab:xyz</message_source>`) → Use `tell_agent` with `target_tab_id: xyz`

**Examples:**
```
<instruction>response_expected:Response Expected:Use mcp__gromozeka__tell_agent with target_tab_id: abc123</instruction>
Calculate 2+2
```
→ Calculate 2+2, then use `tell_agent` with `target_tab_id: abc123` to send answer

Without response_expected instruction:
- User asks "What''s 2+2?" → Answer directly: "4"
- Agent shares info → Use `tell_agent` to respond back to that agent
',
    'Multi-armed AI assistant specializing in software development, system architecture, and technical problem-solving',
    1,
    0,
    0,
    0
);
