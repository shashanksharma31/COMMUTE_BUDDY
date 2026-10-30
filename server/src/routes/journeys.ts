import { Router } from 'express';
import { z } from 'zod';
import { fareStore, OperatorFareStore } from '../ingestion/fareStore.js';
import { gtfsStore, GtfsIngestionStore } from '../ingestion/gtfsFetcher.js';
import { loadAndRegisterRrtsYaml } from '../ingestion/rrtsLoader.js';
import {
  staticTrainScheduleStore,
  StaticTrainScheduleStore
} from '../ingestion/trainScheduleLoader.js';
import { MultiModalJourneyPlanner } from '../journeys/journeyPlanner.js';
import { rateCardCalculator, RoadRateCardCalculator } from '../journeys/rateCardCalculator.js';
import { ConflictLog, conflictLog as defaultConflictLog } from '../lib/conflictLog.js';
import {
  StationEntityResolver,
  stationResolver as defaultResolver
} from '../stations/resolver.js';
import { VerificationEngine } from '../verification/verificationEngine.js';

const coordinateSchema = z.object({
  name: z.string().trim().optional(),
  lat: z.number().min(6.0).max(38.0),
  lng: z.number().min(68.0).max(98.0),
  stationId: z.string().trim().optional()
});

const journeyRequestSchema = z.object({
  start: coordinateSchema,
  end: coordinateSchema,
  departureTime: z.string().trim().optional(),
  reachByTime: z.string().trim().optional(),
  dayType: z.enum(['weekday', 'saturday', 'sunday', 'holiday']).optional(),
  cityCode: z.string().trim().optional()
});

export function createJourneysRouter(deps?: {
  gtfs?: GtfsIngestionStore;
  fares?: OperatorFareStore;
  stationResolver?: StationEntityResolver;
  railStore?: StaticTrainScheduleStore;
  conflictLog?: ConflictLog;
  rates?: RoadRateCardCalculator;
  verificationReferenceDate?: Date;
}): Router {
  const router = Router();
  const activeGtfs = deps?.gtfs ?? gtfsStore;
  const activeFares = deps?.fares ?? fareStore;
  const activeResolver = deps?.stationResolver ?? defaultResolver;
  const activeRail = deps?.railStore ?? staticTrainScheduleStore;
  const activeConflicts = deps?.conflictLog ?? defaultConflictLog;
  const activeRates = deps?.rates ?? rateCardCalculator;

  loadAndRegisterRrtsYaml(activeGtfs, activeFares);

  const verifier = new VerificationEngine(activeResolver, activeGtfs, activeConflicts);
  const planner = new MultiModalJourneyPlanner(
    activeGtfs,
    activeFares,
    activeResolver,
    activeRail,
    verifier,
    activeRates
  );

  // POST /api/journeys — Multi-modal journey planner (Mix mode: walk, auto/e-rickshaw, metro, rrts, train)
  router.post('/journeys', (req, res) => {
    const parsed = journeyRequestSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid multi-modal journey request payload',
        issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`)
      });
      return;
    }

    const { start, end, departureTime, reachByTime, dayType, cityCode } = parsed.data;
    const response = planner.planJourneys({
      start,
      end,
      departureTime,
      reachByTime,
      dayType,
      cityCode,
      referenceDate: deps?.verificationReferenceDate
    });

    res.json(response);
  });

  return router;
}

export const journeysRouter = createJourneysRouter();
