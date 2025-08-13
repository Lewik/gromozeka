#!/usr/bin/env node

/**
 * Claude Code Node.js SDK Proxy Server with True Streaming Support
 * 
 * Maintains a single continuous session throughout the entire conversation.
 * All messages flow through the same query stream without creating new queries.
 * 
 * Communication Protocol:
 * - Input: JSONL messages via stdin (from Gromozeka)
 * - Output: JSONL messages via stdout (to Gromozeka) - REAL-TIME STREAMING
 * - Errors: Logged to stderr for debugging
 */

import readline from 'readline';
import { execSync } from 'child_process';
import path from 'path';
import fs from 'fs';

// Dynamically find Claude Code installation
function findClaudeCodeSdk() {
    try {
        const claudePath = execSync('which claude', { encoding: 'utf8' }).trim();
        console.error('[NodeSDK Proxy] Found claude at:', claudePath);
        
        const realPath = fs.realpathSync(claudePath);
        console.error('[NodeSDK Proxy] Resolved to:', realPath);
        
        const claudeDir = path.dirname(realPath);
        const sdkPath = path.join(claudeDir, 'sdk.mjs');
        
        if (fs.existsSync(sdkPath)) {
            console.error('[NodeSDK Proxy] Found SDK at:', sdkPath);
            return sdkPath;
        }
        
        const altSdkPath = realPath.replace('/bin/claude', '/lib/node_modules/@anthropic-ai/claude-code/sdk.mjs');
        if (fs.existsSync(altSdkPath)) {
            console.error('[NodeSDK Proxy] Found SDK at:', altSdkPath);
            return altSdkPath;
        }
        
        throw new Error(`SDK not found at expected locations: ${sdkPath} or ${altSdkPath}`);
    } catch (error) {
        console.error('[NodeSDK Proxy] Error finding Claude Code:', error.message);
        throw error;
    }
}

let query;
try {
    const sdkPath = findClaudeCodeSdk();
    const sdkModule = await import(sdkPath);
    query = sdkModule.query;
    console.error('[NodeSDK Proxy] Successfully loaded SDK');
} catch (error) {
    console.error('[NodeSDK Proxy] Failed to load Claude Code SDK:', error.message);
    console.error('[NodeSDK Proxy] Make sure Claude Code is installed: npm install -g @anthropic-ai/claude-code');
    process.exit(1);
}

// Configuration from environment variables
const config = {
    systemPrompt: process.env.CLAUDE_SYSTEM_PROMPT || '',
    permissionMode: process.env.CLAUDE_PERMISSION_MODE || 'default',
    model: process.env.CLAUDE_MODEL || undefined,
    resumeSession: process.env.CLAUDE_RESUME_SESSION || undefined,
    cwd: process.cwd()
};

console.error('[NodeSDK Proxy] Starting with config:', {
    ...config,
    systemPrompt: config.systemPrompt ? `<${config.systemPrompt.length} chars>` : 'none'
});

// Create readline interface for stdin
const rl = readline.createInterface({
    input: process.stdin,
    output: null,
    terminal: false
});

// Session and stream management
let activeQuery = null;
let sessionId = null;
let streamController = null;
let messageQueue = [];
let waitingForMessage = null;
let streamEnded = false;

/**
 * Send JSONL message to stdout (streaming)
 */
function sendMessage(message) {
    const json = JSON.stringify(message);
    console.log(json);
}

/**
 * Log to stderr for debugging
 */
function logError(...args) {
    console.error('[NodeSDK Proxy]', ...args);
}

/**
 * Create a continuous async iterator for streaming input to SDK
 * This iterator will keep yielding messages as they arrive from stdin
 * and only complete when the process is terminated
 */
async function* createContinuousInputStream() {
    logError('Creating continuous input stream');
    
    while (!streamEnded) {
        // Check if we have messages in queue
        if (messageQueue.length > 0) {
            const message = messageQueue.shift();
            logError('Yielding queued message to SDK');
            yield message;
        } else {
            // Wait for next message
            logError('Waiting for next message...');
            const message = await new Promise(resolve => {
                waitingForMessage = resolve;
            });
            
            if (message) {
                logError('Yielding new message to SDK');
                yield message;
            }
        }
    }
    
    logError('Input stream ended');
}

/**
 * Start the single continuous streaming query
 */
async function startContinuousStreamingQuery() {
    try {
        logError('Starting continuous streaming query');
        
        // Create abort controller for this session
        streamController = new AbortController();
        
        // Configure SDK options
        const options = {
            appendSystemPrompt: config.systemPrompt,
            permissionMode: config.permissionMode,
            cwd: config.cwd,
            model: config.model,
            resume: config.resumeSession,
            abortController: streamController
        };
        
        logError('Query options:', {
            ...options,
            appendSystemPrompt: options.appendSystemPrompt ? `<${options.appendSystemPrompt.length} chars>` : 'none'
        });
        
        // Start the query with continuous streaming input
        // This creates a SINGLE query that will handle ALL messages
        activeQuery = query({
            prompt: createContinuousInputStream(),
            options
        });
        
        // Process SDK messages as they arrive (REAL-TIME STREAMING)
        // This loop will continue for the entire conversation
        for await (const sdkMessage of activeQuery) {
            logError('SDK message (streaming):', sdkMessage.type);
            
            // Convert and send message immediately
            const streamMessage = convertSdkToStreamMessage(sdkMessage);
            if (streamMessage) {
                sendMessage(streamMessage);
            }
        }
        
        logError('Query stream ended');
        
    } catch (error) {
        logError('Error in continuous streaming query:', error);
        
        // Send error message
        sendMessage({
            type: 'system',
            subtype: 'error',
            data: {
                error: error.message || 'Unknown error'
            }
        });
    }
}

