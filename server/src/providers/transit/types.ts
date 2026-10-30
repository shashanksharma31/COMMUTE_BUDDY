import { DataEnvelope } from '../../types/envelope.js';
import { FareLookupResult } from '../../ingestion/fareStore.js';
import { RouteVerificationReport } from '../../verification/verificationEngine.js';
import { VerifiedTrainLiveStatus } from '../train/licensedTrainAdapter.js';

export type SingleRoutingMode =
  | 'car'
  | 'bike'
  | 'walk'
  | 'metro'
  | 'rrts'
  | 'train'
  | 'bus';

export interface RouteCoordinateInput {
  name?: string;
  lat: number;
  lng: number;
  stationId?: string;
}

export interface RouteStepSeconds {
  mode: SingleRoutingMode;
  instruction: string;
  durationSeconds: number;
  distanceMeters: number;
  fromStationId?: string;
  toStationId?: string;
  routeId?: string;
  lineName?: string;
  trainNumber?: string;
}

export interface SingleModeRouteOption {
  id: string;
  mode: SingleRoutingMode;
  label: 'Fastest' | 'Cheapest' | 'Least walking';
  durationSeconds: DataEnvelope<number>;
  noTrafficDurationSeconds?: DataEnvelope<number>;
  trafficDelaySeconds?: number;
  distanceMeters: number;
  transfers: number;
  walkMeters: number;
  fare: DataEnvelope<FareLookupResult>;
  steps: RouteStepSeconds[];
  path: Array<[number, number]>; // [lat, lng][]
  providerLabel: 'GTFS_GRAPH' | 'RRTS_YAML' | 'IR_STATIC' | 'ORS_ROAD' | 'GOOGLE_ROUTES_FALLBACK';
  verification?: RouteVerificationReport;
  trainLiveStatus?: VerifiedTrainLiveStatus;
}

export interface SingleModeRoutingResponse {
  status: 'ok' | 'no_data' | 'ambiguous_station' | 'error';
  message: string;
  mode: SingleRoutingMode;
  routes: SingleModeRouteOption[];
}

// Swappable transit provider adapter interface controlled by TRANSIT_PROVIDER env var.
export interface TransitProviderAdapter {
  readonly providerName: string;
  planTransit(params: {
    start: RouteCoordinateInput;
    end: RouteCoordinateInput;
    mode: 'metro' | 'rrts' | 'bus';
    departureTime?: string;
    reachByTime?: string;
  }): Promise<SingleModeRoutingResponse>;
}
