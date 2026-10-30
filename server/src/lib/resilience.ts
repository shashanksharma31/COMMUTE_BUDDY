import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { logger } from './logger.js';

export type CircuitState = 'closed' | 'open' | 'half_open';

export interface SourceConfig {
  tier: number;
  baseWeight: number;
  timeoutMs: number;
  maxRetries: number;
  failureThreshold: number;
  resetTimeoutMs: number;
  dailyQuotaLimit: number | null;
}

export interface SourceRuntimeStats {
  sourceName: string;
  tier: number;
  baseWeight: number;
  effectiveWeight: number;
  errorRate: number;
  successCount: number;
  errorCount: number;
  consecutiveFailures: number;
  circuitState: CircuitState;
  circuitOpenedAtMs: number | null;
  dailyQuotaUsed: number;
  dailyQuotaLimit: number | null;
  quotaDayKey: string;
  lastError: string | null;
  updatedAt: string;
}

interface SourcesConfigFile {
  sources: Record<string, SourceConfig>;
}

function loadDefaultSourcesConfig(): Record<string, SourceConfig> {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const candidatePaths = [
    path.resolve(currentDir, '../../config/sources.json'),
    path.resolve(process.cwd(), 'config/sources.json'),
    path.resolve(process.cwd(), 'server/config/sources.json')
  ];
  for (const p of candidatePaths) {
    if (fs.existsSync(p)) {
      const parsed = JSON.parse(fs.readFileSync(p, 'utf8')) as SourcesConfigFile;
      return parsed.sources;
    }
  }
  throw new Error('Could not locate server/config/sources.json');
}

function currentUtcDayKey(): string {
  return new Date().toISOString().slice(0, 10);
}

export class SourceQuotaExceededError extends Error {
  constructor(public readonly sourceName: string, public readonly limit: number) {
    super(`Daily quota limit (${limit}) reached for source "${sourceName}".`);
    this.name = 'SourceQuotaExceededError';
  }
}

export class SourceCircuitOpenError extends Error {
  constructor(public readonly sourceName: string) {
    super(`Circuit breaker is OPEN for source "${sourceName}".`);
    this.name = 'SourceCircuitOpenError';
  }
}

// Shared resilience manager for all external source calls:
// timeout, retry with exponential backoff, circuit breaker, quota tracking, and dynamic weight decay.
export class SourceResilienceManager {
  private readonly configs = new Map<string, SourceConfig>();
  private readonly stats = new Map<string, SourceRuntimeStats>();

  constructor(initialConfigs?: Record<string, SourceConfig>) {
    const loaded = initialConfigs ?? loadDefaultSourcesConfig();
    const dayKey = currentUtcDayKey();
    for (const [name, cfg] of Object.entries(loaded)) {
      this.configs.set(name, cfg);
      this.stats.set(name, {
        sourceName: name,
        tier: cfg.tier,
        baseWeight: cfg.baseWeight,
        effectiveWeight: cfg.baseWeight,
        errorRate: 0,
        successCount: 0,
        errorCount: 0,
        consecutiveFailures: 0,
        circuitState: 'closed',
        circuitOpenedAtMs: null,
        dailyQuotaUsed: 0,
        dailyQuotaLimit: cfg.dailyQuotaLimit,
        quotaDayKey: dayKey,
        lastError: null,
        updatedAt: new Date().toISOString()
      });
    }
  }

  getSourceStats(sourceName: string): SourceRuntimeStats {
    const st = this.stats.get(sourceName);
    if (!st) {
      throw new Error(`Unknown data source "${sourceName}". Register it in config/sources.json.`);
    }
    this.refreshCircuitAndQuotaWindow(sourceName, st);
    return { ...st };
  }

  getAllSourceStats(): SourceRuntimeStats[] {
    return Array.from(this.stats.keys()).map((name) => this.getSourceStats(name));
  }

  getEffectiveWeight(sourceName: string): number {
    return this.getSourceStats(sourceName).effectiveWeight;
  }

