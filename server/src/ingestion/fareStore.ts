import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvelope, DataEnvelope } from '../types/envelope.js';
import { DayType } from './gtfsFetcher.js';

export interface FareOdEntry {
  operatorCode: string;
  fromStationId: string;
  toStationId: string;
  dayType: DayType;
  fareInr: number;
  operator_deeplink: string;
  source_url: string;
  effective_from: string;
}

export interface FareSlabEntry {
  operatorCode: string;
  dayType: DayType;
  minKm: number;
  maxKm: number;
  fareInr: number;
  operator_deeplink: string;
  source_url: string;
  effective_from: string;
}

export interface FareLookupResult {
  fareInr: number | null;
  currency: 'INR';
  matchedBy: 'od_matrix' | 'operator_slab' | 'unavailable';
  message: string;
  operatorCode: string;
  operatorDeepLink: string;
  source_url: string | null;
  effective_from: string | null;
  isStaleOver90Days: boolean;
}

const DEFAULT_OPERATOR_DEEPLINKS: Record<string, string> = {
  DMRC: 'https://www.delhimetrorail.com/pages/en/fare-structure',
  BMRCL: 'https://english.bmrc.co.in/',
  HMRL: 'https://www.ltmetro.com/fare-chart/',
  CMRL: 'https://chennaimetrorail.org/fare-and-parking-charges/',
  NCRTC: 'https://ncrtc.in/',
  IR: 'https://www.irctc.co.in/'
};

function isOlderThan90Days(effectiveFrom: string, referenceDate: Date = new Date()): boolean {
  const eff = new Date(effectiveFrom);
  if (Number.isNaN(eff.getTime())) return true;
  const diffDays = (referenceDate.getTime() - eff.getTime()) / (1000 * 60 * 60 * 24);
  return diffDays > 90;
}

// Exact Operator Fare Store (OD matrices & non-linear operator slab tables).
// Never infers a public-transport fare linearly and never uses a national default.
export class OperatorFareStore {
  private readonly odEntries: FareOdEntry[] = [];
  private readonly slabEntries: FareSlabEntry[] = [];
  private readonly operatorDeepLinks = new Map<string, string>(
    Object.entries(DEFAULT_OPERATOR_DEEPLINKS)
  );

  constructor() {
    this.loadSeedFares();
  }

  private loadSeedFares(): void {
    const currentDir = path.dirname(fileURLToPath(import.meta.url));
    const candidates = [
      path.resolve(currentDir, '../../data/metro/metro_network_and_fares.json'),
      path.resolve(process.cwd(), 'data/metro/metro_network_and_fares.json'),
      path.resolve(process.cwd(), 'server/data/metro/metro_network_and_fares.json')
    ];

    for (const p of candidates) {
      if (fs.existsSync(p)) {
        const raw = JSON.parse(fs.readFileSync(p, 'utf8')) as {
          operators?: Record<string, { operator_deeplink: string }>;
          fareOdMatrix?: FareOdEntry[];
          fareSlabs?: FareSlabEntry[];
        };

        if (raw.operators) {
          for (const [code, meta] of Object.entries(raw.operators)) {
            this.operatorDeepLinks.set(code, meta.operator_deeplink);
          }
        }
        for (const od of raw.fareOdMatrix ?? []) {
          this.registerOdFare(od);
        }
        for (const slab of raw.fareSlabs ?? []) {
          this.registerSlabFare(slab);
        }
        return;
      }
    }
  }

  registerOdFare(entry: FareOdEntry): void {
    if (!entry.source_url || !entry.effective_from || !entry.operator_deeplink) {
      throw new Error(
        `OD fare ${entry.operatorCode}:${entry.fromStationId}->${entry.toStationId} missing required provenance fields`
      );
    }
    this.odEntries.push(entry);
    // Register symmetric reverse station-pair entry if not already present
    this.odEntries.push({
      ...entry,
      fromStationId: entry.toStationId,
      toStationId: entry.fromStationId
    });
  }

