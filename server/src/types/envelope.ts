// Standardized data envelope required for every transit, train, and verification field.
export type DataStatus =
  | 'live'
  | 'scheduled'
  | 'stale'
  | 'unverified'
  | 'estimate'
  | 'unavailable';

export interface DataEnvelope<T> {
  value: T | null;
  confidence: number; // 0 to 1
  sources: string[];
  as_of: string; // ISO-8601 timestamp
  status: DataStatus;
}

export interface ConfidenceInput {
  primarySourceWeight: number; // 0 to 1 from config/sources.json adjusted by error rate
  agreementCount: number; // number of independent sources agreeing (>= 1)
  ageSeconds: number; // age of the observation in seconds
  halfLifeSeconds?: number; // freshness decay half-life (default 3600s for scheduled, 300s for live)
}

// Confidence = sourceWeight * agreementFactor * freshnessDecay, clamped to [0, 1].
export function computeConfidence(input: ConfidenceInput): number {
  const base = Math.max(0, Math.min(1, input.primarySourceWeight));
  if (input.agreementCount <= 0 || base === 0) {
    return 0;
  }

  // Agreement bonus: 1 source = 0.85x base, 2 sources = 1.0x base, 3+ sources = 1.08x base
  const agreementFactor =
    input.agreementCount === 1 ? 0.85 : input.agreementCount === 2 ? 1.0 : 1.08;

  const halfLife = Math.max(1, input.halfLifeSeconds ?? 3600);
  const age = Math.max(0, input.ageSeconds);
  const freshnessDecay = Math.pow(0.5, age / halfLife);

  const raw = base * agreementFactor * freshnessDecay;
  return Math.round(Math.max(0, Math.min(1, raw)) * 1000) / 1000;
}

export function createEnvelope<T>(params: {
  value: T | null;
  status: DataStatus;
  sources: string[];
  primarySourceWeight: number;
  asOf?: Date;
  halfLifeSeconds?: number;
}): DataEnvelope<T> {
  const asOfDate = params.asOf ?? new Date();
  const ageSeconds = Math.max(0, (Date.now() - asOfDate.getTime()) / 1000);
  const confidence =
    params.status === 'unavailable' || params.value === null
      ? 0
      : computeConfidence({
          primarySourceWeight: params.primarySourceWeight,
          agreementCount: params.sources.length,
          ageSeconds,
          halfLifeSeconds:
            params.halfLifeSeconds ?? (params.status === 'live' ? 300 : 86400)
        });

  return {
    value: params.value,
    confidence,
    sources: params.sources,
    as_of: asOfDate.toISOString(),
    status: params.status
  };
}
