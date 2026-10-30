import { FareLookupResult } from '../ingestion/fareStore.js';
import { DayType, GtfsIngestionStore } from '../ingestion/gtfsFetcher.js';
import { ConflictLog, conflictLog as defaultConflictLog } from '../lib/conflictLog.js';
import { SingleModeRouteOption } from '../providers/transit/types.js';
import { CanonicalStation, StationEntityResolver } from '../stations/resolver.js';
import {
  computeConfidence,
  createEnvelope,
  DataEnvelope,
  DataStatus
} from '../types/envelope.js';

export interface StationVerificationResult {
  stationId: string;
  canonicalName: string;
  confirmingSources: string[];
  isVerified: boolean;
  status: DataStatus;
  confidence: number;
  flags: string[];
}

export interface FareVerificationResult {
  envelope: DataEnvelope<FareLookupResult>;
  primaryFareInr: number | null;
  secondaryFareInr?: number | null;
  secondarySource?: string;
  disagreementRatio?: number;
  isStaleOver90Days: boolean;
  flags: string[];
}

export interface DurationVerificationResult {
  envelope: DataEnvelope<number>;
  primaryDurationSeconds: number;
  secondaryDurationSeconds?: number;
  secondarySource?: string;
  disagreementRatio?: number;
  bothValues?: {
    primarySeconds: number;
    secondarySeconds: number;
  };
  flags: string[];
}

export interface OperatingHoursLegViolation {
  routeId: string;
  stationId: string;
  departureTime: string;
  firstTrainTime: string;
  lastTrainTime: string;
}

export interface OperatingHoursVerificationResult {
  withinOperatingHours: boolean;
  checkedDepartureTime: string | null;
  violations: OperatingHoursLegViolation[];
  flags: string[];
}

export interface RouteVerificationReport {
  overallStatus: DataStatus;
  lowestConfidence: number;
  flags: string[];
  stationChecks: StationVerificationResult[];
  operatingHoursCheck: OperatingHoursVerificationResult;
  durationComparison?: DurationVerificationResult;
  fareComparison?: FareVerificationResult;
}

function parseTimeMinutes(timeStr: string): number | null {
  const trimmed = timeStr.trim();
  // Accept HH:MM or ISO-8601 string
  const hhmmMatch = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(trimmed);
  if (hhmmMatch) {
    return Number(hhmmMatch[1]) * 60 + Number(hhmmMatch[2]);
  }
  const parsedDate = new Date(trimmed);
  if (!Number.isNaN(parsedDate.getTime())) {
    // Convert UTC to IST (+05:30 = +330 min) for Indian transit operating hours
    const utcMinutes = parsedDate.getUTCHours() * 60 + parsedDate.getUTCMinutes();
    return (utcMinutes + 330) % (24 * 60);
  }
  return null;
}

function formatMinutesAsHhMm(totalMinutes: number): string {
  const norm = ((totalMinutes % 1440) + 1440) % 1440;
  const hh = String(Math.floor(norm / 60)).padStart(2, '0');
  const mm = String(norm % 60).padStart(2, '0');
  return `${hh}:${mm}`;
}

function isWithinServiceWindow(
  depMinutes: number,
  firstMinutes: number,
  lastMinutes: number
): boolean {
  if (firstMinutes <= lastMinutes) {
    return depMinutes >= firstMinutes && depMinutes <= lastMinutes;
  }
  // Wrap-around past midnight (e.g. 05:30 to 00:30)
  return depMinutes >= firstMinutes || depMinutes <= lastMinutes;
}

function isDateOlderThanDays(
  effectiveFrom: string | null | undefined,
  maxDays: number,
  referenceDate: Date
): boolean {
  if (!effectiveFrom) return false;
  const eff = new Date(effectiveFrom);
  if (Number.isNaN(eff.getTime())) return true;
  const diffDays = (referenceDate.getTime() - eff.getTime()) / (1000 * 60 * 60 * 24);
  return diffDays > maxDays;
}

// Phase 6 — Cross-Source Verification Engine
// Enforces the Source Truth Hierarchy:
//   Tier 1: Operator official data (fare charts, timetables, first/last train tables)
//   Tier 2: Operator GTFS / GTFS-Realtime
//   Tier 3: Licensed train API / aggregator
//   Tier 4: Google Routes, HERE Public Transit, OpenRouteService
//   Tier 5: OpenStreetMap / Wikidata
export class VerificationEngine {
  constructor(
    private readonly resolver: StationEntityResolver,
    private readonly gtfs: GtfsIngestionStore,
    private readonly conflicts: ConflictLog = defaultConflictLog
  ) {}

