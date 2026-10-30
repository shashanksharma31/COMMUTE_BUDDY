import { Router } from 'express';
import { z } from 'zod';
import { ConflictLog, conflictLog as defaultConflictLog } from '../lib/conflictLog.js';

export interface UserIssueReport {
  id: string;
  entityType: 'station' | 'fare' | 'schedule' | 'route';
  entityKey: string;
  issueType: 'wrong_fare' | 'wrong_timing' | 'closed_station' | 'other';
  reportedValue?: string | number;
  description: string;
  reporterId: string;
  createdAtMs: number;
}

export class IssueReportStore {
  private readonly reports: UserIssueReport[] = [];
  private readonly flaggedEntities = new Set<string>();

  constructor(
    private readonly conflicts: ConflictLog = defaultConflictLog,
    private readonly reverificationThreshold = 3,
    private readonly windowMs = 24 * 60 * 60 * 1000
  ) {}

  submitReport(
    input: Omit<UserIssueReport, 'id' | 'createdAtMs'>,
    nowMs = Date.now()
  ): {
    report: UserIssueReport;
    distinctReportersLast24h: number;
    flaggedForReverification: boolean;
  } {
    const report: UserIssueReport = {
      id: `rep_${nowMs}_${this.reports.length + 1}`,
      createdAtMs: nowMs,
      ...input
    };
    this.reports.push(report);

    const compositeKey = `${input.entityType}:${input.entityKey}`;
    const cutoff = nowMs - this.windowMs;
    const recentForEntity = this.reports.filter(
      (r) =>
        r.entityType === input.entityType &&
        r.entityKey === input.entityKey &&
        r.createdAtMs >= cutoff
    );

    const distinctReporters = new Set(recentForEntity.map((r) => r.reporterId)).size;
    let flagged = this.flaggedEntities.has(compositeKey);

    if (distinctReporters >= this.reverificationThreshold && !flagged) {
      flagged = true;
      this.flaggedEntities.add(compositeKey);
      this.conflicts.append({
        entityType: input.entityType,
        entityKey: input.entityKey,
        fieldName: input.issueType,
        sourceValues: recentForEntity.map((r) => ({
          source: `user_report:${r.reporterId}`,
          tier: 5,
          value: r.reportedValue ?? r.description
        })),
        winningSource: 'pending_reverification',
        finalValue: input.reportedValue ?? null,
        reason: `Flagged for re-verification after ${distinctReporters} independent user reports within 24h`
      });
    }

    return {
      report,
      distinctReportersLast24h: distinctReporters,
      flaggedForReverification: flagged
    };
  }

  getEntityStatus(
    entityType: string,
    entityKey: string,
    nowMs = Date.now()
  ): {
    entityType: string;
    entityKey: string;
    distinctReportersLast24h: number;
    flaggedForReverification: boolean;
  } {
    const compositeKey = `${entityType}:${entityKey}`;
    const cutoff = nowMs - this.windowMs;
    const recent = this.reports.filter(
      (r) =>
        r.entityType === entityType &&
        r.entityKey === entityKey &&
        r.createdAtMs >= cutoff
    );
    return {
      entityType,
      entityKey,
      distinctReportersLast24h: new Set(recent.map((r) => r.reporterId)).size,
      flaggedForReverification: this.flaggedEntities.has(compositeKey)
    };
  }
}

const issueReportSchema = z.object({
  entityType: z.enum(['station', 'fare', 'schedule', 'route']),
  entityKey: z.string().trim().min(1).max(160),
  issueType: z.enum(['wrong_fare', 'wrong_timing', 'closed_station', 'other']),
  reportedValue: z.union([z.string().trim().max(120), z.number()]).optional(),
  description: z.string().trim().min(3).max(500),
  reporterId: z.string().trim().min(2).max(120)
});

export function createReportsRouter(deps?: {
  conflictLog?: ConflictLog;
  reportStore?: IssueReportStore;
}): Router {
  const router = Router();
  const store =
    deps?.reportStore ?? new IssueReportStore(deps?.conflictLog ?? defaultConflictLog);

  // POST /api/reports/issue — Submit a user discrepancy report; >= 3 distinct reports in 24h flags entity for re-verification
  router.post('/reports/issue', (req, res) => {
    const parsed = issueReportSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid issue report payload',
        issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`)
      });
      return;
    }

    const result = store.submitReport(parsed.data);
    res.status(201).json({
      status: result.flaggedForReverification ? 'needs_reverification' : 'recorded',
      ...result
    });
  });

  // GET /api/reports/status?entityType=&entityKey=
  router.get('/reports/status', (req, res) => {
    const entityType = String(req.query.entityType ?? '').trim();
    const entityKey = String(req.query.entityKey ?? '').trim();
    if (!entityType || !entityKey) {
      res.status(400).json({
        error: 'Query parameters entityType and entityKey are required'
      });
      return;
    }
    res.json(store.getEntityStatus(entityType, entityKey));
  });

  return router;
}
