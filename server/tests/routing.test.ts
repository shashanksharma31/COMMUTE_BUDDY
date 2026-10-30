import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import { TtlCache } from '../src/lib/cache.js';
import { SourceResilienceManager } from '../src/lib/resilience.js';
import { SingleModeRoutingResponse } from '../src/providers/transit/types.js';

describe('Phase 5 — Single-Mode Routing (POST /api/routes)', () => {
  it('plans Metro & RRTS on our GTFS + YAML graph with durations in seconds and exact operator fares', async () => {
    const app = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test' }),
      cache: new TtlCache()
    });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      // 1. Metro route: Millennium City Centre Gurugram -> Botanical Garden Noida (Yellow Line -> Hauz Khas -> Magenta Line)
      const metroRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: {
            stationId: 'stn_in_del_millennium_city_centre_metro',
            lat: 28.4595,
            lng: 77.0726
          },
          end: {
            stationId: 'stn_in_del_botanical_garden_metro',
            lat: 28.5641,
            lng: 77.3343
          },
          mode: 'metro'
        })
      });
      assert.equal(metroRes.status, 200);
      const metroBody = (await metroRes.json()) as SingleModeRoutingResponse;
      assert.equal(metroBody.status, 'ok');
      assert.equal(metroBody.routes.length, 1);

      const mRoute = metroBody.routes[0];
      assert.equal(mRoute.providerLabel, 'GTFS_GRAPH');
      assert.equal(mRoute.transfers, 1); // Transfer at Hauz Khas
      assert.ok((mRoute.durationSeconds.value ?? 0) > 3000); // Seconds!
      assert.equal(mRoute.fare.value?.fareInr, 60); // Exact DMRC OD matrix fare ₹60

      // 2. RRTS route: Anand Vihar RRTS -> Sahibabad RRTS (Namo Bharat YAML)
      const rrtsRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: {
            stationId: 'stn_in_del_anand_vihar_rrts',
            lat: 28.6475,
            lng: 77.3168
          },
          end: {
            stationId: 'stn_in_del_sahibabad_rrts',
            lat: 28.671,
            lng: 77.436
          },
          mode: 'rrts'
        })
      });
      assert.equal(rrtsRes.status, 200);
      const rrtsBody = (await rrtsRes.json()) as SingleModeRoutingResponse;
      assert.equal(rrtsBody.status, 'ok');
      assert.equal(rrtsBody.routes[0].providerLabel, 'RRTS_YAML');
      assert.equal(rrtsBody.routes[0].fare.value?.fareInr, 30);

      // 3. Static Train route: NDLS -> ANVT
      const trainRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: {
            stationId: 'stn_in_del_ndls_rail',
            lat: 28.6429,
            lng: 77.2191
          },
          end: {
            stationId: 'stn_in_del_anvt_rail',
            lat: 28.6482,
            lng: 77.3152
          },
          mode: 'train'
        })
      });
      assert.equal(trainRes.status, 200);
      const trainBody = (await trainRes.json()) as SingleModeRoutingResponse;
      assert.equal(trainBody.status, 'ok');
      assert.equal(trainBody.routes.length, 2);
      assert.equal(trainBody.routes[0].durationSeconds.value, 1500);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });

  it('returns explicit "no transit data for this area" and "no verified bus data for this area" without inventing routes', async () => {
    const app = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test', GOOGLE_ROUTES_API_KEY: '' }),
      cache: new TtlCache()
    });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      // Uncovered metro region (e.g. Shimla coordinates)
      const noMetroRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: { lat: 31.1048, lng: 77.1734 },
          end: { lat: 31.08, lng: 77.15 },
          mode: 'metro'
        })
      });
      assert.equal(noMetroRes.status, 200);
      const noMetroBody = (await noMetroRes.json()) as SingleModeRoutingResponse;
      assert.equal(noMetroBody.status, 'no_data');
      assert.equal(noMetroBody.message, 'no transit data for this area');
      assert.equal(noMetroBody.routes.length, 0);

      // Bus without Google Routes transit data
      const noBusRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: { lat: 28.6328, lng: 77.2197 },
          end: { lat: 28.5641, lng: 77.3343 },
          mode: 'bus'
        })
      });
      assert.equal(noBusRes.status, 200);
      const noBusBody = (await noBusRes.json()) as SingleModeRoutingResponse;
      assert.equal(noBusBody.status, 'no_data');
      assert.equal(noBusBody.message, 'no verified bus data for this area');
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });

  it('plans car/bike/walk via OpenRouteService and returns 503 if ORS_API_KEY is missing', async () => {
    const fakeOrsFetch: typeof fetch = async () =>
      new Response(
        JSON.stringify({
          features: [
            {
              geometry: {
                coordinates: [
                  [77.0726, 28.4595],
                  [77.21, 28.5678],
                  [77.3343, 28.5641]
                ]
              },
              properties: {
                summary: {
                  distance: 41000,
                  duration: 4260 // 71 min in seconds
                },
                segments: [
                  {
                    steps: [
                      {
                        distance: 41000,
                        duration: 4260,
                        instruction: 'Drive via NH-48, Ring Road and DND Flyway'
                      }
                    ]
                  }
                ]
              }
            }
          ]
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );

    const appWithKey = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test', ORS_API_KEY: 'test_ors_key' }),
      resilience: new SourceResilienceManager(),
      cache: new TtlCache(),
      fetchFn: fakeOrsFetch
    });

    const server = http.createServer(appWithKey);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const carRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: { name: 'Millennium City Centre', lat: 28.4595, lng: 77.0726 },
          end: { name: 'Botanical Garden', lat: 28.5641, lng: 77.3343 },
          mode: 'car'
        })
      });
      assert.equal(carRes.status, 200);
      const carBody = (await carRes.json()) as SingleModeRoutingResponse;
      assert.equal(carBody.status, 'ok');
      assert.equal(carBody.routes[0].durationSeconds.value, 4260);
      assert.equal(carBody.routes[0].distanceMeters, 41000);
      assert.equal(carBody.routes[0].providerLabel, 'ORS_ROAD');
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