  // 1. Station Identity Verification:
  // Requires >= 2 independent confirming sources (e.g. GTFS + OSM/Wikidata/Google Places)
  // to mark a station verified; single-source stations are marked "unverified".
  verifyStationIdentity(stationOrId: CanonicalStation | string): StationVerificationResult {
    const station =
      typeof stationOrId === 'string'
        ? this.resolver.getStationById(stationOrId)
        : stationOrId;

    if (!station) {
      return {
        stationId: typeof stationOrId === 'string' ? stationOrId : 'unknown',
        canonicalName: 'Unknown Station',
        confirmingSources: [],
        isVerified: false,
        status: 'unavailable',
        confidence: 0,
        flags: ['station_not_found']
      };
    }

    const uniqueSources = Array.from(new Set(station.confirmingSources));
    const isVerified = uniqueSources.length >= 2;
    const flags: string[] = [];

    if (!isVerified) {
      flags.push('single_source_station');
    }

    const status: DataStatus = isVerified ? 'scheduled' : 'unverified';
    const confidence = computeConfidence({
      primarySourceWeight: isVerified ? 0.95 : 0.55,
      agreementCount: Math.max(1, uniqueSources.length),
      ageSeconds: 0,
      halfLifeSeconds: 86400
    });

    return {
      stationId: station.id,
      canonicalName: station.canonicalName,
      confirmingSources: uniqueSources,
      isVerified,
      status,
      confidence,
      flags
    };
  }

  // 2. Fare Verification & Cross-Check:
  // - Operator fare table (Tier 1) is authoritative.
  // - If a secondary fare (e.g. Google Routes / ONDC) disagrees by > 5%, keep the operator fare,
  //   log to ConflictLog, and flag "fare_cross_check_disagreement".
  // - If operator fare effective_from is > 90 days old, mark status = "stale".
  verifyFare(params: {
    entityKey: string;
    primaryEnvelope: DataEnvelope<FareLookupResult>;
    secondaryFareInr?: number | null;
    secondarySource?: string;
    secondaryTier?: number;
    referenceDate?: Date;
  }): FareVerificationResult {
    const refDate = params.referenceDate ?? new Date();
    const flags: string[] = [];
    const primaryVal = params.primaryEnvelope.value;

    if (!primaryVal || primaryVal.fareInr === null || params.primaryEnvelope.status === 'unavailable') {
      flags.push('fare_unavailable');
      return {
        envelope: {
          ...params.primaryEnvelope,
          status: 'unavailable',
          confidence: 0
        },
        primaryFareInr: null,
        isStaleOver90Days: false,
        flags
      };
    }

    const staleOver90 = isDateOlderThanDays(primaryVal.effective_from, 90, refDate);
    if (staleOver90) {
      flags.push('fare_table_older_than_90_days');
    }

    const sources = [...params.primaryEnvelope.sources];
    let disagreementRatio: number | undefined;

    if (
      params.secondaryFareInr !== undefined &&
      params.secondaryFareInr !== null &&
      params.secondaryFareInr > 0
    ) {
      const secSource = params.secondarySource ?? 'google_routes';
      const diff = Math.abs(primaryVal.fareInr - params.secondaryFareInr);
      disagreementRatio = Math.round((diff / Math.max(1, primaryVal.fareInr)) * 1000) / 1000;

      if (disagreementRatio > 0.05) {
        flags.push('fare_cross_check_disagreement');
        this.conflicts.append({
          entityType: 'fare',
          entityKey: params.entityKey,
          fieldName: 'fareInr',
          sourceValues: [
            {
              source: sources[0] ?? 'operator_official',
              tier: 1,
              value: primaryVal.fareInr
            },
            {
              source: secSource,
              tier: params.secondaryTier ?? 4,
              value: params.secondaryFareInr
            }
          ],
          winningSource: sources[0] ?? 'operator_official',
          finalValue: primaryVal.fareInr,
          reason: `Operator official fare (Tier 1: ₹${primaryVal.fareInr}) retained over ${secSource} (₹${params.secondaryFareInr}); disagreement ${(disagreementRatio * 100).toFixed(1)}% > 5% threshold`
        });
      } else if (!sources.includes(secSource)) {
        sources.push(secSource);
      }
    }

    const status: DataStatus = staleOver90 ? 'stale' : params.primaryEnvelope.status;
    const ageSeconds = staleOver90 ? 86400 : 0;
    const confidence = computeConfidence({
      primarySourceWeight: staleOver90 ? 0.75 : 1.0,
      agreementCount: sources.length,
      ageSeconds,
      halfLifeSeconds: 86400 * 2
    });

    const updatedEnvelope: DataEnvelope<FareLookupResult> = {
      value: {
        ...primaryVal,
        isStaleOver90Days: staleOver90
      },
      confidence,
      sources,
      as_of: params.primaryEnvelope.as_of,
      status
    };

    return {
      envelope: updatedEnvelope,
      primaryFareInr: primaryVal.fareInr,
      ...(params.secondaryFareInr !== undefined
        ? {
            secondaryFareInr: params.secondaryFareInr,
            secondarySource: params.secondarySource ?? 'google_routes',
            disagreementRatio
          }
        : {}),
      isStaleOver90Days: staleOver90,
      flags
    };
  }

