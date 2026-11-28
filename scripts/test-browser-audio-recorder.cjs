const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function recorderManagerSource() {
  const file = path.join(__dirname, '../presentation/src/wasmJsMain/kotlin/com/gromozeka/presentation/services/BrowserClientAudioRecorder.kt');
  const kotlin = fs.readFileSync(file, 'utf8');
  const declaration = kotlin.indexOf('private external fun createBrowserAudioRecorderManager');
  assert.ok(declaration >= 0);
  const annotation = kotlin.lastIndexOf('@JsFun(', declaration);
  const start = kotlin.indexOf('"""', annotation) + 3;
  const end = kotlin.indexOf('"""', start);
  assert.ok(annotation >= 0 && start > annotation && end < declaration);
  return kotlin.slice(start, end);
}

function fixture() {
  let context;
  class FakeAudioContext {
    constructor() {
      context = this;
      this.state = 'suspended';
      this.suspends = 0;
      this.resumes = 0;
      this.audioWorklet = { addModule: async () => {} };
      this.suspendGate = null;
    }
    async resume() { this.resumes += 1; this.state = 'running'; }
    async suspend() {
      this.suspends += 1;
      if (this.suspendGate) await this.suspendGate;
      this.state = 'suspended';
    }
    async close() { this.state = 'closed'; }
  }
  const sandbox = {
    AudioContext: FakeAudioContext,
    Blob: class {},
    URL: { createObjectURL: () => 'blob:synthetic-worklet', revokeObjectURL: () => {} },
  };
  const createManager = vm.runInNewContext(`(${recorderManagerSource()})`, sandbox);
  return { manager: createManager(), get context() { return context; } };
}

function concatenate(parts) {
  const length = parts.reduce((total, part) => total + part.length, 0);
  const result = new Float32Array(length);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

test('streaming resampler is identical for contiguous and arbitrarily split input', () => {
  const { manager } = fixture();
  const samples = Float32Array.from({ length: 10_003 }, (_, index) =>
    Math.sin(index / 17) * 0.7 + Math.cos(index / 31) * 0.2);
  const contiguous = manager.createStreamingResampler(44_100, 16_000).push(samples);
  const splitResampler = manager.createStreamingResampler(44_100, 16_000);
  const split = concatenate([
    splitResampler.push(samples.slice(0, 1_337)),
    splitResampler.push(samples.slice(1_337, 5_433)),
    splitResampler.push(samples.slice(5_433, 5_434)),
    splitResampler.push(samples.slice(5_434)),
  ]);
  assert.deepEqual(Array.from(split), Array.from(contiguous));
});

test('streaming resampler keeps the exact long-run 48k to 16k sample count', () => {
  const { manager } = fixture();
  const resampler = manager.createStreamingResampler(48_000, 16_000);
  let outputSamples = 0;
  for (let offset = 0; offset < 480_000; offset += 4_096) {
    const size = Math.min(4_096, 480_000 - offset);
    outputSamples += resampler.push(new Float32Array(size)).length;
  }
  assert.equal(outputSamples, 160_000);
});

test('stopping Live does not suspend an earlier ordinary recording', async () => {
  const { manager } = fixture();
  const ordinary = manager.begin();
  const context = await manager.ensureContext();
  const live = manager.begin();
  assert.equal(await manager.ensureContext(), context);
  await manager.release(live);
  assert.equal(context.state, 'running');
  assert.equal(context.suspends, 0);
  await manager.release(ordinary);
  assert.equal(context.state, 'suspended');
  assert.equal(context.suspends, 1);
});

test('stopping ordinary recording does not suspend an earlier Live capture', async () => {
  const { manager } = fixture();
  const live = manager.begin();
  const context = await manager.ensureContext();
  const ordinary = manager.begin();
  await manager.release(ordinary);
  assert.equal(context.state, 'running');
  await manager.release(live);
  assert.equal(context.state, 'suspended');
});

test('duplicate cleanup never releases another capture', async () => {
  const { manager } = fixture();
  const first = manager.begin();
  const context = await manager.ensureContext();
  const second = manager.begin();
  await manager.release(second);
  await manager.release(second);
  assert.equal(context.suspends, 0);
  await manager.release(first);
  await manager.release(first);
  assert.equal(context.suspends, 1);
});

test('a capture starting during suspension is resumed after old cleanup', async () => {
  const { manager } = fixture();
  const old = manager.begin();
  const context = await manager.ensureContext();
  let releaseSuspend;
  context.suspendGate = new Promise(resolve => { releaseSuspend = resolve; });
  const cleanup = manager.release(old);
  assert.equal(context.suspends, 1);
  const next = manager.begin();
  await manager.ensureContext();
  releaseSuspend();
  await cleanup;
  assert.equal(context.state, 'running');
  assert.equal(context.resumes, 2);
  await manager.release(next);
  assert.equal(context.state, 'suspended');
});
