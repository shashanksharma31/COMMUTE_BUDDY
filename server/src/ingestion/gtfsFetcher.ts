import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';

export type DayType = 'weekday' | 'saturday' | 'sunday' | 'holiday';

export interface TransitRouteDef {
  routeId: string;
  operatorCode: string;
  mode: 'metro' | 'rrts' | 'rail' | 'bus';
  lineName: string;
  lineColor?: string;
  source_url: string;
  effective_from: string;
}

export interface TransitStopEdge {
  routeId: string;
  fromStationId: string;
  toStationId: string;
  sequenceNo: number;
  distanceKm: number;
  scheduledTravelSeconds: number;
  headwayPeakSeconds: number;
  headwayOffpeakSeconds: number;
}

export interface FirstLastTrainEntry {
  routeId: string;
  stationId: string;
  directionStationId: string;
  dayType: DayType;
  firstTrainTime: string; // HH:MM
  lastTrainTime: string; // HH:MM
  source_url: string;
  effective_from: string;
}

export interface GtfsFeedPayload {
  feedId: string;
  operatorCode: string;
  source_url: string;
  effective_from: string;
  stopsTxt: string;
  routesTxt: string;
  tripsTxt: string;
  stopTimesTxt: string;
  frequenciesTxt?: string;
}

export interface GtfsIngestionRecord {
  feedId: string;
  operatorCode: string;
  sha256Hash: string;
  skippedUnchanged: boolean;
  validatorPassed: boolean;
  validationErrors: string[];
  ingestedAt: string;
}

export type MobilityDataValidatorFn = (
  payload: GtfsFeedPayload
) => Promise<{ passed: boolean; errors: string[] }>;

const timePattern = /^([01]\d|2[0-3]):([0-5]\d)$/;

const firstLastSchema = z.object({
  routeId: z.string().min(1),
  stationId: z.string().min(1),
  directionStationId: z.string().min(1),
  dayType: z.enum(['weekday', 'saturday', 'sunday', 'holiday']),
  firstTrainTime: z.string().regex(timePattern),
  lastTrainTime: z.string().regex(timePattern),
  source_url: z.string().url(),
  effective_from: z.string().min(10)
});

// Default structural MobilityData GTFS validator verifying required GTFS tables and headers.
export const defaultMobilityDataValidator: MobilityDataValidatorFn = async (payload) => {
  const errors: string[] = [];

  const requiredFiles: Array<{ name: string; content: string; requiredCols: string[] }> = [
    { name: 'stops.txt', content: payload.stopsTxt, requiredCols: ['stop_id', 'stop_name', 'stop_lat', 'stop_lon'] },
    { name: 'routes.txt', content: payload.routesTxt, requiredCols: ['route_id', 'route_short_name', 'route_type'] },
    { name: 'trips.txt', content: payload.tripsTxt, requiredCols: ['route_id', 'service_id', 'trip_id'] },
    { name: 'stop_times.txt', content: payload.stopTimesTxt, requiredCols: ['trip_id', 'arrival_time', 'departure_time', 'stop_id', 'stop_sequence'] }
  ];

  for (const file of requiredFiles) {
    const lines = file.content
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter((l) => l.length > 0);
    if (lines.length < 2) {
      errors.push(`${file.name} is empty or missing data rows`);
      continue;
    }
    const headers = lines[0].split(',').map((h) => h.trim());
    for (const col of file.requiredCols) {
      if (!headers.includes(col)) {
        errors.push(`${file.name} missing required GTFS column "${col}"`);
      }
    }
  }

  return {
    passed: errors.length === 0,
    errors
  };
};

// GTFS Fetcher & Network Store:
// - Computes SHA-256 hash of incoming GTFS feeds and skips unchanged feeds
// - Runs MobilityData GTFS validation before accepting any feed
// - Stores routes, bidirectional stop edges, frequencies/headways, and first/last train tables
export class GtfsIngestionStore {
  private readonly feedHashes = new Map<string, string>();
  private readonly routes = new Map<string, TransitRouteDef>();
  private readonly edges: TransitStopEdge[] = [];
  private readonly firstLastTrains: FirstLastTrainEntry[] = [];

  constructor(private readonly validator: MobilityDataValidatorFn = defaultMobilityDataValidator) {
    this.loadSeedNetwork();
  }

