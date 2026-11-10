import { describeError, sanitize } from './mask';

/**
 * The frontend's only way to the console (ESLint `no-console` everywhere else). Each entry is a
 * short stable event name ("api.server_error") and a small context, masked before it is written
 * (mask.ts: no PAN, Aadhaar, account or mobile number, e-mail, name, amount or pay figure ever
 * reaches the console). Levels: debug for the normal flow while developing, info for a business
 * refusal, warn for a degraded state, error for an exception. Production writes warn and above
 * (NEXT_PUBLIC_LOG_LEVEL overrides); tests write nothing unless a test sets the level. A burst of
 * the same entry is written once per window and the repeats counted (no over-logging).
 */
export type LogLevel = 'debug' | 'info' | 'warn' | 'error' | 'silent';

const ORDER: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40, silent: 99 };
const REPEAT_WINDOW_MS = 5_000;
const MAX_TRACKED = 200;

function initialLevel(): LogLevel {
  const configured = process.env.NEXT_PUBLIC_LOG_LEVEL as LogLevel | undefined;
  if (configured && configured in ORDER) return configured;
  if (process.env.NODE_ENV === 'test') return 'silent';
  return process.env.NODE_ENV === 'production' ? 'warn' : 'debug';
}

let threshold: LogLevel = initialLevel();
const recent = new Map<string, { at: number; repeats: number }>();

/** The level from which entries are written (tests and the diagnostics toggle). */
export function setLogLevel(level: LogLevel): void {
  threshold = level;
}

export function logLevel(): LogLevel {
  return threshold;
}

/** Forgets the repeat window (tests). */
export function resetLogWindow(): void {
  recent.clear();
}

function write(level: Exclude<LogLevel, 'silent'>, event: string, context?: Record<string, unknown>) {
  if (ORDER[level] < ORDER[threshold]) return;
  const key = `${level}|${event}|${JSON.stringify(context?.errorCode ?? context?.status ?? '')}`;
  const now = Date.now();
  const seen = recent.get(key);
  if (seen && now - seen.at < REPEAT_WINDOW_MS) {
    seen.repeats++;
    return;
  }
  if (recent.size >= MAX_TRACKED) recent.clear();
  recent.set(key, { at: now, repeats: 0 });
  const safe = context === undefined ? {} : (sanitize(context) as Record<string, unknown>);
  if (seen?.repeats) safe.repeated = seen.repeats;
  // One line per entry, its context as JSON: readable in the console, searchable when collected.
  const line = `[FCI] ${event} ${JSON.stringify(safe)}`;
  // The one place that writes to the console (no-console is off for this file only).
  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else if (level === 'info') console.info(line);
  else console.debug(line);
}

export const log = {
  debug: (event: string, context?: Record<string, unknown>) => write('debug', event, context),
  info: (event: string, context?: Record<string, unknown>) => write('info', event, context),
  warn: (event: string, context?: Record<string, unknown>) => write('warn', event, context),
  error: (event: string, context?: Record<string, unknown>) => write('error', event, context),
  /** An exception, always at error: its kind, API problem fields and a masked message (stack outside production). */
  exception: (event: string, error: unknown, context?: Record<string, unknown>) =>
    write('error', event, {
      ...context,
      error: describeError(error, process.env.NODE_ENV !== 'production'),
    }),
};
