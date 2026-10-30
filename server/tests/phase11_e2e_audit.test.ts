import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, before, after } from 'node:test';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import { TtlCache } from '../src/lib/cache.js';
import { ConflictLog } from '../src/lib/conflictLog.js';

describe('Phase 11 — End-to-End Integration Tests, Trust/Provenance Audit & Final Verification', () => {
  let server: http.Server;
  let baseUrl = '';
  const conflictLog = new ConflictLog(null);

  before(async () => {
    const mockFetch: typeof fetch = async (input) => {
      const url = typeof input === 'string' ? input : input.toString();

      if (url.includes('photon.komoot.io/api')) {
        return new Response(
          JSON.stringify({
            type: 'FeatureCollection',
            features: [
              {
                type: 'Feature',
                geometry: { type: 'Point', coordinates: [77.6068, 12.9756] },
                properties: {
                  osm_id: 5001,
                  osm_type: 'N',
                  name: 'MG Road Metro Station',
                  city: 'Bengaluru',
                  state: 'Karnataka',
                  countrycode: 'IN',
                  osm_key: 'railway',
                  osm_value: 'subway_entrance'
                }
              },
              {
                type: 'Feature',
                geometry: { type: 'Point', coordinates: [-0.1276, 51.5072] },
                properties: {
                  osm_id: 9999,
                  osm_type: 'N',
                  name: 'London Charing Cross',
                  city: 'London',
                  countrycode: 'GB'
                }
              }
            ]
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      return new Response('{}', { status: 404 });
    };

    const app = createApp({
      appEnv: loadEnv({
        NODE_ENV: 'test',
        GEMINI_API_KEY: '',
        NOMINATIM_USER_AGENT: 'CommuteBuddy/1.0 (audit@commutebuddy.in)'
      }),
      cache: new TtlCache(),
      conflictLog,
      fetchFn: mockFetch
    });

    server = http.createServer(app);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
    const port = (server.address() as { port: number }).port;
    baseUrl = `http://127.0.0.1:${port}`;
  });

  after(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it('1. restricts place search to India (filters out non-IN results) and rejects out-of-India reverse geocode coordinates', async () => {
    const searchRes = await fetch(`${baseUrl}/api/places?q=MG+Road`);
    assert.equal(searchRes.status, 200);
    const searchBody = (await searchRes.json()) as {
      value: Array<{ name: string; countryCode: string }>;
      status: string;
    };
    assert.equal(searchBody.status, 'live');
    assert.equal(searchBody.value.length, 1);
    assert.equal(searchBody.value[0].countryCode, 'IN');

    // Out-of-India coordinates (London: 51.5, -0.12) rejected with 400
    const revOutRes = await fetch(`${baseUrl}/api/places/reverse?lat=51.5072&lng=-0.1276`);
    assert.equal(revOutRes.status, 400);
  });

  it('2. resolves regional scripts across Indian cities and flags ambiguous multi-mode station names', async () => {
    const kannadaRes = await fetch(
      `${baseUrl}/api/stations/resolve?name=${encodeURIComponent('ಮೆಜೆಸ್ಟಿಕ್')}`
    );
    assert.equal(kannadaRes.status, 200);
    const kannadaBody = (await kannadaRes.json()) as {
      value: { resolution: string; station: { id: string; cityCode: string } };
    };
    assert.equal(kannadaBody.value.resolution, 'resolved');
    assert.equal(kannadaBody.value.station.id, 'stn_in_blr_majestic_metro');
    assert.equal(kannadaBody.value.station.cityCode, 'BLR');

    const tamilRes = await fetch(
      `${baseUrl}/api/stations/resolve?name=${encodeURIComponent('சென்னை சென்ட்ரல் மெட்ரோ')}`
    );
    assert.equal(tamilRes.status, 200);
    const tamilBody = (await tamilRes.json()) as {
      value: { resolution: string; station: { id: string; cityCode: string } };
    };
    assert.equal(tamilBody.value.resolution, 'resolved');
    assert.equal(tamilBody.value.station.id, 'stn_in_maa_central_metro');
    assert.equal(tamilBody.value.station.cityCode, 'MAA');

    // Ambiguous query ("Anand Vihar" matches Metro, Rail, and RRTS within 0.1)
    const ambigRes = await fetch(
      `${baseUrl}/api/stations/resolve?name=${encodeURIComponent('Anand Vihar')}`
    );
    assert.equal(ambigRes.status, 200);
    const ambigBody = (await ambigRes.json()) as {
      status: string;
      value: { resolution: string; candidates: unknown[] };
    };
    assert.equal(ambigBody.value.resolution, 'ambiguous');
    assert.equal(ambigBody.status, 'unverified');
    assert.ok(ambigBody.value.candidates.length >= 2);
  });

  it('3. returns exact operator fares for verified metro routes and explicit no_data for unverified areas or buses', async () => {
    // Verified DMRC Metro route: Millennium City Centre Gurugram -> Botanical Garden Noida
    const dmrcRes = await fetch(`${baseUrl}/api/routes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        start: {
          name: 'Millennium City Centre Gurugram',
          lat: 28.4595,
          lng: 77.0726,
          stationId: 'stn_in_del_millennium_city_centre_metro'
        },
        end: {
          name: 'Botanical Garden Metro Station',
          lat: 28.5641,
          lng: 77.3343,
          stationId: 'stn_in_del_botanical_garden_metro'
        },
        mode: 'metro',
        departureTime: '10:30'
      })
    });
    assert.equal(dmrcRes.status, 200);
    const dmrcBody = (await dmrcRes.json()) as {
      status: string;
      routes: Array<{
        durationSeconds: { value: number };
        fare: { value: { fareInr: number }; sources: string[] };
      }>;
    };
    assert.equal(dmrcBody.status, 'ok');
    assert.ok(dmrcBody.routes.length > 0);
    assert.ok(dmrcBody.routes[0].durationSeconds.value > 0);
    assert.equal(dmrcBody.routes[0].fare.value.fareInr, 60);
    assert.ok(dmrcBody.routes[0].fare.sources.includes('operator_official'));

    // Unverified area (Shimla coordinates with no metro) returns no_data, never fake routes
    const unverifiedRes = await fetch(`${baseUrl}/api/routes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        start: { name: 'Shimla Mall Road', lat: 31.1048, lng: 77.1734 },
        end: { name: 'Kufri', lat: 31.0978, lng: 77.2678 },
        mode: 'metro'
      })
    });
    assert.equal(unverifiedRes.status, 200);
    const unverifiedBody = (await unverifiedRes.json()) as {
      status: string;
      routes: unknown[];
      message: string;
    };
    assert.equal(unverifiedBody.status, 'no_data');
    assert.equal(unverifiedBody.routes.length, 0);
    assert.match(unverifiedBody.message, /no transit data for this area/i);
  });

  it('4. plans multi-modal journeys (Mix), solves Reach By backward in IST, and flags closed night-time metro departures', async () => {
    const journeyRes = await fetch(`${baseUrl}/api/journeys`, {
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
        reachByTime: '10:30',
        cityCode: 'DEL'
      })
    });
    assert.equal(journeyRes.status, 200);
    const journeyBody = (await journeyRes.json()) as {
      status: string;
      journeys: Array<{
        estimatedArrivalIst?: string;
        recommendedLeaveByIst?: string;
        totalDurationSeconds: { value: number };
        totalFare: { value: { amountInr: number; isEstimate: boolean } };
      }>;
    };
    assert.equal(journeyBody.status, 'ok');
    assert.ok(journeyBody.journeys.length > 0);
    assert.equal(journeyBody.journeys[0].estimatedArrivalIst, '10:30');
    assert.ok(journeyBody.journeys[0].recommendedLeaveByIst);
    assert.ok(journeyBody.journeys[0].totalDurationSeconds.value > 300);

    // Night-time 01:30 AM metro check -> outside operating hours
    const nightRes = await fetch(`${baseUrl}/api/routes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        start: {
          name: 'Rajiv Chowk Metro Station',
          lat: 28.6328,
          lng: 77.2197,
          stationId: 'stn_in_del_rajiv_chowk_metro'
        },
        end: {
          name: 'Hauz Khas Metro Station',
          lat: 28.5433,
          lng: 77.2066,
          stationId: 'stn_in_del_hauz_khas_metro'
        },
        mode: 'metro',
        departureTime: '01:30'
      })
    });
    assert.equal(nightRes.status, 200);
    const nightBody = (await nightRes.json()) as {
      message?: string;
      routes: Array<{
        verification?: { operatingHoursCheck: { withinOperatingHours: boolean } };
      }>;
    };
    assert.equal(nightBody.routes[0].verification?.operatingHoursCheck.withinOperatingHours, false);
    assert.match(nightBody.message ?? '', /outside transit operating hours/i);
  });

  it('5. flags an entity for re-verification when >= 3 distinct user issue reports arrive within 24 hours', async () => {
    const entityKey = 'DMRC:stn_in_del_rajiv_chowk_metro->stn_in_del_hauz_khas_metro';

    for (let i = 1; i <= 2; i++) {
      const r = await fetch(`${baseUrl}/api/reports/issue`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          entityType: 'fare',
          entityKey,
          issueType: 'wrong_fare',
          reportedValue: 40,
          description: 'Token machine charged Rs 40 instead of Rs 30',
          reporterId: `commuter_${i}`
        })
      });
      assert.equal(r.status, 201);
      const b = (await r.json()) as { status: string; flaggedForReverification: boolean };
      assert.equal(b.status, 'recorded');
      assert.equal(b.flaggedForReverification, false);
    }

    // 3rd independent report within 24h triggers needs_reverification and logs to ConflictLog
    const thirdRes = await fetch(`${baseUrl}/api/reports/issue`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        entityType: 'fare',
        entityKey,
        issueType: 'wrong_fare',
        reportedValue: 40,
        description: 'Smart card deduction was Rs 40 before 8 AM',
        reporterId: 'commuter_3'
      })
    });
    assert.equal(thirdRes.status, 201);
    const thirdBody = (await thirdRes.json()) as {
      status: string;
      distinctReportersLast24h: number;
      flaggedForReverification: boolean;
    };
    assert.equal(thirdBody.status, 'needs_reverification');
    assert.equal(thirdBody.distinctReportersLast24h, 3);
    assert.equal(thirdBody.flaggedForReverification, true);

    const statusRes = await fetch(
      `${baseUrl}/api/reports/status?entityType=fare&entityKey=${encodeURIComponent(entityKey)}`
    );
    assert.equal(statusRes.status, 200);
    const statusBody = (await statusRes.json()) as { flaggedForReverification: boolean };
    assert.equal(statusBody.flaggedForReverification, true);
  });

  it('6. passes static codebase audit: no hardcoded DELHI_PLACES, no fake corridor generator, no linear fake fare formula, and no client-side Gemini keys', () => {
    const testDir = path.dirname(fileURLToPath(import.meta.url));
    const repoRoot = path.resolve(testDir, '../..');
    const tsPlaces = fs.readFileSync(path.join(repoRoot, 'src/data/places.ts'), 'utf8');
    const tsGetRoutes = fs.readFileSync(path.join(repoRoot, 'src/lib/getRoutes.ts'), 'utf8');
    const tsFares = fs.readFileSync(path.join(repoRoot, 'src/lib/fares.ts'), 'utf8');
    const tsChat = fs.readFileSync(path.join(repoRoot, 'src/lib/chatApi.ts'), 'utf8');

    const ktPlaces = fs.readFileSync(
      path.join(repoRoot, 'app/src/main/java/com/example/data/Places.kt'),
      'utf8'
    );
    const ktGetRoutes = fs.readFileSync(
      path.join(repoRoot, 'app/src/main/java/com/example/lib/GetRoutes.kt'),
      'utf8'
    );
    const ktFares = fs.readFileSync(
      path.join(repoRoot, 'app/src/main/java/com/example/lib/Fares.kt'),
      'utf8'
    );
    const ktChat = fs.readFileSync(
      path.join(repoRoot, 'app/src/main/java/com/example/lib/ChatApi.kt'),
      'utf8'
    );

    // No hardcoded 30-place list in frontend
    assert.ok(!tsPlaces.includes('Select CITYWALK Mall'));
    assert.ok(!ktPlaces.includes('Select CITYWALK Mall'));

    // No fake corridor path or fake step builder in frontend
    assert.ok(!tsGetRoutes.includes('buildDelhiCorridorPath'));
    assert.ok(!ktGetRoutes.includes('buildDelhiCorridorPath'));

    // No linear fake fare formula in frontend
    assert.ok(!tsFares.includes('perKmInr'));
    assert.ok(!ktFares.includes('perKmInr'));

    // No Gemini API key in frontend chat clients
    assert.ok(!tsChat.includes('GEMINI_API_KEY'));
    assert.ok(!ktChat.includes('GEMINI_API_KEY'));
  });
});
