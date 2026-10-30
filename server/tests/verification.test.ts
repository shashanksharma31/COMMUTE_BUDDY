import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import { fareStore } from '../src/ingestion/fareStore.js';
import { gtfsStore } from '../src/ingestion/gtfsFetcher.js';
import { TtlCache } from '../src/lib/cache.js';
import { ConflictLog } from '../src/lib/conflictLog.js';
import { SingleModeRoutingResponse } from '../src/providers/transit/types.js';
import { stationResolver } from '../src/stations/resolver.js';
import { createEnvelope } from '../src/types/envelope.js';
import { VerificationEngine } from '../src/verification/verificationEngine.js';

describe('Phase 6 — Verification Engine', () => {
  it('verifies station identity requiring >= 2 independent sources and marks single-source stations unverified', () => {
    const conflicts = new ConflictLog(null);
    const engine = new VerificationEngine(stationResolver, gtfsStore, conflicts);

    // Multi-source station from seed (Rajiv Chowk has gtfs, osm, wikidata)
    const verifiedCheck = engine.verifyStationIdentity('stn_in_del_rajiv_chowk_metro');
    assert.equal(verifiedCheck.isVerified, true);
    assert.equal(verifiedCheck.status, 'scheduled');
    assert.ok(verifiedCheck.confirmingSources.length >= 2);
    assert.equal(verifiedCheck.flags.length, 0);

    // Single-source synthetic station
    const singleSourceCheck = engine.verifyStationIdentity({
      id: 'stn_test_single_source',
      canonicalName: 'Single Source Halt',
      aliases: [],
      cityCode: 'DEL',
      stationType: 'metro',
      lat: 28.61,
      lng: 77.21,
      h3Res10: 'test_cell',
      gtfsStopIds: ['STOP_1'],
      irStationCode: null,
      osmNodeIds: [],
      googlePlaceId: null,
      wikidataId: null,
      confirmingSources: ['osm'],
      isVerified: false,
      source_url: 'https://www.openstreetmap.org/',
      effective_from: '2025-01-01'
    });
    assert.equal(singleSourceCheck.isVerified, false);
    assert.equal(singleSourceCheck.status, 'unverified');
    assert.ok(singleSourceCheck.flags.includes('single_source_station'));
  });

  it('cross-checks fares: retains operator Tier 1 fare on > 5% disagreement, logs conflict, and flags > 90-day staleness', () => {
    const conflicts = new ConflictLog(null);
    const engine = new VerificationEngine(stationResolver, gtfsStore, conflicts);

    const primaryFareEnv = fareStore.lookupExactTransitFare({
      operatorCode: 'DMRC',
      fromStationId: 'stn_in_del_millennium_city_centre_metro',
      toStationId: 'stn_in_del_botanical_garden_metro',
      dayType: 'weekday',
      referenceDate: new Date('2025-02-15T10:00:00Z') // 45 days after 2025-01-01 (fresh)
    });

    // 1. Secondary fare disagrees by > 5% (e.g. ₹60 operator vs ₹50 Google Routes = 16.7% diff)
    const disagreeResult = engine.verifyFare({
      entityKey: 'DMRC:MCC->BG',
      primaryEnvelope: primaryFareEnv,
      secondaryFareInr: 50,
      secondarySource: 'google_routes',
      referenceDate: new Date('2025-02-15T10:00:00Z')
    });

    assert.equal(disagreeResult.envelope.value?.fareInr, 60); // Tier 1 operator fare wins
    assert.equal(disagreeResult.envelope.status, 'scheduled');
    assert.ok(disagreeResult.flags.includes('fare_cross_check_disagreement'));
    assert.equal(conflicts.getAll().length, 1);
    assert.equal(conflicts.getAll()[0].winningSource, 'operator_official');
    assert.equal(conflicts.getAll()[0].finalValue, 60);

    // 2. Reference date > 90 days after effective_from (2025-01-01 -> 2025-06-01 = 151 days)
    const staleResult = engine.verifyFare({
      entityKey: 'DMRC:MCC->BG',
      primaryEnvelope: primaryFareEnv,
      referenceDate: new Date('2025-06-01T10:00:00Z')
    });
    assert.equal(staleResult.isStaleOver90Days, true);
    assert.equal(staleResult.envelope.status, 'stale');
    assert.ok(staleResult.flags.includes('fare_table_older_than_90_days'));
  });

  it('cross-checks durations: flags > 25% disagreement as unverified with both values, and boosts confidence when <= 25%', () => {
    const conflicts = new ConflictLog(null);
    const engine = new VerificationEngine(stationResolver, gtfsStore, conflicts);

    const primaryDurationEnv = createEnvelope<number>({
      value: 3600, // 60 min in seconds
      status: 'scheduled',
      sources: ['operator_gtfs'],
      primarySourceWeight: 0.95
    });

    // 1. Secondary agrees within <= 25% (3900s vs 3600s = 8.3% diff)
    const agreeRes = engine.verifyDuration({
      entityKey: 'route_agree',
      primaryEnvelope: primaryDurationEnv,
      secondaryDurationSeconds: 3900,
      secondarySource: 'google_routes'
    });
    assert.equal(agreeRes.envelope.status, 'scheduled');
    assert.deepEqual(agreeRes.envelope.sources, ['operator_gtfs', 'google_routes']);
    assert.ok(agreeRes.envelope.confidence > primaryDurationEnv.confidence);
    assert.equal(conflicts.getAll().length, 0);

    // 2. Secondary disagrees by > 25% (5400s vs 3600s = 50% diff)
    const disagreeRes = engine.verifyDuration({
      entityKey: 'route_disagree',
      primaryEnvelope: primaryDurationEnv,
      secondaryDurationSeconds: 5400,
      secondarySource: 'google_routes'
    });
    assert.equal(disagreeRes.envelope.status, 'unverified');
    assert.ok(disagreeRes.flags.includes('duration_sources_disagree'));
    assert.deepEqual(disagreeRes.bothValues, {
      primarySeconds: 3600,
      secondarySeconds: 5400
    });
    assert.equal(conflicts.getAll().length, 1);
    assert.equal(conflicts.getAll()[0].entityType, 'route_duration');
  });

  it('enforces operating hours (rejects 01:30 AM closed metro) and guards live vs scheduled status', async () => {
    const conflicts = new ConflictLog(null);
    const engine = new VerificationEngine(stationResolver, gtfsStore, conflicts);

    // Live vs scheduled guard: static feed claiming "live" is downgraded to "scheduled"
    const fakeLiveEnv = createEnvelope<number>({
      value: 1800,
      status: 'live',
      sources: ['operator_gtfs'],
      primarySourceWeight: 0.95
    });
    const downgraded = engine.verifyRealtimeStatus({
      envelope: fakeLiveEnv,
      hasRealtimeFeed: false
    });
    assert.equal(downgraded.envelope.status, 'scheduled');
    assert.ok(downgraded.flags.includes('downgraded_from_live_to_scheduled'));

    // Live feed older than 90s is downgraded to "stale"
    const oldAsOf = new Date('2025-03-01T10:00:00Z');
    const refNow = new Date('2025-03-01T10:03:00Z'); // 180s old (> 90s)
    const staleLive = engine.verifyRealtimeStatus({
      envelope: {
        ...fakeLiveEnv,
        as_of: oldAsOf.toISOString(),
        status: 'live'
      },
      hasRealtimeFeed: true,
      referenceDate: refNow
    });
    assert.equal(staleLive.envelope.status, 'stale');
    assert.ok(staleLive.flags.includes('stale_realtime_feed'));

    // End-to-end POST /api/routes operating hours check at 01:30 AM vs 09:30 AM
    const app = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test' }),
      cache: new TtlCache(),
      conflictLog: conflicts,
      verificationReferenceDate: new Date('2025-02-15T10:00:00Z')
    });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      // 01:30 AM — DMRC Yellow Line at Millennium City Centre is closed (runs 05:45 - 23:00)
      const closedRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
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
          mode: 'metro',
          departureTime: '01:30'
        })
      });
      assert.equal(closedRes.status, 200);
      const closedBody = (await closedRes.json()) as SingleModeRoutingResponse;
      assert.equal(
        closedBody.message,
        'Requested departure time is outside transit operating hours'
      );
      assert.equal(closedBody.routes[0].durationSeconds.status, 'unavailable');
      assert.equal(closedBody.routes[0].verification?.overallStatus, 'unavailable');
      assert.equal(
        closedBody.routes[0].verification?.operatingHoursCheck.withinOperatingHours,
        false
      );
      assert.ok(
        closedBody.routes[0].verification?.flags.includes('outside_operating_hours')
      );

      // 09:30 AM — Within operating hours
      const openRes = await fetch(`http://127.0.0.1:${port}/api/routes`, {
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
          mode: 'metro',
          departureTime: '09:30'
        })
      });
      assert.equal(openRes.status, 200);
      const openBody = (await openRes.json()) as SingleModeRoutingResponse;
      assert.equal(openBody.routes[0].durationSeconds.status, 'scheduled');
      assert.equal(openBody.routes[0].verification?.overallStatus, 'scheduled');
      assert.equal(
        openBody.routes[0].verification?.operatingHoursCheck.withinOperatingHours,
        true
      );
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
