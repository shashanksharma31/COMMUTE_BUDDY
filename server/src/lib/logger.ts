export type LogLevel = 'info' | 'warn' | 'error';

export interface LogEntry {
  timestamp: string;
  level: LogLevel;
  event: string;
  details?: Record<string, unknown>;
}

// Structured JSON logger for external calls, circuit state transitions, and conflicts.
export const logger = {
  log(level: LogLevel, event: string, details?: Record<string, unknown>): LogEntry {
    const entry: LogEntry = {
      timestamp: new Date().toISOString(),
      level,
      event,
      ...(details ? { details } : {})
    };
    if (process.env.NODE_ENV !== 'test') {
      const serialized = JSON.stringify(entry);
      if (level === 'error') {
        console.error(serialized);
      } else if (level === 'warn') {
        console.warn(serialized);
      } else {
        console.log(serialized);
      }
    }
    return entry;
  },

  info(event: string, details?: Record<string, unknown>): LogEntry {
    return this.log('info', event, details);
  },

  warn(event: string, details?: Record<string, unknown>): LogEntry {
    return this.log('warn', event, details);
  },

  error(event: string, details?: Record<string, unknown>): LogEntry {
    return this.log('error', event, details);
  }
};
