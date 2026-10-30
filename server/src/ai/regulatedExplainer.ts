import { z } from 'zod';
import { AppEnv } from '../env.js';
import { MultiModalJourneyOption } from '../journeys/journeyPlanner.js';
import { logger } from '../lib/logger.js';
import { SourceResilienceManager } from '../lib/resilience.js';

export const llmExplanationSchema = z.object({
  summary: z.string().min(5).max(600),
  referencedLegIds: z.array(z.string().min(1)).min(1),
  citedNumbers: z.array(z.number()),
  confidenceNote: z.string().min(3).max(300)
});

export type LlmExplanationPayload = z.infer<typeof llmExplanationSchema>;

export interface RegulatedExplanationResult {
  summary: string;
  confidenceNote: string;
  referencedLegIds: string[];
  citedNumbers: number[];
  generatedBy: 'gemini_verified' | 'deterministic_template';
  hallucinationRejected: boolean;
  rejectionReason?: string;
}

interface GeminiGenerateContentResponse {
  candidates?: Array<{
    content?: {
      parts?: Array<{
        text?: string;
      }>;
    };
  }>;
}

const EMOJI_REGEX = /\p{Extended_Pictographic}/u;

export function formatDurationSecondsForTemplate(totalSeconds: number): string {
  const sec = Math.max(0, Math.round(totalSeconds));
  const totalMinutes = Math.max(1, Math.round(sec / 60));
  if (totalMinutes < 60) {
    return `${totalMinutes} min`;
  }
  const hours = Math.floor(totalMinutes / 60);
  const mins = totalMinutes % 60;
  if (hours < 24) {
    return mins > 0 ? `${hours} hr ${mins} min` : `${hours} hr`;
  }
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return remHours > 0 ? `${days} d ${remHours} hr` : `${days} d`;
}

// Extracts the exact set of numbers present in the verified journey option (seconds, rounded minutes, fares, distances, transfers).
export function extractAllowedNumbersFromJourney(
  journey: MultiModalJourneyOption
): Set<number> {
  const allowed = new Set<number>([0, 1, 2, 3, 4]);

  const totalSec = journey.totalDurationSeconds.value ?? 0;
  allowed.add(totalSec);
  allowed.add(Math.round(totalSec / 60));
  allowed.add(Math.floor(totalSec / 3600));
  allowed.add(Math.round((totalSec % 3600) / 60));

  allowed.add(journey.totalDistanceMeters);
  allowed.add(Math.round(journey.totalDistanceMeters / 1000));
  allowed.add(journey.totalWalkMeters);
  allowed.add(journey.transfers);
  allowed.add(journey.legs.length);
  allowed.add(Math.round(journey.lowestConfidence * 100));

  if (journey.totalFare.value?.amountInr !== null && journey.totalFare.value?.amountInr !== undefined) {
    allowed.add(journey.totalFare.value.amountInr);
  }

  for (const leg of journey.legs) {
    const legSec = leg.durationSeconds.value ?? 0;
    allowed.add(legSec);
    allowed.add(Math.round(legSec / 60));
    allowed.add(leg.distanceMeters);
    allowed.add(leg.transferBufferSeconds);
    allowed.add(Math.round(leg.transferBufferSeconds / 60));
    if (leg.fare.value?.fareInr !== null && leg.fare.value?.fareInr !== undefined) {
      allowed.add(leg.fare.value.fareInr);
    }
    // Extract numbers that are part of official line/train names (e.g. trainNumber 12004 or leg_1 -> 1)
    const idNums = leg.legId.match(/\d+/g) ?? [];
    for (const n of idNums) allowed.add(Number(n));
    if (leg.trainNumber) {
      const trNums = leg.trainNumber.match(/\d+/g) ?? [];
      for (const n of trNums) allowed.add(Number(n));
    }
  }

  return allowed;
}

// Extracts all standalone numbers from free text (ignoring leg_1 style underscores by stripping legId tokens first).
function extractNumbersFromText(text: string, validLegIds: Set<string>): number[] {
  let cleaned = text;
  for (const id of validLegIds) {
    cleaned = cleaned.split(id).join(' ');
  }
  const matches = cleaned.match(/\d+(?:\.\d+)?/g) ?? [];
  return matches.map((m) => Number(m));
}

