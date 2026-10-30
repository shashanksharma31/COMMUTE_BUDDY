import cors from 'cors';
import express, { Express } from 'express';
import rateLimit from 'express-rate-limit';
import { AppEnv, env as defaultEnv } from './env.js';
import { OperatorFareStore } from './ingestion/fareStore.js';
import { GtfsIngestionStore } from './ingestion/gtfsFetcher.js';
import { StaticTrainScheduleStore } from './ingestion/trainScheduleLoader.js';
import { TtlCache } from './lib/cache.js';
import { ConflictLog } from './lib/conflictLog.js';
import { SourceResilienceManager } from './lib/resilience.js';
import { createExplainRouter } from './routes/explain.js';
import { healthRouter } from './routes/health.js';
import { createJourneysRouter } from './routes/journeys.js';
import { createPlacesRouter } from './routes/places.js';
import { createReportsRouter, IssueReportStore } from './routes/reports.js';
import { createRoutingRouter } from './routes/routing.js';
import { createStationsRouter } from './routes/stations.js';
import { createWeatherRouter } from './routes/weather.js';
import { StationEntityResolver } from './stations/resolver.js';

export function createApp(deps?: {
  appEnv?: AppEnv;
  resilience?: SourceResilienceManager;
  cache?: TtlCache;
  fetchFn?: typeof fetch;
  nominatimMinIntervalMs?: number;
  stationResolver?: StationEntityResolver;
  gtfs?: GtfsIngestionStore;
  fares?: OperatorFareStore;
  railStore?: StaticTrainScheduleStore;
  conflictLog?: ConflictLog;
  reportStore?: IssueReportStore;
  verificationReferenceDate?: Date;
}): Express {
  const app = express();
  const activeEnv = deps?.appEnv ?? defaultEnv;

  const allowedOrigins = activeEnv.FRONTEND_ORIGIN.split(',').map((o) => o.trim());
  app.use(
    cors({
      origin: (origin, callback) => {
        if (!origin || allowedOrigins.includes(origin) || allowedOrigins.includes('*')) {
          callback(null, true);
        } else {
          callback(new Error(`Origin ${origin} not allowed by CORS`));
        }
      },
      methods: ['GET', 'POST', 'OPTIONS']
    })
  );

  app.use(express.json({ limit: '256kb' }));

  const limiter = rateLimit({
    windowMs: 60 * 1000,
    limit: 120,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    message: {
      error: 'Too many requests, please try again in a minute.'
    }
  });
  app.use('/api', limiter);

  app.use('/api', healthRouter);
  app.use('/api', createPlacesRouter(deps));
  app.use('/api', createStationsRouter(deps?.stationResolver));
  app.use('/api', createRoutingRouter(deps));
  app.use('/api', createJourneysRouter(deps));
  app.use('/api', createWeatherRouter(deps));
  app.use('/api', createExplainRouter(deps));
  app.use('/api', createReportsRouter(deps));

  return app;
}
