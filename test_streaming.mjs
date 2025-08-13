#!/usr/bin/env node

/**
 * Test script for Claude Code SDK streaming
 * Verifies that we get only ONE init message for multiple user messages
 */

import { execSync } from 'child_process';
import path from 'path';
import fs from 'fs';

// Find Claude Code SDK the same way as proxy server
function findClaudeCodeSdk() {
    try {
        const claudePath = execSync('which claude', { encoding: 'utf8' }).trim();
        const realPath = fs.realpathSync(claudePath);
        const claudeDir = path.dirname(realPath);
        const sdkPath = path.join(claudeDir, 'sdk.mjs');
        
        if (fs.existsSync(sdkPath)) {
            return sdkPath;
        }
        
        const altSdkPath = realPath.replace('/bin/claude', '/lib/node_modules/@anthropic-ai/claude-code/sdk.mjs');
        if (fs.existsSync(altSdkPath)) {
            return altSdkPath;
        }
        
        throw new Error(`SDK not found`);
    } catch (error) {
        throw new Error(`Failed to find Claude Code SDK: ${error.message}`);
    }
}

const sdkPath = findClaudeCodeSdk();
console.log(`Found SDK at: ${sdkPath}\n`);
const { query } = await import(sdkPath);

console.log('🚀 Starting streaming test...\n');

// Track init messages
let initCount = 0;
let sessionId = null;
let messageCount = 0;

// Create async generator for multiple messages
async function* createMessageStream() {
    const messages = [
        { text: "раз", delay: 1000 },
        { text: "два", delay: 2000 },
        { text: "три", delay: 2000 }
    ];
    
    for (const msg of messages) {
        await new Promise(resolve => setTimeout(resolve, msg.delay));
        
        console.log(`\n📤 Sending message ${++messageCount}: "${msg.text}"`);
        
        yield {
            type: 'user',
            message: {
                role: 'user',
                content: [{ type: 'text', text: msg.text }]
            },
            parent_tool_use_id: null,
            session_id: sessionId || 'initial'
        };
    }
}

// Start the query with streaming input
console.log('Starting query with streaming input...\n');

try {
    const stream = query({
        prompt: createMessageStream(),
        options: {
            model: 'claude-sonnet-4-20250514',
            permissionMode: 'acceptEdits',
            maxTurns: 10 // Allow multiple turns
        }
    });

    // Process messages from Claude
    for await (const message of stream) {
        switch (message.type) {
            case 'system':
                if (message.subtype === 'init') {
                    initCount++;
                    sessionId = message.session_id;
                    console.log(`\n⚡ INIT #${initCount} - Session ID: ${sessionId}`);
                    console.log(`   Model: ${message.model}`);
                }
                break;
                
            case 'assistant':
                const text = message.message?.content?.[0]?.text || '';
                console.log(`\n🤖 Assistant: ${text.substring(0, 100)}`);
                break;
                
            case 'result':
                console.log(`\n✅ Result (turn ${message.num_turns}): ${message.result?.substring(0, 100)}`);
                console.log(`   Cost: $${message.total_cost_usd?.toFixed(4) || 0}`);
                break;
        }
    }
} catch (error) {
    console.error('\n❌ Error:', error.message);
}

console.log('\n' + '='.repeat(50));
console.log(`📊 FINAL STATS:`);
console.log(`   Init messages received: ${initCount}`);
console.log(`   User messages sent: ${messageCount}`);
console.log(`   Expected inits: 1`);
console.log(`   Test ${initCount === 1 ? '✅ PASSED' : '❌ FAILED'}: ${initCount === 1 ? 'Only one init as expected!' : `Got ${initCount} inits instead of 1`}`);
console.log('='.repeat(50));