package com.gromozeka.bot

object StreamJsonLineTestData {

    val systemInitMessage = """{
        "type": "system",
        "subtype": "init",
        "data": {
            "model": "claude-3-5-sonnet-20241022",
            "max_tokens": 4096
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val systemErrorMessage = """{
        "type": "system",
        "subtype": "error",
        "data": {
            "error": "Connection failed",
            "code": 500
        }
    }"""

    val userStringMessage = """{
        "type": "user",
        "message": {
            "role": "user",
            "content": "Hello Claude, how are you?"
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val userArrayMessage = """{
        "type": "user",
        "message": {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": "Please run this command:"
                },
                {
                    "type": "tool_result",
                    "tool_use_id": "tool_123",
                    "content": "Command executed successfully"
                }
            ]
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9",
        "parent_tool_use_id": "tool_456"
    }"""

    val assistantTextMessage = """{
        "type": "assistant",
        "message": {
            "role": "assistant",
            "id": "msg_123",
            "model": "claude-3-5-sonnet-20241022",
            "content": [
                {
                    "type": "text",
                    "text": "I'm doing well, thank you for asking!"
                }
            ],
            "stop_reason": "end_turn",
            "usage": {
                "input_tokens": 15,
                "output_tokens": 12
            }
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val assistantToolUseMessage = """{
        "type": "assistant",
        "message": {
            "role": "assistant",
            "id": "msg_456",
            "model": "claude-3-5-sonnet-20241022",
            "content": [
                {
                    "type": "text",
                    "text": "I'll help you check the current directory."
                },
                {
                    "type": "tool_use",
                    "id": "tool_789",
                    "name": "Bash",
                    "input": {
                        "command": "pwd"
                    }
                }
            ],
            "stop_reason": "tool_use",
            "usage": {
                "input_tokens": 25,
                "output_tokens": 18
            }
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val assistantThinkingMessage = """{
        "type": "assistant",
        "message": {
            "role": "assistant",
            "id": "msg_789",
            "model": "claude-3-5-sonnet-20241022",
            "content": [
                {
                    "type": "thinking",
                    "thinking": "The user is asking for help with a file operation. I should check what files are available first.",
                    "signature": "thinking_sig_123"
                },
                {
                    "type": "text",
                    "text": "Let me help you with that file operation."
                }
            ],
            "stop_reason": "end_turn",
            "usage": {
                "input_tokens": 30,
                "output_tokens": 45,
                "cache_read_input_tokens": 100
            }
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val resultSuccessMessage = """{
        "type": "result",
        "subtype": "success",
        "duration_ms": 2500,
        "duration_api_ms": 1800,
        "is_error": false,
        "num_turns": 3,
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9",
        "total_cost_usd": 0.0042,
        "usage": {
            "input_tokens": 150,
            "output_tokens": 85,
            "cache_creation_input_tokens": 50,
            "cache_read_input_tokens": 200,
            "service_tier": "standard"
        }
    }"""

    val resultErrorMessage = """{
        "type": "result",
        "subtype": "error",
        "duration_ms": 1200,
        "duration_api_ms": 800,
        "is_error": true,
        "num_turns": 1,
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9",
        "result": "API rate limit exceeded",
        "usage": {
            "input_tokens": 25,
            "output_tokens": 0
        }
    }"""

    val toolResultStringContent = """{
        "type": "user",
        "message": {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": "tool_789",
                    "content": "/Users/slavik/code/gromozeka/dev"
                }
            ]
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val toolResultArrayContent = """{
        "type": "user",
        "message": {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": "tool_abc",
                    "content": [
                        {
                            "type": "text",
                            "text": "File contents:"
                        },
                        {
                            "type": "text", 
                            "text": "Hello World"
                        }
                    ],
                    "is_error": false
                }
            ]
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val complexAssistantMessage = """{
        "type": "assistant",
        "message": {
            "role": "assistant",
            "id": "msg_complex",
            "model": "claude-3-5-sonnet-20241022",
            "content": [
                {
                    "type": "thinking",
                    "thinking": "I need to analyze this request carefully and provide multiple tools.",
                    "signature": "complex_thinking_sig"
                },
                {
                    "type": "text",
                    "text": "I'll help you with multiple operations:"
                },
                {
                    "type": "tool_use",
                    "id": "tool_read",
                    "name": "Read",
                    "input": {
                        "file_path": "/path/to/file.txt"
                    }
                },
                {
                    "type": "text",
                    "text": "Let me also check the directory structure:"
                },
                {
                    "type": "tool_use",
                    "id": "tool_ls",
                    "name": "LS",
                    "input": {
                        "path": "/path/to/directory"
                    }
                }
            ],
            "stop_reason": "tool_use",
            "usage": {
                "input_tokens": 120,
                "output_tokens": 95,
                "cache_creation_input_tokens": 30,
                "service_tier": "premium"
            }
        },
        "session_id": "00f8f214-a5c5-40cf-a07e-4a3b383a94e9"
    }"""

    val malformedJson = """{
        "type": "user",
        "message": {
            "role": "user",
            "content": "Missing closing brace"
        }
    """

    val unknownMessageType = """{
        "type": "unknown_type",
        "data": "This should not deserialize to any known type"
    }"""
}