/**
 * Convert SDK message format to stream-json format
 */
function convertSdkToStreamMessage(sdkMessage) {
    switch (sdkMessage.type) {
        case 'system':
            if (sdkMessage.subtype === 'init') {
                sessionId = sdkMessage.session_id;
                logError('Session initialized:', sessionId);
                
                return {
                    type: 'system',
                    subtype: 'init',
                    data: {
                        session_id: sessionId,
                        model: sdkMessage.model,
                        tools: sdkMessage.tools || [],
                        mcp_servers: sdkMessage.mcp_servers || [],
                        permission_mode: sdkMessage.permissionMode
                    },
                    session_id: sessionId
                };
            }
            break;

        case 'assistant':
            // Stream assistant message immediately
            return {
                type: 'assistant',
                message: sdkMessage.message,
                session_id: sessionId || sdkMessage.session_id,
                parent_tool_use_id: sdkMessage.parent_tool_use_id
            };

        case 'user':
            // Echo user messages (shouldn't happen in normal flow)
            return {
                type: 'user',
                message: sdkMessage.message,
                session_id: sessionId || sdkMessage.session_id,
                parent_tool_use_id: sdkMessage.parent_tool_use_id
            };

        case 'result':
            // Result messages in streaming mode
            // Don't treat result as end of conversation
            logError('Got result message but keeping stream alive');
            return {
                type: 'result',
                subtype: sdkMessage.subtype,
                duration_ms: sdkMessage.duration_ms,
                duration_api_ms: sdkMessage.duration_api_ms,
                is_error: sdkMessage.is_error,
                num_turns: sdkMessage.num_turns,
                total_cost_usd: sdkMessage.total_cost_usd,
                usage: sdkMessage.usage,
                result: sdkMessage.result,
                session_id: sessionId || sdkMessage.session_id
            };

        default:
            logError('Unknown SDK message type:', sdkMessage.type);
            return null;
    }
}

/**
 * Process incoming user message
 */
function processUserMessage(userMessage) {
    logError('Processing user message:', 
        userMessage.message?.content?.[0]?.text?.substring(0, 100) || '<empty>');
    
    // Convert to SDK format
    const sdkMessage = {
        type: 'user',
        message: {
            role: 'user',
            content: userMessage.message.content
        },
        parent_tool_use_id: userMessage.parent_tool_use_id || null,
        session_id: userMessage.session_id || sessionId
    };
    
    // If someone is waiting for a message, resolve immediately
    if (waitingForMessage) {
        logError('Resolving waiting promise with message');
        const resolve = waitingForMessage;
        waitingForMessage = null;
        resolve(sdkMessage);
    } else {
        // Otherwise queue it
        logError('Queueing message');
        messageQueue.push(sdkMessage);
    }
}

/**
 * Process control request (interrupt, etc.)
 */
async function processControlRequest(controlRequest) {
    try {
        logError('Processing control request:', controlRequest.request?.subtype);
        
        if (controlRequest.request?.subtype === 'interrupt') {
            if (activeQuery && typeof activeQuery.interrupt === 'function') {
                await activeQuery.interrupt();
                
                sendMessage({
                    type: 'control_response',
                    response: {
                        request_id: controlRequest.request_id,
                        subtype: 'success'
                    }
                });
            } else {
                sendMessage({
                    type: 'control_response',
                    response: {
                        request_id: controlRequest.request_id,
                        subtype: 'error',
                        error: 'No active query to interrupt'
                    }
                });
            }
        }
    } catch (error) {
        logError('Error processing control request:', error);
        
        sendMessage({
            type: 'control_response',
            response: {
                request_id: controlRequest.request_id,
                subtype: 'error',
                error: error.message || 'Unknown error'
            }
        });
    }
}

// Start the continuous streaming query ONCE
startContinuousStreamingQuery();

// Handle incoming messages from stdin
rl.on('line', async (line) => {
    try {
        const message = JSON.parse(line);
        logError('Received message type:', message.type);
        
        if (message.type === 'user') {
            processUserMessage(message);
        } else if (message.type === 'control_request') {
            await processControlRequest(message);
        }
        
    } catch (error) {
        logError('Failed to parse input:', error);
    }
});

// Handle stdin close
rl.on('close', () => {
    logError('Stdin closed, ending stream...');
    streamEnded = true;
    
    // If waiting for message, resolve with null to end the stream
    if (waitingForMessage) {
        waitingForMessage(null);
    }
    
    if (streamController && !streamController.signal.aborted) {
        streamController.abort();
    }
    
    setTimeout(() => process.exit(0), 100);
});

// Handle process signals
process.on('SIGTERM', () => {
    logError('Received SIGTERM, exiting...');
    streamEnded = true;
    if (streamController && !streamController.signal.aborted) {
        streamController.abort();
    }
    process.exit(0);
});

process.on('SIGINT', () => {
    logError('Received SIGINT, exiting...');
    streamEnded = true;
    if (streamController && !streamController.signal.aborted) {
        streamController.abort();
    }
    process.exit(0);
});

logError('Node.js SDK proxy server started (continuous streaming mode)');