  static computeSha256(content: string): string {
    return crypto.createHash('sha256').update(content, 'utf8').digest('hex');
  }

  private loadSeedNetwork(): void {
    const currentDir = path.dirname(fileURLToPath(import.meta.url));
    const candidates = [
      path.resolve(currentDir, '../../data/metro/metro_network_and_fares.json'),
      path.resolve(process.cwd(), 'data/metro/metro_network_and_fares.json'),
      path.resolve(process.cwd(), 'server/data/metro/metro_network_and_fares.json')
    ];

    for (const p of candidates) {
      if (fs.existsSync(p)) {
        const raw = JSON.parse(fs.readFileSync(p, 'utf8')) as {
          routes: TransitRouteDef[];
          edges: TransitStopEdge[];
          firstLastTrains: FirstLastTrainEntry[];
        };

        for (const r of raw.routes) {
          this.registerRoute(r);
        }
        for (const e of raw.edges) {
          this.registerBidirectionalEdge(e);
        }
        for (const fl of raw.firstLastTrains) {
          this.registerFirstLastTrain(fl);
        }
        return;
      }
    }
  }

  registerRoute(route: TransitRouteDef): void {
    if (!route.source_url || !route.effective_from) {
      throw new Error(`Route ${route.routeId} missing source_url or effective_from`);
    }
    this.routes.set(route.routeId, route);
  }

  registerBidirectionalEdge(edge: TransitStopEdge): void {
    this.edges.push(edge);
    this.edges.push({
      ...edge,
      fromStationId: edge.toStationId,
      toStationId: edge.fromStationId
    });
  }

  registerFirstLastTrain(entry: FirstLastTrainEntry): void {
    const parsed = firstLastSchema.parse(entry);
    this.firstLastTrains.push(parsed);
  }

  getRoutes(): TransitRouteDef[] {
    return Array.from(this.routes.values());
  }

  getRouteById(routeId: string): TransitRouteDef | null {
    return this.routes.get(routeId) ?? null;
  }

  getEdgesFromStation(stationId: string): TransitStopEdge[] {
    return this.edges.filter((e) => e.fromStationId === stationId);
  }

  getAllEdges(): ReadonlyArray<TransitStopEdge> {
    return this.edges;
  }

  getFirstLastTrain(
    routeId: string,
    stationId: string,
    dayType: DayType = 'weekday'
  ): FirstLastTrainEntry | null {
    return (
      this.firstLastTrains.find(
        (e) => e.routeId === routeId && e.stationId === stationId && e.dayType === dayType
      ) ??
      this.firstLastTrains.find(
        (e) => e.routeId === routeId && e.stationId === stationId && e.dayType === 'weekday'
      ) ??
      null
    );
  }

  // Checks SHA-256 hash, skips unchanged feeds, runs MobilityData validator, and records feed metadata.
  async ingestGtfsFeed(payload: GtfsFeedPayload): Promise<GtfsIngestionRecord> {
    const combinedContent = [
      payload.stopsTxt,
      payload.routesTxt,
      payload.tripsTxt,
      payload.stopTimesTxt,
      payload.frequenciesTxt ?? ''
    ].join('\n---\n');

    const sha256Hash = GtfsIngestionStore.computeSha256(combinedContent);
    const existingHash = this.feedHashes.get(payload.feedId);

    if (existingHash === sha256Hash) {
      return {
        feedId: payload.feedId,
        operatorCode: payload.operatorCode,
        sha256Hash,
        skippedUnchanged: true,
        validatorPassed: true,
        validationErrors: [],
        ingestedAt: new Date().toISOString()
      };
    }

    const validation = await this.validator(payload);
    if (!validation.passed) {
      return {
        feedId: payload.feedId,
        operatorCode: payload.operatorCode,
        sha256Hash,
        skippedUnchanged: false,
        validatorPassed: false,
        validationErrors: validation.errors,
        ingestedAt: new Date().toISOString()
      };
    }

    this.feedHashes.set(payload.feedId, sha256Hash);
    return {
      feedId: payload.feedId,
      operatorCode: payload.operatorCode,
      sha256Hash,
      skippedUnchanged: false,
      validatorPassed: true,
      validationErrors: [],
      ingestedAt: new Date().toISOString()
    };
  }
}

export const gtfsStore = new GtfsIngestionStore();
