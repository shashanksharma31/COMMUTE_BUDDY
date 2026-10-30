import { DelhiPlace, getBackendBaseUrl } from '../data/places.js';
import { formatVerifiedFareDisplay, mapEnvelopeStatusToDataLabel } from './fares.js';
import { CrowdLevel, Route, RouteLabel, RouteStep, VehicleMode } from './types.js';

function mapModeString(mode: string): VehicleMode {
  const m = mode.toLowerCase();
  if (m === 'metro') return 'Metro';
  if (m === 'bus') return 'Bus';
  if (m === 'train' || m === 'rrts') return 'Train';
  if (m === 'car') return 'Car';
  if (m === 'bike' || m === 'auto' || m === 'e_rickshaw') return 'Bike';
  if (m === 'walk') return 'Walk';
  return 'Mix';
}

function mapCrowdLevel(raw?: string): CrowdLevel {
  if (!raw) return 'Medium';
  const norm = raw.toLowerCase();
  if (norm === 'high') return 'High';
  if (norm === 'low') return 'Low';
  return 'Medium';
}

function mapLabel(raw?: string): RouteLabel {
  if (raw === 'Least walking' || raw === 'Least Walking') return 'Least walking';
  if (raw === 'Cheapest') return 'Cheapest';
  return 'Fastest';
}

export async function getRoutes(
  start: DelhiPlace,
  end: DelhiPlace,
  vehicle: VehicleMode,
  reachBy?: string
): Promise<Route[]> {
  const baseUrl = getBackendBaseUrl();

  if (vehicle === 'Mix') {
    const res = await fetch(`${baseUrl}/api/journeys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({
        start: {
          lat: start.lat,
          lng: start.lng,
          name: start.name,
          stationId: start.stationId
        },
        end: {
          lat: end.lat,
          lng: end.lng,
          name: end.name,
          stationId: end.stationId
        },
        reachByTime: reachBy
      })
    });

    if (!res.ok) {
      throw new Error(`Journey API error: HTTP ${res.status}`);
    }

    const payload = (await res.json()) as {
      status?: string;
      journeys?: Array<{
        id: string;
        label: string;
        totalDurationSeconds: { value: number | null; status: string };
        totalFare: {
          value: { amountInr: number | null; isEstimate: boolean } | null;
          status: string;
        };
        totalDistanceMeters: number;
        transfers: number;
        totalWalkMeters: number;
        lowestConfidence: number;
        overallStatus: string;
        legs: Array<{
          legId: string;
          mode: string;
          instruction: string;
          durationSeconds: { value: number | null };
          path: [number, number][];
        }>;
      }>;
    };

    if (payload.status === 'no_data' || !Array.isArray(payload.journeys)) {
      return [];
    }

    return payload.journeys.map((j) => {
      const durationSec = Math.max(60, Math.round(j.totalDurationSeconds?.value ?? 60));
      const fareFormatted = formatVerifiedFareDisplay({
        fareInr: j.totalFare?.value?.amountInr ?? null,
        isEstimate: Boolean(j.totalFare?.value?.isEstimate),
        status: j.totalFare?.status ?? 'unavailable'
      });

      const steps: RouteStep[] = (j.legs ?? []).map((leg) => {
        const legSec = Math.max(60, Math.round(leg.durationSeconds?.value ?? 60));
        return {
          mode: mapModeString(leg.mode),
          text: leg.instruction,
          durationSeconds: legSec,
          minutes: Math.max(1, Math.round(legSec / 60))
        };
      });

      const mergedPath: [number, number][] = [];
      for (const leg of j.legs ?? []) {
        for (const pt of leg.path ?? []) {
          mergedPath.push(pt);
        }
      }

      return {
        id: j.id,
        label: mapLabel(j.label),
        durationSeconds: durationSec,
        minutes: Math.max(1, Math.round(durationSec / 60)),
        costInr: fareFormatted.costInr,
        costDisplay: fareFormatted.costDisplay,
        isEstimate: fareFormatted.isEstimate,
        distanceKm: Math.round(((j.totalDistanceMeters ?? 1000) / 1000) * 10) / 10,
        transfers: j.transfers ?? 0,
        walkMeters: j.totalWalkMeters ?? 0,
        crowd: 'Medium',
        dataLabel: mapEnvelopeStatusToDataLabel(j.overallStatus),
        reliability: Math.max(1, Math.min(10, Math.round((j.lowestConfidence ?? 0.85) * 10))),
        steps,
        path:
          mergedPath.length >= 2
            ? mergedPath
            : [
                [start.lat, start.lng],
                [end.lat, end.lng]
              ],
        rawJourneyPayload: j
      };
    });
  }

  const res = await fetch(`${baseUrl}/api/routes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({
      start: {
        lat: start.lat,
        lng: start.lng,
        name: start.name,
        stationId: start.stationId
      },
      end: {
        lat: end.lat,
        lng: end.lng,
        name: end.name,
        stationId: end.stationId
      },
      mode: vehicle.toLowerCase(),
      reachByTime: reachBy
    })
  });

  if (!res.ok) {
    throw new Error(`Routes API error: HTTP ${res.status}`);
  }

  const payload = (await res.json()) as {
    status?: string;
    routes?: Array<{
      id: string;
      label: string;
      durationSeconds: { value: number | null; status: string };
      distanceMeters: number;
      fare: {
        value: { fareInr: number | null; isEstimate?: boolean } | null;
        status: string;
      };
      transfers: number;
      walkMeters: number;
      crowdLevel?: string;
      reliabilityScore?: number;
      steps: Array<{
        mode: string;
        instruction: string;
        durationSeconds: { value: number | null } | number;
      }>;
      path: [number, number][];
    }>;
  };

  if (payload.status === 'no_data' || !Array.isArray(payload.routes)) {
    return [];
  }

  return payload.routes.map((r) => {
    const durationSec = Math.max(60, Math.round(r.durationSeconds?.value ?? 60));
    const fareStatus = r.fare?.status ?? 'unavailable';
    const fareFormatted = formatVerifiedFareDisplay({
      fareInr: r.fare?.value?.fareInr ?? null,
      isEstimate: Boolean(r.fare?.value?.isEstimate) || fareStatus === 'estimate',
      status: fareStatus
    });

    const steps: RouteStep[] = (r.steps ?? []).map((step) => {
      const rawStepSec =
        typeof step.durationSeconds === 'number'
          ? step.durationSeconds
          : (step.durationSeconds?.value ?? 60);
      const stepSec = Math.max(60, Math.round(rawStepSec));
      return {
        mode: mapModeString(step.mode),
        text: step.instruction,
        durationSeconds: stepSec,
        minutes: Math.max(1, Math.round(stepSec / 60))
      };
    });

    return {
      id: r.id,
      label: mapLabel(r.label),
      durationSeconds: durationSec,
      minutes: Math.max(1, Math.round(durationSec / 60)),
      costInr: fareFormatted.costInr,
      costDisplay: fareFormatted.costDisplay,
      isEstimate: fareFormatted.isEstimate,
      distanceKm: Math.round(((r.distanceMeters ?? 1000) / 1000) * 10) / 10,
      transfers: r.transfers ?? 0,
      walkMeters: r.walkMeters ?? 0,
      crowd: mapCrowdLevel(r.crowdLevel),
      dataLabel: mapEnvelopeStatusToDataLabel(r.durationSeconds?.status),
      reliability: r.reliabilityScore ?? 8,
      steps,
      path:
        Array.isArray(r.path) && r.path.length >= 2
          ? r.path
          : [
              [start.lat, start.lng],
              [end.lat, end.lng]
            ]
    };
  });
}
