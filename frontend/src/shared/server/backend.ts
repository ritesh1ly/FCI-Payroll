import { cookies, headers } from 'next/headers';
import { cache } from 'react';
import type { LoginOptions, Me } from '@/shared/session';
import { resolveBackendUrl } from './backendUrl';

/**
 * Server-side calls from the Next.js server to the online backend, made while rendering a page.
 * Only the backend's own cookies are forwarded (session and language), together with the client
 * address and the correlation id NGINX set, so the backend sees the same caller as for the browser's
 * own /api calls. Nothing here is ever sent to the browser except the rendered result.
 *
 * BACKEND_URL is the online instance on loopback (backendUrl.ts: the variable or a web.env outside the
 * release, required in production, OD-158); never the public origin, so the call does not loop through
 * NGINX. Under `next dev` it is the backend the dev server proxies /api to (FCI_DEV_BACKEND_URL,
 * next.config.ts), so server rendering and the browser's own calls always see the same session store.
 * Resolved at the first call, not at import: `next build` loads this module without any web.env.
 */
let resolvedBackendUrl: string | undefined;
function backendUrl(): string {
  resolvedBackendUrl ??= resolveBackendUrl();
  return resolvedBackendUrl;
}
/** The session cookie: __Host-FCISESSION over HTTPS, FCISESSION on a local HTTP stack. */
const SESSION_COOKIES = ['__Host-FCISESSION', 'FCISESSION'];
const FORWARDED_COOKIES = new Set([...SESSION_COOKIES, 'FCI_LOCALE']);

/** How long the public sign-in options are kept by this Next.js server process (they are config flags). */
const LOGIN_OPTIONS_TTL_MS = 60_000;

const OFFENCE_CODE = 'AUTH-401-OFFENCE';

/** The caller's own cookies and headers, for a call made on its behalf. */
async function callerHeaders(): Promise<Record<string, string>> {
  const jar = await cookies();
  const incoming = await headers();
  const cookie = jar
    .getAll()
    .filter((c) => FORWARDED_COOKIES.has(c.name))
    .map((c) => `${c.name}=${c.value}`)
    .join('; ');
  const forwarded: Record<string, string> = { Accept: 'application/json' };
  if (cookie) forwarded.Cookie = cookie;
  const clientIp = incoming.get('x-forwarded-for');
  if (clientIp) forwarded['X-Forwarded-For'] = clientIp;
  // The session is bound to the browser that signed in (hijack detection): send its User-Agent.
  const userAgent = incoming.get('user-agent');
  if (userAgent) forwarded['User-Agent'] = userAgent;
  const correlationId = incoming.get('x-correlation-id');
  if (correlationId) forwarded['X-Correlation-Id'] = correlationId;
  return forwarded;
}

function ask(path: string, forwarded: Record<string, string>): Promise<Response> {
  return fetch(`${backendUrl()}${path}`, {
    headers: forwarded,
    cache: 'no-store',
    redirect: 'error',
    signal: AbortSignal.timeout(5000),
  });
}

async function fetchJson<T>(path: string, forwarded: Record<string, string>): Promise<T | null> {
  try {
    const response = await ask(path, forwarded);
    return response.ok ? ((await response.json()) as T) : null;
  } catch {
    // Backend unreachable: render the page without server data; the browser's own calls report it.
    return null;
  }
}

/**
 * The signed-in user of this request, or null (no cookie, expired or ended session). Asked once per
 * request however many server components or guards need it (React cache(): the memo lives for one
 * server render only, so one user's profile never reaches another request); never kept longer,
 * because a lock, sign-out or forced password change must apply on the next page.
 */
const session = cache(async (): Promise<{ me: Me | null; offence: boolean }> => {
  const jar = await cookies();
  if (!SESSION_COOKIES.some((name) => jar.has(name))) return { me: null, offence: false };
  try {
    const response = await ask('/auth/me', await callerHeaders());
    const body = (await response.json()) as Me & { errorCode?: string };
    return response.ok
      ? { me: body, offence: false }
      : { me: null, offence: body.errorCode === OFFENCE_CODE };
  } catch {
    // Backend unreachable, or an answer with no JSON body: as fetchJson.
    return { me: null, offence: false };
  }
});

export const serverMe = cache(async (): Promise<Me | null> => (await session()).me);

/**
 * OD-130: this request's session was ended for an access offence. The backend says so once, on the
 * first 401 after the offence: a page load is often that call, and the sign-in page must say why.
 */
export const serverOffence = cache(async (): Promise<boolean> => (await session()).offence);

let loginOptions: { value: LoginOptions; at: number } | null = null;

/**
 * The sign-in page's options: public configuration flags, the same for every caller and language, so
 * asked without the caller's cookies or headers and kept for LOGIN_OPTIONS_TTL_MS by this process
 * (every sign-in page render would otherwise call the backend). A failed answer is never kept.
 */
export async function serverLoginOptions(): Promise<LoginOptions | null> {
  const now = Date.now();
  if (loginOptions && now - loginOptions.at < LOGIN_OPTIONS_TTL_MS) return loginOptions.value;
  const value = await fetchJson<LoginOptions>('/auth/login-options', { Accept: 'application/json' });
  loginOptions = value ? { value, at: now } : null;
  return value;
}