  // 3. Duration Cross-Check:
  // Compares primary duration (GTFS / ORS) with a secondary routing engine (Google Routes / TomTom).
  // If they differ by > 25%, logs to ConflictLog, marks status = "unverified", and returns both values.
  verifyDuration(params: {
    entityKey: string;
    primaryEnvelope: DataEnvelope<number>;
    primaryTier?: number;
    secondaryDurationSeconds?: number | null;
    secondarySource?: string;
    secondaryTier?: number;
  }): DurationVerificationResult {
    const flags: string[] = [];
    const primarySec = params.primaryEnvelope.value;

    if (primarySec === null || primarySec <= 0 || params.primaryEnvelope.status === 'unavailable') {
      return {
        envelope: {
          ...params.primaryEnvelope,
          status: 'unavailable',
          confidence: 0
        },
        primaryDurationSeconds: 0,
        flags: ['duration_unavailable']
      };
    }

    const sources = [...params.primaryEnvelope.sources];
    let status: DataStatus = params.primaryEnvelope.status;
    let confidence = params.primaryEnvelope.confidence;
    let disagreementRatio: number | undefined;
    let bothValues: { primarySeconds: number; secondarySeconds: number } | undefined;

    if (
      params.secondaryDurationSeconds !== undefined &&
      params.secondaryDurationSeconds !== null &&
      params.secondaryDurationSeconds > 0
    ) {
      const secSec = params.secondaryDurationSeconds;
      const secSource = params.secondarySource ?? 'google_routes';
      const baseMin = Math.min(primarySec, secSec);
      disagreementRatio = Math.round((Math.abs(primarySec - secSec) / Math.max(1, baseMin)) * 1000) / 1000;

      if (disagreementRatio > 0.25) {
        flags.push('duration_sources_disagree');
        status = 'unverified';
        bothValues = {
          primarySeconds: primarySec,
          secondarySeconds: secSec
        };

        this.conflicts.append({
          entityType: 'route_duration',
          entityKey: params.entityKey,
          fieldName: 'durationSeconds',
          sourceValues: [
            {
              source: sources[0] ?? 'operator_gtfs',
              tier: params.primaryTier ?? 2,
              value: primarySec
            },
            {
              source: secSource,
              tier: params.secondaryTier ?? 4,
              value: secSec
            }
          ],
          winningSource: sources[0] ?? 'operator_gtfs',
          finalValue: primarySec,
          reason: `Duration sources disagree by ${(disagreementRatio * 100).toFixed(1)}% (> 25% threshold): primary=${primarySec}s vs ${secSource}=${secSec}s`
        });

        confidence = computeConfidence({
          primarySourceWeight: 0.6,
          agreementCount: 1,
          ageSeconds: 0,
          halfLifeSeconds: 3600
        });
      } else {
        if (!sources.includes(secSource)) {
          sources.push(secSource);
        }
        confidence = computeConfidence({
          primarySourceWeight: 0.95,
          agreementCount: sources.length,
          ageSeconds: 0,
          halfLifeSeconds: 3600
        });
      }
    }

    return {
      envelope: {
        ...params.primaryEnvelope,
        confidence,
        sources,
        status
      },
      primaryDurationSeconds: primarySec,
      ...(params.secondaryDurationSeconds !== undefined && params.secondaryDurationSeconds !== null
        ? {
            secondaryDurationSeconds: params.secondaryDurationSeconds,
            secondarySource: params.secondarySource ?? 'google_routes',
            disagreementRatio,
            ...(bothValues ? { bothValues } : {})
          }
        : {}),
      flags
    };
  }

