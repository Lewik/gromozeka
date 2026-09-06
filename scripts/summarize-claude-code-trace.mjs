import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const root = process.argv[2];
if (!root) throw new Error('Usage: node scripts/summarize-claude-code-trace.mjs <trace directory>');
function countUnicodeEscapes(text) {
  let count = 0;
  for (let index = 0; index < text.length; index++) {
    if (text[index] !== '\\') continue;
    if (text[index + 1] === 'u' && /^[0-9a-f]{4}$/i.test(text.slice(index + 2, index + 6))) count++;
    index++;
  }
  return count;
}
for (const directory of readdirSync(root).filter(name => name.startsWith('process-')).sort()) {
  const file = join(root, directory, 'protocol.jsonl');
  const records = readFileSync(file, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  const args = records.find(record => record.direction === 'launch').data.args;
  const system = readFileSync(join(root, directory, 'system-prompt.txt'), 'utf8');
  let turn;
  let turnNumber = 0;
  for (const record of records) {
    if (record.direction === 'stdin') {
      if (turn) {
        turn.inputs++;
        turn.inputChars += record.data.length;
        continue;
      }
      turn = { start: record.ms, first: {}, events: 0, apiMessages: new Set(), assistantIds: new Set(),
        text: '', toolNames: [], retries: 0, toolErrors: 0, inputs: 1, inputChars: record.data.length,
        lastEventMs: 0, longestEventGapMs: 0, apiCalls: [], inputJson: '', textDelta: '' };
      turnNumber++;
      continue;
    }
    if (!turn || record.direction !== 'stdout') continue;
    const message = JSON.parse(record.data);
    turn.events++;
    const elapsed = Math.round(record.ms - turn.start);
    turn.longestEventGapMs = Math.max(turn.longestEventGapMs, elapsed - turn.lastEventMs);
    turn.lastEventMs = elapsed;
    const event = message.event;
    if (message.type === 'stream_event') {
      if (event.type === 'message_start') {
        turn.apiMessages.add(event.message.id);
        turn.apiCalls.push({ id: event.message.id, startMs: elapsed, first: {}, usage: event.message.usage });
      }
      const apiCall = turn.apiCalls.at(-1);
      const kind = event.delta?.type;
      if (kind) {
        if (turn.first[kind] === undefined) turn.first[kind] = elapsed;
        if (apiCall && apiCall.first[kind] === undefined) apiCall.first[kind] = elapsed;
        turn.lastDeltaMs = elapsed;
        if (kind === 'input_json_delta') turn.inputJson += event.delta.partial_json ?? '';
        if (kind === 'text_delta') turn.textDelta += event.delta.text ?? '';
      }
      if (event.type === 'message_delta' && apiCall) {
        apiCall.usage = { ...apiCall.usage, ...event.usage };
        apiCall.stopReason = event.delta?.stop_reason;
      }
      if (event.type === 'message_stop' && apiCall) apiCall.endMs = elapsed;
    }
    if (message.type === 'assistant') {
      turn.assistantIds.add(message.message.id);
      for (const block of message.message.content ?? []) {
        if (block.type === 'text') turn.text += block.text;
        if (block.type === 'tool_use') turn.toolNames.push(block.name);
      }
    }
    if (message.type === 'system' && message.subtype === 'api_retry') turn.retries++;
    if (message.type === 'user') {
      turn.toolErrors += (message.message?.content ?? []).filter(block => block.type === 'tool_result' && block.is_error).length;
    }
    if (message.type === 'result') {
      const schemaIndex = args.indexOf('--json-schema');
      console.log(JSON.stringify({ directory, turn: turnNumber, startedAt: records[0].at,
        schema: schemaIndex >= 0, externalActions: system.includes('gromozeka_external_action_protocol'),
        inputChars: turn.inputChars, systemChars: system.length, elapsedMs: elapsed,
        first: turn.first, apiMessages: turn.apiMessages.size, assistantMessages: turn.assistantIds.size,
        toolNames: turn.toolNames, toolErrors: turn.toolErrors, stdinMessages: turn.inputs,
        retries: turn.retries, numTurns: message.num_turns,
        fastMode: message.fast_mode_state, fastModeDisabledReason: message.fast_mode_disabled_reason,
        apiMs: message.duration_api_ms, cliMs: message.duration_ms, resultChars: message.result?.length,
        intermediateTextChars: turn.text.length, structuredChars: JSON.stringify(message.structured_output)?.length,
        usage: message.usage, modelUsage: message.modelUsage, isError: message.is_error,
        longestEventGapMs: turn.longestEventGapMs, lastDeltaMs: turn.lastDeltaMs,
        afterLastDeltaMs: turn.lastDeltaMs === undefined ? undefined : elapsed - turn.lastDeltaMs,
        apiCalls: turn.apiCalls,
        inputJsonChars: turn.inputJson.length,
        unicodeEscapes: countUnicodeEscapes(turn.inputJson),
        textDeltaChars: turn.textDelta.length,
        textUnicodeEscapes: countUnicodeEscapes(turn.textDelta),
      }));
      turn = undefined;
    }
  }
}
