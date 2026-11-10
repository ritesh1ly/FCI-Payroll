import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { checkBackendUrl } from '@/instrumentation';
import {
  BackendUrlMissing,
  cleanAddress,
  parseEnvFile,
  resolveBackendUrl,
  webEnvFiles,
} from '@/shared/server/backendUrl';

/**
 * The address of the online backend is a per-environment setting outside the release (local, dev,
 * uat, prod differ in host, port and context path): the variable first, then a web.env file in the
 * environment's config folder. A deployment therefore needs no edit. In production nothing configured is an
 * error (OD-158, fail fast): the server refuses to start rather than guess an address.
 */

const PROD = { NODE_ENV: 'production' };

function files(map: Record<string, string>) {
  const asked: string[] = [];
  const read = (file: string) => {
    asked.push(file);
    if (!(file in map)) throw new Error('ENOENT');
    return map[file] as string;
  };
  return { read, asked };
}

describe('OPS backend address: where the web server looks', () => {
  it('lists the candidate files in order, without blanks or duplicates', () => {
    expect(webEnvFiles({})).toEqual(['/etc/fci/web.env', '/data/fci/frontend/web.env', '/data/fci/web.env']);
    expect(webEnvFiles({ FCI_WEB_ENV: ' /srv/x/web.env ', FCI_CONFIG_DIR: '/data/fci//' })).toEqual([
      '/srv/x/web.env',
      '/data/fci/web.env',
      '/etc/fci/web.env',
      '/data/fci/frontend/web.env',
    ]);
    expect(webEnvFiles({ FCI_WEB_ENV: '  ', FCI_CONFIG_DIR: '  ' })).toEqual([
      '/etc/fci/web.env',
      '/data/fci/frontend/web.env',
      '/data/fci/web.env',
    ]);
  });

  it('OPS: reads the DEV layout /data/fci/frontend/web.env when /etc/fci has none', () => {
    const f = files({ '/data/fci/frontend/web.env': 'BACKEND_URL=http://127.0.0.1:8643/fci-payroll\n' });
    expect(resolveBackendUrl(PROD, f.read)).toBe('http://127.0.0.1:8643/fci-payroll');
    expect(f.asked).toEqual(['/etc/fci/web.env', '/data/fci/frontend/web.env']);
  });

  it('parses KEY=VALUE lines, comments, quotes, CRLF and ignores malformed lines', () => {
    const parsed = parseEnvFile(
      [
        '# the backend of this environment',
        '',
        'BACKEND_URL="http://127.0.0.1:8643/fci-payroll"',
        "OTHER='a b'",
        'NOEQUALS',
        '=nokey',
        'SPACED = value with = sign  ',
        'EMPTY=',
      ].join('\r\n'),
    );
    expect(parsed).toEqual({
      BACKEND_URL: 'http://127.0.0.1:8643/fci-payroll',
      OTHER: 'a b',
      SPACED: 'value with = sign',
      EMPTY: '',
    });
    expect(parseEnvFile('')).toEqual({});
  });

  it('accepts only an http(s) address and drops the trailing slash', () => {
    expect(cleanAddress('http://127.0.0.1:8643/fci-payroll/')).toBe('http://127.0.0.1:8643/fci-payroll');
    expect(cleanAddress(' https://api.fci.example:8443 ')).toBe('https://api.fci.example:8443');
    expect(cleanAddress('HTTP://HOST/x')).toBe('HTTP://HOST/x');
    for (const bad of [
      undefined,
      '',
      '   ',
      'localhost:8080',
      'ftp://h/x',
      'http://',
      'http:// host',
      '/api',
    ]) {
      expect(cleanAddress(bad)).toBeNull();
    }
  });
});

