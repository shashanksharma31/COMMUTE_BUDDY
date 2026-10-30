import { Router } from 'express';
import { z } from 'zod';
import { AppEnv, env as defaultEnv, MissingEnvKeyError } from '../env.js';
import { fareStore, OperatorFareStore } from '../ingestion/fareStore.js';
import { gtfsStore, GtfsIngestionStore } from '../ingestion/gtfsFetcher.js';
import {
  staticTrainScheduleStore,
  StaticTrainScheduleStore
} from '../ingestion/trainScheduleLoader.js';
import { sharedCache, TtlCache } from '../lib/cache.js';
import { ConflictLog, conflictLog as defaultConflictLog } from '../lib/conflictLog.js';
import { resilienceManager, SourceResilienceManager } from '../lib/resilience.js';
import { rateCardCalculator } from '../journeys/rateCardCalculator.js';
import { LiveTrafficProvider } from '../providers/road/liveTrafficProvider.js';
import { OpenRouteServiceProvider } from '../providers/road/orsProvider.js';
import {
  CrossCheckedTrainService,
  LicensedHttpTrainAdapter
} from '../providers/train/licensedTrainAdapter.js';
import { GoogleRoutesTransitAdapter } from '../providers/transit/googleRoutesAdapter.js';
import { GtfsLocalTransitAdapter } from '../providers/transit/gtfsGraphPlanner.js';
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

const routeRequestSchema = z.object({
  start: coordinateSchema,
  end: coordinateSchema,
  mode: z.enum(['car', 'bike', 'walk', 'metro', 'rrts', 'train', 'bus']),
  departureTime: z.string().trim().optional(),
  reachByTime: z.string().trim().optional()
});

