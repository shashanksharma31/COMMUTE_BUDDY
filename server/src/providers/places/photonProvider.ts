import { AppEnv, requireEnvKey } from '../../env.js';
import { TtlCache } from '../../lib/cache.js';
import { SourceResilienceManager } from '../../lib/resilience.js';
import { createEnvelope, DataEnvelope } from '../../types/envelope.js';
import { PlaceCategory, PlaceSuggestion, StationHint } from './types.js';

// India bounding box: [minLon, minLat, maxLon, maxLat]
const INDIA_BBOX = '68.1,6.5,97.4,37.1';
const CACHE_TTL_SECONDS = 3600;

interface PhotonFeature {
  geometry?: {
    coordinates?: [number, number];
  };
  properties?: {
    osm_id?: number;
    osm_type?: 'N' | 'W' | 'R';
    osm_key?: string;
    osm_value?: string;
    name?: string;
    street?: string;
    district?: string;
    city?: string;
    county?: string;
    state?: string;
    postcode?: string;
    countrycode?: string;
  };
}

interface PhotonResponse {
  features?: PhotonFeature[];
}

export function inferPlaceTypeAndStationHint(
  props: NonNullable<PhotonFeature['properties']>
): { placeType: PlaceCategory; stationHint?: StationHint } {
  const name = (props.name ?? '').trim();
  const lowerName = name.toLowerCase();
  const osmKey = (props.osm_key ?? '').toLowerCase();
  const osmValue = (props.osm_value ?? '').toLowerCase();

  // RRTS / Namo Bharat detection
  if (
    lowerName.includes('rrts') ||
    lowerName.includes('namo bharat')
  ) {
    return {
      placeType: 'rrts_station',
      stationHint: {
        isStation: true,
        stationMode: 'rrts',
        osmType: props.osm_type,
        osmId: props.osm_id,
        rawName: name,
        city: props.city ?? props.district,
        state: props.state
      }
    };
  }

  // Metro station detection
  if (
    osmValue === 'subway_entrance' ||
    osmValue === 'subway' ||
    lowerName.includes('metro station') ||
    lowerName.endsWith(' metro')
  ) {
    return {
      placeType: 'metro_station',
      stationHint: {
        isStation: true,
        stationMode: 'metro',
        osmType: props.osm_type,
        osmId: props.osm_id,
        rawName: name,
        city: props.city ?? props.district,
        state: props.state
      }
    };
  }

  // Railway station detection
  if (
    (osmKey === 'railway' && (osmValue === 'station' || osmValue === 'halt')) ||
    lowerName.includes('railway station') ||
    lowerName.includes('junction') ||
    lowerName.endsWith(' jn') ||
    lowerName.includes('terminus') ||
    lowerName.includes('terminal')
  ) {
    return {
      placeType: 'railway_station',
      stationHint: {
        isStation: true,
        stationMode: 'rail',
        osmType: props.osm_type,
        osmId: props.osm_id,
        rawName: name,
        city: props.city ?? props.district,
        state: props.state
      }
    };
  }

  // Bus stop / station
  if (
    osmValue === 'bus_station' ||
    osmValue === 'bus_stop' ||
    lowerName.includes('isbt') ||
    lowerName.includes('bus stand')
  ) {
    return {
      placeType: 'bus_stop',
      stationHint: {
        isStation: true,
        stationMode: 'bus',
        osmType: props.osm_type,
        osmId: props.osm_id,
        rawName: name,
        city: props.city ?? props.district,
        state: props.state
      }
    };
  }

  if (osmKey === 'aeroway' || lowerName.includes('airport')) {
    return { placeType: 'airport' };
  }

  if (osmKey === 'place') {
    return { placeType: 'locality' };
  }

  if (osmKey === 'highway' || osmKey === 'building') {
    return { placeType: 'address' };
  }

  return { placeType: 'poi' };
}

