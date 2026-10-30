import { AppEnv, requireEnvKey } from '../../env.js';
import { TtlCache } from '../../lib/cache.js';
import { SourceResilienceManager } from '../../lib/resilience.js';
import { createEnvelope, DataEnvelope } from '../../types/envelope.js';
import { RouteCoordinateInput } from '../transit/types.js';

export interface LiveTrafficResult {
  provider: 'tomtom' | 'here';
  liveTrafficDurationSeconds: DataEnvelope<number>;
  noTrafficDurationSeconds: DataEnvelope<number>;
  trafficDelaySeconds: number;
  trafficSurgeMultiplier: number;
}

interface TomTomRouteResponse {
  routes?: Array<{
    summary?: {
      travelTimeInSeconds?: number;
      noTrafficTravelTimeInSeconds?: number;
      trafficDelayInSeconds?: number;
    };
  }>;
}

interface HereRouteResponse {
  routes?: Array<{
    sections?: Array<{
      summary?: {
        duration?: number;
        baseDuration?: number;
      };
    }>;
  }>;
}

// Fetches live-traffic car duration alongside no-traffic baseline duration from TomTom or HERE.
export class LiveTrafficProvider {
  constructor(
    private readonly appEnv: AppEnv,
    private readonly resilience: SourceResilienceManager,
    private readonly cache: TtlCache,
    private readonly fetchFn: typeof fetch = fetch
  ) {}

  isConfigured(): boolean {
    if (this.appEnv.TRAFFIC_PROVIDER === 'here') {
      return this.appEnv.HERE_API_KEY.trim().length > 0;
    }
    return this.appEnv.TOMTOM_API_KEY.trim().length > 0;
  }

  async fetchCarTrafficDurations(params: {
    start: RouteCoordinateInput;
    end: RouteCoordinateInput;
  }): Promise<LiveTrafficResult> {
    const provider = this.appEnv.TRAFFIC_PROVIDER;
    const cacheKey = `traffic:${provider}:${params.start.lat.toFixed(4)},${params.start.lng.toFixed(4)}->${params.end.lat.toFixed(4)},${params.end.lng.toFixed(4)}`;
    const cached = this.cache.get<LiveTrafficResult>(cacheKey);
    if (cached) {
      return cached;
    }

    const result =
      provider === 'here'
        ? await this.fetchHereTraffic(params.start, params.end)
        : await this.fetchTomTomTraffic(params.start, params.end);

    // Cache live traffic for 120 seconds
    this.cache.set(cacheKey, result, 120);
    return result;
  }

  private async fetchTomTomTraffic(
    start: RouteCoordinateInput,
    end: RouteCoordinateInput
  ): Promise<LiveTrafficResult> {
    const apiKey = requireEnvKey(
      this.appEnv,
      'TOMTOM_API_KEY',
      'TomTom live traffic routing'
    );

    const url = `https://api.tomtom.com/routing/1/calculateRoute/${start.lat},${start.lng}:${end.lat},${end.lng}/json?traffic=true&computeTravelTimeFor=all&key=${encodeURIComponent(apiKey)}`;

    const data = await this.resilience.execute<TomTomRouteResponse>(
      'tomtom_traffic',
      async (signal) => {
        const res = await this.fetchFn(url, { signal });
        if (!res.ok) {
          throw new Error(`TomTom Traffic API HTTP ${res.status}`);
        }
        return (await res.json()) as TomTomRouteResponse;
      }
    );

    const summary = data.routes?.[0]?.summary;
    if (
      !summary ||
      summary.travelTimeInSeconds === undefined ||
      summary.noTrafficTravelTimeInSeconds === undefined
    ) {
      throw new Error('TomTom Traffic API returned incomplete summary');
    }

    const liveSec = Math.max(1, Math.round(summary.travelTimeInSeconds));
    const baseSec = Math.max(1, Math.round(summary.noTrafficTravelTimeInSeconds));
    const delaySec = Math.max(
      0,
      Math.round(summary.trafficDelayInSeconds ?? liveSec - baseSec)
    );
    const weight = this.resilience.getEffectiveWeight('tomtom_traffic');

    return this.buildTrafficResult('tomtom', 'tomtom_traffic', liveSec, baseSec, delaySec, weight);
  }

  private async fetchHereTraffic(
    start: RouteCoordinateInput,
    end: RouteCoordinateInput
  ): Promise<LiveTrafficResult> {
    const apiKey = requireEnvKey(
      this.appEnv,
      'HERE_API_KEY',
      'HERE live traffic routing'
    );

    const url = `https://router.hereapi.com/v8/routes?transportMode=car&origin=${start.lat},${start.lng}&destination=${end.lat},${end.lng}&return=summary&apiKey=${encodeURIComponent(apiKey)}`;

    const data = await this.resilience.execute<HereRouteResponse>(
      'here_traffic',
      async (signal) => {
        const res = await this.fetchFn(url, { signal });
        if (!res.ok) {
          throw new Error(`HERE Traffic API HTTP ${res.status}`);
        }
        return (await res.json()) as HereRouteResponse;
      }
    );

    const summary = data.routes?.[0]?.sections?.[0]?.summary;
    if (!summary || summary.duration === undefined || summary.baseDuration === undefined) {
      throw new Error('HERE Traffic API returned incomplete summary');
    }

    const liveSec = Math.max(1, Math.round(summary.duration));
    const baseSec = Math.max(1, Math.round(summary.baseDuration));
    const delaySec = Math.max(0, liveSec - baseSec);
    const weight = this.resilience.getEffectiveWeight('here_traffic');

    return this.buildTrafficResult('here', 'here_traffic', liveSec, baseSec, delaySec, weight);
  }

  private buildTrafficResult(
    provider: 'tomtom' | 'here',
    sourceKey: string,
    liveSec: number,
    baseSec: number,
    delaySec: number,
    weight: number
  ): LiveTrafficResult {
    // Surge multiplier is bounded to [1.0, 1.5] proportional to congestion ratio
    const ratio = liveSec / Math.max(1, baseSec);
    const surgeMultiplier =
      ratio <= 1.1
        ? 1.0
        : Math.min(1.5, Math.round((1.0 + (ratio - 1.1) * 0.5) * 100) / 100);

    return {
      provider,
      liveTrafficDurationSeconds: createEnvelope<number>({
        value: liveSec,
        status: 'live',
        sources: [sourceKey],
        primarySourceWeight: weight,
        halfLifeSeconds: 300
      }),
      noTrafficDurationSeconds: createEnvelope<number>({
        value: baseSec,
        status: 'scheduled',
        sources: [sourceKey],
        primarySourceWeight: weight,
        halfLifeSeconds: 86400
      }),
      trafficDelaySeconds: delaySec,
      trafficSurgeMultiplier: surgeMultiplier
    };
  }
}