  // Executes an external call with quota check, circuit breaker, timeout, and exponential backoff retry.
  async execute<T>(
    sourceName: string,
    operation: (signal: AbortSignal) => Promise<T>,
    options?: { retryDelayBaseMs?: number }
  ): Promise<T> {
    const cfg = this.configs.get(sourceName);
    const st = this.stats.get(sourceName);
    if (!cfg || !st) {
      throw new Error(`Unknown data source "${sourceName}". Register it in config/sources.json.`);
    }

    this.refreshCircuitAndQuotaWindow(sourceName, st);

    if (st.circuitState === 'open') {
      throw new SourceCircuitOpenError(sourceName);
    }

    if (st.dailyQuotaLimit !== null && st.dailyQuotaUsed >= st.dailyQuotaLimit) {
      throw new SourceQuotaExceededError(sourceName, st.dailyQuotaLimit);
    }

    const maxAttempts = Math.max(1, cfg.maxRetries + 1);
    const baseDelayMs = options?.retryDelayBaseMs ?? 120;
    let lastError: unknown = null;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      // Count each upstream request against daily quota
      if (st.dailyQuotaLimit !== null && st.dailyQuotaUsed >= st.dailyQuotaLimit) {
        this.recordFailure(st, cfg, new SourceQuotaExceededError(sourceName, st.dailyQuotaLimit));
        throw new SourceQuotaExceededError(sourceName, st.dailyQuotaLimit);
      }
      st.dailyQuotaUsed += 1;

      try {
        const result = await this.runWithTimeout(operation, cfg.timeoutMs);
        this.recordSuccess(st, cfg);
        return result;
      } catch (err) {
        lastError = err;
        if (attempt < maxAttempts) {
          const backoffMs = baseDelayMs * Math.pow(2, attempt - 1);
          await new Promise((res) => setTimeout(res, backoffMs));
        }
      }
    }

    this.recordFailure(st, cfg, lastError);
    throw lastError instanceof Error ? lastError : new Error(String(lastError));
  }

  private async runWithTimeout<T>(
    operation: (signal: AbortSignal) => Promise<T>,
    timeoutMs: number
  ): Promise<T> {
    const controller = new AbortController();
    let timer: NodeJS.Timeout | undefined;
    const timeoutPromise = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        controller.abort();
        reject(new Error(`External call timed out after ${timeoutMs}ms`));
      }, timeoutMs);
    });

    try {
      return await Promise.race([operation(controller.signal), timeoutPromise]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  private refreshCircuitAndQuotaWindow(sourceName: string, st: SourceRuntimeStats): void {
    const cfg = this.configs.get(sourceName);
    if (!cfg) return;

    const today = currentUtcDayKey();
    if (st.quotaDayKey !== today) {
      st.quotaDayKey = today;
      st.dailyQuotaUsed = 0;
    }

    if (
      st.circuitState === 'open' &&
      st.circuitOpenedAtMs !== null &&
      Date.now() - st.circuitOpenedAtMs >= cfg.resetTimeoutMs
    ) {
      st.circuitState = 'half_open';
      st.updatedAt = new Date().toISOString();
      logger.info('circuit_half_open', { sourceName });
    }
  }

  private recordSuccess(st: SourceRuntimeStats, cfg: SourceConfig): void {
    st.successCount += 1;
    st.consecutiveFailures = 0;
    if (st.circuitState !== 'closed') {
      logger.info('circuit_closed', { sourceName: st.sourceName });
    }
    st.circuitState = 'closed';
    st.circuitOpenedAtMs = null;
    this.recalculateWeight(st, cfg);
  }

  private recordFailure(st: SourceRuntimeStats, cfg: SourceConfig, err: unknown): void {
    st.errorCount += 1;
    st.consecutiveFailures += 1;
    st.lastError = err instanceof Error ? err.message : String(err);

    if (
      st.circuitState === 'half_open' ||
      st.consecutiveFailures >= cfg.failureThreshold
    ) {
      st.circuitState = 'open';
      st.circuitOpenedAtMs = Date.now();
      logger.warn('circuit_opened', {
        sourceName: st.sourceName,
        consecutiveFailures: st.consecutiveFailures,
        lastError: st.lastError
      });
    }

    this.recalculateWeight(st, cfg);
  }

  // Drops effective source weight automatically as measured error rate rises.
  private recalculateWeight(st: SourceRuntimeStats, cfg: SourceConfig): void {
    const total = st.successCount + st.errorCount;
    const errorRate = total === 0 ? 0 : st.errorCount / total;
    st.errorRate = Math.round(errorRate * 1000) / 1000;
    const penalized = cfg.baseWeight * (1 - errorRate * 0.75);
    st.effectiveWeight = Math.round(Math.max(0.05, penalized) * 1000) / 1000;
    st.updatedAt = new Date().toISOString();
  }
}

export const resilienceManager = new SourceResilienceManager();
