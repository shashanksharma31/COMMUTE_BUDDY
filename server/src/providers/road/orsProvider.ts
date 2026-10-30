import { AppEnv, requireEnvKey } from '../../env.js';
import { TtlCache } from '../../lib/cache.js';
import { SourceResilienceManager } from '../../lib/resilience.js';
import { createEnvelope } from '../../types/envelope.js';
import { FareLookupResult } from '../../ingestion/fareStore.js';
import { rateCardCalculator } from '../../journeys/rateCardCalculator.js';
import {
  RouteCoordinateInput,
  RouteStepSeconds,
  SingleModeRouteOption,
  SingleModeRoutingResponse
} from '../transit/types.js';

type OrsProfile = 'driving-car' | 'cycling-regular' | 'foot-walking';

const MODE_TO_ORS_PROFILE: Record<'car' | 'bike' | 'walk', OrsProfile> = {
  car: 'driving-car',
  bike: 'cycling-regular',
  walk: 'foot-walking'
};

interface OrsFeatureCollection {
  features?: Array<{
    geometry?: {
      coordinates?: Array<[number, number]>; // [lng, lat]
    };
    properties?: {
      summary?: {
        distance?: number; // meters
        duration?: number; // seconds
      };
      segments?: Array<{
        steps?: Array<{
          distance?: number;
          duration?: number;
          instruction?: string;
        }>;
      }>;
    };
  }>;
}

export class OpenRouteServiceProvider {
  constructor(
    private readonly appEnv: AppEnv,
    private readonly resilience: SourceResilienceManager,
    private readonly cache: TtlCache,
    private readonly fetchFn: typeof fetch = fetch
  ) {}

  async planRoadRoute(params: {
    start: RouteCoordinateInput;
    end: RouteCoordinateInput;
    mode: 'car' | 'bike' | 'walk';
  }): Promise<SingleModeRoutingResponse> {
    const apiKey = requireEnvKey(
      this.appEnv,
      'ORS_API_KEY',
      `OpenRouteService ${params.mode} routing`
    );

    const profile = MODE_TO_ORS_PROFILE[params.mode];
    const cacheKey = `ors:${profile}:${params.start.lat.toFixed(4)},${params.start.lng.toFixed(4)}->${params.end.lat.toFixed(4)},${params.end.lng.toFixed(4)}`;
    const cached = this.cache.get<SingleModeRoutingResponse>(cacheKey);
    if (cached) {
      return cached;
    }

    const url = new URL(`/v2/directions/${profile}/geojson`, this.appEnv.ORS_BASE_URL);

    const data = await this.resilience.execute<OrsFeatureCollection>(
      'openrouteservice',
      async (signal) => {
        const res = await this.fetchFn(url.toString(), {
          method: 'POST',
          headers: {
            Authorization: apiKey,
            'Content-Type': 'application/json',
            Accept: 'application/geo+json, application/json'
          },
          body: JSON.stringify({
            coordinates: [
              [params.start.lng, params.start.lat],
              [params.end.lng, params.end.lat]
            ]
          }),
          signal
        });
        if (!res.ok) {
          throw new Error(`OpenRouteService HTTP ${res.status}`);
        }
        return (await res.json()) as OrsFeatureCollection;
      }
    );

    const feat = data.features?.[0];
    const summary = feat?.properties?.summary;
    const rawCoords = feat?.geometry?.coordinates ?? [];

    if (!summary || summary.duration === undefined || summary.distance === undefined) {
      return {
        status: 'no_data',
        message: `No verified ${params.mode} route returned by OpenRouteService`,
        mode: params.mode,
        routes: []
      };
    }

    const durationSeconds = Math.max(1, Math.round(summary.duration));
    const distanceMeters = Math.max(1, Math.round(summary.distance));
    const path: Array<[number, number]> = rawCoords.map(([lng, lat]) => [lat, lng]);

    const rawSteps = feat?.properties?.segments?.[0]?.steps ?? [];
    const steps: RouteStepSeconds[] =
      rawSteps.length > 0
        ? rawSteps.map((s) => ({
            mode: params.mode,
            instruction: s.instruction || `Continue via ${params.mode}`,
            durationSeconds: Math.max(1, Math.round(s.duration ?? 0)),
            distanceMeters: Math.max(0, Math.round(s.distance ?? 0))
          }))
        : [
            {
              mode: params.mode,
              instruction: `${params.mode} from ${params.start.name ?? 'origin'} to ${params.end.name ?? 'destination'}`,
              durationSeconds,
              distanceMeters
            }
          ];

    const weight = this.resilience.getEffectiveWeight('openrouteservice');
    const durationEnv = createEnvelope<number>({
      value: durationSeconds,
      status: 'estimate',
      sources: ['openrouteservice'],
      primarySourceWeight: weight
    });

    // Walk is ₹0; car and bike fares are calculated from server/config/rates.json with isEstimate: true
    const fareEnv =
      params.mode === 'walk'
        ? createEnvelope<FareLookupResult>({
            value: {
              fareInr: 0,
              currency: 'INR',
              matchedBy: 'operator_slab',
              message: '₹0',
              operatorCode: 'WALK',
              operatorDeepLink: '',
              source_url: 'https://openrouteservice.org/',
              effective_from: '2025-01-01',
              isStaleOver90Days: false
            },
            status: 'scheduled',
            sources: ['openrouteservice'],
            primarySourceWeight: weight
          })
        : rateCardCalculator.calculateEstimatedFare({
            mode: params.mode,
            distanceMeters,
            durationSeconds
          });

    const option: SingleModeRouteOption = {
      id: `ors_${params.mode}_fastest`,
      mode: params.mode,
      label: 'Fastest',
      durationSeconds: durationEnv,
      distanceMeters,
      transfers: 0,
      walkMeters: params.mode === 'walk' ? distanceMeters : 50,
      fare: fareEnv,
      steps,
      path:
        path.length >= 2
          ? path
          : [
              [params.start.lat, params.start.lng],
              [params.end.lat, params.end.lng]
            ],
      providerLabel: 'ORS_ROAD'
    };

    const response: SingleModeRoutingResponse = {
      status: 'ok',
      message: 'ok',
      mode: params.mode,
      routes: [option]
    };

    this.cache.set(cacheKey, response, 900);
    return response;
  }
}