  registerSlabFare(entry: FareSlabEntry): void {
    if (!entry.source_url || !entry.effective_from || !entry.operator_deeplink) {
      throw new Error(`Slab fare for ${entry.operatorCode} missing required provenance fields`);
    }
    this.slabEntries.push(entry);
  }

  // Matches exact station-pair matrix first, then exact operator distance slab.
  // Returns "fare unavailable" + operator deep-link if no verified operator fare exists.
  lookupExactTransitFare(params: {
    operatorCode: string;
    fromStationId: string;
    toStationId: string;
    trackDistanceKm?: number;
    dayType?: DayType;
    referenceDate?: Date;
  }): DataEnvelope<FareLookupResult> {
    const dayType: DayType = params.dayType ?? 'weekday';
    const normalizedDay: DayType = dayType === 'holiday' ? 'sunday' : dayType;
    const refDate = params.referenceDate ?? new Date();
    const fallbackDeepLink =
      this.operatorDeepLinks.get(params.operatorCode) ?? 'https://otd.delhi.gov.in/';

    // 1. Exact station-pair OD matrix match
    const odMatch =
      this.odEntries.find(
        (e) =>
          e.operatorCode === params.operatorCode &&
          e.fromStationId === params.fromStationId &&
          e.toStationId === params.toStationId &&
          e.dayType === normalizedDay
      ) ??
      this.odEntries.find(
        (e) =>
          e.operatorCode === params.operatorCode &&
          e.fromStationId === params.fromStationId &&
          e.toStationId === params.toStationId &&
          e.dayType === 'weekday'
      );

    if (odMatch) {
      const stale = isOlderThan90Days(odMatch.effective_from, refDate);
      return createEnvelope<FareLookupResult>({
        value: {
          fareInr: odMatch.fareInr,
          currency: 'INR',
          matchedBy: 'od_matrix',
          message: `₹${odMatch.fareInr}`,
          operatorCode: params.operatorCode,
          operatorDeepLink: odMatch.operator_deeplink,
          source_url: odMatch.source_url,
          effective_from: odMatch.effective_from,
          isStaleOver90Days: stale
        },
        status: 'scheduled',
        sources: ['operator_official'],
        primarySourceWeight: 1.0
      });
    }

    // 2. Exact operator non-linear distance slab match
    if (params.trackDistanceKm !== undefined && params.trackDistanceKm > 0) {
      const dist = params.trackDistanceKm;
      const slabMatch =
        this.slabEntries.find(
          (s) =>
            s.operatorCode === params.operatorCode &&
            s.dayType === normalizedDay &&
            dist > s.minKm &&
            dist <= s.maxKm
        ) ??
        this.slabEntries.find(
          (s) =>
            s.operatorCode === params.operatorCode &&
            s.dayType === 'weekday' &&
            dist > s.minKm &&
            dist <= s.maxKm
        );

      if (slabMatch) {
        const stale = isOlderThan90Days(slabMatch.effective_from, refDate);
        return createEnvelope<FareLookupResult>({
          value: {
            fareInr: slabMatch.fareInr,
            currency: 'INR',
            matchedBy: 'operator_slab',
            message: `₹${slabMatch.fareInr}`,
            operatorCode: params.operatorCode,
            operatorDeepLink: slabMatch.operator_deeplink,
            source_url: slabMatch.source_url,
            effective_from: slabMatch.effective_from,
            isStaleOver90Days: stale
          },
          status: 'scheduled',
          sources: ['operator_official'],
          primarySourceWeight: 0.98
        });
      }
    }

    // 3. No exact fare exists -> never guess or use a national default
    return createEnvelope<FareLookupResult>({
      value: {
        fareInr: null,
        currency: 'INR',
        matchedBy: 'unavailable',
        message: 'fare unavailable',
        operatorCode: params.operatorCode,
        operatorDeepLink: fallbackDeepLink,
        source_url: null,
        effective_from: null,
        isStaleOver90Days: false
      },
      status: 'unavailable',
      sources: [],
      primarySourceWeight: 0
    });
  }
}

export const fareStore = new OperatorFareStore();
