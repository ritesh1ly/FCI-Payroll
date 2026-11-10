import { afterEach, beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest';
import { api } from '@/shared/api';
import {
  log,
  logLevel,
  maskPath,
  maskText,
  REDACTED,
  resetLogWindow,
  sanitize,
  setLogLevel,
} from '@/shared/log';
import { sensitiveIn } from '../../e2e/support/console-guard';
import { backend, problem } from './support/harness';

/**
 * Frontend logging (owner 2026-10-25; R-PII, BR-SEC-001, BR-SEC-002, SRS-7-23): every exception
 * logged, nothing personal or financial in the console, no over-logging.
 */

type Spy = MockInstance<(...args: unknown[]) => void>;
let spies: Record<'debug' | 'info' | 'warn' | 'error', Spy>;

beforeEach(() => {
  resetLogWindow();
  setLogLevel('debug');
  spies = {
    debug: vi.spyOn(console, 'debug').mockImplementation(() => undefined),
    info: vi.spyOn(console, 'info').mockImplementation(() => undefined),
    warn: vi.spyOn(console, 'warn').mockImplementation(() => undefined),
    error: vi.spyOn(console, 'error').mockImplementation(() => undefined),
  };
});

afterEach(() => {
  setLogLevel('silent');
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

const written = (level: keyof typeof spies) => spies[level].mock.calls.map((c) => String(c[0]));

describe('masking', () => {
  it('R_PII_identifiersInTextAreMasked', () => {
    expect(maskText('PAN KLKPK7001A')).toBe('PAN XXXXXX001A');
    expect(maskText('aadhaar 1234 5678 9012')).toBe('aadhaar XXXXXXXX9012');
    expect(maskText('a/c 390000870001 mobile 9390070001')).toBe('a/c XXXXXXXX0001 mobile XXXXXX0001');
    expect(maskText('mail asha.kulkarni.870001@fci-demo.test now')).toBe('mail [email] now');
    // Short numbers (an EIS, a year, an amount without context) stay readable.
    expect(maskText('EIS 870001 in 2026')).toBe('EIS 870001 in 2026');
    expect(maskText('x'.repeat(301))).toMatch(/…\(301 chars\)$/);
  });

  it('R_PII_sensitiveKeysAreRedactedWhateverTheValue', () => {
    expect(
      sanitize({
        panNumber: 'KLKPK7001A',
        name: 'Asha Kulkarni',
        grossSalary: 120000,
        tdsDeducted: 8000,
        otp: { code: '123456' },
        bankAccountNumber: '39000870001',
        registeredMobile: '9390070001',
        status: 404,
        errorCode: 'COM-404-NOT-FOUND',
        correlationId: 'c-1',
        path: '/v1/tax/forecasts/870001?finYear=2026-27',
        kind: 'ApiError',
      }),
    ).toEqual({
      panNumber: REDACTED,
      name: REDACTED,
      grossSalary: REDACTED,
      tdsDeducted: REDACTED,
      otp: REDACTED,
      bankAccountNumber: REDACTED,
      registeredMobile: REDACTED,
      status: 404,
      errorCode: 'COM-404-NOT-FOUND',
      correlationId: 'c-1',
      path: '/v1/tax/forecasts/:id?…',
      kind: 'ApiError',
    });
  });

  it('R_PII_pathsKeepTheirShapeWithoutIdentifiers', () => {
    expect(maskPath('/v1/tax/returns/21/documents/9')).toBe('/v1/tax/returns/21/documents/9');
    expect(maskPath('/v1/tax/returns/212/documents/987')).toBe('/v1/tax/returns/:id/documents/:id');
    expect(maskPath('/v1/tax/savings/members/870003/entries')).toBe('/v1/tax/savings/members/:id/entries');
    expect(maskPath('/v1/tax/form16-certificates/mine')).toBe('/v1/tax/form16-certificates/mine');
    expect(maskPath('/v1/tax/challans?finYear=2026-27')).toBe('/v1/tax/challans?…');
  });

  it('R_PII_sizesAndDepthAreCapped', () => {
    const big = Array.from({ length: 15 }, (_, i) => i);
    expect(sanitize(big)).toEqual([...big.slice(0, 10), '…(15 items)']);
    expect(sanitize({ a: { b: { c: { d: 1 } } } })).toEqual({ a: { b: { c: '[…]' } } });
    const wide = Object.fromEntries(Array.from({ length: 25 }, (_, i) => [`k${i}`, i]));
    expect(Object.keys(sanitize(wide) as object)).toHaveLength(21);
    expect(sanitize(new FormData())).toBe('[form data]');
    expect(sanitize(new Blob(['abc']))).toBe('[file 3 bytes]');
    expect(sanitize(() => 1)).toBe('[function]');
    expect(sanitize(10n)).toBe('10');
    expect(sanitize(null)).toBeNull();
    expect(sanitize(true)).toBe(true);
  });
});

describe('logger', () => {
  it('OWASP_A09_anExceptionIsAlwaysLoggedWithItsProblemFieldsAndNoPii', () => {
    setLogLevel('error');
    const e = Object.assign(new Error('PAN KLKPK7001A refused'), {
      status: 422,
      code: 'TAX-422-ENTRY-KIND',
      messageKey: 'error.TAX-422-ENTRY-KIND',
      correlationId: 'c-2',
    });
    log.exception('ui.save_failed', e, { screen: 'I-18', grossSalary: 1 });
    const [line] = written('error');
    expect(line).toContain('[FCI] ui.save_failed');
    expect(line).toContain('"errorCode":"TAX-422-ENTRY-KIND"');
    expect(line).toContain('"correlationId":"c-2"');
    expect(line).toContain('XXXXXX001A');
    expect(line).not.toContain('KLKPK7001A');
    expect(line).toContain(`"grossSalary":"${REDACTED}"`);
    expect(sensitiveIn(line ?? '')).toBeNull();
    log.exception('ui.odd', 'just text 9390070001');
    expect(written('error')[1]).toContain('XXXXXX0001');
    log.exception('ui.odd2', { panNumber: 'KLKPK7001A' });
    expect(written('error')[2]).not.toContain('KLKPK7001A');
  });

  it('OWASP_A09_theLevelDecidesWhatIsWrittenAndABurstIsWrittenOnce', () => {
    setLogLevel('warn');
    expect(logLevel()).toBe('warn');
    log.debug('a');
    log.info('b');
    log.warn('c');
    expect(written('debug')).toHaveLength(0);
    expect(written('info')).toHaveLength(0);
    expect(written('warn')).toHaveLength(1);
    for (let i = 0; i < 5; i++) log.warn('c');
    expect(written('warn')).toHaveLength(1);
    // The same event with another error code is another entry.
    log.warn('c', { errorCode: 'X' });
    expect(written('warn')).toHaveLength(2);
    setLogLevel('silent');
    log.error('d');
    expect(written('error')).toHaveLength(0);
  });

  it('OWASP_A09_aBurstReportsItsRepeatsWhenTheWindowHasPassed', () => {
    vi.useFakeTimers();
    try {
      log.info('burst');
      log.info('burst');
      log.info('burst');
      vi.advanceTimersByTime(5_001);
      log.info('burst');
      expect(written('info')).toHaveLength(2);
      expect(written('info')[1]).toContain('"repeated":2');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('API client logging', () => {
  it('OWASP_A09_aServerErrorAndANetworkFailureAreErrorsARefusalIsInfo', async () => {
    backend({
      'GET /v1/tax/forecasts/870001': () => ({ status: 500, body: { errorCode: 'COM-500-INTERNAL' } }),
      'GET /v1/tax/forecasts/870004': () => problem(404, 'COM-404-NOT-FOUND'),
      'GET /v1/tax/outputs-ok': () => [],
    });
    await expect(api('/v1/tax/forecasts/870001')).rejects.toThrow();
    await expect(api('/v1/tax/forecasts/870004')).rejects.toThrow();
    await api('/v1/tax/outputs-ok');
    expect(written('error')[0]).toContain('api.server_error');
    expect(written('error')[0]).toContain('/v1/tax/forecasts/:id');
    expect(written('error')[0]).not.toContain('870001');
    expect(written('info')[0]).toContain('api.refused');
    expect(written('info')[0]).toContain('COM-404-NOT-FOUND');
    expect(written('debug').some((l) => l.includes('api.ok'))).toBe(true);
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))),
    );
    await expect(api('/v1/tax/forecasts/870002')).rejects.toThrow();
    expect(written('error').some((l) => l.includes('api.network_error'))).toBe(true);
  });

  it('OWASP_A09_anUnauthenticatedAnswerIsDebugOnly', async () => {
    backend({ 'GET /v1/me': () => ({ status: 401, body: { errorCode: 'AUTH-401-SESSION-EXPIRED' } }) });
    await expect(api('/v1/me', { silent: true })).rejects.toThrow();
    expect(written('debug').some((l) => l.includes('api.unauthenticated'))).toBe(true);
    expect(written('error')).toHaveLength(0);
  });
});

describe('E2E console guard', () => {
  it('R_PII_theGuardFindsWhatMustNotReachTheConsole', () => {
    expect(sensitiveIn('[FCI] api.ok {"path":"/v1/tax/forecasts/:id"}')).toBeNull();
    expect(sensitiveIn('PAN KLKPK7001A')).toBe('a PAN');
    expect(sensitiveIn('acct 390000870001')).toBe('an Aadhaar, account or mobile number');
    expect(sensitiveIn('1234 5678 9012')).toBe('an Aadhaar number');
    expect(sensitiveIn('mail a.b@fci-demo.test')).toBe('an e-mail address');
    expect(sensitiveIn('paid ₹1,50,000.00')).toBe('a rupee amount');
    expect(sensitiveIn('password Fci@Demo2026')).toBe('the demo password');
  });
});

describe('the starting log level and the burst window', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  async function freshLevel(): Promise<string> {
    vi.resetModules();
    const fresh = await import('@/shared/log/logger');
    return fresh.logLevel();
  }

  it('the configured level wins; else silent in tests, warn in production and debug in development', async () => {
    vi.stubEnv('NEXT_PUBLIC_LOG_LEVEL', 'info');
    expect(await freshLevel()).toBe('info');
    vi.stubEnv('NEXT_PUBLIC_LOG_LEVEL', 'loud');
    vi.stubEnv('NODE_ENV', 'test');
    expect(await freshLevel()).toBe('silent');
    vi.stubEnv('NODE_ENV', 'production');
    expect(await freshLevel()).toBe('warn');
    vi.stubEnv('NODE_ENV', 'development');
    expect(await freshLevel()).toBe('debug');
  });

  it('after 200 distinct entries the window starts again, so a repeat is written', () => {
    log.warn('first', { status: 1 });
    for (let i = 0; i < 200; i++) log.warn(`event.${i}`, { status: i });
    spies.warn.mockClear();
    log.warn('first', { status: 1 });
    expect(spies.warn).toHaveBeenCalledTimes(1);
  });
});
