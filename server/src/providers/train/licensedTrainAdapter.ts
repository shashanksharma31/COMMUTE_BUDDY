import { AppEnv } from '../../env.js';
import { TtlCache } from '../../lib/cache.js';
import { ConflictLog, conflictLog as defaultConflictLog } from '../../lib/conflictLog.js';
import { SourceResilienceManager } from '../../lib/resilience.js';
import {
  computeConfidence,
  createEnvelope,
  DataEnvelope,
  DataStatus
} from '../../types/envelope.js';

export interface TrainSeatAvailability {
  classCode: string; // e.g. 'SL', '3A', '2A', 'CC'
  availabilityText: string; // e.g. 'AVAILABLE-0042', 'RAC 14', 'WL 21'
  bookingStatus: 'AVAILABLE' | 'RAC' | 'WAITLIST' | 'UNAVAILABLE';
}

export interface RawTrainLiveObservation {
  trainNumber: string;
  fromStationCode: string;
  toStationCode: string;
  delaySeconds: number;
  currentStationCode?: string;
  seatAvailability: TrainSeatAvailability[];
  observedAt: string; // ISO-8601
}

export interface VerifiedTrainLiveStatus {
  trainNumber: string;
  delaySeconds: DataEnvelope<number>;
  seatAvailability: DataEnvelope<TrainSeatAvailability[]>;
  flags: string[];
}

// Pluggable licensed train provider adapter interface (CRIS/IRCTC authorized or licensed aggregators).
// Never scrapes IRCTC or NTES.
export interface TrainLiveProviderAdapter {
  readonly providerName: string;
  readonly sourceKey: 'licensed_train_primary' | 'licensed_train_secondary';
  readonly isUnofficialListing: boolean;
  isConfigured(): boolean;
  fetchLiveTrainStatus(params: {
    trainNumber: string;
    fromStationCode: string;
    toStationCode: string;
    journeyDate?: string;
  }): Promise<RawTrainLiveObservation>;
}

function isUnofficialProviderName(name: string): boolean {
  const lower = name.trim().toLowerCase();
  return (
    lower.includes('rapidapi') ||
    lower.includes('erail') ||
    lower.includes('unofficial')
  );
}

export class LicensedHttpTrainAdapter implements TrainLiveProviderAdapter {
  public readonly providerName: string;
  public readonly sourceKey: 'licensed_train_primary' | 'licensed_train_secondary';
  public readonly isUnofficialListing: boolean;

  constructor(
    private readonly role: 'primary' | 'secondary',
    private readonly appEnv: AppEnv,
    private readonly resilience: SourceResilienceManager,
    private readonly fetchFn: typeof fetch = fetch
  ) {
    this.providerName =
      role === 'primary'
        ? appEnv.TRAIN_PRIMARY_PROVIDER.trim()
        : appEnv.TRAIN_SECONDARY_PROVIDER.trim();
    this.sourceKey =
      role === 'primary' ? 'licensed_train_primary' : 'licensed_train_secondary';
    this.isUnofficialListing = isUnofficialProviderName(this.providerName);
  }

  private getApiKey(): string {
    return this.role === 'primary'
      ? this.appEnv.TRAIN_PRIMARY_API_KEY.trim()
      : this.appEnv.TRAIN_SECONDARY_API_KEY.trim();
  }

  isConfigured(): boolean {
    return this.providerName.length > 0 && this.getApiKey().length > 0;
  }

  async fetchLiveTrainStatus(params: {
    trainNumber: string;
    fromStationCode: string;
    toStationCode: string;
    journeyDate?: string;
  }): Promise<RawTrainLiveObservation> {
    if (!this.isConfigured()) {
      throw new Error(`Licensed train ${this.role} provider is not configured`);
    }

    const dateStr = params.journeyDate ?? new Date().toISOString().slice(0, 10);
    const url = `https://api.${this.providerName.toLowerCase().replace(/[^a-z0-9.-]/g, '')}.com/v1/train/${encodeURIComponent(params.trainNumber)}/live?from=${encodeURIComponent(params.fromStationCode)}&to=${encodeURIComponent(params.toStationCode)}&date=${encodeURIComponent(dateStr)}`;

    return this.resilience.execute<RawTrainLiveObservation>(
      this.sourceKey,
      async (signal) => {
        const res = await this.fetchFn(url, {
          headers: {
            Authorization: `Bearer ${this.getApiKey()}`,
            Accept: 'application/json'
          },
          signal
        });
        if (!res.ok) {
          throw new Error(
            `Train provider ${this.providerName} HTTP ${res.status}`
          );
        }
        return (await res.json()) as RawTrainLiveObservation;
      }
    );
  }
}

// Orchestrates primary licensed train provider and cross-checks against the secondary provider.
// Treats RapidAPI/ERail-style listings as unofficial and low weight (<= 0.35, status = 'unverified').
export class CrossCheckedTrainService {
  constructor(
    private readonly primaryAdapter: TrainLiveProviderAdapter,
    private readonly secondaryAdapter: TrainLiveProviderAdapter | null,
    private readonly resilience: SourceResilienceManager,
    private readonly cache: TtlCache,
    private readonly conflicts: ConflictLog = defaultConflictLog
  ) {}

