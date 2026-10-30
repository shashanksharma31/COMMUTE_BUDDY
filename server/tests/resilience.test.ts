import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import { createApp } from '../src/app.js';
import { loadEnv, MissingEnvKeyError, requireEnvKey } from '../src/env.js';
import { TtlCache } from '../src/lib/cache.js';
import { ConflictLog } from '../src/lib/conflictLog.js';
import {
  SourceCircuitOpenError,
  SourceQuotaExceededError,
  SourceResilienceManager
} from '../src/lib/resilience.js';
import { computeConfidence, createEnvelope } from '../src/types/envelope.js';

describe('Phase 1 — Backend Foundation & Resilience', () => {
  it('validates env and throws a clear MissingEnvKeyError when a required key is missing', () => {
    const parsed = loadEnv({
      PORT: '3005',
      NODE_ENV: 'test',
      FRONTEND_ORIGIN: 'http://localhost:5173',
      ORS_API_KEY: ''
    });
    assert.equal(parsed.PORT, 3005);
    assert.throws(
      () => requireEnvKey(parsed, 'ORS_API_KEY', 'OpenRouteService road routing'),
      (err: unknown) =>
        err instanceof MissingEnvKeyError &&
        err.keyName === 'ORS_API_KEY' &&
        err.message.includes('OpenRouteService road routing')
    );
  });

  it('computes DataEnvelope confidence with agreement bonus and freshness decay', () => {
    const freshSingle = computeConfidence({
      primarySourceWeight: 0.9,
      agreementCount: 1,
      ageSeconds: 0,
      halfLifeSeconds: 3600
    });
    const freshDual = computeConfidence({
      primarySourceWeight: 0.9,
      agreementCount: 2,
      ageSeconds: 0,
      halfLifeSeconds: 3600
    });
    const agedDual = computeConfidence({
      primarySourceWeight: 0.9,
      agreementCount: 2,
      ageSeconds: 3600,
      halfLifeSeconds: 3600
    });

    assert.ok(freshDual > freshSingle);
    assert.ok(agedDual < freshDual);

    const unavailableEnv = createEnvelope({
      value: null,
      status: 'unavailable',
      sources: [],
      primarySourceWeight: 1.0
    });
    assert.equal(unavailableEnv.confidence, 0);
  });

  it('stores and expires entries in TtlCache', async () => {
    const cache = new TtlCache(10);
    cache.set('place:ndls', { name: 'New Delhi' }, 1);
    assert.deepEqual(cache.get('place:ndls'), { name: 'New Delhi' });

    await new Promise((r) => setTimeout(r, 1100));
    assert.equal(cache.get('place:ndls'), null);
  });

  it('appends source disagreements to ConflictLog', () => {
    const log = new ConflictLog(null);
    const rec = log.append({
      entityType: 'fare',
      entityKey: 'DMRC:m1->m4',
      fieldName: 'fareInr',
      sourceValues: [
        { source: 'operator_official', tier: 1, value: 50 },
        { source: 'google_routes', tier: 4, value: 60 }
      ],
      winningSource: 'operator_official',
      finalValue: 50,
      reason: 'Tier 1 operator fare matrix overrides Tier 4 aggregator (>5% diff)'
    });

    assert.equal(log.getAll().length, 1);
    assert.equal(rec.winningSource, 'operator_official');
    assert.equal(rec.finalValue, 50);
  });

  it('retries transient errors, decays weight on failures, and opens circuit breaker', async () => {
    const manager = new SourceResilienceManager({
      test_source: {
        tier: 4,
        baseWeight: 0.8,
        timeoutMs: 200,
        maxRetries: 1,
        failureThreshold: 2,
        resetTimeoutMs: 150,
        dailyQuotaLimit: 10
      }
    });

    let attempts = 0;
    const recovered = await manager.execute(
      'test_source',
      async () => {
        attempts += 1;
        if (attempts === 1) throw new Error('Transient 503');
        return 'ok';
      },
      { retryDelayBaseMs: 10 }
    );
    assert.equal(recovered, 'ok');
    assert.equal(attempts, 2);

    // Cause 2 consecutive failures to trip circuit breaker
    await assert.rejects(
      () =>
        manager.execute(
          'test_source',
          async () => {
            throw new Error('Upstream down 1');
          },
          { retryDelayBaseMs: 5 }
        ),
      /Upstream down 1/
    );
    await assert.rejects(
      () =>
        manager.execute(
          'test_source',
          async () => {
            throw new Error('Upstream down 2');
          },
          { retryDelayBaseMs: 5 }
        ),
      /Upstream down 2/
    );

    const statsAfterTrip = manager.getSourceStats('test_source');
    assert.equal(statsAfterTrip.circuitState, 'open');
    assert.ok(statsAfterTrip.effectiveWeight < statsAfterTrip.baseWeight);

    // Next call must fail immediately with SourceCircuitOpenError
    await assert.rejects(
      () => manager.execute('test_source', async () => 'never'),
      (err: unknown) => err instanceof SourceCircuitOpenError
    );

    // Wait for resetTimeoutMs and verify half_open -> closed recovery
    await new Promise((r) => setTimeout(r, 170));
    const afterReset = await manager.execute('test_source', async () => 'recovered');
    assert.equal(afterReset, 'recovered');
    assert.equal(manager.getSourceStats('test_source').circuitState, 'closed');
  });

  it('enforces per-source daily quota limit', async () => {
    const manager = new SourceResilienceManager({
      capped_source: {
        tier: 4,
        baseWeight: 0.8,
        timeoutMs: 200,
        maxRetries: 0,
        failureThreshold: 5,
        resetTimeoutMs: 1000,
        dailyQuotaLimit: 2
      }
    });

    await manager.execute('capped_source', async () => 1);
    await manager.execute('capped_source', async () => 2);

    await assert.rejects(
      () => manager.execute('capped_source', async () => 3),
      (err: unknown) => err instanceof SourceQuotaExceededError
    );
  });

  it('serves GET /api/health and GET /api/sources/health', async () => {
    const app = createApp();
    const server = http.createServer(app);
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const address = server.address();
    const port = typeof address === 'object' && address ? address.port : 0;

    try {
      const healthRes = await fetch(`http://127.0.0.1:${port}/api/health`);
      assert.equal(healthRes.status, 200);
      const healthBody = (await healthRes.json()) as { status: string };
      assert.equal(healthBody.status, 'ok');

      const sourcesRes = await fetch(`http://127.0.0.1:${port}/api/sources/health`);
      assert.equal(sourcesRes.status, 200);
      const sourcesBody = (await sourcesRes.json()) as {
        status: string;
        sources: Array<{ sourceName: string }>;
      };
      assert.equal(sourcesBody.status, 'ok');
      assert.ok(sourcesBody.sources.some((s) => s.sourceName === 'operator_official'));
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
});
