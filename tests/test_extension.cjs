const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const extension = path.join(__dirname, '..', 'extension');

function processor(rate) {
  let Processor;
  const output = [];
  const context = vm.createContext({sampleRate: rate, Int16Array, Math,
    AudioWorkletProcessor: class {constructor() {this.port = {postMessage: buffer => output.push(new Int16Array(buffer).slice())};}},
    registerProcessor: (name, value) => {Processor = value;}
  });
  vm.runInContext(fs.readFileSync(path.join(extension, 'pcm-worklet.js'), 'utf8'), context);
  return {node: new Processor(), output};
}

for (const rate of [44100, 48000, 16000]) {
  test(`${rate} Hz becomes exactly 16000 PCM samples without drift`, () => {
    const {node, output} = processor(rate);
    for (let i = 0; i < rate; i += 128) {
      const chunk = new Float32Array(Math.min(128, rate - i)).fill(.25);
      node.process([[chunk]]);
    }
    assert.equal(output.length, 5);
    assert.equal(output.reduce((sum, x) => sum + x.length, 0), 16000);
    assert.ok(output.every(frame => frame.every(x => Math.abs(x - 8192) <= 1)));
  });
}

test('stereo audio is mixed to mono without clipping', () => {
  const {node, output} = processor(48000);
  node.process([[new Float32Array(9600).fill(1), new Float32Array(9600).fill(-1)]]);
  assert.equal(output.length, 1);
  assert.ok(output[0].every(x => x === 0));
});

test('seek reset discards partial PCM', () => {
  const {node, output} = processor(48000);
  node.process([[new Float32Array(4800).fill(.8)]]);
  node.port.onmessage({data: {type: 'reset'}});
  node.process([[new Float32Array(9600).fill(0)]]);
  assert.equal(output.length, 1);
  assert.ok(output[0].every(x => x === 0));
});

test('background routes translated subtitle to the overlay and rejects stale sessions', async () => {
  let listener;
  const messages = [];
  const current = {status: 'running', sessionId: 'new-session', tabId: 42};
  const chrome = {
    runtime: {onMessage: {addListener: value => {listener = value;}}},
    storage: {session: {get: async () => ({captureState: current})}},
    tabs: {sendMessage: async (id, data) => messages.push({id, data}),
      onRemoved: {addListener() {}}, onUpdated: {addListener() {}}}
  };
  vm.runInNewContext(fs.readFileSync(path.join(extension, 'background.js'), 'utf8'), {chrome});
  const deliver = id => new Promise(resolve => listener({target: 'background', type: 'EVENT',
    sessionId: id, event: {type: 'subtitle', text: 'Привет', original: 'Hello'}}, {}, resolve));
  await deliver('old-session');
  assert.equal(messages.length, 0);
  await deliver('new-session');
  assert.equal(messages.length, 1);
  assert.equal(messages[0].data.type, 'SUBTITLE');
  assert.equal(messages[0].data.text, 'Привет');
  assert.equal(messages[0].id, 42);
});

test('manifest uses local code and only loopback host permissions', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(extension, 'manifest.json'), 'utf8'));
  assert.equal(manifest.manifest_version, 3);
  assert.deepEqual(manifest.host_permissions, ['http://127.0.0.1:8765/*']);
  for (const file of [manifest.background.service_worker, manifest.action.default_popup]) {
    assert.ok(fs.existsSync(path.join(extension, file)));
  }
  assert.ok(!manifest.permissions.includes('debugger'));
});

test('project links are explicit user clicks without background loading', () => {
  const html = fs.readFileSync(path.join(extension, 'popup.html'), 'utf8');
  for (const url of ['https://t.me/qecervov', 'https://github.com/qecervov']) {
    const anchor = html.match(new RegExp(`<a[^>]*href="${url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"[^>]*>`));
    assert.ok(anchor, `Missing project link: ${url}`);
    assert.ok(anchor[0].includes('rel="noopener noreferrer"'));
  }
  assert.ok(!/<(?:iframe|img|script)[^>]+src="https?:/i.test(html));
  assert.ok(!/<link[^>]+rel="(?:prefetch|preconnect)"/i.test(html));
});