function formatPhotonAddress(props: NonNullable<PhotonFeature['properties']>): string {
  const parts = [
    props.street,
    props.district,
    props.city ?? props.county,
    props.state,
    props.postcode
  ]
    .map((p) => (p ?? '').trim())
    .filter((p) => p.length > 0 && p.toLowerCase() !== (props.name ?? '').trim().toLowerCase());

  return Array.from(new Set(parts)).join(', ') || 'India';
}

export class PhotonPlacesProvider {
  constructor(
    private readonly appEnv: AppEnv,
    private readonly resilience: SourceResilienceManager,
    private readonly cache: TtlCache,
    private readonly fetchFn: typeof fetch = fetch
  ) {}

  async searchIndiaPlaces(
    query: string,
    options?: { lat?: number; lng?: number; maxResults?: number }
  ): Promise<DataEnvelope<PlaceSuggestion[]>> {
    const trimmed = query.trim();
    const maxResults = Math.min(5, Math.max(1, options?.maxResults ?? 5));
    const userAgent = requireEnvKey(
      this.appEnv,
      'NOMINATIM_USER_AGENT',
      'Photon search-as-you-type geocoding (User-Agent required)'
    );

    const cacheKey = `photon:in:${trimmed.toLowerCase()}:${maxResults}:${options?.lat ?? ''}:${options?.lng ?? ''}`;
    const cached = this.cache.get<DataEnvelope<PlaceSuggestion[]>>(cacheKey);
    if (cached) {
      return cached;
    }

    const url = new URL('/api/', this.appEnv.PHOTON_BASE_URL);
    url.searchParams.set('q', trimmed);
    url.searchParams.set('limit', '15');
    url.searchParams.set('bbox', INDIA_BBOX);
    url.searchParams.set('lang', 'en');
    if (options?.lat !== undefined && options?.lng !== undefined) {
      url.searchParams.set('lat', String(options.lat));
      url.searchParams.set('lon', String(options.lng));
    }

    const data = await this.resilience.execute<PhotonResponse>(
      'photon_geocoder',
      async (signal) => {
        const res = await this.fetchFn(url.toString(), {
          method: 'GET',
          headers: {
            'User-Agent': userAgent,
            Accept: 'application/json'
          },
          signal
        });
        if (!res.ok) {
          throw new Error(`Photon HTTP ${res.status}`);
        }
        return (await res.json()) as PhotonResponse;
      }
    );

    const features = data.features ?? [];
    const results: PlaceSuggestion[] = [];

    for (const feat of features) {
      const props = feat.properties;
      const coords = feat.geometry?.coordinates;
      if (!props || !coords || coords.length < 2) continue;

      const country = (props.countrycode ?? '').toUpperCase();
      if (country !== 'IN') continue;

      const lng = Number(coords[0]);
      const lat = Number(coords[1]);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;

      const name = (props.name ?? props.street ?? props.city ?? '').trim();
      if (!name) continue;

      const { placeType, stationHint } = inferPlaceTypeAndStationHint(props);
      const id = `osm_${props.osm_type ?? 'N'}_${props.osm_id ?? `${lat}_${lng}`}`;

      if (results.some((r) => r.id === id)) continue;

      results.push({
        id,
        name,
        fullAddress: formatPhotonAddress(props),
        lat,
        lng,
        placeType,
        countryCode: 'IN',
        ...(stationHint ? { stationHint } : {})
      });

      if (results.length >= maxResults) break;
    }

    const weight = this.resilience.getEffectiveWeight('photon_geocoder');
    const envelope = createEnvelope<PlaceSuggestion[]>({
      value: results,
      status: 'live',
      sources: ['photon_geocoder'],
      primarySourceWeight: weight,
      halfLifeSeconds: CACHE_TTL_SECONDS
    });

    this.cache.set(cacheKey, envelope, CACHE_TTL_SECONDS);
    return envelope;
  }
}
