import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FareLookupResult } from '../ingestion/fareStore.js';
import { createEnvelope, DataEnvelope } from '../types/envelope.js';

export type MeteredRoadMode = 'auto' | 'e_rickshaw' | 'bike' | 'car';

export interface ModeRateCard {
  baseFareInr: number;
  baseDistanceKm: number;
  perKmInr: number;
  perMinuteInr: number;
  minFareInr: number;
}

export interface CityRateEntry {
  cityName: string;
  source_url: string;
  effective_from: string;
  modes: Record<MeteredRoadMode, ModeRateCard>;
}

export interface EstimatedFareResult extends FareLookupResult {
  isEstimate: true;
  cityCodeUsed: string;
}

function loadRatesFile(): Record<string, CityRateEntry> {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const candidates = [
    path.resolve(currentDir, '../../config/rates.json'),
    path.resolve(process.cwd(), 'config/rates.json'),
    path.resolve(process.cwd(), 'server/config/rates.json')
  ];

  for (const p of candidates) {
    if (fs.existsSync(p)) {
      const parsed = JSON.parse(fs.readFileSync(p, 'utf8')) as {
        cities: Record<string, CityRateEntry>;
      };
      for (const [code, entry] of Object.entries(parsed.cities)) {
        if (!entry.source_url || !entry.effective_from) {
          throw new Error(`Rate card for city ${code} missing source_url or effective_from`);
        }
      }
      return parsed.cities;
    }
  }
  throw new Error('Could not locate server/config/rates.json');
}

// Calculates estimated road & last-mile fares from editable city/national rate cards.
// Always sets isEstimate: true and status: 'estimate'. Never claims live Uber/Ola prices.
export class RoadRateCardCalculator {
  private readonly cities: Record<string, CityRateEntry>;

  constructor(customCities?: Record<string, CityRateEntry>) {
    this.cities = customCities ?? loadRatesFile();
  }

  calculateEstimatedFare(params: {
    mode: MeteredRoadMode;
    distanceMeters: number;
    durationSeconds: number;
    cityCode?: string;
    trafficSurgeMultiplier?: number;
  }): DataEnvelope<EstimatedFareResult> {
    const requestedCity = params.cityCode?.toUpperCase() ?? 'NATIONAL_DEFAULT';
    const cityEntry =
      this.cities[requestedCity] ?? this.cities['NATIONAL_DEFAULT'];
    const cityCodeUsed = this.cities[requestedCity] ? requestedCity : 'NATIONAL_DEFAULT';

    const card = cityEntry.modes[params.mode];
    const distanceKm = Math.max(0, params.distanceMeters / 1000);
    const durationMinutes = Math.max(0, params.durationSeconds / 60);
    const surge = Math.max(1.0, params.trafficSurgeMultiplier ?? 1.0);

    const extraDistanceKm = Math.max(0, distanceKm - card.baseDistanceKm);
    const rawFare =
      (card.baseFareInr +
        extraDistanceKm * card.perKmInr +
        durationMinutes * card.perMinuteInr) *
      surge;

    const fareInr = Math.max(card.minFareInr, Math.round(rawFare));

    return createEnvelope<EstimatedFareResult>({
      value: {
        fareInr,
        currency: 'INR',
        matchedBy: 'operator_slab',
        message: `Estimated ₹${fareInr} (meter tariff)`,
        operatorCode: `RATE_CARD_${cityCodeUsed}`,
        operatorDeepLink: cityEntry.source_url,
        source_url: cityEntry.source_url,
        effective_from: cityEntry.effective_from,
        isStaleOver90Days: false,
        isEstimate: true,
        cityCodeUsed
      },
      status: 'estimate',
      sources: [`rate_card_${cityCodeUsed.toLowerCase()}`],
      primarySourceWeight: 0.8
    });
  }
}

export const rateCardCalculator = new RoadRateCardCalculator();