  // 4. Operating Hours Verification:
  // Checks every metro/RRTS leg against first_last_trains for the requested departure time.
  // Never suggests a closed metro at 1:30 AM.
  verifyOperatingHours(params: {
    steps: SingleModeRouteOption['steps'];
    departureTime?: string;
    dayType?: DayType;
  }): OperatingHoursVerificationResult {
    if (!params.departureTime) {
      return {
        withinOperatingHours: true,
        checkedDepartureTime: null,
        violations: [],
        flags: []
      };
    }

    const startMinutes = parseTimeMinutes(params.departureTime);
    if (startMinutes === null) {
      return {
        withinOperatingHours: true,
        checkedDepartureTime: null,
        violations: [],
        flags: ['unparseable_departure_time']
      };
    }

    const dayType: DayType = params.dayType ?? 'weekday';
    const violations: OperatingHoursLegViolation[] = [];
    let elapsedSeconds = 0;

    for (const step of params.steps) {
      const legDepMinutes = (startMinutes + Math.floor(elapsedSeconds / 60)) % 1440;
      const legDepFormatted = formatMinutesAsHhMm(legDepMinutes);

      if ((step.mode === 'metro' || step.mode === 'rrts') && step.routeId && step.fromStationId) {
        const windowEntry = this.gtfs.getFirstLastTrain(
          step.routeId,
          step.fromStationId,
          dayType
        );
        if (windowEntry) {
          const firstMin = parseTimeMinutes(windowEntry.firstTrainTime);
          const lastMin = parseTimeMinutes(windowEntry.lastTrainTime);
          if (
            firstMin !== null &&
            lastMin !== null &&
            !isWithinServiceWindow(legDepMinutes, firstMin, lastMin)
          ) {
            violations.push({
              routeId: step.routeId,
              stationId: step.fromStationId,
              departureTime: legDepFormatted,
              firstTrainTime: windowEntry.firstTrainTime,
              lastTrainTime: windowEntry.lastTrainTime
            });
          }
        }
      }

      elapsedSeconds += step.durationSeconds;
    }

    const withinOperatingHours = violations.length === 0;
    return {
      withinOperatingHours,
      checkedDepartureTime: formatMinutesAsHhMm(startMinutes),
      violations,
      flags: withinOperatingHours ? [] : ['outside_operating_hours']
    };
  }

  // 5. Live vs. Scheduled Status Guard:
  // Never allows a static-schedule departure or duration to be labeled "live".
  // Only allows status = "live" if hasRealtimeFeed === true AND observation age <= 90 seconds.
  verifyRealtimeStatus<T>(params: {
    envelope: DataEnvelope<T>;
    hasRealtimeFeed: boolean;
    maxRealtimeAgeSeconds?: number;
    referenceDate?: Date;
  }): { envelope: DataEnvelope<T>; flags: string[] } {
    const refDate = params.referenceDate ?? new Date();
    const maxAge = params.maxRealtimeAgeSeconds ?? 90;
    const flags: string[] = [];

    const asOfDate = new Date(params.envelope.as_of);
    const ageSeconds = Number.isNaN(asOfDate.getTime())
      ? Infinity
      : Math.max(0, (refDate.getTime() - asOfDate.getTime()) / 1000);

    if (params.envelope.status === 'live') {
      if (!params.hasRealtimeFeed) {
        flags.push('downgraded_from_live_to_scheduled');
        return {
          envelope: {
            ...params.envelope,
            status: 'scheduled'
          },
          flags
        };
      }

      if (ageSeconds > maxAge) {
        flags.push('stale_realtime_feed');
        return {
          envelope: {
            ...params.envelope,
            status: 'stale',
            confidence: computeConfidence({
              primarySourceWeight: 0.65,
              agreementCount: params.envelope.sources.length,
              ageSeconds,
              halfLifeSeconds: 300
            })
          },
          flags
        };
      }
    }

    return {
      envelope: params.envelope,
      flags
    };
  }

