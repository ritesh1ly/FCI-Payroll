/**
 * The only way the browser talks to the backend (ADR-0010 §4). Every call goes to /nginx/fci-payroll on the same
 * origin with the HttpOnly session cookie; tokens never reach the browser and nothing is kept in
 * browser storage. Adds X-XSRF-TOKEN on unsafe methods, X-Correlation-Id per request and an
 * Idempotency-Key on POST/PUT. Errors are RFC 9457 problems; their text is rendered from the
 * label bundle by {@link ApiError.messageKey} (the servers never translate).
 */
import { log } from '@/shared/log';
import { API_GATES } from './apiGates';

export const API_BASE = '/nginx/fci-payroll';

export interface Violation {
  field: string;
  code: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly messageKey: string;
  readonly args: unknown[];
  readonly violations: Violation[];
  readonly correlationId: string | undefined;
  /** SRS-EH-1: BLOCKING, WARNING, INFORMATIONAL or SECURITY, held against the message. */
  readonly severity: string | undefined;
  /** SRS-EH-2: the key of the message's own corrective action ({@code error.<code>.action}). */
  readonly actionKey: string | undefined;
  /** Retry-After of a 429 / 503 download refusal (OD-144), in seconds; null otherwise. */
  retryAfterSeconds: number | null = null;

