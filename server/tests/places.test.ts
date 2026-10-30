import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import { TtlCache } from '../src/lib/cache.js';
import { SourceResilienceManager } from '../src/lib/resilience.js';
import { PlaceSuggestion } from '../src/providers/places/types.js';
import { DataEnvelope } from '../src/types/envelope.js';

describe('Phase 2 — Places Search Across India & Reverse Geocoding', () => {
  it('returns 503 with clear missingKey when NOMINATIM_USER_AGENT is not set', async () => {
    const testEnv = loadEnv({
      NODE_ENV: 'test',
      NOMINATIM_USER_AGENT: ''
    });
    const app = createApp({
      appEnv: testEnv,
      cache: new TtlCache()
    });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const res = await fetch(`http://127.0.0.1:${port}/api/places?q=Rajiv+Chowk`);
      assert.equal(res.status, 503);
      const body = (await res.json()) as { missingKey: string; status: string };
      assert.equal(body.missingKey, 'NOMINATIM_USER_AGENT');
      assert.equal(body.status, 'unavailable');
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });

  it('filters to countrycode IN, caps at max 5 suggestions, attaches stationHint, and caches results', async () => {
    let fetchCalls = 0;
    let capturedUserAgent = '';

    const fakeFetch: typeof fetch = async (input, init) => {
      fetchCalls += 1;
      const headers = init?.headers as Record<string, string> | undefined;
      capturedUserAgent = headers?.['User-Agent'] ?? '';

      return new Response(
        JSON.stringify({
          features: [
            {
              geometry: { coordinates: [-0.1276, 51.5072] },
              properties: {
                osm_id: 999,
                osm_type: 'N',
                name: 'London Charing Cross',
                countrycode: 'GB'
              }
            },
            {
              geometry: { coordinates: [77.2197, 28.6328] },
              properties: {
                osm_id: 101,
                osm_type: 'N',
                osm_key: 'railway',
                osm_value: 'subway_entrance',
                name: 'Rajiv Chowk Metro Station',
                district: 'Connaught Place',
                city: 'New Delhi',
                state: 'Delhi',
                countrycode: 'in'
              }
            },
            {
              geometry: { coordinates: [77.2191, 28.6429] },
              properties: {
                osm_id: 102,
                osm_type: 'N',
                osm_key: 'railway',
                osm_value: 'station',
                name: 'New Delhi Railway Station',
                city: 'New Delhi',
                state: 'Delhi',
                countrycode: 'IN'
              }
            },
            {
              geometry: { coordinates: [77.436, 28.671] },
              properties: {
                osm_id: 103,
                osm_type: 'N',
                osm_key: 'railway',
                osm_value: 'station',
                name: 'Sahibabad RRTS Station (Namo Bharat)',
                city: 'Ghaziabad',
                state: 'Uttar Pradesh',
                countrycode: 'IN'
              }
            },
            {
              geometry: { coordinates: [77.3343, 28.5641] },
              properties: {
                osm_id: 104,
                osm_type: 'N',
                name: 'Botanical Garden Metro Station',
                city: 'Noida',
                state: 'Uttar Pradesh',
                countrycode: 'IN'
              }
            },
            {
              geometry: { coordinates: [77.1926, 28.545] },
              properties: {
                osm_id: 105,
                osm_type: 'W',
                osm_key: 'amenity',
                osm_value: 'university',
                name: 'IIT Delhi',
                city: 'New Delhi',
                state: 'Delhi',
                countrycode: 'IN'
              }
            },
            {
              geometry: { coordinates: [77.0726, 28.4595] },
              properties: {
                osm_id: 106,
                osm_type: 'N',
                name: 'Millennium City Centre Metro Station',
                city: 'Gurugram',
                state: 'Haryana',
                countrycode: 'IN'
              }
            }
          ]
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    };

    const testEnv = loadEnv({
      NODE_ENV: 'test',
      NOMINATIM_USER_AGENT: 'CommuteBuddy/1.0 (shasmitgupta2@gmail.com)'
    });
    const app = createApp({
      appEnv: testEnv,
      resilience: new SourceResilienceManager(),
      cache: new TtlCache(),
      fetchFn: fakeFetch
    });

    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const res1 = await fetch(`http://127.0.0.1:${port}/api/places?q=Delhi`);
      assert.equal(res1.status, 200);
      const env1 = (await res1.json()) as DataEnvelope<PlaceSuggestion[]>;

      assert.equal(env1.status, 'live');
      assert.ok(env1.confidence > 0);
      assert.equal(capturedUserAgent, 'CommuteBuddy/1.0 (shasmitgupta2@gmail.com)');
      assert.ok(env1.value);
      // Non-IN London item filtered out, remaining 6 IN items capped at max 5
      assert.equal(env1.value.length, 5);
      assert.ok(env1.value.every((p) => p.countryCode === 'IN'));

      // Check stationHints for metro, rail, and RRTS stations
      const metro = env1.value[0];
      assert.equal(metro.placeType, 'metro_station');
      assert.equal(metro.stationHint?.stationMode, 'metro');
      assert.equal(metro.stationHint?.osmId, 101);

      const rail = env1.value[1];
      assert.equal(rail.placeType, 'railway_station');
      assert.equal(rail.stationHint?.stationMode, 'rail');

      const rrts = env1.value[2];
      assert.equal(rrts.placeType, 'rrts_station');
      assert.equal(rrts.stationHint?.stationMode, 'rrts');

      // Second identical call should hit TTL cache without invoking fetch again
      const res2 = await fetch(`http://127.0.0.1:${port}/api/places?q=delhi`);
      assert.equal(res2.status, 200);
      assert.equal(fetchCalls, 1);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });

  it('reverse geocodes India coordinates and enforces Nominatim rate limit interval', async () => {
    const requestTimestamps: number[] = [];

    const fakeFetch: typeof fetch = async () => {
      requestTimestamps.push(Date.now());
      return new Response(
        JSON.stringify({
          place_id: 5001,
          osm_type: 'node',
          osm_id: 300200,
          lat: '28.6328',
          lon: '77.2197',
          name: 'Rajiv Chowk Metro Station',
          display_name: 'Rajiv Chowk Metro Station, Connaught Place, New Delhi, Delhi, 110001, India',
          category: 'railway',
          type: 'subway_entrance',
          address: {
            suburb: 'Connaught Place',
            city: 'New Delhi',
            state: 'Delhi',
            postcode: '110001',
            country_code: 'in'
          }
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    };

    const testEnv = loadEnv({
      NODE_ENV: 'test',
      NOMINATIM_USER_AGENT: 'CommuteBuddy/1.0 (shasmitgupta2@gmail.com)'
    });
    const app = createApp({
      appEnv: testEnv,
      resilience: new SourceResilienceManager(),
      cache: new TtlCache(),
      fetchFn: fakeFetch,
      nominatimMinIntervalMs: 120
    });

    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const [r1, r2] = await Promise.all([
        fetch(`http://127.0.0.1:${port}/api/places/reverse?lat=28.6328&lng=77.2197`),
        fetch(`http://127.0.0.1:${port}/api/places/reverse?lat=28.5433&lng=77.2066`)
      ]);

      assert.equal(r1.status, 200);
      assert.equal(r2.status, 200);
      const body1 = (await r1.json()) as DataEnvelope<PlaceSuggestion>;
      assert.equal(body1.value?.name, 'Rajiv Chowk Metro Station');
      assert.equal(body1.value?.stationHint?.stationMode, 'metro');

      assert.equal(requestTimestamps.length, 2);
      const gapMs = requestTimestamps[1] - requestTimestamps[0];
      assert.ok(gapMs >= 100, `Expected >=100ms Nominatim rate limit gap, got ${gapMs}ms`);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