// Deterministic fallback template built 100% from verified DataEnvelope fields.
export function buildDeterministicExplanation(
  journey: MultiModalJourneyOption
): RegulatedExplanationResult {
  const totalSec = journey.totalDurationSeconds.value ?? 0;
  const durationText = formatDurationSecondsForTemplate(totalSec);
  const fareMsg = journey.totalFare.value?.message ?? 'fare unavailable';
  const legIds = journey.legs.map((l) => l.legId);

  const legDescriptions = journey.legs.map((l) => {
    const legMin = Math.max(1, Math.round((l.durationSeconds.value ?? 0) / 60));
    const modeLabel = l.lineName ?? l.mode.toUpperCase();
    return `${modeLabel} (${l.fromName} to ${l.toName}, ${legMin} min)`;
  });

  const summary = `${journey.label} option: ${durationText}, ${fareMsg}, ${journey.transfers} transfers, ${journey.totalWalkMeters} m walk via ${legDescriptions.join(' then ')}.`;

  const statusLabel = journey.overallStatus;
  const confPct = Math.round(journey.lowestConfidence * 100);
  const confidenceNote = `Data status: ${statusLabel} (lowest confidence ${confPct}%).`;

  const citedNumbers: number[] = [
    Math.round(totalSec / 60),
    journey.transfers,
    journey.totalWalkMeters,
    confPct
  ];
  if (journey.totalFare.value?.amountInr !== null && journey.totalFare.value?.amountInr !== undefined) {
    citedNumbers.push(journey.totalFare.value.amountInr);
  }

  return {
    summary,
    confidenceNote,
    referencedLegIds: legIds,
    citedNumbers,
    generatedBy: 'deterministic_template',
    hallucinationRejected: false
  };
}

// Regulated Gemini Explainer:
// - Prompts Gemini Flash with structured JSON output schema
// - Rejects any output containing emojis, unknown legIds, or numbers not present in the verified journey envelopes
// - Falls back to deterministic template whenever disabled, errored, or rejected
export class RegulatedJourneyExplainer {
  constructor(
    private readonly appEnv: AppEnv,
    private readonly resilience: SourceResilienceManager,
    private readonly fetchFn: typeof fetch = fetch
  ) {}

  isConfigured(): boolean {
    return this.appEnv.GEMINI_API_KEY.trim().length > 0;
  }

  validateCandidateExplanation(
    candidate: unknown,
    journey: MultiModalJourneyOption
  ): { valid: true; payload: LlmExplanationPayload } | { valid: false; reason: string } {
    const parsed = llmExplanationSchema.safeParse(candidate);
    if (!parsed.success) {
      return {
        valid: false,
        reason: `Schema validation failed: ${parsed.error.issues.map((i) => i.message).join('; ')}`
      };
    }

    const payload = parsed.data;

    // 1. Check for banned emojis (AGENTS.md rule: no emojis anywhere in UI text or data)
    if (EMOJI_REGEX.test(payload.summary) || EMOJI_REGEX.test(payload.confidenceNote)) {
      return {
        valid: false,
        reason: 'Rejected LLM output containing prohibited emoji characters'
      };
    }

    // 2. Verify every referenced legId exists in the journey
    const validLegIds = new Set(journey.legs.map((l) => l.legId));
    for (const refId of payload.referencedLegIds) {
      if (!validLegIds.has(refId)) {
        return {
          valid: false,
          reason: `Rejected hallucinated legId "${refId}" not present in verified journey`
        };
      }
    }

    // 3. Verify every cited number and every number appearing in summary/confidenceNote exists in the verified envelopes
    const allowedNumbers = extractAllowedNumbersFromJourney(journey);
    for (const num of payload.citedNumbers) {
      if (!allowedNumbers.has(num)) {
        return {
          valid: false,
          reason: `Rejected hallucinated cited number ${num} not present in verified journey envelopes`
        };
      }
    }

    const textNumbers = [
      ...extractNumbersFromText(payload.summary, validLegIds),
      ...extractNumbersFromText(payload.confidenceNote, validLegIds)
    ];
    for (const num of textNumbers) {
      if (!allowedNumbers.has(num)) {
        return {
          valid: false,
          reason: `Rejected hallucinated number ${num} in explanation text not matching any verified envelope value`
        };
      }
    }

    return { valid: true, payload };
  }