  constructor(status: number, problem: Record<string, unknown>, correlationId: string | undefined) {
    super(typeof problem.title === 'string' ? problem.title : `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = typeof problem.errorCode === 'string' ? problem.errorCode : undefined;
    this.messageKey =
      typeof problem.messageKey === 'string'
        ? problem.messageKey
        : this.code
          ? `error.${this.code}`
          : status === 0
            ? 'error.network'
            : 'error.unexpected';
    this.args = Array.isArray(problem.args) ? problem.args : [];
    this.violations = Array.isArray(problem.violations) ? (problem.violations as Violation[]) : [];
    this.correlationId =
      typeof problem.correlationId === 'string' ? problem.correlationId : (correlationId ?? undefined);
    this.severity = typeof problem.severity === 'string' ? problem.severity : undefined;
    this.actionKey = typeof problem.actionKey === 'string' ? problem.actionKey : undefined;
  }

  /** A 428 asking for the acting user's OTP: the challenge id to answer. */
  get otpChallengeId(): number | null {
    if (this.code !== 'AUTH-428-OTP') return null;
    const id = Number(this.args[0]);
    return Number.isSafeInteger(id) && id > 0 ? id : null;
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  /** Do not trigger the global 401 handler (session probe, login). */
  silent?: boolean;
  /** Do not run the OTP step-up on AUTH-403-STEP-UP (the step-up call itself). */
  noStepUp?: boolean;
  /**
   * Let the browser keep the response and revalidate it with its ETag (If-None-Match -> 304, body
   * from the browser cache). Only for non-personal data the server marks `Cache-Control: private,
   * no-cache` (the M-16 reference lists); everything else is `no-store`.
   */
  revalidate?: boolean;
}

/** Why the session went: {@code offence} when the server ended it for an access offence (OD-130). */
export type SignedOutReason = 'expired' | 'offence';

let unauthorized: ((reason: SignedOutReason) => void) | null = null;

export function onUnauthorized(handler: ((reason: SignedOutReason) => void) | null): void {
  unauthorized = handler;
}

/** The controllers' gates by method and number of path segments. */
const GATES = (() => {
  const index = new Map<string, { parts: string[]; held: readonly string[] }[]>();
  for (const [method, route, held] of API_GATES) {
    const parts = route.split('/');
    const key = `${method} ${parts.length}`;
    index.set(key, [...(index.get(key) ?? []), { parts, held }]);
  }
  return index;
})();

/** The permissions of a route: its literal form before one with path values (/rules/pending). */
function gateOf(method: string, path: string): readonly string[] | undefined {
  const parts = path.split('/');
  let best: { held: readonly string[]; values: number } | undefined;
  for (const row of GATES.get(`${method} ${parts.length}`) ?? []) {
    if (!row.parts.every((part, i) => part === '{}' || part === parts[i])) continue;
    const values = row.parts.filter((part) => part === '{}').length;
    if (!best || values < best.values) best = { held: row.held, values };
  }
  return best?.held;
}

/** The refused response that ended the session; the next call's 401 says the same. */
const SESSION_ENDED_HEADER = 'X-FCI-Session-Ended';
const OFFENCE_CODE = 'AUTH-401-OFFENCE';

/** The session's permissions, read when a call is made; undefined until they are known. */
let permissions: (() => readonly string[] | undefined) | null = null;

export function onPermissions(source: (() => readonly string[] | undefined) | null): void {
  permissions = source;
}

/**
 * OD-130: the gate a call would fail, or null. A refused call logs the user out as an offence, so a
 * screen drawn for a role must never send one, whatever panel or lookup it holds: the session's
 * permissions are compared with the controllers' own static gates here, in one place. An unknown
 * route, an empty or unknown permission set answer null: the server decides, as always.
 */
export function refusedGate(method: string, path: string): readonly [string, string] | null {
  const held = permissions?.();
  if (!held?.length) return null;
  const gate = gateOf(method, path.split(/[?#]/)[0] ?? path);
  if (!gate || gate.some((p) => held.includes(p))) return null;
  const [screen = '', action = ''] = (gate[0] ?? '').split(':');
  return [screen, action];
}

/** The ApiError of a call {@link refusedGate} stopped: what the server would have answered. */
export function refusedError(gate: readonly [string, string]): ApiError {
  return new ApiError(403, { errorCode: 'AUTHZ-403-PERMISSION', args: [...gate] }, undefined);
}

/** OD-130: the server ended this session for an access offence: to sign-in, saying why. */
export function reportOffence(response: Response, code: string | undefined): boolean {
  if (!response.headers.get(SESSION_ENDED_HEADER) && code !== OFFENCE_CODE) return false;
  unauthorized?.('offence');
  return true;
}

/**
 * The OTP step-up of a sensitive screen (SRS-G-48, Module 02 D-2): when the server answers
 * AUTH-403-STEP-UP the handler asks the user for the OTP and resolves true once the session is
 * confirmed; the refused call then runs once more (a new Idempotency-Key: the first never ran).
 */
let stepUpHandler: (() => Promise<boolean>) | null = null;

export function onStepUpRequired(handler: (() => Promise<boolean>) | null): void {
  stepUpHandler = handler;
}

/** Every answer from the server (the session's idle timer restarts with it). */
let activity: (() => void) | null = null;

export function onServerActivity(handler: (() => void) | null): void {
  activity = handler;
}

export function newId(): string {
  return crypto.randomUUID();
}

function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find((part) => part.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

let csrfPromise: Promise<void> | null = null;

async function csrfToken(): Promise<string | null> {
  const existing = readCookie('XSRF-TOKEN');
  if (existing) return existing;
  csrfPromise ??= fetch(`${API_BASE}/auth/csrf`, { credentials: 'same-origin', cache: 'no-store' }).then(
    () => {
      csrfPromise = null;
    },
  );
  await csrfPromise;
  return readCookie('XSRF-TOKEN');
}

export async function api<T = unknown>(path: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await request<T>(path, options);
  } catch (error) {
    if (
      error instanceof ApiError &&
      error.code === 'AUTH-403-STEP-UP' &&
      !options.noStepUp &&
      stepUpHandler &&
      (await stepUpHandler())
    ) {
      return request<T>(path, options);
    }
    throw error;
  }
}

async function request<T>(path: string, options: RequestOptions): Promise<T> {
  const method = options.method ?? 'GET';
  const gate = refusedGate(method, path);
  if (gate) {
    // OD-130: never sent. A refused call would end the session as an offence.
    log.info('api.refused_locally', { method, path, screen: gate[0], action: gate[1] });
    throw refusedError(gate);
  }
  const correlationId = newId();
  const started = Date.now();
  const headers: Record<string, string> = {
    Accept: 'application/json, application/problem+json',
    'X-Correlation-Id': correlationId,
  };
  if (method !== 'GET') {
    const token = await csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
    headers['Idempotency-Key'] = newId();
  }
  let body: string | FormData | undefined;
  if (options.body instanceof FormData) {
    // A file upload: the browser sets multipart/form-data with its boundary.
    body = options.body;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body,
      credentials: 'same-origin',
      cache: options.revalidate ? 'no-cache' : 'no-store',
      redirect: 'error',
    });
  } catch (cause) {
    // An exception: always logged (masked path, the request's correlation id).
    log.exception('api.network_error', cause, { method, path, correlationId });
    throw new ApiError(0, { messageKey: 'error.network' }, correlationId);
  }
  activity?.();
  const text = response.status === 204 || response.status === 304 ? '' : await response.text();
  let payload: unknown;
  try {
    payload = text ? JSON.parse(text) : undefined;
  } catch {
    payload = text;
  }
  if (!response.ok) {
    const error = new ApiError(
      response.status,
      payload && typeof payload === 'object' ? (payload as Record<string, unknown>) : {},
      response.headers.get('X-Correlation-Id') ?? correlationId,
    );
    const entry = {
      method,
      path,
      status: error.status,
      errorCode: error.code,
      correlationId: error.correlationId,
      ms: Date.now() - started,
    };
    if (response.status >= 500) log.error('api.server_error', entry);
    else if (response.status === 401) log.debug('api.unauthenticated', entry);
    // A business refusal (4xx): expected, told to the user; info for diagnosis.
    else log.info('api.refused', entry);
    const ended = reportOffence(response, error.code);
    if (!ended && response.status === 401 && !options.silent) unauthorized?.('expired');
    throw error;
  }
  log.debug('api.ok', { method, path, status: response.status, ms: Date.now() - started });
  return payload as T;
}

/** A downloaded file: its bytes, the server's name for it and its type (OD-144, OD-149). */
export interface ApiFile {
  blob: Blob;
  name: string | null;
  type: string | null;
}

/** The longest a download may take before it is abandoned: twice the 1-minute download SLA. */
export const FILE_TIMEOUT_MS = 120_000;

/**
 * Fetches a file (a PDF or a ZIP) with the session as every call does: a call the session's gates
 * refuse is never sent (OD-130); an expired session goes to sign-in; a sensitive screen's OTP
 * step-up is asked and the download made once more; a refusal comes back as an ApiError (its
 * {@code retryAfterSeconds} set from Retry-After on a 429 / 503), never as a file of error text;
 * a download longer than {@link FILE_TIMEOUT_MS} is abandoned as a timeout. Nothing of the file or
 * its name is logged (R-PII): only the method, the masked path and the status.
 */
export async function apiFile(path: string, timeoutMs = FILE_TIMEOUT_MS): Promise<ApiFile> {
  const gate = refusedGate('GET', path);
  if (gate) throw refusedError(gate);
  const correlationId = newId();
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), timeoutMs);
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: { 'X-Correlation-Id': correlationId },
      signal: abort.signal,
    });
  } catch {
    const timedOut = abort.signal.aborted;
    log.info(timedOut ? 'api.file_timeout' : 'api.file_network_error', { path, correlationId });
    throw new ApiError(0, { messageKey: timedOut ? 'download.timeout' : 'error.network' }, correlationId);
  } finally {
    clearTimeout(timer);
  }
  activity?.();
  if (!response.ok) {
    let problem: Record<string, unknown> = {};
    try {
      problem = (await response.json()) as Record<string, unknown>;
    } catch {
      // Not a problem document: the status alone.
    }
    const error = new ApiError(
      response.status,
      problem,
      response.headers.get('X-Correlation-Id') ?? correlationId,
    );
    const retry = Number(response.headers.get('Retry-After'));
    error.retryAfterSeconds = Number.isFinite(retry) && retry > 0 ? retry : null;
    log.info('api.file_refused', { path, status: response.status, errorCode: error.code });
    if (reportOffence(response, error.code)) throw error;
    if (error.code === 'AUTH-403-STEP-UP' && stepUpHandler && (await stepUpHandler())) {
      return apiFile(path, timeoutMs);
    }
    if (response.status === 401) unauthorized?.('expired');
    throw error;
  }
  const blob = await response.blob();
  return {
    blob,
    name: fileName(response.headers.get('Content-Disposition')),
    type: response.headers.get('Content-Type'),
  };
}

/** The file name of a Content-Disposition, if it names one. */
export function fileName(disposition: string | null): string | null {
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition ?? '');
  if (!m?.[1]) return null;
  try {
    return decodeURIComponent(m[1]);
  } catch {
    return m[1];
  }
}

/**
 * Query options for anything that holds employee or personal data (records, pay, events, users,
 * approval subjects, audit values): never served from the browser's query cache and dropped from
 * memory as soon as the screen that shows it is gone, so it is always fetched fresh and never kept.
 * (The API answers such requests with Cache-Control: no-store; the service worker never stores /nginx/fci-payroll.)
 */
export const PERSONAL_DATA = { staleTime: 0, gcTime: 0 } as const;
