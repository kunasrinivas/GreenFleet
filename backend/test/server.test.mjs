import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHandler } from '../src/server.mjs';
const token = 'test-token-'.repeat(4);
test('backend authenticates, validates JSON, and rejects oversized bodies before provider work', async () => {
  let calls = 0;
  const server = createServer(createHandler({ token, provider: { async geocode(input) { calls++; return { address: input, lat: 52, lng: 4 }; } } }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`; // Test transport only; production server is HTTPS-only.
  try {
    assert.equal((await fetch(`${url}/health`)).status, 200);
    assert.equal((await fetch(`${url}/v1/geocode`, { method: 'POST' })).status, 401);
    const headers = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
    assert.equal((await fetch(`${url}/v1/geocode`, { method: 'POST', headers, body: 'bad json' })).status, 400);
    assert.equal((await fetch(`${url}/v1/geocode`, { method: 'POST', headers, body: JSON.stringify({ input: 'x'.repeat(40000) }) })).status, 413);
    const response = await fetch(`${url}/v1/geocode`, { method: 'POST', headers, body: JSON.stringify({ input: 'Test address' }) });
    assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(calls, 1);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
test('missing or short access token fails startup closed', () => {
  assert.throws(() => createHandler({ provider: {}, token: '' }));
});
