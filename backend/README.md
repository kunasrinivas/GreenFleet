# GreenFleet routing backend

Node 22+, with no third-party dependencies. It implements only geocoding, a directed driving matrix, and an ordered road path. Optimization runs in the Android domain module.

## Setup

Copy `.env.example` to `.env` and fill it locally:

```sh
cp .env.example .env
```

- `GOOGLE_MAPS_SERVER_KEY`: server-only Google key with Routes and Geocoding enabled and an IP restriction.
- `GREENFLEET_ACCESS_TOKEN`: a randomly generated token of at least 32 characters, provisioned to the private MVP operator. For example, generate a value with `openssl rand -hex 32`; never commit it.
- `TLS_CERT_PATH` / `TLS_KEY_PATH`: your HTTPS certificate chain and private key paths. Use a certificate trusted by the Android system trust store and matching your backend hostname.
- `HOST`: defaults to loopback. Set the intended listening interface explicitly when deploying.
- `PORT`: defaults to 8443.

```sh
npm start
```

There is deliberately no HTTP fallback or unauthenticated routing mode. The app requires an HTTPS **origin** without a path/query. A local development hostname therefore needs an appropriately trusted certificate. TLS termination and hosting are operator-provided; this repository does not deploy or buy cloud resources.

## Contract

All `/v1/*` endpoints require `Authorization: Bearer <access-token>` and `Content-Type: application/json`. Responses use `Cache-Control: no-store`.

| Endpoint | Body | Result |
|---|---|---|
| `GET /health` | none; no authentication | `{ "status": "ok" }` |
| `POST /v1/geocode` | `{ "input": "full street address" }` | `{ "address": "resolved address", "lat": 52.0, "lng": 4.0 }` |
| `POST /v1/matrix` | `{ "locations": [{"lat":52.0,"lng":4.0}, ...] }` | `{ "rows": [[{"distanceMeters":0,"durationSeconds":0}, ...], ...] }` |
| `POST /v1/route` | same shape, in visiting order including the depot return | `{ "legs": [{"distanceMeters":1000,"durationSeconds":120}, ...], "polylines": ["encoded Google polyline", ...] }` |

Geocoding rejects zero results, partial matches, multiple matches, and imprecise centroids. The user can supply exact coordinates if an address cannot be resolved. Coordinate inputs are parsed directly by Android, rather than sent to the address geocoder.

The matrix supports 3–52 unique points. The route endpoint supports 2–53 ordered points, including the repeated depot. Requests over 32 KB and provider responses over 8 MB are rejected. Invalid or incomplete route data fails closed. Error messages never expose provider response bodies or keys.

Both road endpoints use `DRIVE` and `TRAFFIC_UNAWARE`, without automatic waypoint reordering. The matrix is split into at most 625-element blocks. The ordered path is split into at most 25 intermediate waypoints per call. Google indices reconstruct the matrix, even if elements arrive out of order.

## Operational scope

This is an authenticated private single-operator MVP, not a public multi-tenant service. A process allows at most 90 authenticated requests/minute, 2,900 matrix elements/minute and four concurrent requests. Provider requests have a 25-second deadline each. The Android caller has bounded request and plan deadlines. Rate-limit errors request a later retry; automatic retry loops do not multiply billable calls.

For a public or multi-instance deployment, replace the shared token with per-user expiring access tokens, a proper sign-in/provisioning flow, and shared per-user quotas. Set provider billing/quota controls and ensure your hosting proxy does not log request bodies, tokens, or address-bearing provider URLs. The included process itself does not log these values or retain locations.

## Tests

```sh
node --test
```

Tests inject Google response fixtures, cover matrix/route batching, precision validation, authentication, limits and sanitized failures. They do not spend API quota or certify live Google credentials. Android client tests exercise the client over a local HTTPS server with an explicitly trusted test certificate; production Android trust remains unchanged.
