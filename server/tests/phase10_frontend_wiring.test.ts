import assert from 'node:assert/strict';
import http from 'node:http';
import { describe, it, before, after } from 'node:test';
import {
  DELHI_PLACES,
  searchPlaces,
  reverseGeocodePlace,
  setBackendBaseUrl
} from '../../src/data/places.js';
import { formatDuration } from '../../src/lib/formatDuration.js';
import { getRoutes } from '../../src/lib/getRoutes.js';
import { sendMessage } from '../../src/lib/chatApi.js';
import { createApp } from '../src/app.js';
import { loadEnv } from '../src/env.js';
import { TtlCache } from '../src/lib/cache.js';

describe('Phase 10 — Frontend Clean-Up & Live Backend Wiring', () => {
  let server: http.Server;

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
                geometry: { type: 'Point', coordinates: [77.2197, 28.6328] },
                properties: {
                  osm_id: 1001,
                  osm_type: 'N',
                  name: 'Rajiv Chowk Metro Station',
                  city: 'New Delhi',
                  state: 'Delhi',
                  countrycode: 'IN',
                  osm_key: 'railway',
                  osm_value: 'subway_entrance'
                }
              }
            ]
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      if (url.includes('nominatim.openstreetmap.org/reverse')) {
        return new Response(
          JSON.stringify({
            place_id: 2002,
            lat: '28.6328',
            lon: '77.2197',
            name: 'Connaught Place',
            display_name: 'Connaught Place, New Delhi, Delhi, India',
            category: 'place',
            type: 'neighbourhood',
            address: {
              suburb: 'Connaught Place',
              city: 'New Delhi',
              state: 'Delhi',
              country_code: 'in'
            }
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
        NOMINATIM_USER_AGENT: 'CommuteBuddy/1.0 (test@commutebuddy.in)'
      }),
      cache: new TtlCache(),
      fetchFn: mockFetch
    });

    server = http.createServer(app);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
    const port = (server.address() as { port: number }).port;
    setBackendBaseUrl(`http://127.0.0.1:${port}`);
  });

  after(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it('formats durations in seconds across <60m, 1h..24h, and >=24h ranges', () => {
    assert.equal(formatDuration(240), '4 min');
    assert.equal(formatDuration(3540), '59 min');
    assert.equal(formatDuration(3600), '1 hr');
    assert.equal(formatDuration(4260), '1 hr 11 min');
    assert.equal(formatDuration(86400), '1 d');
    assert.equal(formatDuration(93600), '1 d 2 hr');
  });

  it('removes hardcoded DELHI_PLACES and queries live /api/places and /api/places/reverse', async () => {
    assert.equal(DELHI_PLACES.length, 0);

    const places = await searchPlaces('Rajiv Chowk', 5);
    assert.ok(places.length > 0);
    assert.ok(places[0].name.includes('Rajiv Chowk'));
    assert.equal(places[0].category, 'metro_station');

    const rev = await reverseGeocodePlace(28.6328, 77.2197);
    assert.ok(rev !== null);
    assert.ok(rev.name.length > 0);
  });

  it('fetches live Mix journeys and single-mode Metro routes with durationSeconds and verified fare strings', async () => {
    const start = {
      id: 'stn_in_del_millennium_city_centre_metro',
      name: 'Millennium City Centre Gurugram',
      area: 'Sector 29, Gurugram',
      category: 'Metro Station',
      lat: 28.4595,
      lng: 77.0726,
      stationId: 'stn_in_del_millennium_city_centre_metro'
    };
    const end = {
      id: 'stn_in_del_hauz_khas_metro',
      name: 'Hauz Khas Metro Station',
      area: 'South Delhi',
      category: 'Metro Station',
      lat: 28.5433,
      lng: 77.2066,
      stationId: 'stn_in_del_hauz_khas_metro'
    };

    const mixRoutes = await getRoutes(start, end, 'Mix');
    assert.ok(mixRoutes.length > 0);
    assert.ok(mixRoutes[0].durationSeconds > 60);
    assert.match(mixRoutes[0].costDisplay, /₹|Fare unavailable/);

    const metroRoutes = await getRoutes(start, end, 'Metro');
    assert.ok(metroRoutes.length > 0);
    assert.ok(metroRoutes[0].durationSeconds > 60);
    assert.ok(metroRoutes[0].costInr > 0);
    assert.match(metroRoutes[0].costDisplay, /^₹\d+/);

    const chatReply = await sendMessage('When should I leave?', {
      start,
      end,
      vehicle: 'Mix',
      selectedRoute: mixRoutes[0]
    });
    assert.ok(chatReply.length > 10);
  });
});
