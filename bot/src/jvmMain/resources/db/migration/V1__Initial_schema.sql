-- Initial database schema for Gromozeka
-- Unified schema combining all previous migrations

-- Projects table
CREATE TABLE projects (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    path VARCHAR(500) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    favorite BOOLEAN NOT NULL,
    archived BOOLEAN NOT NULL,
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

-- Conversations - основная таблица
CREATE TABLE conversations (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL DEFAULT '',

    -- Ссылка на текущий Thread (всегда существует)
    current_thread_id VARCHAR(255) NOT NULL,

    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,

    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE INDEX idx_conversations_project ON conversations(project_id);
CREATE INDEX idx_conversations_updated ON conversations(updated_at);
CREATE INDEX idx_conversations_current_thread ON conversations(current_thread_id);

-- Threads - метаданные списка сообщений
CREATE TABLE threads (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,

    -- Ссылка на Thread, из которого был создан этот Thread
    original_thread_id VARCHAR(255) NULL,

    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,

    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

CREATE INDEX idx_threads_conversation ON threads(conversation_id);
CREATE INDEX idx_threads_created ON threads(conversation_id, created_at DESC);
CREATE INDEX idx_threads_original ON threads(original_thread_id);

-- Thread Messages - связь Thread с Message + порядок
CREATE TABLE thread_messages (
    thread_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    position INT NOT NULL,

    PRIMARY KEY (thread_id, message_id),
    FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE,
    FOREIGN KEY (message_id) REFERENCES messages(id)
);

CREATE INDEX idx_thread_messages_thread_position ON thread_messages(thread_id, position);
CREATE INDEX idx_thread_messages_message ON thread_messages(message_id);

-- Messages - сообщения принадлежат conversation
CREATE TABLE messages (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,

    -- История происхождения (edit/squash) - DEPRECATED, use squash_operation_id
    original_ids_json TEXT NOT NULL DEFAULT '[]',

    -- Reply threading (future feature)
    reply_to_id VARCHAR(255) NULL,

    -- Squash operation reference
    squash_operation_id VARCHAR(255) NULL,

    role VARCHAR(50) NOT NULL,
    created_at BIGINT NOT NULL,

    -- Полная сериализация Message (content + instructions)
    message_json TEXT NOT NULL,

    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_messages_created ON messages(conversation_id, created_at);

-- Squash Operations - immutable record of squash provenance
CREATE TABLE squash_operations (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,

    -- Source messages that were squashed
    source_message_ids TEXT NOT NULL,

    -- Result message ID
    result_message_id VARCHAR(255) NOT NULL,

    -- AI squash prompt (nullable for manual edits)
    prompt TEXT NULL,

    -- Model used for squash
    model VARCHAR(255) NULL,

    -- true = AI squash, false = manual edit
    performed_by_agent BOOLEAN NOT NULL,

    created_at BIGINT NOT NULL,

    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    FOREIGN KEY (result_message_id) REFERENCES messages(id)
);

CREATE INDEX idx_squash_operations_conversation ON squash_operations(conversation_id);
CREATE INDEX idx_squash_operations_result_message ON squash_operations(result_message_id);

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
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
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
