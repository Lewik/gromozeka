#!/usr/bin/env python3
"""
Test script for claude_proxy_server.py
Tests basic functionality of the proxy server
"""

import json
import asyncio
import subprocess
import sys
from pathlib import Path

async def test_proxy_server():
    """Test the proxy server with basic messages"""
    
    # Start the proxy server as a subprocess
    print("Starting claude_proxy_server.py...")
    env = {
        "CLAUDE_PERMISSION_MODE": "acceptEdits",
        "CLAUDE_SYSTEM_PROMPT": "You are a helpful assistant. Keep responses brief.",
        "PATH": os.environ.get("PATH", ""),
        "HOME": os.environ.get("HOME", ""),
    }
    
    proc = await asyncio.create_subprocess_exec(
        sys.executable,
        "claude_proxy_server.py",
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env
    )
    
    print("Proxy server started, PID:", proc.pid)
    
    # Read init message
    print("\nWaiting for init message...")
    line = await proc.stdout.readline()
    if line:
        init_msg = json.loads(line.decode())
        print("Received init:", json.dumps(init_msg, indent=2))
        session_id = init_msg.get("session_id")
    else:
        print("ERROR: No init message received")
        return
    
    # Send a test message
    test_message = {
        "type": "user",
        "message": {
            "role": "user",
            "content": [
                {"type": "text", "text": "What is 2 + 2?"}
            ]
        },
        "session_id": session_id
    }
    
    print("\nSending test message:", json.dumps(test_message, indent=2))
    proc.stdin.write((json.dumps(test_message) + "\n").encode())
    await proc.stdin.drain()
    
    # Read responses for up to 10 seconds
    print("\nReading responses...")
    try:
        async with asyncio.timeout(10):
            while True:
                line = await proc.stdout.readline()
                if not line:
                    break
                    
                response = json.loads(line.decode())
                print(f"Response [{response.get('type')}]:", json.dumps(response, indent=2))
                
                # Break after result message
                if response.get("type") == "result":
                    print("\nReceived result message, test complete!")
                    break
                    
    except asyncio.TimeoutError:
        print("\nTimeout after 10 seconds")
    
    # Clean up
    print("\nShutting down proxy server...")
    proc.terminate()
    await proc.wait()
    
    # Check stderr for any errors
    stderr = await proc.stderr.read()
    if stderr:
        print("\nStderr output:")
        print(stderr.decode())

async def test_interrupt():
    """Test interrupt functionality"""
    print("\n" + "="*50)
    print("Testing interrupt functionality")
    print("="*50)
    
    # Start the proxy server
    env = {
        "CLAUDE_PERMISSION_MODE": "acceptEdits",
        "CLAUDE_SYSTEM_PROMPT": "You are a helpful assistant.",
        "PATH": os.environ.get("PATH", ""),
        "HOME": os.environ.get("HOME", ""),
    }
    
    proc = await asyncio.create_subprocess_exec(
        sys.executable,
        "claude_proxy_server.py",
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env
    )
    
    # Read init
    line = await proc.stdout.readline()
    init_msg = json.loads(line.decode())
    session_id = init_msg.get("session_id")
    
    # Send a long task
    long_task = {
        "type": "user",
        "message": {
            "role": "user",
            "content": [
                {"type": "text", "text": "Count from 1 to 100 slowly"}
            ]
        },
        "session_id": session_id
    }
    
    print("Sending long task...")
    proc.stdin.write((json.dumps(long_task) + "\n").encode())
    await proc.stdin.drain()
    
    # Wait 2 seconds then interrupt
    await asyncio.sleep(2)
    
    interrupt_msg = {
        "type": "control_request",
        "request_id": "int-001",
        "request": {
            "subtype": "interrupt"
        }
    }
    
    print("\nSending interrupt...")
    proc.stdin.write((json.dumps(interrupt_msg) + "\n").encode())
    await proc.stdin.drain()
    
    # Read responses
    try:
        async with asyncio.timeout(5):
            while True:
                line = await proc.stdout.readline()
                if not line:
                    break
                    
                response = json.loads(line.decode())
                print(f"Response [{response.get('type')}]")
                
                if response.get("type") == "control_response":
                    print("Received interrupt confirmation!")
                    break
                    
    except asyncio.TimeoutError:
        print("Timeout waiting for interrupt response")
    
    # Clean up
    proc.terminate()
    await proc.wait()

if __name__ == "__main__":
    import os
    
    # Check if claude-code-sdk is installed
    try:
        import claude_code_sdk
        print("✓ claude-code-sdk is installed")
    except ImportError:
        print("✗ claude-code-sdk is NOT installed")
        print("Install it with: pip install claude-code-sdk")
        sys.exit(1)
    
    print("\nRunning proxy server tests...")
    asyncio.run(test_proxy_server())
    
    # Optionally test interrupt
    # asyncio.run(test_interrupt())