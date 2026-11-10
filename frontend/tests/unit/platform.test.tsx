import { act, render, renderHook } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAppCaches, PwaRegistration, useInstallPrompt, useOnline } from '@/shared/pwa';

/**
 * Platform pieces outside the screens: the PWA kill switch and install prompt (SRS-NF-6), and the
 * server-side session call that forwards only the backend's own cookies and the caller's headers
 * (SRS-SEC-14: the session stays bound to the browser that signed in).
 */

const cookieJar = new Map<string, string>();
const headerJar = new Map<string, string>();
vi.mock('next/headers', () => ({
  cookies: async () => ({
    getAll: () => [...cookieJar].map(([name, value]) => ({ name, value })),
    has: (name: string) => cookieJar.has(name),
  }),
  headers: async () => ({ get: (name: string) => headerJar.get(name) ?? null }),
}));

describe('PWA', () => {
  const register = vi.fn(async () => ({}));
  const unregister = vi.fn(async () => true);
  const postMessage = vi.fn();
  const keys = vi.fn(async () => ['fcilekha-static-1', 'other-site']);
  const remove = vi.fn(async () => true);

  beforeEach(() => {
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: {
        register,
        getRegistrations: async () => [{ unregister }],
        controller: { postMessage },
      },
    });
    vi.stubGlobal('caches', { keys, delete: remove });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    Reflect.deleteProperty(navigator, 'serviceWorker');
  });

  it('SRS-NF-6: enabled, the versioned worker is registered', () => {
    render(<PwaRegistration enabled version="1.2 beta" />);
    expect(register).toHaveBeenCalledWith('/sw.js?v=1.2%20beta', { scope: '/' });
  });

  it('disabled, every worker is unregistered and the app caches deleted (kill switch)', async () => {
    render(<PwaRegistration enabled={false} version="1" />);
    await vi.waitFor(() => expect(unregister).toHaveBeenCalled());
    await vi.waitFor(() => expect(remove).toHaveBeenCalledWith('fcilekha-static-1'));
    expect(remove).not.toHaveBeenCalledWith('other-site');
  });

  it('sign-out clears the caches through the worker and the page; storage errors are ignored', async () => {
    await clearAppCaches();
    expect(postMessage).toHaveBeenCalledWith({ type: 'CLEAR' });
    expect(remove).toHaveBeenCalledTimes(1);
    keys.mockRejectedValueOnce(new Error('private mode'));
    await expect(clearAppCaches()).resolves.toBeUndefined();
  });

  it('the install offer appears with the browser prompt and is used once; installing clears it', async () => {
    render(<PwaRegistration enabled version="1" />);
    const { result } = renderHook(() => useInstallPrompt());
    expect(result.current.available).toBe(false);
    expect(await result.current.install()).toBe(false);
    const prompt = vi.fn(async () => undefined);
    const offer = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt,
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    });
    act(() => {
      window.dispatchEvent(offer);
    });
    expect(offer.defaultPrevented).toBe(true);
    expect(result.current.available).toBe(true);
    let accepted = false;
    await act(async () => {
      accepted = await result.current.install();
    });
    expect(accepted).toBe(true);
    expect(prompt).toHaveBeenCalledTimes(1);
    expect(result.current.available).toBe(false);
    act(() => {
      window.dispatchEvent(
        Object.assign(new Event('beforeinstallprompt'), {
          prompt,
          userChoice: Promise.resolve({ outcome: 'dismissed' }),
        }),
      );
    });
    expect(result.current.available).toBe(true);
    act(() => {
      window.dispatchEvent(new Event('appinstalled'));
    });
    expect(result.current.available).toBe(false);
  });

  it('without service workers nothing is registered', () => {
    Reflect.deleteProperty(navigator, 'serviceWorker');
    render(<PwaRegistration enabled version="1" />);
    expect(register).not.toHaveBeenCalled();
  });

  it('follows the browser going offline and online', () => {
    const online = vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true);
    const { result } = renderHook(() => useOnline());
    expect(result.current).toBe(true);
    online.mockReturnValue(false);
    act(() => {
      window.dispatchEvent(new Event('offline'));
    });
    expect(result.current).toBe(false);
    online.mockRestore();
  });
  it('a worker that fails to register is ignored, never an uncaught rejection', async () => {
    register.mockRejectedValueOnce(new Error('insecure origin'));
    render(<PwaRegistration enabled version="2" />);
    await vi.waitFor(() => expect(register).toHaveBeenCalledTimes(1));
  });

  it('on the server the app is online and offers no install', () => {
    function Probe() {
      const online = useOnline();
      const { available } = useInstallPrompt();
      return <span>{`${online}-${available}`}</span>;
    }
    expect(renderToString(<Probe />)).toContain('true-false');
  });
});