  async getVerifiedLiveTrainStatus(params: {
    trainNumber: string;
    fromStationCode: string;
    toStationCode: string;
    journeyDate?: string;
  }): Promise<VerifiedTrainLiveStatus> {
    if (!this.primaryAdapter.isConfigured()) {
      return {
        trainNumber: params.trainNumber,
        delaySeconds: createEnvelope<number>({
          value: null,
          status: 'unavailable',
          sources: [],
          primarySourceWeight: 0
        }),
        seatAvailability: createEnvelope<TrainSeatAvailability[]>({
          value: null,
          status: 'unavailable',
          sources: [],
          primarySourceWeight: 0
        }),
        flags: ['licensed_train_provider_unconfigured']
      };
    }

    const cacheKey = `train_live:${params.trainNumber}:${params.fromStationCode}->${params.toStationCode}:${params.journeyDate ?? 'today'}`;
    const cached = this.cache.get<VerifiedTrainLiveStatus>(cacheKey);
    if (cached) {
      return cached;
    }

    const flags: string[] = [];
    let primaryObs: RawTrainLiveObservation;

    try {
      primaryObs = await this.primaryAdapter.fetchLiveTrainStatus(params);
    } catch {
      // Never fail open: if primary call fails, mark live status unavailable
      return {
        trainNumber: params.trainNumber,
        delaySeconds: createEnvelope<number>({
          value: null,
          status: 'unavailable',
          sources: [],
          primarySourceWeight: 0
        }),
        seatAvailability: createEnvelope<TrainSeatAvailability[]>({
          value: null,
          status: 'unavailable',
          sources: [],
          primarySourceWeight: 0
        }),
        flags: ['primary_train_provider_failed']
      };
    }

    const sources: string[] = [this.primaryAdapter.providerName];
    let secondaryObs: RawTrainLiveObservation | null = null;

    if (this.secondaryAdapter && this.secondaryAdapter.isConfigured()) {
      try {
        secondaryObs = await this.secondaryAdapter.fetchLiveTrainStatus(params);
      } catch {
        flags.push('secondary_train_provider_failed');
      }
    }

    const isUnofficial = this.primaryAdapter.isUnofficialListing;
    if (isUnofficial) {
      flags.push('unofficial_low_weight_train_source');
    }

    let delayStatus: DataStatus = isUnofficial ? 'unverified' : 'live';
    let baseWeight = isUnofficial
      ? 0.35
      : this.resilience.getEffectiveWeight('licensed_train_primary');

    if (secondaryObs) {
      const delayDiffSeconds = Math.abs(
        primaryObs.delaySeconds - secondaryObs.delaySeconds
      );
      if (delayDiffSeconds > 300) {
        // Disagreement > 5 min (300s) -> log conflict and mark unverified
        flags.push('train_live_delay_disagreement');
        delayStatus = 'unverified';
        baseWeight = Math.min(baseWeight, 0.55);

        this.conflicts.append({
          entityType: 'train_live_delay',
          entityKey: `${params.trainNumber}:${params.fromStationCode}->${params.toStationCode}`,
          fieldName: 'delaySeconds',
          sourceValues: [
            {
              source: this.primaryAdapter.providerName,
              tier: isUnofficial ? 5 : 3,
              value: primaryObs.delaySeconds
            },
            {
              source: this.secondaryAdapter!.providerName,
              tier: this.secondaryAdapter!.isUnofficialListing ? 5 : 3,
              value: secondaryObs.delaySeconds
            }
          ],
          winningSource: this.primaryAdapter.providerName,
          finalValue: primaryObs.delaySeconds,
          reason: `Live train delay disagreement of ${delayDiffSeconds}s (> 300s threshold) between ${this.primaryAdapter.providerName} and ${this.secondaryAdapter!.providerName}`
        });
      } else if (!this.secondaryAdapter!.isUnofficialListing) {
        sources.push(this.secondaryAdapter!.providerName);
      }
    }

    const obsDate = new Date(primaryObs.observedAt);
    const ageSeconds = Number.isNaN(obsDate.getTime())
      ? 0
      : Math.max(0, (Date.now() - obsDate.getTime()) / 1000);

    if (delayStatus === 'live' && ageSeconds > 90) {
      delayStatus = 'stale';
      flags.push('stale_realtime_feed');
    }

    const confidence = computeConfidence({
      primarySourceWeight: baseWeight,
      agreementCount: sources.length,
      ageSeconds,
      halfLifeSeconds: 300
    });

    const delayEnvelope: DataEnvelope<number> = {
      value: primaryObs.delaySeconds,
      confidence,
      sources,
      as_of: Number.isNaN(obsDate.getTime())
        ? new Date().toISOString()
        : obsDate.toISOString(),
      status: delayStatus
    };

    const seatEnvelope: DataEnvelope<TrainSeatAvailability[]> = {
      value: primaryObs.seatAvailability,
      confidence,
      sources,
      as_of: delayEnvelope.as_of,
      status: delayStatus
    };

    const result: VerifiedTrainLiveStatus = {
      trainNumber: params.trainNumber,
      delaySeconds: delayEnvelope,
      seatAvailability: seatEnvelope,
      flags
    };

    this.cache.set(cacheKey, result, 60);
    return result;
  }
}
