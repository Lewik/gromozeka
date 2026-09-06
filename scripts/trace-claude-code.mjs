#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { appendFileSync, chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { createInterface } from 'node:readline';

const root = process.env.GROMOZEKA_CLAUDE_DIAGNOSTIC_DIR;
const executable = process.env.GROMOZEKA_CLAUDE_CODE_REAL_EXECUTABLE;
if (!root || !executable || process.env.GROMOZEKA_CLAUDE_SYNTHETIC_PROBE !== 'true') {
  throw new Error('Raw protocol capture requires an explicit synthetic probe, output directory and real executable');
}
process.umask(0o077);
const directory = mkdtempSync(join(resolve(root), 'process-'));
chmodSync(directory, 0o700);
const trace = join(directory, 'protocol.jsonl');
const startedAt = process.hrtime.bigint();
let recordedBytes = 0;
function record(direction, data) {
  const line = JSON.stringify({ at: new Date().toISOString(), ms: Number(process.hrtime.bigint() - startedAt) / 1e6, direction, data }) + '\n';
  recordedBytes += Buffer.byteLength(line);
  if (recordedBytes > 32 * 1024 * 1024) throw new Error('Synthetic protocol trace exceeded 32 MiB');
  appendFileSync(trace, line, { mode: 0o600 });
}
const args = process.argv.slice(2);
record('launch', { executable, args });
const systemPromptIndex = args.indexOf('--system-prompt-file');
if (systemPromptIndex >= 0) {
  writeFileSync(join(directory, 'system-prompt.txt'), readFileSync(args[systemPromptIndex + 1]), { mode: 0o600 });
}
if (!args.includes('--include-partial-messages')) args.push('--include-partial-messages');
args.push('--debug-file', join(directory, 'claude-debug.log'));
const child = spawn(executable, args, { stdio: ['pipe', 'pipe', 'pipe'] });
record('spawn', { pid: child.pid });
createInterface({ input: process.stdin }).on('line', line => record('stdin', line));
createInterface({ input: child.stdout }).on('line', line => record('stdout', line));
createInterface({ input: child.stderr }).on('line', line => record('stderr', line));
process.stdin.pipe(child.stdin);
child.stdout.pipe(process.stdout);
child.stderr.pipe(process.stderr);
child.stdin.on('error', error => {
  if (error.code !== 'EPIPE') throw error;
});
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => child.kill(signal));
child.on('error', error => {
  record('error', { message: error.message });
  process.exitCode = 1;
});
child.on('close', (code, signal) => {
  record('close', { code, signal });
  process.stdin.destroy();
  process.exitCode = code ?? 1;
});
