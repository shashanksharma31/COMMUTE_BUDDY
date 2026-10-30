import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it } from 'node:test';
import { createApp } from '../src/app.js';
import {
  StationEntityResolver,
  StationResolutionResult
} from '../src/stations/resolver.js';
import { DataEnvelope } from '../src/types/envelope.js';

describe('Phase 3 — Canonical Stations & Entity Resolution', () => {
  const resolver = new StationEntityResolver();

  it('links GTFS stop IDs, Indian Railways codes, OSM node IDs, and Wikidata IDs to one canonical station ID', () => {
    const byGtfs = resolver.resolveStation({ gtfsStopId: 'DMRC_YL_15' });
    const byOsm = resolver.resolveStation({ osmNodeId: 258914231 });
    const byWikidata = resolver.resolveStation({ wikidataId: 'Q3530133' });

    assert.equal(byGtfs.value?.resolution, 'resolved');
    assert.equal(byGtfs.value?.station?.id, 'stn_in_del_rajiv_chowk_metro');
    assert.equal(byOsm.value?.station?.id, 'stn_in_del_rajiv_chowk_metro');
    assert.equal(byWikidata.value?.station?.id, 'stn_in_del_rajiv_chowk_metro');

    const byIrCode = resolver.resolveStation({ irStationCode: 'NDLS' });
    assert.equal(byIrCode.value?.station?.id, 'stn_in_del_ndls_rail');
  });

  it('matches Hindi and regional script aliases across cities (no city special-casing in logic)', () => {
    const hindiRajiv = resolver.resolveStation({ name: 'राजीव चौक मेट्रो स्टेशन' });
    assert.equal(hindiRajiv.value?.resolution, 'resolved');
    assert.equal(hindiRajiv.value?.station?.id, 'stn_in_del_rajiv_chowk_metro');

    const kannadaMajestic = resolver.resolveStation({ name: 'ಮೆಜೆಸ್ಟಿಕ್' });
    assert.equal(kannadaMajestic.value?.resolution, 'resolved');
    assert.equal(kannadaMajestic.value?.station?.id, 'stn_in_blr_majestic_metro');

    const teluguAmeerpet = resolver.resolveStation({ name: 'అమీర్‌పేట్ మెట్రో స్టేషన్' });
    assert.equal(teluguAmeerpet.value?.resolution, 'resolved');
    assert.equal(teluguAmeerpet.value?.station?.id, 'stn_in_hyd_ameerpet_metro');

    const tamilChennai = resolver.resolveStation({ name: 'சென்னை சென்ட்ரல் மெட்ரோ' });
    assert.equal(tamilChennai.value?.resolution, 'resolved');
    assert.equal(tamilChennai.value?.station?.id, 'stn_in_maa_central_metro');
  });

  it('returns an explicit ambiguity result when two station candidates score within 0.1', () => {
    // Querying "Anand Vihar" without mode or exact coordinates matches Anand Vihar Metro, ANVT Rail, and Anand Vihar RRTS within 0.1
    const env = resolver.resolveStation({ name: 'Anand Vihar' });
    assert.equal(env.value?.resolution, 'ambiguous');
    assert.equal(env.value?.station, null);
    assert.equal(env.status, 'unverified');
    assert.ok((env.value?.candidates.length ?? 0) >= 2);
  });

  it('requires at least 2 confirming sources to mark a station as verified', () => {
    const verified = resolver.resolveStation({ id: 'stn_in_del_rajiv_chowk_metro' });
    assert.equal(verified.value?.station?.isVerified, true);
    assert.equal(verified.status, 'scheduled');

    const singleSource = resolver.resolveStation({ id: 'stn_in_del_unconfirmed_halt' });
    assert.equal(singleSource.value?.station?.isVerified, false);
    assert.equal(singleSource.status, 'unverified');
  });

  it('models interchange hubs with walking links <= 400m and serves /api/stations endpoints', async () => {
    const links = resolver.getHubWalkingLinks('stn_in_del_anand_vihar_metro');
    assert.equal(links.length, 2);
    assert.ok(links.every((l) => l.walkDistanceMeters <= 400));

    const app = createApp({ stationResolver: resolver });
    const server = http.createServer(app);
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as { port: number }).port;

    try {
      const res = await fetch(
        `http://127.0.0.1:${port}/api/stations/resolve?name=${encodeURIComponent('हाउज़ खास')}`
      );
      assert.equal(res.status, 200);
      const body = (await res.json()) as DataEnvelope<StationResolutionResult>;
      assert.equal(body.value?.resolution, 'resolved');
      assert.equal(body.value?.station?.id, 'stn_in_del_hauz_khas_metro');

      const hubRes = await fetch(
        `http://127.0.0.1:${port}/api/stations/stn_in_del_new_delhi_metro/hub`
      );
      assert.equal(hubRes.status, 200);
      const hubBody = (await hubRes.json()) as {
        hubLinks: Array<{ toStationId: string; walkDistanceMeters: number }>;
      };
      assert.equal(hubBody.hubLinks.length, 1);
      assert.equal(hubBody.hubLinks[0].toStationId, 'stn_in_del_ndls_rail');
      assert.ok(hubBody.hubLinks[0].walkDistanceMeters <= 400);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
