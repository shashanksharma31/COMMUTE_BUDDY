import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { OperatorFareStore } from '../src/ingestion/fareStore.js';
import { GtfsIngestionStore } from '../src/ingestion/gtfsFetcher.js';
import { loadAndRegisterRrtsYaml } from '../src/ingestion/rrtsLoader.js';
import { StaticTrainScheduleStore } from '../src/ingestion/trainScheduleLoader.js';

describe('Phase 4 — Ingestion: GTFS, Operator Fares, First/Last Trains, RRTS YAML & Rail Timetable', () => {
  it('checks SHA-256 hash, skips unchanged GTFS feeds, and runs MobilityData validation', async () => {
    const store = new GtfsIngestionStore();

    const validPayload = {
      feedId: 'DMRC_STATIC_2025',
      operatorCode: 'DMRC',
      source_url: 'https://otd.delhi.gov.in/data/staticDMRC/',
      effective_from: '2025-01-01',
      stopsTxt: 'stop_id,stop_name,stop_lat,stop_lon\nDMRC_YL_15,Rajiv Chowk,28.6328,77.2197',
      routesTxt: 'route_id,route_short_name,route_type\nDMRC_YELLOW,YL,1',
      tripsTxt: 'route_id,service_id,trip_id\nDMRC_YELLOW,WK,TRIP_1',
      stopTimesTxt: 'trip_id,arrival_time,departure_time,stop_id,stop_sequence\nTRIP_1,08:00:00,08:00:30,DMRC_YL_15,1'
    };

    const firstRun = await store.ingestGtfsFeed(validPayload);
    assert.equal(firstRun.validatorPassed, true);
    assert.equal(firstRun.skippedUnchanged, false);

    // Second run with identical content must be skipped by SHA-256 hash check
    const secondRun = await store.ingestGtfsFeed(validPayload);
    assert.equal(secondRun.validatorPassed, true);
    assert.equal(secondRun.skippedUnchanged, true);
    assert.equal(secondRun.sha256Hash, firstRun.sha256Hash);

    // Malformed GTFS feed missing required columns must fail validator
    const invalidRun = await store.ingestGtfsFeed({
      ...validPayload,
      feedId: 'BAD_FEED',
      stopsTxt: 'stop_id,stop_name\n1,OnlyTwoCols'
    });
    assert.equal(invalidRun.validatorPassed, false);
    assert.ok(invalidRun.validationErrors.some((e) => e.includes('stop_lat')));
  });

  it('matches exact operator OD matrices and non-linear slabs, and returns fare unavailable with deep-link when missing', () => {
    const fares = new OperatorFareStore();

    // 1. Exact OD matrix match (Rajiv Chowk -> Botanical Garden = ₹40)
    const od = fares.lookupExactTransitFare({
      operatorCode: 'DMRC',
      fromStationId: 'stn_in_del_rajiv_chowk_metro',
      toStationId: 'stn_in_del_botanical_garden_metro',
      dayType: 'weekday'
    });
    assert.equal(od.status, 'scheduled');
    assert.equal(od.value?.fareInr, 40);
    assert.equal(od.value?.matchedBy, 'od_matrix');

    // 2. Non-linear slab match on Sunday/Holiday discount (15 km on DMRC = ₹40 weekday, ₹30 Sunday/Holiday)
    const sundaySlab = fares.lookupExactTransitFare({
      operatorCode: 'DMRC',
      fromStationId: 'stn_in_del_kashmere_gate_metro',
      toStationId: 'stn_in_del_hauz_khas_metro',
      trackDistanceKm: 15.7,
      dayType: 'sunday'
    });
    assert.equal(sundaySlab.status, 'scheduled');
    assert.equal(sundaySlab.value?.fareInr, 30);
    assert.equal(sundaySlab.value?.matchedBy, 'operator_slab');

    // 3. Missing operator fare -> never guesses or applies national default; returns "fare unavailable" + deep-link
    const missing = fares.lookupExactTransitFare({
      operatorCode: 'BMRCL',
      fromStationId: 'stn_in_blr_majestic_metro',
      toStationId: 'stn_in_blr_sbc_rail'
    });
    assert.equal(missing.status, 'unavailable');
    assert.equal(missing.value?.fareInr, null);
    assert.equal(missing.value?.message, 'fare unavailable');
    assert.equal(missing.value?.operatorDeepLink, 'https://english.bmrc.co.in/');
  });

  it('loads and validates NCRTC Namo Bharat RRTS YAML and Indian Railways static schedules', () => {
    const gtfs = new GtfsIngestionStore();
    const fares = new OperatorFareStore();

    const rrts = loadAndRegisterRrtsYaml(gtfs, fares);
    assert.equal(rrts.meta.operator_code, 'NCRTC');

    const rrtsFare = fares.lookupExactTransitFare({
      operatorCode: 'NCRTC',
      fromStationId: 'stn_in_del_anand_vihar_rrts',
      toStationId: 'stn_in_del_sahibabad_rrts'
    });
    assert.equal(rrtsFare.value?.fareInr, 30);

    const rrtsFirstLast = gtfs.getFirstLastTrain(
      'NCRTC_RRTS_DEL_MEERUT',
      'stn_in_del_anand_vihar_rrts',
      'weekday'
    );
    assert.equal(rrtsFirstLast?.firstTrainTime, '06:00');
    assert.equal(rrtsFirstLast?.lastTrainTime, '22:00');

    const railStore = new StaticTrainScheduleStore();
    const trains = railStore.findSchedulesBetween(
      'stn_in_del_ndls_rail',
      'stn_in_del_anvt_rail'
    );
    assert.equal(trains.length, 2);
    assert.equal(trains[0].trainNumber, '12004');
  });
});
