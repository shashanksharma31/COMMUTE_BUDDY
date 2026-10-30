import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import { TtlCache } from '../src/lib/cache.js';
import { ConflictLog } from '../src/lib/conflictLog.js';
import { SourceResilienceManager } from '../src/lib/resilience.js';
import {
  CrossCheckedTrainService,
  LicensedHttpTrainAdapter
} from '../src/providers/train/licensedTrainAdapter.js';
import { SingleModeRoutingResponse } from '../src/providers/transit/types.js';
import { LiveWeatherReport } from '../src/providers/weather/weatherService.js';

describe('Phase 8 — Live Traffic, Weather & Licensed Train Live Adapter', () => {
  it('enriches car routes with TomTom live-traffic + no-traffic durations and dynamic surge fare', async () => {
    const mockFetch: typeof fetch = async (input) => {
      const url = String(input);
      if (url.includes('openrouteservice.org')) {
        return new Response(
          JSON.stringify({
            features: [
              {
                geometry: {
                  coordinates: [
                    [77.0726, 28.4595],
                    [77.3343, 28.5641]
                  ]
                },
                properties: {
                  summary: { distance: 41000, duration: 4200 },
                  segments: [{ steps: [{ distance: 41000, duration: 4200, instruction: 'Drive via NH-48' }] }]
                }
              }
            ]
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      if (url.includes('api.tomtom.com')) {
        return new Response(
          JSON.stringify({
            routes: [
              {
                summary: {
                  travelTimeInSeconds: 4560, // 76 min in live traffic
                  noTrafficTravelTimeInSeconds: 3600, // 60 min without traffic
                  trafficDelayInSeconds: 960
                }
              }
            ]
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      throw new Error(`Unexpected URL: ${url}`);
    };

    const app = createApp({
      appEnv: loadEnv({
        NODE_ENV: 'test',
        ORS_API_KEY: 'test_ors_key',
        TRAFFIC_PROVIDER: 'tomtom',
        TOMTOM_API_KEY: 'test_tomtom_key'
      }),
      resilience: new SourceResilienceManager(),
      cache: new TtlCache(),
      fetchFn: mockFetch
    });

    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const res = await fetch(`http://127.0.0.1:${port}/api/routes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: { name: 'Millennium City Centre', lat: 28.4595, lng: 77.0726 },
          end: { name: 'Botanical Garden', lat: 28.5641, lng: 77.3343 },
          mode: 'car'
        })
      });

      assert.equal(res.status, 200);
      const body = (await res.json()) as SingleModeRoutingResponse;
      assert.equal(body.status, 'ok');
      const route = body.routes[0];

      assert.equal(route.durationSeconds.value, 4560);
      assert.equal(route.durationSeconds.status, 'live');
      assert.equal(route.noTrafficDurationSeconds?.value, 3600);
      assert.equal(route.noTrafficDurationSeconds?.status, 'scheduled');
      assert.equal(route.trafficDelaySeconds, 960);
      assert.equal(route.fare.status, 'estimate');
      assert.ok((route.fare.value?.fareInr ?? 0) > 600);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });

  it('cross-checks licensed train primary and secondary providers, logs >300s delay disagreements, and treats RapidAPI/ERail as unofficial low-weight', async () => {
    const conflicts = new ConflictLog(null);
    const nowIso = new Date().toISOString();

    // 1. Agreement within <= 300s between CRIS_AUTHORIZED and RAILWAYAPI
    const agreeFetch: typeof fetch = async (input) => {
      const url = String(input);
      const delaySec = url.includes('crisauthorized') ? 600 : 720; // 120s diff <= 300s
      return new Response(
        JSON.stringify({
          trainNumber: '12004',
          fromStationCode: 'NDLS',
          toStationCode: 'ANVT',
          delaySeconds: delaySec,
          seatAvailability: [
            { classCode: 'CC', availabilityText: 'AVAILABLE-0042', bookingStatus: 'AVAILABLE' }
          ],
          observedAt: nowIso
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    };

    const envAgree = loadEnv({
      NODE_ENV: 'test',
      TRAIN_PRIMARY_PROVIDER: 'CRISAuthorized',
      TRAIN_PRIMARY_API_KEY: 'key_1',
      TRAIN_SECONDARY_PROVIDER: 'RailwayAPI',
      TRAIN_SECONDARY_API_KEY: 'key_2'
    });
    const resMgr = new SourceResilienceManager();
    const serviceAgree = new CrossCheckedTrainService(
      new LicensedHttpTrainAdapter('primary', envAgree, resMgr, agreeFetch),
      new LicensedHttpTrainAdapter('secondary', envAgree, resMgr, agreeFetch),
      resMgr,
      new TtlCache(),
      conflicts
    );

    const verifiedAgree = await serviceAgree.getVerifiedLiveTrainStatus({
      trainNumber: '12004',
      fromStationCode: 'NDLS',
      toStationCode: 'ANVT'
    });
    assert.equal(verifiedAgree.delaySeconds.status, 'live');
    assert.equal(verifiedAgree.delaySeconds.value, 600);
    assert.deepEqual(verifiedAgree.delaySeconds.sources, ['CRISAuthorized', 'RailwayAPI']);
    assert.ok(verifiedAgree.delaySeconds.confidence >= 0.85);

    // 2. Disagreement > 300s logs to ConflictLog and marks status = 'unverified'
    const disagreeFetch: typeof fetch = async (input) => {
      const url = String(input);
      const delaySec = url.includes('crisauthorized') ? 300 : 1800; // 1500s diff > 300s
      return new Response(
        JSON.stringify({
          trainNumber: '12004',
          fromStationCode: 'NDLS',
          toStationCode: 'ANVT',
          delaySeconds: delaySec,
          seatAvailability: [],
          observedAt: nowIso
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    };

    const serviceDisagree = new CrossCheckedTrainService(
      new LicensedHttpTrainAdapter('primary', envAgree, resMgr, disagreeFetch),
      new LicensedHttpTrainAdapter('secondary', envAgree, resMgr, disagreeFetch),
      resMgr,
      new TtlCache(),
      conflicts
    );

    const verifiedDisagree = await serviceDisagree.getVerifiedLiveTrainStatus({
      trainNumber: '12004',
      fromStationCode: 'NDLS',
      toStationCode: 'ANVT'
    });
    assert.equal(verifiedDisagree.delaySeconds.status, 'unverified');
    assert.ok(verifiedDisagree.flags.includes('train_live_delay_disagreement'));
    assert.equal(conflicts.getAll().length, 1);
    assert.equal(conflicts.getAll()[0].entityType, 'train_live_delay');

    // 3. Unofficial RapidAPI/ERail listing is treated as low weight and never 'live'
    const envUnofficial = loadEnv({
      NODE_ENV: 'test',
      TRAIN_PRIMARY_PROVIDER: 'RapidAPI_ERail_Unofficial',
      TRAIN_PRIMARY_API_KEY: 'rapid_key'
    });
    const serviceUnofficial = new CrossCheckedTrainService(
      new LicensedHttpTrainAdapter('primary', envUnofficial, resMgr, agreeFetch),
      null,
      resMgr,
      new TtlCache(),
      conflicts
    );
    const unofficialRes = await serviceUnofficial.getVerifiedLiveTrainStatus({
      trainNumber: '12004',
      fromStationCode: 'NDLS',
      toStationCode: 'ANVT'
    });
    assert.equal(unofficialRes.delaySeconds.status, 'unverified');
    assert.ok(unofficialRes.delaySeconds.confidence <= 0.35);
    assert.ok(unofficialRes.flags.includes('unofficial_low_weight_train_source'));
  });

  it('serves GET /api/weather via Open-Meteo with 10-min caching and heavy rain walk/bike speed adjustments', async () => {
    let fetchCalls = 0;
    const weatherFetch: typeof fetch = async () => {
      fetchCalls += 1;
      return new Response(
        JSON.stringify({
          current: {
            time: '2025-07-15T12:00',
            temperature_2m: 29.4,
            precipitation: 12.2, // > 7.5 mm/h heavy rain
            weather_code: 95,
            wind_speed_10m: 22.5
          }
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    };

    const app = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test' }),
      resilience: new SourceResilienceManager(),
      cache: new TtlCache(),
      fetchFn: weatherFetch
    });

    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const res1 = await fetch(
        `http://127.0.0.1:${port}/api/weather?lat=28.6328&lng=77.2197`
      );
      assert.equal(res1.status, 200);
      const report1 = (await res1.json()) as LiveWeatherReport;
      assert.equal(report1.temperatureCelsius.value, 29.4);
      assert.equal(report1.precipitationMmPerHour.value, 12.2);
      assert.equal(report1.isHeavyRain, true);
      assert.equal(report1.walkSpeedMultiplier, 0.75);
      assert.equal(report1.bikeSpeedMultiplier, 0.65);
      assert.ok(report1.advisoryMessage?.includes('Heavy rain'));

      // Second request within 10 minutes hits cache
      const res2 = await fetch(
        `http://127.0.0.1:${port}/api/weather?lat=28.6328&lng=77.2197`
      );
      assert.equal(res2.status, 200);
      assert.equal(fetchCalls, 1);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