export function createRoutingRouter(deps?: {
  appEnv?: AppEnv;
  resilience?: SourceResilienceManager;
  cache?: TtlCache;
  fetchFn?: typeof fetch;
  gtfs?: GtfsIngestionStore;
  fares?: OperatorFareStore;
  stationResolver?: StationEntityResolver;
  railStore?: StaticTrainScheduleStore;
  conflictLog?: ConflictLog;
  verificationReferenceDate?: Date;
}): Router {
  const router = Router();
  const activeEnv = deps?.appEnv ?? defaultEnv;
  const activeResilience = deps?.resilience ?? resilienceManager;
  const activeCache = deps?.cache ?? sharedCache;
  const activeGtfs = deps?.gtfs ?? gtfsStore;
  const activeFares = deps?.fares ?? fareStore;
  const activeResolver = deps?.stationResolver ?? defaultResolver;
  const activeRail = deps?.railStore ?? staticTrainScheduleStore;
  const activeConflicts = deps?.conflictLog ?? defaultConflictLog;

  const verifier = new VerificationEngine(activeResolver, activeGtfs, activeConflicts);

  const ors = new OpenRouteServiceProvider(
    activeEnv,
    activeResilience,
    activeCache,
    deps?.fetchFn
  );
  const liveTraffic = new LiveTrafficProvider(
    activeEnv,
    activeResilience,
    activeCache,
    deps?.fetchFn
  );
  const primaryTrainAdapter = new LicensedHttpTrainAdapter(
    'primary',
    activeEnv,
    activeResilience,
    deps?.fetchFn
  );
  const secondaryTrainAdapter = new LicensedHttpTrainAdapter(
    'secondary',
    activeEnv,
    activeResilience,
    deps?.fetchFn
  );
  const trainLiveService = new CrossCheckedTrainService(
    primaryTrainAdapter,
    secondaryTrainAdapter,
    activeResilience,
    activeCache,
    activeConflicts
  );

  const gtfsPlanner = new GtfsLocalTransitAdapter(
    activeGtfs,
    activeFares,
    activeResolver,
    activeRail
  );
  const googleAdapter = new GoogleRoutesTransitAdapter(
    activeEnv,
    activeResilience,
    activeCache,
    deps?.fetchFn
  );

  // POST /api/routes — Single-mode routing (all durations in seconds, cross-verified via VerificationEngine)
  router.post('/routes', async (req, res) => {
    const parsed = routeRequestSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid route request payload',
        issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`)
      });
      return;
    }

    const { start, end, mode, departureTime, reachByTime } = parsed.data;
    const refDate = deps?.verificationReferenceDate;

    try {
      // 1. Road modes: Car, Bike, Walk via OpenRouteService (+ TomTom/HERE live traffic for Car)
      if (mode === 'car' || mode === 'bike' || mode === 'walk') {
        const roadRes = await ors.planRoadRoute({ start, end, mode });

        if (mode === 'car' && roadRes.routes.length > 0 && liveTraffic.isConfigured()) {
          try {
            const trafficInfo = await liveTraffic.fetchCarTrafficDurations({ start, end });
            roadRes.routes = roadRes.routes.map((opt) => {
              const surgedFare = rateCardCalculator.calculateEstimatedFare({
                mode: 'car',
                distanceMeters: opt.distanceMeters,
                durationSeconds:
                  trafficInfo.liveTrafficDurationSeconds.value ??
                  (opt.durationSeconds.value ?? 0),
                trafficSurgeMultiplier: trafficInfo.trafficSurgeMultiplier
              });

              const verifiedOpt = verifier.verifyRouteOption({
                option: {
                  ...opt,
                  durationSeconds: trafficInfo.liveTrafficDurationSeconds,
                  noTrafficDurationSeconds: trafficInfo.noTrafficDurationSeconds,
                  trafficDelaySeconds: trafficInfo.trafficDelaySeconds,
                  fare: surgedFare
                },
                departureTime,
                secondaryDurationSeconds: opt.durationSeconds.value,
                secondarySource: 'openrouteservice',
                hasRealtimeFeed: true,
                referenceDate: refDate
              });

              return verifiedOpt;
            });
            res.json(roadRes);
            return;
          } catch {
            // Never fail open: keep ORS estimate and record that live traffic failed
          }
        }

        roadRes.routes = roadRes.routes.map((opt) =>
          verifier.verifyRouteOption({
            option: opt,
            departureTime,
            referenceDate: refDate
          })
        );
        res.json(roadRes);
        return;
      }

      // 2. Train mode: Static schedule + Cross-checked Licensed Live Train Adapter
      if (mode === 'train') {
        const trainRes = gtfsPlanner.planStaticTrain({ start, end });
        const enrichedRoutes = await Promise.all(
          trainRes.routes.map(async (opt) => {
            const step = opt.steps[0];
            const trainNumber = step?.trainNumber;
            const fromStn = step?.fromStationId
              ? activeResolver.getStationById(step.fromStationId)
              : null;
            const toStn = step?.toStationId
              ? activeResolver.getStationById(step.toStationId)
              : null;

            const verifiedOpt = verifier.verifyRouteOption({
              option: opt,
              departureTime,
              referenceDate: refDate
            });

            if (trainNumber && fromStn?.irStationCode && toStn?.irStationCode) {
              const liveStatus = await trainLiveService.getVerifiedLiveTrainStatus({
                trainNumber,
                fromStationCode: fromStn.irStationCode,
                toStationCode: toStn.irStationCode
              });
              return {
                ...verifiedOpt,
                trainLiveStatus: liveStatus
              };
            }

            return verifiedOpt;
          })
        );

        trainRes.routes = enrichedRoutes;
        res.json(trainRes);
        return;
      }

      // 3. Bus mode: Google Routes transit where available, else explicit "no verified bus data for this area"
      if (mode === 'bus') {
        const busRes = await googleAdapter.planTransit({
          start,
          end,
          mode: 'bus',
          departureTime,
          reachByTime
        });
        busRes.routes = busRes.routes.map((opt) =>
          verifier.verifyRouteOption({
            option: opt,
            departureTime,
            referenceDate: refDate
          })
        );
        res.json(busRes);
        return;
      }

      // 4. Metro & RRTS: Swappable TRANSIT_PROVIDER (gtfs_local primary, Google Routes cross-check or fallback)
      if (activeEnv.TRANSIT_PROVIDER === 'google_routes') {
        const googlePrimary = await googleAdapter.planTransit({
          start,
          end,
          mode,
          departureTime,
          reachByTime
        });
        googlePrimary.routes = googlePrimary.routes.map((opt) =>
          verifier.verifyRouteOption({
            option: opt,
            departureTime,
            referenceDate: refDate
          })
        );
        res.json(googlePrimary);
        return;
      }

      const primaryTransit = await gtfsPlanner.planTransit({
        start,
        end,
        mode,
        departureTime,
        reachByTime
      });

      if (primaryTransit.status === 'ok' && primaryTransit.routes.length > 0) {
        let secondaryDurationSeconds: number | null | undefined;
        let secondaryFareInr: number | null | undefined;

        if (activeEnv.GOOGLE_ROUTES_API_KEY.trim().length > 0) {
          try {
            const secondaryRes = await googleAdapter.planTransit({
              start,
              end,
              mode,
              departureTime,
              reachByTime
            });
            if (secondaryRes.status === 'ok' && secondaryRes.routes.length > 0) {
              secondaryDurationSeconds = secondaryRes.routes[0].durationSeconds.value;
              secondaryFareInr = secondaryRes.routes[0].fare.value?.fareInr;
            }
          } catch {
            // Never fail open silently: if secondary cross-check fails, primary still runs through verifier without secondary boost
            secondaryDurationSeconds = undefined;
          }
        }

        primaryTransit.routes = primaryTransit.routes.map((opt) =>
          verifier.verifyRouteOption({
            option: opt,
            departureTime,
            secondaryDurationSeconds,
            secondaryFareInr,
            secondarySource: 'google_routes',
            referenceDate: refDate
          })
        );

        const allOutsideHours = primaryTransit.routes.every(
          (r) => r.verification && !r.verification.operatingHoursCheck.withinOperatingHours
        );
        if (allOutsideHours) {
          primaryTransit.message =
            'Requested departure time is outside transit operating hours';
        }

        res.json(primaryTransit);
        return;
      }

      if (
        primaryTransit.status === 'no_data' &&
        activeEnv.GOOGLE_ROUTES_API_KEY.trim().length > 0
      ) {
        const fallback = await googleAdapter.planTransit({
          start,
          end,
          mode,
          departureTime,
          reachByTime
        });
        if (fallback.status === 'ok') {
          fallback.routes = fallback.routes.map((opt) =>
            verifier.verifyRouteOption({
              option: opt,
              departureTime,
              referenceDate: refDate
            })
          );
          res.json(fallback);
          return;
        }
      }

      res.json(primaryTransit);
    } catch (err) {
      if (err instanceof MissingEnvKeyError) {
        res.status(503).json({
          status: 'error',
          error: err.message,
          missingKey: err.keyName,
          mode,
          routes: []
        });
        return;
      }
      res.status(502).json({
        status: 'error',
        error: err instanceof Error ? err.message : 'Routing failed',
        mode,
        routes: []
      });
    }
  });

  return router;
}

export const routingRouter = createRoutingRouter();
