export type VehicleMode = 'Mix' | 'Metro' | 'Bus' | 'Train' | 'Car' | 'Bike' | 'Walk';

export type RouteLabel = 'Fastest' | 'Cheapest' | 'Least walking';

export type CrowdLevel = 'Low' | 'Medium' | 'High';

export type DataLabel =
  | 'LIVE'
  | 'TIMETABLE'
  | 'ESTIMATED'
  | 'STALE'
  | 'UNVERIFIED'
  | 'UNAVAILABLE';

export interface RouteStep {
  mode: VehicleMode;
  text: string;
  durationSeconds: number;
  minutes: number;
}

export interface Route {
  id: string;
  label: RouteLabel;
  durationSeconds: number;
  minutes: number;
  costInr: number;
  costDisplay: string;
  isEstimate: boolean;
  distanceKm: number;
  transfers: number;
  walkMeters: number;
  crowd: CrowdLevel;
  dataLabel: DataLabel;
  reliability: number; // 0-10 derived from verified envelope confidence
  steps: RouteStep[];
  path: [number, number][]; // [lat, lng][]
  rawJourneyPayload?: Record<string, unknown>;
}
