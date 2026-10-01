import { createServer } from 'node:https';
import { readFileSync } from 'node:fs';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { ApiError, GoogleProvider, locations } from './provider.mjs';

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Strict-Transport-Security': 'max-age=31536000' });
  res.end(JSON.stringify(body));
};
async function readJson(req) {
  if (!req.headers['content-type']?.startsWith('application/json')) throw new ApiError(415, 'CONTENT_TYPE', 'Use application/json.');
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 32768) throw new ApiError(413, 'TOO_LARGE', 'Request is too large.');
    chunks.push(chunk);
  }
  try { const body = JSON.parse(Buffer.concat(chunks)); if (!body || Array.isArray(body)) throw Error(); return body; }
  catch { throw new ApiError(400, 'INVALID_JSON', 'Provide a valid JSON object.'); }
}
export function createHandler({ provider, token, now = Date.now }) {
  if (typeof token !== 'string' || token.length < 32) throw new Error('Configure a token with at least 32 characters.');
  const expected = Buffer.from(`Bearer ${token}`);
  let windowStart = now(), requests = 0, elements = 0, inFlight = 0;
  return async (req, res) => {
    let active = false;
    const controller = new AbortController();
    res.on('close', () => { if (!res.writableEnded) controller.abort(); });
    try {
      if (req.method === 'GET' && req.url === '/health') return json(res, 200, { status: 'ok' });
      const supplied = Buffer.from(req.headers.authorization ?? '');
      if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) throw new ApiError(401, 'UNAUTHORIZED', 'Check the access token in Settings.');
      if (req.method !== 'POST') throw new ApiError(405, 'METHOD', 'Use POST.');
      if (!['/v1/geocode', '/v1/matrix', '/v1/route'].includes(req.url)) throw new ApiError(404, 'NOT_FOUND', 'Endpoint not found.');
      if (now() - windowStart >= 60000) { requests = 0; elements = 0; windowStart = now(); }
      if (++requests > 90 || inFlight >= 4) throw new ApiError(429, 'RATE_LIMIT', 'Too many requests. Wait a minute and retry.');
      inFlight++; active = true;
      const body = await readJson(req);
      let result;
      if (req.url === '/v1/geocode') result = await provider.geocode(body.input, controller.signal);
      else if (req.url === '/v1/matrix') {
        const points = locations(body.locations);
        if (elements + points.length ** 2 > 2900) throw new ApiError(429, 'RATE_LIMIT', 'Matrix quota reached. Wait a minute and retry.');
        elements += points.length ** 2;
        result = await provider.matrix(points, controller.signal);
      } else result = await provider.route(locations(body.locations, 2, 53), controller.signal);
      json(res, 200, result);
    } catch (error) {
      // Never log request bodies, URL query strings, provider responses, tokens or addresses.
      if (!res.destroyed) json(res, error instanceof ApiError ? error.status : 500, {
        code: error instanceof ApiError ? error.code : 'INTERNAL',
        message: error instanceof ApiError ? error.message : 'Unable to process the route. Please retry.',
      });
    } finally { if (active) inFlight--; }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { GOOGLE_MAPS_SERVER_KEY: key, GREENFLEET_ACCESS_TOKEN: token, TLS_CERT_PATH, TLS_KEY_PATH } = process.env;
  if (!key || !TLS_CERT_PATH || !TLS_KEY_PATH) throw new Error('Configure the provider key and HTTPS certificate paths. See .env.example.');
  const server = createServer({ cert: readFileSync(TLS_CERT_PATH), key: readFileSync(TLS_KEY_PATH), minVersion: 'TLSv1.2' },
    createHandler({ provider: new GoogleProvider(key), token }));
  server.requestTimeout = 30000; server.headersTimeout = 10000; server.keepAliveTimeout = 5000;
  server.listen(Number(process.env.PORT ?? 8443), process.env.HOST ?? '127.0.0.1', () => console.info('GreenFleet HTTPS routing backend is ready.'));
}
