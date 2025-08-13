#!/usr/bin/env python3
"""
Claude Code Proxy Server - обертка над Python SDK для Gromozeka
Принимает JSON через stdin/stdout и проксирует в Claude Code SDK
"""

import asyncio
import json
import os
import sys
import uuid
from typing import AsyncIterator, Any, Dict, Optional
from claude_code_sdk import (
    ClaudeSDKClient,
    ClaudeCodeOptions,
    UserMessage,
    AssistantMessage,
    SystemMessage,
    ResultMessage,
    TextBlock,
    ToolUseBlock,
    ToolResultBlock,
    ThinkingBlock,
)

class ClaudeProxy:
    def __init__(self):
        self.client = None
        self.session_id = str(uuid.uuid4())
        self.active_session_id = None
        self.request_counter = 0
        
    async def start(self):
        """Initialize Claude SDK client"""
        # Build options from environment variables
        options = ClaudeCodeOptions(
            permission_mode=os.environ.get("CLAUDE_PERMISSION_MODE", "acceptEdits"),
            system_prompt=os.environ.get("CLAUDE_SYSTEM_PROMPT"),
            model=os.environ.get("CLAUDE_MODEL"),
            resume=os.environ.get("CLAUDE_RESUME_SESSION"),
            max_thinking_tokens=10000,
        )
        
        self.client = ClaudeSDKClient(options)
        await self.client.connect()
        
        # Start receiving task
        asyncio.create_task(self.receive_from_claude())
        
    async def receive_from_claude(self):
        """Continuously receive messages from Claude"""
        try:
            async for message in self.client.receive_messages():
                # Convert SDK message to stream-json format
                output = self.convert_to_stream_json(message)
                if output:
                    print(json.dumps(output, ensure_ascii=False), flush=True)
        except asyncio.CancelledError:
            # Normal cancellation, don't print error
            pass
        except Exception as e:
            error_msg = {
                "type": "system",
                "subtype": "error",
                "data": {"error": str(e)}
            }
            print(json.dumps(error_msg, ensure_ascii=False), flush=True)
    
    def convert_to_stream_json(self, message) -> Optional[Dict[str, Any]]:
        """Convert SDK message to stream-json format compatible with Gromozeka"""
        try:
            # Convert different message types
            if isinstance(message, UserMessage):
                # Convert user message
                content = self._convert_content_blocks(message.content)
                return {
                    "type": "user",
                    "message": {
                        "role": "user",
                        "content": content
                    },
                    "session_id": self.active_session_id
                }
                
            elif isinstance(message, AssistantMessage):
                # Convert assistant message
                content = self._convert_content_blocks(message.content)
                return {
                    "type": "assistant",
                    "message": {
                        "role": "assistant",
                        "content": content,
                        "model": message.model
                    },
                    "session_id": self.active_session_id
                }
                
            elif isinstance(message, SystemMessage):
                # System messages - handle init and other subtypes
                if message.subtype == "init":
                    # Extract session ID from system message if available
                    if "session_id" in message.data:
                        self.active_session_id = message.data["session_id"]
                    elif not self.active_session_id:
                        self.active_session_id = self.session_id
                        
                return {
                    "type": "system",
                    "subtype": message.subtype,
                    "data": message.data,
                    "session_id": self.active_session_id
                }
                
            elif isinstance(message, ResultMessage):
                # Result message with usage stats - fields at top level for compatibility
                return {
                    "type": "result",
                    "subtype": message.subtype,
                    "duration_ms": message.duration_ms,
                    "duration_api_ms": message.duration_api_ms,
                    "is_error": message.is_error,
                    "num_turns": message.num_turns,
                    "session_id": message.session_id or self.active_session_id,
                    "total_cost_usd": message.total_cost_usd,
                    "usage": message.usage,
                    "result": message.result
                }
            else:
                # Unknown message type - log for debugging
                return {
                    "type": "system",
                    "subtype": "unknown_message",
                    "data": {"message_type": type(message).__name__, "content": str(message)}
                }
                
        except Exception as e:
            return {
                "type": "system",
                "subtype": "conversion_error", 
                "data": {"error": str(e), "message_type": type(message).__name__}
            }
    
    def _convert_content_blocks(self, content) -> list:
        """Convert SDK content blocks to stream-json format"""
        if isinstance(content, str):
            # Simple string content
            return [{"type": "text", "text": content}]
        
        if isinstance(content, list):
            # List of content blocks
            result = []
            for block in content:
                if isinstance(block, TextBlock):
                    result.append({"type": "text", "text": block.text})
                elif isinstance(block, ToolUseBlock):
                    result.append({
                        "type": "tool_use",
                        "id": block.id,
                        "name": block.name,
                        "input": block.input
                    })
                elif isinstance(block, ToolResultBlock):
                    result.append({
                        "type": "tool_result",
                        "tool_use_id": block.tool_use_id,
                        "content": block.content,
                        "is_error": block.is_error
                    })
                elif isinstance(block, ThinkingBlock):
                    result.append({
                        "type": "thinking",
                        "thinking": block.thinking,
                        "signature": block.signature
                    })
            return result
        
        # Fallback - convert to string
        return [{"type": "text", "text": str(content)}]
    
    async def handle_input(self, line: str):
        """Handle input from Gromozeka"""
        try:
            data = json.loads(line)
            
            if data.get("type") == "user":
                # Extract message content and session ID
                message_data = data["message"]
                content_blocks = message_data.get("content", [])
                session_id = data.get("session_id", self.session_id)
                
                # Store active session ID
                self.active_session_id = session_id
                
                # Convert content blocks to text (SDK expects string for simple queries)
                text_content = ""
                for block in content_blocks:
                    if isinstance(block, dict) and block.get("type") == "text":
                        text_content += block.get("text", "")
                
                # Send to Claude via SDK
                await self.client.query(text_content, session_id=session_id)
                
            elif data.get("type") == "control_request":
                # Handle control messages
                request_id = data.get("request_id")
                request = data.get("request", {})
                subtype = request.get("subtype")
                
                if subtype == "interrupt":
                    # Send interrupt to SDK
                    await self.client.interrupt()
                    
                    # Send control response
                    response = {
                        "type": "control_response",
                        "response": {
                            "request_id": request_id,
                            "subtype": "interrupt",
                            "error": None
                        }
                    }
                    print(json.dumps(response, ensure_ascii=False), flush=True)
                    
        except json.JSONDecodeError as e:
            error_msg = {
                "type": "system",
                "subtype": "parse_error",
                "data": {"error": f"Invalid JSON: {e}", "line": line}
            }
            print(json.dumps(error_msg, ensure_ascii=False), flush=True)
        except Exception as e:
            error_msg = {
                "type": "system",
                "subtype": "error",
                "data": {"error": str(e)}
            }
            print(json.dumps(error_msg, ensure_ascii=False), flush=True)
    
    async def run(self):
        """Main loop - read from stdin, write to stdout"""
        await self.start()
        
        # Don't send our own init - Claude SDK will send one
        # Just store initial session ID
        self.active_session_id = self.session_id
        
        # Read from stdin
        loop = asyncio.get_event_loop()
        reader = asyncio.StreamReader()
        protocol = asyncio.StreamReaderProtocol(reader)
        await loop.connect_read_pipe(lambda: protocol, sys.stdin)
        
        while True:
            try:
                line_bytes = await reader.readline()
                if not line_bytes:
                    break
                line = line_bytes.decode('utf-8').strip()
                if line:
                    await self.handle_input(line)
            except asyncio.CancelledError:
                break
            except Exception as e:
                error_msg = {
                    "type": "system",
                    "subtype": "error",
                    "data": {"error": str(e)}
                }
                print(json.dumps(error_msg, ensure_ascii=False), flush=True)
    
    async def cleanup(self):
        """Clean up resources"""
        if self.client:
            try:
                await self.client.disconnect()
            except Exception as e:
                # Log but don't fail on cleanup errors
                sys.stderr.write(f"Cleanup error: {e}\n")

async def main():
    """Main entry point"""
    proxy = ClaudeProxy()
    try:
        await proxy.run()
    except KeyboardInterrupt:
        sys.stderr.write("Interrupted by user\n")
    except Exception as e:
        sys.stderr.write(f"Fatal error: {e}\n")
        raise
    finally:
        await proxy.cleanup()

if __name__ == "__main__":
    # Set up event loop and run
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
    except Exception:
        sys.exit(1)