  async explainJourney(params: {
    journey: MultiModalJourneyOption;
    question?: string;
  }): Promise<RegulatedExplanationResult> {
    if (!this.isConfigured()) {
      return buildDeterministicExplanation(params.journey);
    }

    const apiKey = this.appEnv.GEMINI_API_KEY.trim();
    const model = this.appEnv.GEMINI_MODEL.trim() || 'gemini-2.5-flash';
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(apiKey)}`;

    const verifiedFacts = {
      id: params.journey.id,
      label: params.journey.label,
      totalDurationSeconds: params.journey.totalDurationSeconds.value,
      totalDurationMinutes: Math.round((params.journey.totalDurationSeconds.value ?? 0) / 60),
      totalFareInr: params.journey.totalFare.value?.amountInr,
      totalFareMessage: params.journey.totalFare.value?.message,
      transfers: params.journey.transfers,
      totalWalkMeters: params.journey.totalWalkMeters,
      overallStatus: params.journey.overallStatus,
      lowestConfidencePercent: Math.round(params.journey.lowestConfidence * 100),
      legs: params.journey.legs.map((l) => ({
        legId: l.legId,
        mode: l.mode,
        lineName: l.lineName,
        fromName: l.fromName,
        toName: l.toName,
        durationSeconds: l.durationSeconds.value,
        durationMinutes: Math.round((l.durationSeconds.value ?? 0) / 60),
        fareInr: l.fare.value?.fareInr,
        status: l.durationSeconds.status
      }))
    };

    const promptText = [
      'You are a regulated transit data narrator for CommuteBuddy.',
      'STRICT RULES:',
      '1. Use ONLY the exact numbers, station names, and legIds present in VERIFIED_JOURNEY_JSON.',
      '2. Never guess, round differently, or invent any fare, duration, or station.',
      '3. Never use emojis.',
      '4. Return valid JSON with keys: summary, referencedLegIds, citedNumbers, confidenceNote.',
      params.question ? `USER_QUESTION: ${params.question}` : '',
      `VERIFIED_JOURNEY_JSON: ${JSON.stringify(verifiedFacts)}`
    ]
      .filter(Boolean)
      .join('\n');

    try {
      const data = await this.resilience.execute<GeminiGenerateContentResponse>(
        'gemini_flash',
        async (signal) => {
          const res = await this.fetchFn(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              contents: [{ parts: [{ text: promptText }] }],
              generationConfig: {
                responseMimeType: 'application/json',
                temperature: 0.0
              }
            }),
            signal
          });
          if (!res.ok) {
            throw new Error(`Gemini API HTTP ${res.status}`);
          }
          return (await res.json()) as GeminiGenerateContentResponse;
        }
      );

      const rawText = data.candidates?.[0]?.content?.parts?.[0]?.text;
      if (!rawText) {
        throw new Error('Empty response from Gemini API');
      }

      const parsedJson: unknown = JSON.parse(rawText);
      const check = this.validateCandidateExplanation(parsedJson, params.journey);

      if (!check.valid) {
        logger.warn('llm_explanation_rejected', {
          journeyId: params.journey.id,
          reason: check.reason
        });
        const fallback = buildDeterministicExplanation(params.journey);
        return {
          ...fallback,
          hallucinationRejected: true,
          rejectionReason: check.reason
        };
      }

      return {
        summary: check.payload.summary,
        confidenceNote: check.payload.confidenceNote,
        referencedLegIds: check.payload.referencedLegIds,
        citedNumbers: check.payload.citedNumbers,
        generatedBy: 'gemini_verified',
        hallucinationRejected: false
      };
    } catch (err) {
      logger.warn('llm_explanation_fallback_used', {
        journeyId: params.journey.id,
        error: err instanceof Error ? err.message : String(err)
      });
      return buildDeterministicExplanation(params.journey);
    }
  }
}