describe('server-side backend calls', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    cookieJar.clear();
    headerJar.clear();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function load() {
    vi.resetModules();
    return import('@/shared/server/backend');
  }

  it('SRS-SEC-14: forwards only the session and language cookies, the client address, user agent and correlation id', async () => {
    cookieJar.set('__Host-FCISESSION', 's1');
    cookieJar.set('FCI_LOCALE', 'hi');
    cookieJar.set('tracker', 'x');
    headerJar.set('x-forwarded-for', '10.0.0.7');
    headerJar.set('user-agent', 'Firefox/140');
    headerJar.set('x-correlation-id', 'c-1');
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ loginId: 'a@fci-demo.test' }), { status: 200 }),
    );
    const { serverMe } = await load();
    expect(await serverMe()).toEqual({ loginId: 'a@fci-demo.test' });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/api\/auth\/me$/);
    expect(init.headers).toEqual({
      Accept: 'application/json',
      Cookie: '__Host-FCISESSION=s1; FCI_LOCALE=hi',
      'X-Forwarded-For': '10.0.0.7',
      'User-Agent': 'Firefox/140',
      'X-Correlation-Id': 'c-1',
    });
    expect(init.redirect).toBe('error');
  });

  it('SRS-22-2: a role, office or identity the browser sends is never forwarded (RBAC-SEC-167)', async () => {
    cookieJar.clear();
    cookieJar.set('__Host-FCISESSION', 's2');
    cookieJar.set('fci.role', 'SYSTEM_ADMINISTRATOR');
    cookieJar.set('fci.userId', '1');
    for (const [name, value] of [
      ['x-role', 'SYSTEM_ADMINISTRATOR'],
      ['x-active-role', 'SUPER_USER'],
      ['x-office', 'HQ001'],
      ['x-user-id', '1'],
      ['x-forwarded-user', 'sysadmin@fci-demo.test'],
      ['authorization', 'Basic eDp5'],
      ['x-original-url', '/api/v1/admin/roles'],
    ] as const) {
      headerJar.set(name, value);
    }
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ loginId: 'b' }), { status: 200 }));
    const { serverMe } = await load();
    await serverMe();
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.headers).toEqual({ Accept: 'application/json', Cookie: '__Host-FCISESSION=s2' });
    cookieJar.clear();
  });

  it('no session cookie: no call at all; a refused or unreachable backend gives null', async () => {
    const { serverMe, serverLoginOptions } = await load();
    expect(await serverMe()).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
    cookieJar.set('FCISESSION', 'local');
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 401 }));
    expect(await serverMe()).toBeNull();
    fetchMock.mockRejectedValueOnce(new Error('ECONNREFUSED'));
    expect(await serverMe()).toBeNull();
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ demo: true }), { status: 200 }));
    expect(await serverLoginOptions()).toEqual({ demo: true });
    // Public flags: asked without the caller's cookies (nothing personal goes into a shared answer).
    const headersSent = (fetchMock.mock.calls.at(-1) as [string, RequestInit])[1].headers;
    expect(headersSent).toEqual({ Accept: 'application/json' });
  });

  it('OD-130: the 401 of a session ended for an offence is told apart; any other is not', async () => {
    const { serverOffence } = await load();
    expect(await serverOffence()).toBe(false);
    cookieJar.set('FCISESSION', 'local');
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ errorCode: 'AUTH-401-OFFENCE' }), { status: 401 }),
    );
    expect(await serverOffence()).toBe(true);
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ errorCode: 'AUTH-401-EXPIRED' }), { status: 401 }),
    );
    expect(await serverOffence()).toBe(false);
    fetchMock.mockResolvedValueOnce(new Response('', { status: 502 }));
    expect(await serverOffence()).toBe(false);
    fetchMock.mockRejectedValueOnce(new Error('ECONNREFUSED'));
    expect(await serverOffence()).toBe(false);
    cookieJar.clear();
  });

  it('HLD 3.12: the sign-in options are kept for a minute; a failed answer is never kept', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      const { serverLoginOptions } = await load();
      fetchMock.mockRejectedValueOnce(new Error('ECONNREFUSED'));
      expect(await serverLoginOptions()).toBeNull();
      fetchMock.mockResolvedValue(new Response(JSON.stringify({ demo: true }), { status: 200 }));
      expect(await serverLoginOptions()).toEqual({ demo: true });
      expect(await serverLoginOptions()).toEqual({ demo: true });
      expect(fetchMock).toHaveBeenCalledTimes(2);
      vi.setSystemTime(Date.now() + 59_999);
      await serverLoginOptions();
      expect(fetchMock).toHaveBeenCalledTimes(2);
      vi.setSystemTime(Date.now() + 1);
      fetchMock.mockResolvedValue(new Response(JSON.stringify({ demo: false }), { status: 200 }));
      expect(await serverLoginOptions()).toEqual({ demo: false });
      expect(fetchMock).toHaveBeenCalledTimes(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it('BACKEND_URL overrides the backend address', async () => {
    vi.stubEnv('BACKEND_URL', 'http://10.1.1.1:8080/api');
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ demo: false }), { status: 200 }));
    const { serverLoginOptions } = await load();
    await serverLoginOptions();
    expect(fetchMock.mock.calls[0]?.[0]).toBe('http://10.1.1.1:8080/api/auth/login-options');
    vi.unstubAllEnvs();
  });
});