describe('OPS backend address: which one wins', () => {
  it('uses the BACKEND_URL variable first, and never reads a file for it', () => {
    const f = files({ '/etc/fci/web.env': 'BACKEND_URL=http://file:1/x' });
    expect(resolveBackendUrl({ ...PROD, BACKEND_URL: 'http://127.0.0.1:8643/fci-payroll/' }, f.read)).toBe(
      'http://127.0.0.1:8643/fci-payroll',
    );
    expect(f.asked).toEqual([]);
  });

  it('reads the first web.env that gives an address, in the documented order', () => {
    const f = files({
      '/etc/fci/web.env': 'BACKEND_URL=http://etc:1/api',
      '/data/fci/web.env': 'BACKEND_URL=http://data:2/fci-payroll',
    });
    expect(resolveBackendUrl({ ...PROD, FCI_CONFIG_DIR: '/data/fci' }, f.read)).toBe(
      'http://data:2/fci-payroll',
    );
    expect(f.asked).toEqual(['/data/fci/web.env']);
    expect(resolveBackendUrl({ ...PROD, FCI_WEB_ENV: '/etc/fci/web.env' }, f.read)).toBe('http://etc:1/api');
    expect(resolveBackendUrl(PROD, f.read)).toBe('http://etc:1/api');
  });

  it('skips a missing file, a file without the key and a file with an invalid address', () => {
    const f = files({
      '/etc/fci/web.env': 'OTHER=1\n',
      '/data/fci/web.env': 'BACKEND_URL=http://127.0.0.1:8643/fci-payroll\n',
    });
    expect(resolveBackendUrl({ ...PROD, FCI_WEB_ENV: '/missing/web.env' }, f.read)).toBe(
      'http://127.0.0.1:8643/fci-payroll',
    );
    expect(f.asked).toEqual([
      '/missing/web.env',
      '/etc/fci/web.env',
      '/data/fci/frontend/web.env',
      '/data/fci/web.env',
    ]);
    const bad = files({ '/etc/fci/web.env': 'BACKEND_URL=not-an-address' });
    expect(() => resolveBackendUrl(PROD, bad.read)).toThrow(BackendUrlMissing);
  });

  it('an invalid BACKEND_URL variable does not hide a good file, and alone is not configured', () => {
    const f = files({ '/etc/fci/web.env': 'BACKEND_URL=http://etc:1/api' });
    expect(resolveBackendUrl({ ...PROD, BACKEND_URL: 'oops' }, f.read)).toBe('http://etc:1/api');
    expect(() => resolveBackendUrl({ ...PROD, BACKEND_URL: 'oops' }, files({}).read)).toThrow(
      BackendUrlMissing,
    );
  });

  it('OD-158: in production with nothing configured it refuses, naming every file looked at', () => {
    const f = files({});
    let error: unknown;
    try {
      resolveBackendUrl({ ...PROD, FCI_CONFIG_DIR: '/srv/cfg' }, f.read);
    } catch (e) {
      error = e;
    }
    expect(error).toBeInstanceOf(BackendUrlMissing);
    const message = (error as Error).message;
    expect((error as Error).name).toBe('BackendUrlMissing');
    expect(message).toContain('BACKEND_URL is not configured');
    for (const file of [
      '/srv/cfg/web.env',
      '/etc/fci/web.env',
      '/data/fci/frontend/web.env',
      '/data/fci/web.env',
    ]) {
      expect(message).toContain(file);
    }
    expect(message).toContain('/fci-payroll');
    expect(f.asked).toEqual([
      '/srv/cfg/web.env',
      '/etc/fci/web.env',
      '/data/fci/frontend/web.env',
      '/data/fci/web.env',
    ]);
  });

  it('next dev keeps its own backend and never reads a file', () => {
    const f = files({ '/etc/fci/web.env': 'BACKEND_URL=http://etc:1/api' });
    expect(resolveBackendUrl({ NODE_ENV: 'development' }, f.read)).toBe('http://localhost:8180/api');
    expect(
      resolveBackendUrl({ NODE_ENV: 'test', FCI_DEV_BACKEND_URL: 'http://localhost:9999' }, f.read),
    ).toBe('http://localhost:9999/api');
    expect(resolveBackendUrl({}, f.read)).toBe('http://localhost:8180/api');
    expect(f.asked).toEqual([]);
  });

  it('reads a real file with the default reader', () => {
    const dir = mkdtempSync(join(tmpdir(), 'fci-web-'));
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- a temporary test file
      writeFileSync(join(dir, 'web.env'), 'BACKEND_URL=http://127.0.0.1:8643/fci-payroll\n');
      expect(resolveBackendUrl({ ...PROD, FCI_CONFIG_DIR: dir })).toBe('http://127.0.0.1:8643/fci-payroll');
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe('OD-158 start-up check (src/instrumentation.ts)', () => {
  it('stops the production Node server with exit 1 and the message when nothing is configured', async () => {
    const exit = vi.fn();
    const error = vi.spyOn(process.stderr, 'write').mockImplementation(() => true);
    try {
      await checkBackendUrl(
        { NEXT_RUNTIME: 'nodejs', NODE_ENV: 'production', FCI_WEB_ENV: '/nope/web.env' },
        exit,
      );
      expect(exit).toHaveBeenCalledWith(1);
      expect(error.mock.calls[0]?.[0]).toMatch(/^\[fci-frontend\] BACKEND_URL is not configured/);
    } finally {
      error.mockRestore();
    }
  });

  it('lets the server start when the address is set', async () => {
    const exit = vi.fn();
    await checkBackendUrl(
      { NEXT_RUNTIME: 'nodejs', NODE_ENV: 'production', BACKEND_URL: 'http://127.0.0.1:8643/fci-payroll' },
      exit,
    );
    expect(exit).not.toHaveBeenCalled();
  });

  it('checks nothing during next build, in next dev or on the edge runtime', async () => {
    const exit = vi.fn();
    await checkBackendUrl(
      { NEXT_RUNTIME: 'nodejs', NODE_ENV: 'production', NEXT_PHASE: 'phase-production-build' },
      exit,
    );
    await checkBackendUrl({ NEXT_RUNTIME: 'nodejs', NODE_ENV: 'development' }, exit);
    await checkBackendUrl({ NEXT_RUNTIME: 'edge', NODE_ENV: 'production' }, exit);
    await checkBackendUrl({ NODE_ENV: 'production' }, exit);
    expect(exit).not.toHaveBeenCalled();
  });
});
