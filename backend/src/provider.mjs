export class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const invalid = message => { throw new ApiError(400, 'INVALID_INPUT', message); };
const malformed = () => { throw new ApiError(502, 'INVALID_PROVIDER_RESPONSE', 'The routing provider returned incomplete data. Please retry.'); };

export function coordinate(value) {
  if (!value || typeof value.lat !== 'number' || !Number.isFinite(value.lat) || Math.abs(value.lat) > 90 ||
      typeof value.lng !== 'number' || !Number.isFinite(value.lng) || Math.abs(value.lng) > 180) invalid('Provide valid latitude and longitude.');
  return { lat: value.lat, lng: value.lng };
}
export function locations(value, min = 3, max = 52) {
  if (!Array.isArray(value) || value.length < min || value.length > max) invalid(`Provide ${min}–${max} locations.`);
  return value.map(coordinate);
}
function seconds(value) {
  if (typeof value !== 'string' || !/^\d+(\.\d{1,9})?s$/.test(value)) malformed();
  const parsed = Number(value.slice(0, -1));
  if (!Number.isFinite(parsed) || parsed > 31536000) malformed();
  return parsed;
}
function cost(value) {
  // Protobuf JSON can omit scalar zero values, but not the message/condition.
  const distanceMeters = value.distanceMeters ?? 0;
  const durationSeconds = seconds(value.duration);
  if (!Number.isSafeInteger(distanceMeters) || distanceMeters < 0 || distanceMeters > 50000000 ||
      (distanceMeters > 0 && durationSeconds === 0)) malformed();
  return { distanceMeters, durationSeconds };
}
const waypoint = p => ({ location: { latLng: { latitude: p.lat, longitude: p.lng } } });

export class GoogleProvider {
  constructor(key, fetchImpl = fetch) { this.key = key; this.fetch = fetchImpl; }
  async request(url, body, fields, signal) {
    try {
      const response = await this.fetch(url, {
        method: body ? 'POST' : 'GET', redirect: 'error',
        headers: { ...(body ? { 'Content-Type': 'application/json' } : {}),
          ...(fields ? { 'X-Goog-FieldMask': fields, 'X-Goog-Api-Key': this.key } : {}) },
        body: body ? JSON.stringify(body) : undefined,
        signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(25000)]) : AbortSignal.timeout(25000),
      });
      if (!response.ok) {
        await response.body?.cancel();
        throw new ApiError(response.status === 429 ? 429 : 502, 'PROVIDER_UNAVAILABLE',
          response.status === 429 ? 'Routing quota reached. Wait a minute and retry.' : 'The routing provider is unavailable. Check its configuration or retry.');
      }
      const reader = response.body.getReader();
      let length = 0; const chunks = [];
      while (true) {
        const { done, value } = await reader.read(); if (done) break;
        length += value.length;
        if (length > 8 * 1024 * 1024) { await reader.cancel(); malformed(); }
        chunks.push(value);
      }
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(502, 'PROVIDER_UNAVAILABLE', 'Routing request failed or timed out. Please retry.');
    }
  }
  async geocode(input, signal) {
    if (typeof input !== 'string' || input.trim().length < 3 || input.length > 300 || /[\u0000-\u001f\u007f]/.test(input)) invalid('Enter a complete address (3–300 characters).');
    const url = new URL('https://maps.googleapis.com/maps/api/geocode/json');
    url.searchParams.set('address', input.trim()); url.searchParams.set('key', this.key);
    const data = await this.request(url, null, null, signal);
    if (data.status === 'ZERO_RESULTS') throw new ApiError(422, 'GEOCODING_FAILED', 'Address not found. Add the city and postal code, or enter coordinates.');
    if (data.status !== 'OK') throw new ApiError(502, 'GEOCODING_FAILED', 'Geocoding failed. Check provider configuration or retry.');
    if (!Array.isArray(data.results) || data.results.length !== 1 || data.results[0].partial_match) {
      throw new ApiError(422, 'AMBIGUOUS_ADDRESS', 'Address is ambiguous. Use a full street address and postal code, or coordinates.');
    }
    const result = data.results[0];
    if (typeof result.formatted_address !== 'string' || result.formatted_address.length > 300) malformed();
    // Reject region/city centroids; they are unsuitable delivery points.
    if (!['ROOFTOP', 'RANGE_INTERPOLATED'].includes(result.geometry?.location_type)) {
      throw new ApiError(422, 'IMPRECISE_ADDRESS', 'A precise delivery point was not found. Use a street number or coordinates.');
    }
    let point;
    try { point = coordinate(result.geometry.location); } catch { malformed(); }
    return { address: result.formatted_address, ...point };
  }
  async matrix(points, signal) {
    const rows = Array.from({ length: points.length }, () => Array(points.length).fill(null));
    // 25 x 25 = 625 elements maximum per request; preserve direction and explicit indices.
    for (let i = 0; i < points.length; i += 25) {
      for (let j = 0; j < points.length; j += 25) {
        const origins = points.slice(i, i + 25); const destinations = points.slice(j, j + 25);
        const data = await this.request('https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix', {
          origins: origins.map(p => ({ waypoint: waypoint(p) })),
          destinations: destinations.map(p => ({ waypoint: waypoint(p) })),
          travelMode: 'DRIVE', routingPreference: 'TRAFFIC_UNAWARE',
        }, 'originIndex,destinationIndex,status,condition,distanceMeters,duration', signal);
        if (!Array.isArray(data) || data.length !== origins.length * destinations.length) malformed();
        for (const element of data) {
          const a = element.originIndex ?? 0; const b = element.destinationIndex ?? 0;
          if (!Number.isInteger(a) || !Number.isInteger(b) || a < 0 || b < 0 || a >= origins.length || b >= destinations.length || rows[i + a][j + b] !== null) malformed();
          if (element.status?.code) throw new ApiError(502, 'MATRIX_FAILED', 'A driving-matrix element failed. Please retry.');
          if (element.condition !== 'ROUTE_EXISTS') throw new ApiError(422, 'UNREACHABLE', 'Some locations cannot be connected by car. Check access and coordinates.');
          rows[i + a][j + b] = i + a === j + b ? { distanceMeters: 0, durationSeconds: 0 } : cost(element);
        }
      }
    }
    if (rows.some(row => row.some(item => item === null))) malformed();
    return { rows };
  }
  async route(points, signal) {
    const legs = []; const polylines = [];
    // 25 intermediate waypoints per call. Overlap the endpoint, never add a synthetic leg.
    for (let start = 0; start < points.length - 1; start += 26) {
      const chunk = points.slice(start, start + 27);
      const data = await this.request('https://routes.googleapis.com/directions/v2:computeRoutes', {
        origin: waypoint(chunk[0]), destination: waypoint(chunk.at(-1)),
        intermediates: chunk.slice(1, -1).map(waypoint), travelMode: 'DRIVE',
        routingPreference: 'TRAFFIC_UNAWARE', computeAlternativeRoutes: false,
        polylineQuality: 'HIGH_QUALITY', polylineEncoding: 'ENCODED_POLYLINE',
      }, 'routes.legs.distanceMeters,routes.legs.duration,routes.polyline.encodedPolyline', signal);
      const route = data.routes?.[0];
      if (!route || !Array.isArray(route.legs) || route.legs.length !== chunk.length - 1 ||
          typeof route.polyline?.encodedPolyline !== 'string' || !route.polyline.encodedPolyline.length || route.polyline.encodedPolyline.length > 1000000) malformed();
      legs.push(...route.legs.map(cost)); polylines.push(route.polyline.encodedPolyline);
    }
    return { legs, polylines };
  }
}
