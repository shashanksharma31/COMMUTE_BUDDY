import { Router } from 'express';
import { conflictLog } from '../lib/conflictLog.js';
import { resilienceManager } from '../lib/resilience.js';

export const healthRouter = Router();

// GET /api/health — overall server health & uptime
healthRouter.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    service: 'commutebuddy-server',
    timestamp: new Date().toISOString(),
    uptimeSeconds: Math.round(process.uptime())
  });
});

// GET /api/sources/health — per-source circuit state, quota usage, error rate, and effective weight
healthRouter.get('/sources/health', (_req, res) => {
  const sources = resilienceManager.getAllSourceStats();
  const recentConflicts = conflictLog.getAll().slice(-20);
  res.json({
    status: 'ok',
    as_of: new Date().toISOString(),
    sources,
    conflictCount: conflictLog.getAll().length,
    recentConflicts
  });
});
