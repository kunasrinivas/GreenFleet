import test from 'node:test';
import assert from 'node:assert/strict';
import { GoogleProvider, coordinate, locations } from '../src/provider.mjs';
const reply = data => new Response(JSON.stringify(data), { status: 200 });
const points = n => Array.from({ length: n }, (_, i) => ({ lat: 52 + i / 10000, lng: 4.9 }));

test('reject invalid coordinates and stop counts', () => {
  for (const p of [{ lat: 91, lng: 0 }, { lat: NaN, lng: 0 }, { lat: 0, lng: Infinity }, { lat: '52', lng: 4 }]) assert.throws(() => coordinate(p));
  assert.throws(() => locations(points(53)));
});
test('geocoding encodes address, uses HTTPS and rejects ambiguous or imprecise results', async () => {
  const precise = { formatted_address: 'A test address', geometry: { location: { lat: 52, lng: 4 }, location_type: 'ROOFTOP' } };
  const provider = new GoogleProvider('test-key', async (url, options) => {
    assert.equal(url.protocol, 'https:'); assert.equal(url.searchParams.get('address'), 'Street & square 1');
    assert.equal(options.redirect, 'error'); return reply({ status: 'OK', results: [precise] });
  });
  assert.deepEqual(await provider.geocode('Street & square 1'), { address: 'A test address', lat: 52, lng: 4 });
  for (const results of [[precise, precise], [{ ...precise, partial_match: true }], [{ ...precise, geometry: { ...precise.geometry, location_type: 'APPROXIMATE' } }]]) {
    await assert.rejects(new GoogleProvider('x', async () => reply({ status: 'OK', results })).geocode('Test address'));
  }
});
test('52-point matrix batches to 625 elements and reconstructs out-of-order directed results', async () => {
  const input = points(52); let calls = 0;
  const provider = new GoogleProvider('x', async (url, options) => {
    calls++;
    const body = JSON.parse(options.body);
    assert.equal(body.travelMode, 'DRIVE'); assert.equal(body.routingPreference, 'TRAFFIC_UNAWARE');
    assert.ok(body.origins.length * body.destinations.length <= 625);
    const data = body.origins.flatMap((origin, i) => body.destinations.map((destination, j) => ({
      ...(i ? { originIndex: i } : {}), ...(j ? { destinationIndex: j } : {}), condition: 'ROUTE_EXISTS', status: {},
      distanceMeters: Math.round((origin.waypoint.location.latLng.latitude - 52) * 10000) * 100 + Math.round((destination.waypoint.location.latLng.latitude - 52) * 10000) + 1,
      duration: '12.5s',
    })));
    return reply(data.reverse());
  });
  const { rows } = await provider.matrix(input);
  assert.equal(calls, 9); assert.equal(rows[51][50].distanceMeters, 5151);
  assert.equal(rows[50][51].distanceMeters, 5052); assert.equal(rows[51][51].distanceMeters, 0);
});
test('matrix rejects missing, duplicate, unreachable and errored elements', async () => {
  const good = Array.from({ length: 9 }, (_, i) => ({ originIndex: Math.floor(i / 3), destinationIndex: i % 3,
    condition: 'ROUTE_EXISTS', status: {}, distanceMeters: 1000, duration: '120s' }));
  for (const bad of [good.slice(1), [good[1], ...good.slice(1)], [{ ...good[0], condition: 'ROUTE_NOT_FOUND' }, ...good.slice(1)],
      [{ ...good[0], status: { code: 13 } }, ...good.slice(1)], [good[0], { ...good[1], distanceMeters: -2 }, ...good.slice(2)]]) {
    await assert.rejects(new GoogleProvider('x', async () => reply(bad)).matrix(points(3)));
  }
});
test('53 ordered points use overlapping route chunks and preserve all 52 legs', async () => {
  const seen = [];
  const provider = new GoogleProvider('x', async (url, options) => {
    const body = JSON.parse(options.body); seen.push(body);
    assert.ok(body.intermediates.length <= 25);
    return reply({ routes: [{ legs: Array.from({ length: body.intermediates.length + 1 }, () => ({ distanceMeters: 1000, duration: '120s' })),
      polyline: { encodedPolyline: '_p~iF~ps|U_ulLnnqC_mqNvxq`@' } }] });
  });
  const result = await provider.route(points(53));
  assert.equal(result.legs.length, 52); assert.equal(seen.length, 2);
  assert.deepEqual(seen[0].destination, seen[1].origin);
});
test('provider failures are sanitized and no key or address escapes in errors', async () => {
  const provider = new GoogleProvider('private-key', async () => { throw new Error('private-key Secret Home Address'); });
  await assert.rejects(provider.geocode('Secret Home Address'), error => !error.message.includes('Secret') && !error.message.includes('private-key'));
});