  // 6. Full Route Option Verification Pipeline:
  // Runs station identity, operating hours, fare cross-check, duration cross-check, and realtime guard
  // on a SingleModeRouteOption and attaches a complete RouteVerificationReport.
  verifyRouteOption(params: {
    option: SingleModeRouteOption;
    departureTime?: string;
    dayType?: DayType;
    secondaryDurationSeconds?: number | null;
    secondaryFareInr?: number | null;
    secondarySource?: string;
    hasRealtimeFeed?: boolean;
    referenceDate?: Date;
  }): SingleModeRouteOption {
    const { option } = params;
    const refDate = params.referenceDate ?? new Date();
    const allFlags = new Set<string>();

    // Collect unique station IDs referenced in route steps
    const stationIds = new Set<string>();
    for (const step of option.steps) {
      if (step.fromStationId) stationIds.add(step.fromStationId);
      if (step.toStationId) stationIds.add(step.toStationId);
    }

    const stationChecks: StationVerificationResult[] = [];
    for (const stnId of stationIds) {
      const check = this.verifyStationIdentity(stnId);
      stationChecks.push(check);
      for (const f of check.flags) allFlags.add(f);
    }

    // Operating hours check
    const operatingHoursCheck = this.verifyOperatingHours({
      steps: option.steps,
      departureTime: params.departureTime,
      dayType: params.dayType
    });
    for (const f of operatingHoursCheck.flags) allFlags.add(f);

    // Duration check + realtime status guard
    const rtGuard = this.verifyRealtimeStatus({
      envelope: option.durationSeconds,
      hasRealtimeFeed: params.hasRealtimeFeed ?? false,
      referenceDate: refDate
    });
    for (const f of rtGuard.flags) allFlags.add(f);

    const durationComparison = this.verifyDuration({
      entityKey: option.id,
      primaryEnvelope: rtGuard.envelope,
      secondaryDurationSeconds: params.secondaryDurationSeconds,
      secondarySource: params.secondarySource
    });
    for (const f of durationComparison.flags) allFlags.add(f);

    // Fare check (for transit/train modes; road modes retain estimate status)
    let fareComparison: FareVerificationResult | undefined;
    let verifiedFareEnvelope = option.fare;

    if (option.fare.status !== 'estimate') {
      fareComparison = this.verifyFare({
        entityKey: option.id,
        primaryEnvelope: option.fare,
        secondaryFareInr: params.secondaryFareInr,
        secondarySource: params.secondarySource,
        referenceDate: refDate
      });
      verifiedFareEnvelope = fareComparison.envelope;
      for (const f of fareComparison.flags) allFlags.add(f);
    }

    let verifiedDurationEnvelope = durationComparison.envelope;

    // If outside operating hours, mark the route option unavailable so closed metros are never suggested as active
    if (!operatingHoursCheck.withinOperatingHours) {
      verifiedDurationEnvelope = createEnvelope<number>({
        value: verifiedDurationEnvelope.value,
        status: 'unavailable',
        sources: verifiedDurationEnvelope.sources,
        primarySourceWeight: 0
      });
    }

    // Determine overall route status and lowest confidence across verified components
    const confidences: number[] = [verifiedDurationEnvelope.confidence];
    if (verifiedFareEnvelope.status !== 'unavailable') {
      confidences.push(verifiedFareEnvelope.confidence);
    }
    for (const sc of stationChecks) {
      confidences.push(sc.confidence);
    }

    const lowestConfidence =
      confidences.length > 0
        ? Math.round(Math.min(...confidences) * 1000) / 1000
        : 0;

    let overallStatus: DataStatus = verifiedDurationEnvelope.status;
    if (!operatingHoursCheck.withinOperatingHours) {
      overallStatus = 'unavailable';
    } else if (
      verifiedDurationEnvelope.status === 'unverified' ||
      stationChecks.some((s) => !s.isVerified)
    ) {
      overallStatus = 'unverified';
    } else if (
      verifiedDurationEnvelope.status === 'stale' ||
      verifiedFareEnvelope.status === 'stale'
    ) {
      overallStatus = 'stale';
    }

    const report: RouteVerificationReport = {
      overallStatus,
      lowestConfidence,
      flags: Array.from(allFlags),
      stationChecks,
      operatingHoursCheck,
      durationComparison,
      ...(fareComparison ? { fareComparison } : {})
    };

    return {
      ...option,
      durationSeconds: verifiedDurationEnvelope,
      fare: verifiedFareEnvelope,
      verification: report
    };
  }
}
