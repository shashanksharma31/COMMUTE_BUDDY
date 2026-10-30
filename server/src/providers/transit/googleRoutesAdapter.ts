import { AppEnv } from '../../env.js';
import { FareLookupResult } from '../../ingestion/fareStore.js';
import { TtlCache } from '../../lib/cache.js';
import { SourceResilienceManager } from '../../lib/resilience.js';
import { createEnvelope } from '../../types/envelope.js';
import {
  RouteCoordinateInput,
  SingleModeRouteOption,
  SingleModeRoutingResponse,
  TransitProviderAdapter
} from './types.js';

interface GoogleComputeRoutesResponse {
  routes?: Array<{
    duration?: string; // e.g. "2160s"
    distanceMeters?: number;
    legs?: Array<{
      steps?: Array<{
        staticDuration?: string;
        distanceMeters?: number;
        transitDetails?: {
          stopDetails?: {
            departureStop?: { name?: string };
            arrivalStop?: { name?: string };
          };
          transitLine?: {
            nameShort?: string;
            name?: string;
            vehicle?: { type?: string };
          };
        };
      }>;
    }>;
  }>;
}

function parseGoogleSeconds(raw?: string): number {
  if (!raw) return 0;
  const num = Number(raw.replace('s', ''));
  return Number.isFinite(num) && num > 0 ? Math.round(num) : 0;
}

// Google Routes API transit adapter:
// - Used as comparison and fallback for metro/RRTS (never as a fare source for Indian metro)
// - Used for bus routing where Google Routes has transit data; otherwise returns "no verified bus data for this area"
export class GoogleRoutesTransitAdapter implements TransitProviderAdapter {
  public readonly providerName = 'google_routes';

  constructor(
    private readonly appEnv: AppEnv,
    private readonly resilience: SourceResilienceManager,
    private readonly cache: TtlCache,
    private readonly fetchFn: typeof fetch = fetch
  ) {}

  async planTransit(params: {
    start: RouteCoordinateInput;
    end: RouteCoordinateInput;
    mode: 'metro' | 'rrts' | 'bus';
    departureTime?: string;
    reachByTime?: string;
  }): Promise<SingleModeRoutingResponse> {
    const apiKey = this.appEnv.GOOGLE_ROUTES_API_KEY.trim();
    if (!apiKey) {
      return {
        status: 'no_data',
        message:
          params.mode === 'bus'
            ? 'no verified bus data for this area'
            : 'no transit data for this area',
        mode: params.mode,
        routes: []
      };
    }

    const allowedVehicles =
      params.mode === 'bus' ? ['BUS'] : params.mode === 'metro' ? ['SUBWAY'] : ['COMMUTER_TRAIN', 'RAIL'];

    const cacheKey = `groutes:${params.mode}:${params.start.lat.toFixed(4)},${params.start.lng.toFixed(4)}->${params.end.lat.toFixed(4)},${params.end.lng.toFixed(4)}`;
    const cached = this.cache.get<SingleModeRoutingResponse>(cacheKey);
    if (cached) return cached;

    const data = await this.resilience.execute<GoogleComputeRoutesResponse>(
      'google_routes',
      async (signal) => {
        const res = await this.fetchFn(
          'https://routes.googleapis.com/directions/v2:computeRoutes',
          {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-Goog-Api-Key': apiKey,
              'X-Goog-FieldMask':
                'routes.duration,routes.distanceMeters,routes.legs.steps.staticDuration,routes.legs.steps.distanceMeters,routes.legs.steps.transitDetails'
            },
            body: JSON.stringify({
              origin: {
                location: {
                  latLng: { latitude: params.start.lat, longitude: params.start.lng }
                }
              },
              destination: {
                location: {
                  latLng: { latitude: params.end.lat, longitude: params.end.lng }
                }
              },
              travelMode: 'TRANSIT',
              transitPreferences: {
                allowedTravelModes: allowedVehicles
              }
            }),
            signal
          }
        );
        if (!res.ok) {
          throw new Error(`Google Routes HTTP ${res.status}`);
        }
        return (await res.json()) as GoogleComputeRoutesResponse;
      }
    );

    const gRoute = data.routes?.[0];
    const durationSeconds = parseGoogleSeconds(gRoute?.duration);

    if (!gRoute || durationSeconds <= 0) {
      return {
        status: 'no_data',
        message:
          params.mode === 'bus'
            ? 'no verified bus data for this area'
            : 'no transit data for this area',
        mode: params.mode,
        routes: []
      };
    }

    const weight = this.resilience.getEffectiveWeight('google_routes');
    const durationEnv = createEnvelope<number>({
      value: durationSeconds,
      status: 'scheduled',
      sources: ['google_routes'],
      primarySourceWeight: weight
    });

    // Google Routes is NEVER used as a fare source for Indian metro/bus; fare comes from Phase 8 operator tables
    const fareEnv = createEnvelope<FareLookupResult>({
      value: {
        fareInr: null,
        currency: 'INR',
        matchedBy: 'unavailable',
        message: 'fare unavailable',
        operatorCode: 'GOOGLE_ROUTES_FALLBACK',
        operatorDeepLink: 'https://maps.google.com',
        source_url: null,
        effective_from: null,
        isStaleOver90Days: false
      },
      status: 'unavailable',
      sources: ['google_routes'],
      primarySourceWeight: 0
    });

    const distanceMeters = gRoute.distanceMeters ?? 1000;
    const rawSteps = gRoute.legs?.[0]?.steps ?? [];
    const steps =
      rawSteps.length > 0
        ? rawSteps.map((s) => {
            const line =
              s.transitDetails?.transitLine?.nameShort ??
              s.transitDetails?.transitLine?.name ??
              params.mode.toUpperCase();
            const dep = s.transitDetails?.stopDetails?.departureStop?.name ?? 'Origin';
            const arr = s.transitDetails?.stopDetails?.arrivalStop?.name ?? 'Destination';
            return {
              mode: params.mode,
              instruction: `${line}: ${dep} to ${arr}`,
              durationSeconds: Math.max(60, parseGoogleSeconds(s.staticDuration)),
              distanceMeters: s.distanceMeters ?? 500,
              lineName: line
            };
          })
        : [
            {
              mode: params.mode,
              instruction: `${params.mode.toUpperCase()} service (Google Routes fallback)`,
              durationSeconds,
              distanceMeters
            }
          ];

    const option: SingleModeRouteOption = {
      id: `google_routes_${params.mode}_fallback`,
      mode: params.mode,
      label: 'Fastest',
      durationSeconds: durationEnv,
      distanceMeters,
      transfers: Math.max(0, steps.length - 1),
      walkMeters: 250,
      fare: fareEnv,
      steps,
      path: [
        [params.start.lat, params.start.lng],
        [params.end.lat, params.end.lng]
      ],
      providerLabel: 'GOOGLE_ROUTES_FALLBACK'
    };

    const response: SingleModeRoutingResponse = {
      status: 'ok',
      message: 'ok (Google Routes fallback)',
      mode: params.mode,
      routes: [option]
    };

    this.cache.set(cacheKey, response, 900);
    return response;
  }
}
