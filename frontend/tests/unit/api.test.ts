import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, onUnauthorized } from '@/shared/api';

afterEach(() => {
  vi.unstubAllGlobals();
  onUnauthorized(null);
});

describe('api client', () => {
  it('sends CSRF and Idempotency-Key on unsafe calls and never on GET', async () => {
    document.cookie = 'XSRF-TOKEN=abc; Path=/';
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await api('/v1/actions', { method: 'POST', body: { screen: 'M-01' } });
    await api('/auth/me');
    const [postUrl, post] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const [, get] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(postUrl).toBe('/nginx/fci-payroll/v1/actions');
    const headers = post.headers as Record<string, string>;
    expect(headers['X-XSRF-TOKEN']).toBe('abc');
    expect(headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(post.credentials).toBe('same-origin');
    expect((get.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('fetches the CSRF cookie first when it is missing', async () => {
    const fetchMock = vi.fn(async (...args: [string, RequestInit?]) => {
      if (args[0].endsWith('/auth/csrf')) document.cookie = 'XSRF-TOKEN=fresh; Path=/';
      return new Response(null, { status: 204 });
    });
    vi.stubGlobal('fetch', fetchMock);
    await api('/auth/logout', { method: 'POST' });
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/nginx/fci-payroll/auth/csrf');
    expect((fetchMock.mock.calls[1]?.[1] as RequestInit).headers).toMatchObject({ 'X-XSRF-TOKEN': 'fresh' });
  });

  it('turns problems into message keys and exposes the OTP challenge', async () => {
    const problem = {
      errorCode: 'AUTH-428-OTP',
      messageKey: 'error.AUTH-428-OTP',
      args: ['42'],
      correlationId: 'c-1',
    };
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify(problem), { status: 428 })),
    );
    const error = await api('/v1/admin/assignments', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const e = error as ApiError;
    expect(e.messageKey).toBe('error.AUTH-428-OTP');
    expect(e.otpChallengeId).toBe(42);
    expect(e.correlationId).toBe('c-1');
  });

  it('falls back to error.<code>, reports network failures and signals 401', async () => {
    const unauthorized = vi.fn();
    onUnauthorized(unauthorized);
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ errorCode: 'COM-401-AUTH' }), { status: 401 })),
    );
    const e = (await api('/auth/me').catch((x: unknown) => x)) as ApiError;
    expect(e.messageKey).toBe('error.COM-401-AUTH');
    expect(e.otpChallengeId).toBeNull();
    expect(unauthorized).toHaveBeenCalledOnce();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Promise.reject(new TypeError('offline'))),
    );
    const offline = (await api('/auth/me', { silent: true }).catch((x: unknown) => x)) as ApiError;
    expect(offline.status).toBe(0);
    expect(offline.messageKey).toBe('error.network');
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('not json', { status: 500 })),
    );
    const broken = (await api('/v1/inbox', { silent: true }).catch((x: unknown) => x)) as ApiError;
    expect(broken.messageKey).toBe('error.unexpected');
  });
});
