import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import {
  CROSS_OPERATOR_HUB_BUFFER_SECONDS,
  MultiModalJourneyResponse,
  SAME_COMPLEX_TRANSFER_BUFFER_SECONDS
} from '../src/journeys/journeyPlanner.js';
import { rateCardCalculator } from '../src/journeys/rateCardCalculator.js';
import { TtlCache } from '../src/lib/cache.js';

describe('Phase 7 — Multi-Modal Journey Planner (POST /api/journeys)', () => {
  it('calculates state tariff meter fares for auto, e-rickshaw, bike, and car with isEstimate: true and status: estimate', () => {
    const autoDel = rateCardCalculator.calculateEstimatedFare({
      mode: 'auto',
      distanceMeters: 3200, // 3.2 km
      durationSeconds: 600, // 10 min
      cityCode: 'DEL'
    });

    assert.equal(autoDel.status, 'estimate');
    assert.equal(autoDel.value?.isEstimate, true);
    assert.equal(autoDel.value?.cityCodeUsed, 'DEL');
    assert.ok((autoDel.value?.fareInr ?? 0) >= 30);
    assert.match(autoDel.value?.message ?? '', /Estimated ₹\d+ \(meter tariff\)/);

    const fallbackCity = rateCardCalculator.calculateEstimatedFare({
      mode: 'e_rickshaw',
      distanceMeters: 1200,
      durationSeconds: 300,
      cityCode: 'UNKNOWN_CITY'
    });
    assert.equal(fallbackCity.value?.cityCodeUsed, 'NATIONAL_DEFAULT');
    assert.equal(fallbackCity.value?.isEstimate, true);
  });

  it('plans multi-modal journeys combining first-mile walk/e-rickshaw + DMRC Metro + Hub Transfer + Namo Bharat RRTS (max 4 legs, max 2 transfers)', async () => {
    const app = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test' }),
      cache: new TtlCache(),
      verificationReferenceDate: new Date('2025-02-15T10:00:00Z')
    });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      // Origin ~700m from Rajiv Chowk Metro (Connaught Place Outer Circle) -> Sahibabad RRTS Station
      const res = await fetch(`http://127.0.0.1:${port}/api/journeys`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          start: {
            name: 'Connaught Place Block M',
            lat: 28.6382,
            lng: 77.2225
          },
          end: {
            name: 'Sahibabad RRTS Station',
            stationId: 'stn_in_del_sahibabad_rrts',
            lat: 28.671,
            lng: 77.436
          },
          departureTime: '09:00',
          cityCode: 'DEL'
        })
      });

      assert.equal(res.status, 200);
      const body = (await res.json()) as MultiModalJourneyResponse;
      assert.equal(body.status, 'ok');
      assert.ok(body.journeys.length >= 1);

      for (const j of body.journeys) {
        // Hard limits: max 4 legs, max 2 transfers
        assert.ok(j.legs.length <= 4, `Expected <= 4 legs, got ${j.legs.length}`);
        assert.ok(j.transfers <= 2, `Expected <= 2 transfers, got ${j.transfers}`);
        assert.ok((j.totalDurationSeconds.value ?? 0) > 0);
        assert.ok(j.tags.length >= 1);

        // Check cross-operator hub transfer buffer (Anand Vihar Metro -> Anand Vihar RRTS uses 600s buffer)
        const rrtsLeg = j.legs.find((l) => l.mode === 'rrts');
        assert.ok(rrtsLeg, 'Expected an RRTS leg in the multi-modal journey');
        assert.equal(
          rrtsLeg.transferBufferSeconds,
          CROSS_OPERATOR_HUB_BUFFER_SECONDS
        );
      }

      // Verify Pareto set contains both a walk-first-mile option (Cheapest / Most verified) and an e-rickshaw-first-mile option (Fastest)
      const hasWalkAccess = body.journeys.some((j) => j.legs[0].mode === 'walk');
      const hasMeteredAccess = body.journeys.some(
        (j) => j.legs[0].mode === 'e_rickshaw' || j.legs[0].mode === 'auto'
      );
      assert.equal(hasWalkAccess, true);
      assert.equal(hasMeteredAccess, true);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });

  it('solves backward from reachByTime in IST and enforces same-complex transfer buffer (300s)', async () => {
    const app = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test' }),
      cache: new TtlCache(),
      verificationReferenceDate: new Date('2025-02-15T10:00:00Z')
    });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      // Millennium City Centre -> Botanical Garden (Yellow Line -> Hauz Khas -> Magenta Line) with reachByTime = "10:30"
      const res = await fetch(`http://127.0.0.1:${port}/api/journeys`, {
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
          reachByTime: '10:30'
        })
      });

      assert.equal(res.status, 200);
      const body = (await res.json()) as MultiModalJourneyResponse;
      assert.equal(body.status, 'ok');
      assert.ok(body.journeys.length >= 1);

      const journey = body.journeys[0];
      assert.equal(journey.estimatedArrivalIst, '10:30');
      assert.ok(
        journey.recommendedLeaveByIst && journey.recommendedLeaveByIst < '10:30',
        `Expected leave-by before 10:30, got ${journey.recommendedLeaveByIst}`
      );

      // Second metro leg (after transfer at Hauz Khas) must have SAME_COMPLEX_TRANSFER_BUFFER_SECONDS (300s)
      assert.equal(journey.legs.length, 2);
      assert.equal(
        journey.legs[1].transferBufferSeconds,
        SAME_COMPLEX_TRANSFER_BUFFER_SECONDS
      );
      assert.ok(journey.legs[0].departureTimeIst);
      assert.equal(journey.legs[1].arrivalTimeIst, '10:30');
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
