import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import {
  RegulatedExplanationResult,
  RegulatedJourneyExplainer
} from '../src/ai/regulatedExplainer.js';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import { MultiModalJourneyOption } from '../src/journeys/journeyPlanner.js';
import { TtlCache } from '../src/lib/cache.js';
import { SourceResilienceManager } from '../src/lib/resilience.js';
import { createEnvelope } from '../src/types/envelope.js';

function createSampleVerifiedJourney(): MultiModalJourneyOption {
  return {
    id: 'journey_metro_test',
    label: 'Fastest',
    tags: ['Fastest'],
    legs: [
      {
        legId: 'leg_1',
        mode: 'metro',
        instruction: 'Yellow Line: Millennium City Centre to Hauz Khas',
        fromName: 'Millennium City Centre Gurugram',
        toName: 'Hauz Khas Metro Station',
        fromStationId: 'stn_in_del_millennium_city_centre_metro',
        toStationId: 'stn_in_del_hauz_khas_metro',
        routeId: 'DMRC_YELLOW',
        lineName: 'Yellow Line',
        operatorCode: 'DMRC',
        durationSeconds: createEnvelope<number>({
          value: 1800, // 30 min
          status: 'scheduled',
          sources: ['operator_gtfs'],
          primarySourceWeight: 0.95
        }),
        distanceMeters: 17500,
        transferBufferSeconds: 0,
        fare: createEnvelope({
          value: {
            fareInr: 60,
            currency: 'INR',
            matchedBy: 'od_matrix',
            message: '₹60',
            operatorCode: 'DMRC',
            operatorDeepLink: 'https://www.delhimetrorail.com/',
            source_url: 'https://www.delhimetrorail.com/',
            effective_from: '2025-01-01',
            isStaleOver90Days: false
          },
          status: 'scheduled',
          sources: ['operator_official'],
          primarySourceWeight: 1.0
        }),
        path: [
          [28.4595, 77.0726],
          [28.5433, 77.2066]
        ]
      }
    ],
    totalDurationSeconds: createEnvelope<number>({
      value: 1800,
      status: 'scheduled',
      sources: ['operator_gtfs'],
      primarySourceWeight: 0.95
    }),
    totalDistanceMeters: 17500,
    totalWalkMeters: 120,
    transfers: 0,
    totalFare: createEnvelope({
      value: {
        amountInr: 60,
        currency: 'INR',
        hasEstimatedLeg: false,
        hasUnavailableLeg: false,
        isEstimate: false,
        message: '₹60'
      },
      status: 'scheduled',
      sources: ['operator_official'],
      primarySourceWeight: 1.0
    }),
    lowestConfidence: 0.95,
    overallStatus: 'scheduled',
    flags: []
  };
}

describe('Phase 9 — Regulated Gemini Explanations & Chat (POST /api/journeys/explain & POST /api/chat)', () => {
  it('accepts Gemini JSON when all legIds and numbers match the verified journey envelopes', async () => {
    const journey = createSampleVerifiedJourney();
    const validGeminiFetch: typeof fetch = async () =>
      new Response(
        JSON.stringify({
          candidates: [
            {
              content: {
                parts: [
                  {
                    text: JSON.stringify({
                      summary:
                        'Take leg_1 on the Yellow Line from Millennium City Centre Gurugram to Hauz Khas Metro Station in 30 min for ₹60 with 0 transfers and 120 m walk.',
                      referencedLegIds: ['leg_1'],
                      citedNumbers: [30, 60, 0, 120],
                      confidenceNote: 'Verified scheduled timetable with 95% confidence.'
                    })
                  }
                ]
              }
            }
          ]
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );

    const explainer = new RegulatedJourneyExplainer(
      loadEnv({ NODE_ENV: 'test', GEMINI_API_KEY: 'test_gemini_key' }),
      new SourceResilienceManager(),
      validGeminiFetch
    );

    const res = await explainer.explainJourney({ journey });
    assert.equal(res.generatedBy, 'gemini_verified');
    assert.equal(res.hallucinationRejected, false);
    assert.deepEqual(res.referencedLegIds, ['leg_1']);
    assert.ok(res.summary.includes('30 min'));
    assert.ok(res.summary.includes('₹60'));
  });

  it('rejects hallucinated numbers, unknown legIds, or emojis and falls back to the deterministic template', async () => {
    const journey = createSampleVerifiedJourney();

    // 1. Hallucinated fare (₹42 instead of ₹60)
    const hallucinatedNumberFetch: typeof fetch = async () =>
      new Response(
        JSON.stringify({
          candidates: [
            {
              content: {
                parts: [
                  {
                    text: JSON.stringify({
                      summary: 'Take leg_1 for ₹42 in 30 min.',
                      referencedLegIds: ['leg_1'],
                      citedNumbers: [42, 30],
                      confidenceNote: 'Confidence 95%.'
                    })
                  }
                ]
              }
            }
          ]
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );

    const explainerNum = new RegulatedJourneyExplainer(
      loadEnv({ NODE_ENV: 'test', GEMINI_API_KEY: 'test_gemini_key' }),
      new SourceResilienceManager(),
      hallucinatedNumberFetch
    );

    const numRes = await explainerNum.explainJourney({ journey });
    assert.equal(numRes.hallucinationRejected, true);
    assert.equal(numRes.generatedBy, 'deterministic_template');
    assert.ok(numRes.rejectionReason?.includes('42'));
    assert.ok(numRes.summary.includes('₹60'));

    // 2. Hallucinated legId (leg_99)
    const checkLeg = explainerNum.validateCandidateExplanation(
      {
        summary: 'Take 30 min ride for ₹60.',
        referencedLegIds: ['leg_99'],
        citedNumbers: [30, 60],
        confidenceNote: 'Confidence 95%.'
      },
      journey
    );
    assert.equal(checkLeg.valid, false);

    // 3. Prohibited emoji in output
    const checkEmoji = explainerNum.validateCandidateExplanation(
      {
        summary: 'Fast metro ride 🚆 in 30 min for ₹60.',
        referencedLegIds: ['leg_1'],
        citedNumbers: [30, 60],
        confidenceNote: 'Confidence 95%.'
      },
      journey
    );
    assert.equal(checkEmoji.valid, false);
  });

  it('serves POST /api/journeys/explain and POST /api/chat with deterministic fallback when GEMINI_API_KEY is unset', async () => {
    const journey = createSampleVerifiedJourney();
    const app = createApp({
      appEnv: loadEnv({ NODE_ENV: 'test', GEMINI_API_KEY: '' }),
      cache: new TtlCache()
    });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const explainRes = await fetch(`http://127.0.0.1:${port}/api/journeys/explain`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          journey,
          question: 'How long is this trip and what is the fare?'
        })
      });
      assert.equal(explainRes.status, 200);
      const explainBody = (await explainRes.json()) as RegulatedExplanationResult;
      assert.equal(explainBody.generatedBy, 'deterministic_template');
      assert.equal(explainBody.hallucinationRejected, false);
      assert.ok(explainBody.summary.includes('30 min'));
      assert.ok(explainBody.summary.includes('₹60'));

      const chatRes = await fetch(`http://127.0.0.1:${port}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: 'Is the fare verified?',
          journey
        })
      });
      assert.equal(chatRes.status, 200);
      const chatBody = (await chatRes.json()) as { reply: string; generatedBy: string };
      assert.equal(chatBody.generatedBy, 'deterministic_template');
      assert.ok(chatBody.reply.includes('₹60'));
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
