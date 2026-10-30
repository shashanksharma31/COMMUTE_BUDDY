import fs from 'node:fs';
import path from 'node:path';
import { logger } from './logger.js';

export interface ConflictSourceEntry {
  source: string;
  tier: number;
  value: unknown;
}

export interface ConflictRecord {
  id: string;
  occurredAt: string;
  entityType: string;
  entityKey: string;
  fieldName: string;
  sourceValues: ConflictSourceEntry[];
  winningSource: string;
  finalValue: unknown;
  reason: string;
}

// Append-only conflict log recording every cross-source disagreement and the hierarchy winner.
export class ConflictLog {
  private readonly records: ConflictRecord[] = [];
  private readonly logFilePath: string | null;

  constructor(logFilePath?: string | null) {
    this.logFilePath = logFilePath ?? null;
  }

  append(entry: Omit<ConflictRecord, 'id' | 'occurredAt'>): ConflictRecord {
    const record: ConflictRecord = {
      id: `conflict_${Date.now()}_${this.records.length + 1}`,
      occurredAt: new Date().toISOString(),
      ...entry
    };
    this.records.push(record);

    if (this.logFilePath) {
      try {
        const dir = path.dirname(this.logFilePath);
        fs.mkdirSync(dir, { recursive: true });
        fs.appendFileSync(this.logFilePath, JSON.stringify(record) + '\n', 'utf8');
      } catch (err) {
        logger.warn('conflict_log_write_failed', {
          error: err instanceof Error ? err.message : String(err)
        });
      }
    }

    logger.info('source_conflict_recorded', {
      entityType: record.entityType,
      entityKey: record.entityKey,
      fieldName: record.fieldName,
      winningSource: record.winningSource,
      reason: record.reason
    });

    return record;
  }

  getAll(): ReadonlyArray<ConflictRecord> {
    return this.records;
  }
}

export const conflictLog = new ConflictLog(
  process.env.NODE_ENV === 'test'
    ? null
    : path.resolve(process.cwd(), 'logs', 'conflicts.jsonl')
);
