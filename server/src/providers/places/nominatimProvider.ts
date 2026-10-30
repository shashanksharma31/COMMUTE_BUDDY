import { AppEnv, requireEnvKey } from '../../env.js';
import { TtlCache } from '../../lib/cache.js';
import { SourceResilienceManager } from '../../lib/resilience.js';
import { createEnvelope, DataEnvelope } from '../../types/envelope.js';
import { inferPlaceTypeAndStationHint } from './photonProvider.js';
import { PlaceSuggestion } from './types.js';

const CACHE_TTL_SECONDS = 3600;

interface NominatimPlaceItem {
  place_id?: number;
  osm_type?: 'node' | 'way' | 'relation';
  osm_id?: number;
  lat?: string;
  lon?: string;
  name?: string;
  display_name?: string;
  category?: string;
  type?: string;
  address?: {
    road?: string;
    suburb?: string;
    city_district?: string;
    city?: string;
    town?: string;
    state_district?: string;
    state?: string;
    postcode?: string;
    country_code?: string;
  };
}

function osmTypeShort(raw?: string): 'N' | 'W' | 'R' | undefined {
  if (raw === 'node') return 'N';
  if (raw === 'way') return 'W';
  if (raw === 'relation') return 'R';
  return undefined;
}

// Nominatim provider for reverse geocoding ("Your location") and exact lookup fallback.
// Enforces a strict >=1000ms interval between requests per the OSMF Nominatim Usage Policy.
export class NominatimProvider {
  private lastRequestAtMs = 0;
  private rateLimitChain: Promise<void> = Promise.resolve();

  constructor(
    private readonly appEnv: AppEnv,
    private readonly resilience: SourceResilienceManager,
    private readonly cache: TtlCache,
    private readonly fetchFn: typeof fetch = fetch,
    private readonly minIntervalMs = 1000
  ) {}

  private async waitForRateSlot(): Promise<void> {
    const next = this.rateLimitChain.then(async () => {
      const now = Date.now();
      const elapsed = now - this.lastRequestAtMs;
      if (elapsed < this.minIntervalMs && this.lastRequestAtMs > 0) {
        await new Promise((r) => setTimeout(r, this.minIntervalMs - elapsed));
      }
      this.lastRequestAtMs = Date.now();
    });
    this.rateLimitChain = next.catch(() => {});
    await next;
  }

  async reverseGeocodeIndia(
    lat: number,
    lng: number
  ): Promise<DataEnvelope<PlaceSuggestion>> {
    const userAgent = requireEnvKey(
      this.appEnv,
      'NOMINATIM_USER_AGENT',
      'Nominatim reverse geocoding (User-Agent required)'
    );

    const roundedLat = Math.round(lat * 10000) / 10000;
    const roundedLng = Math.round(lng * 10000) / 10000;
    const cacheKey = `nominatim:rev:${roundedLat}:${roundedLng}`;
    const cached = this.cache.get<DataEnvelope<PlaceSuggestion>>(cacheKey);
    if (cached) {
      return cached;
    }

    const url = new URL('/reverse', this.appEnv.NOMINATIM_BASE_URL);
    url.searchParams.set('lat', String(lat));
    url.searchParams.set('lon', String(lng));
    url.searchParams.set('format', 'jsonv2');
    url.searchParams.set('addressdetails', '1');
    url.searchParams.set('accept-language', 'en');

    const item = await this.resilience.execute<NominatimPlaceItem>(
      'nominatim_geocoder',
      async (signal) => {
        await this.waitForRateSlot();
        const res = await this.fetchFn(url.toString(), {
          method: 'GET',
          headers: {
            'User-Agent': userAgent,
            Accept: 'application/json'
          },
          signal
        });
        if (!res.ok) {
          throw new Error(`Nominatim reverse HTTP ${res.status}`);
        }
        return (await res.json()) as NominatimPlaceItem;
      }
    );

    const countryCode = (item.address?.country_code ?? '').toUpperCase();
    if (countryCode !== 'IN') {
      return createEnvelope<PlaceSuggestion>({
        value: null,
        status: 'unavailable',
        sources: ['nominatim_geocoder'],
        primarySourceWeight: 0
      });
    }

    const parsedLat = Number(item.lat ?? lat);
    const parsedLng = Number(item.lon ?? lng);
    const displayParts = (item.display_name ?? '').split(',').map((s) => s.trim());
    const name =
      (item.name ?? '').trim() ||
      item.address?.suburb ||
      item.address?.road ||
      displayParts[0] ||
      'Selected location';

    const { placeType, stationHint } = inferPlaceTypeAndStationHint({
      name,
      osm_id: item.osm_id,
      osm_type: osmTypeShort(item.osm_type),
      osm_key: item.category,
      osm_value: item.type,
      city: item.address?.city ?? item.address?.town ?? item.address?.state_district,
      state: item.address?.state
    });

    const suggestion: PlaceSuggestion = {
      id: `nom_${osmTypeShort(item.osm_type) ?? 'N'}_${item.osm_id ?? item.place_id ?? `${roundedLat}_${roundedLng}`}`,
      name,
      fullAddress: item.display_name ?? name,
      lat: parsedLat,
      lng: parsedLng,
      placeType,
      countryCode: 'IN',
      ...(stationHint ? { stationHint } : {})
    };

    const weight = this.resilience.getEffectiveWeight('nominatim_geocoder');
    const envelope = createEnvelope<PlaceSuggestion>({
      value: suggestion,
      status: 'live',
      sources: ['nominatim_geocoder'],
      primarySourceWeight: weight,
      halfLifeSeconds: CACHE_TTL_SECONDS
    });

    this.cache.set(cacheKey, envelope, CACHE_TTL_SECONDS);
    return envelope;
  }
}
