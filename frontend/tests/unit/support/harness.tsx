import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { businessMonth } from '@/shared/time';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { vi } from 'vitest';
import { GovernedProvider } from '@/modules/workflow';
import { I18nProvider, translate } from '@/shared/i18n';
import type { Me } from '@/shared/session';
import { ToastProvider } from '@/shared/ui';

/**
 * Shared harness of the screen tests: a fetch stub that answers by "METHOD path-fragment" (the
 * longest matching key wins) and records every call, problem answers, and the providers every screen
 * runs inside (labels, queries, toasts, the OTP dialog). Files that render screens using the router
 * also declare: vi.mock('next/navigation', ...).
 */

export interface Call {
  url: string;
  method: string;
  body: unknown;
}

/** A route's answer: a body (200), or { status, body } for anything else. */
export type Route = (url: string, init?: RequestInit) => unknown;

export function backend(routes: Record<string, Route>, fallback: unknown = {}): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      if (url.endsWith('/auth/csrf')) {
        document.cookie = 'XSRF-TOKEN=t; Path=/';
        return new Response(null, { status: 204 });
      }
      const raw = init?.body;
      calls.push({
        url,
        method,
        body: raw instanceof FormData ? raw : typeof raw === 'string' ? JSON.parse(raw) : undefined,
      });
      const key = Object.keys(routes)
        .sort((a, b) => b.length - a.length)
        .find((k) => {
          const [m, fragment] = k.split(' ');
          return m === method && fragment !== undefined && url.includes(fragment);
        });
      const answer = key ? routes[key]?.(url, init) : fallback;
      const explicit =
        answer !== null && typeof answer === 'object' && 'status' in answer && 'body' in answer
          ? (answer as { status: number; body: unknown })
          : null;
      const status = explicit ? explicit.status : 200;
      if (status === 204) return new Response(null, { status });
      return new Response(JSON.stringify(explicit ? explicit.body : (answer ?? {})), {
        status,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
  return calls;
}

/**
 * A page of a paged list endpoint (docs/plan/X-pagination.md) holding {@code rows}: the page the
 * request asked for (from its URL), or the first page at 20 rows; the total is the rows' count unless
 * given.
 */
export function paged<T>(rows: T[], total = rows.length, url = '') {
  const page = Number(/[?&]page=(\d+)/.exec(url)?.[1] ?? 0);
  const size = Number(/[?&]size=(\d+)/.exec(url)?.[1] ?? 20);
  return { items: rows, page, size, total };
}

/** An RFC 9457 problem answer with the error code the screens translate. */
export function problem(status: number, code: string, args: unknown[] = []) {
  return { status, body: { errorCode: code, messageKey: `error.${code}`, args } };
}

/** A plain status without a body (204 No Content, …). */
export function noContent() {
  return { status: 204, body: null };
}

/** The current payroll period in IST ("YYYY-MM"), so no fixture pins a month or a year (OD-123). */
export function currentPeriod(): string {
  return businessMonth();
}

/** A session profile with the given permissions (only the fields a test needs are meaningful). */
export function me(permissions: string[], over: Partial<Me> = {}): Me {
  return {
    userId: 1,
    loginId: 'user@fci-demo.test',
    displayName: 'Test User',
    persona: 'payroll',
    route: '/payroll',
    landingScreen: null,
    role: 'PAYROLL_MAKER',
    roleName: 'Payroll Maker',
    location: { code: 'DH001', label: 'DO — Delhi HQ (DH001)' },
    // Literal until every fixture derives its period (PENDING TEST-DATES-1); new tests use currentPeriod().
    period: '2026-10',
    periodStatus: 'CURRENT',
    roles: ['PAYROLL_MAKER'],
    groups: [],
    permissions,
    readOnlyConsolidated: false,
    passwordChangeRequired: false,
    stepUpRequired: false,
    sensitiveScreens: [],
    stepUpValidUntil: null,
    hqOnlyScreens: [],
    atHeadquarters: true,
    otpRequired: true,
    paging: { sizes: [10, 20, 50, 100], defaultSize: 20 },
    locale: 'en',
    lastLoginAt: '2026-10-20T04:30:00Z',
    session: {
      idleExpiresAt: '2026-10-21T10:15:00Z',
      absoluteExpiresAt: '2026-10-21T18:00:00Z',
      idleTimeoutSeconds: 900,
    },
    demo: true,
    ...over,
  };
}

/** Renders inside every provider a screen expects; the profile is pre-loaded as /auth/me. */
export function wrap(children: ReactNode, profile: Me | string[] = []) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  });
  client.setQueryData(['me'], Array.isArray(profile) ? me(profile) : profile);
  const view = render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        <ToastProvider>
          <GovernedProvider>{children}</GovernedProvider>
        </ToastProvider>
      </QueryClientProvider>
    </I18nProvider>,
  );
  return { ...view, client };
}

/** The JSON bodies sent with {@code method} to a URL containing {@code fragment}. */
export function sent(calls: Call[], method: string, fragment: string): unknown[] {
  return calls.filter((c) => c.method === method && c.url.includes(fragment)).map((c) => c.body);
}

/** GET /v1/reference/lists answer: list code -> [code, English label] pairs (all in force). */
export function referenceLists(lists: Record<string, [string, string][]>) {
  return {
    asOf: '2026-10-21',
    lists: Object.fromEntries(
      Object.entries(lists).map(([code, values]) => [
        code,
        {
          code,
          name: code,
          module: null,
          system: true,
          values: values.map(([value, label], i) => ({
            code: value,
            label,
            labelHi: null,
            sortOrder: i,
            parent: null,
            active: true,
          })),
        },
      ]),
    ),
  };
}

/** The English text of a label, as the screens render it. */
export function t(key: string, args: unknown[] = []): string {
  return translate('en', key, args);
}

/**
 * The download URL of the screen's export (SRS-G-13): opens the Export dialog, chooses
 * {@code format} and reads the Download link, then closes the dialog.
 */
export async function exportedUrl(
  format: 'CSV' | 'XLSX' = 'CSV',
  label?: string,
  /** Where the button sits, on a screen that draws another of the same name. */
  scope: Pick<typeof screen, 'queryByRole' | 'getByRole'> = screen,
): Promise<string | null> {
  // The button as the screen draws it (its own label), as the kit draws it ("⇩ Export") or the plain one.
  await userEvent.click(
    (label ? scope.queryByRole('button', { name: label }) : null) ??
      scope.queryByRole('button', { name: t('wf.export') }) ??
      scope.getByRole('button', { name: t('action.export') }),
  );
  const dialog = screen.getByRole('dialog');
  await userEvent.click(dialog.querySelector(`input[value="${format}"]`) as HTMLElement);
  const href = within(dialog)
    .getByRole('link', { name: t('export.download') })
    .getAttribute('href');
  await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
  return href;
}

/**
 * OD-144: captures the files handed to the browser (the anchor's download name), with the object URLs stubbed for
 * jsdom; `restore()` puts the anchor's click back.
 */
export function captureDownloads() {
  const names: string[] = [];
  URL.createObjectURL = (() => 'blob:fci/test') as typeof URL.createObjectURL;
  URL.revokeObjectURL = (() => undefined) as typeof URL.revokeObjectURL;
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    names.push(this.download);
  });
  return { names, restore: () => click.mockRestore() };
}
