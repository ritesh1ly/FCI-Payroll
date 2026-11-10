import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PeriodSwitcher } from '@/modules/iam';
import type { PayrollPeriod } from '@/modules/iam/api/authApi';
import { I18nProvider } from '@/shared/i18n';
import { type Me, periodLabel } from '@/shared/session';

/**
 * The top-bar payroll period selector (M-10 calendar): every approved period up to the current one
 * is offered, newest first, with its cycle state; future periods are not offered; a choice is sent
 * to the server and the refreshed profile handed back; a refusal is reported.
 */

const CALENDAR: PayrollPeriod[] = [
  { period: '2026-12', finYear: '2026-27', status: null, current: false },
  { period: '2026-11', finYear: '2026-27', status: null, current: false },
  { period: '2026-10', finYear: '2026-27', status: 'INITIATED', current: true },
  { period: '2026-09', finYear: '2026-27', status: 'LOCKED', current: false },
  { period: '2026-08', finYear: '2026-27', status: 'FROZEN', current: false },
  { period: '2026-07', finYear: '2026-27', status: 'COMPUTED', current: false },
  { period: '2025-04', finYear: '2025-26', status: null, current: false },
];

const SESSION = { userId: 1, period: '2026-10', periodStatus: 'CURRENT', permissions: [] } as unknown as Me;

interface Stub {
  periods?: unknown;
  put?: { status: number; body?: unknown };
}

function backend({ periods = CALENDAR, put = { status: 204 } }: Stub) {
  const calls: { url: string; method: string; body: unknown }[] = [];
  let chosen = SESSION.period;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      if (url.endsWith('/auth/csrf')) {
        document.cookie = 'XSRF-TOKEN=t; Path=/';
        return new Response(null, { status: 204 });
      }
      const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as { period: string }) : undefined;
      calls.push({ url, method, body });
      const json = (status: number, payload: unknown) =>
        new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } });
      if (url.endsWith('/v1/periods')) return json(200, periods);
      if (url.endsWith('/auth/period')) {
        if (put.status === 204 && body) chosen = body.period;
        return put.status === 204 ? new Response(null, { status: 204 }) : json(put.status, put.body);
      }
      if (url.endsWith('/auth/me')) return json(200, { ...SESSION, period: chosen, periodStatus: 'PAST' });
      return json(200, {});
    }),
  );
  return calls;
}

function mount(enabled = true, session: Me = SESSION) {
  const onSwitched = vi.fn();
  const onError = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        <PeriodSwitcher session={session} enabled={enabled} onSwitched={onSwitched} onError={onError} />
      </QueryClientProvider>
    </I18nProvider>,
  );
  return { onSwitched, onError, select: () => screen.getByLabelText('Payroll period') as HTMLSelectElement };
}

const values = (select: HTMLSelectElement) => [...select.options].map((o) => o.value);

describe('PeriodSwitcher (M-10 payroll calendar)', () => {
  it('M_10: offers the current and every back-dated period with its state, never a future one', async () => {
    backend({});
    const { select } = mount();
    await waitFor(() => expect(select().options).toHaveLength(5));
    expect(values(select())).toEqual(['2026-10', '2026-09', '2026-08', '2026-07', '2025-04']);
    expect(values(select())).not.toContain('2026-11');
    expect(select().value).toBe('2026-10');
    const labels = [...select().options].map((o) => o.textContent);
    expect(labels).toEqual([
      'OCT 2026 · Initiated · current',
      'SEP 2026 · Locked',
      'AUG 2026 · Frozen',
      'JUL 2026 · Computed',
      'APR 2025 · Not started',
    ]);
    expect(select()).toBeEnabled();
  });

  it('M_10: choosing a back-dated period sends it and hands back the refreshed session', async () => {
    const calls = backend({});
    const { select, onSwitched, onError } = mount();
    await waitFor(() => expect(select().options).toHaveLength(5));
    await userEvent.setup().selectOptions(select(), '2026-08');
    await waitFor(() => expect(onSwitched).toHaveBeenCalledTimes(1));
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ period: '2026-08' });
    expect(onSwitched.mock.calls[0]?.[0]).toMatchObject({ period: '2026-08', periodStatus: 'PAST' });
    expect(onError).not.toHaveBeenCalled();
  });

  it('a period refused by the server is reported with its message, not applied', async () => {
    backend({
      put: {
        status: 422,
        body: { errorCode: 'CYC-422-CALENDAR', messageKey: 'error.CYC-422-CALENDAR', args: ['2026-07'] },
      },
    });
    const { select, onSwitched, onError } = mount();
    await waitFor(() => expect(select().options).toHaveLength(5));
    await userEvent.setup().selectOptions(select(), '2026-07');
    await waitFor(() => expect(onError).toHaveBeenCalledTimes(1));
    expect(onError.mock.calls[0]?.[0]).toMatch(/2026-07/);
    expect(onSwitched).not.toHaveBeenCalled();
  });

  it('a failure without a problem body falls back to the generic message', async () => {
    backend({ put: { status: 500, body: 'boom' } });
    const { select, onError } = mount();
    await waitFor(() => expect(select().options).toHaveLength(5));
    await userEvent.setup().selectOptions(select(), '2026-09');
    await waitFor(() => expect(onError).toHaveBeenCalledTimes(1));
    expect(onError.mock.calls[0]?.[0]).toEqual(expect.any(String));
  });

  it('before the session is ready the calendar is not asked and only the session period is shown', () => {
    const calls = backend({});
    const { select } = mount(false);
    expect(values(select())).toEqual(['2026-10']);
    expect(select().options[0]?.textContent).toBe(periodLabel('2026-10'));
    expect(select()).toBeDisabled();
    expect(calls.some((c) => c.url.endsWith('/v1/periods'))).toBe(false);
  });

  it('an empty calendar leaves the session period as the only, disabled option', async () => {
    const calls = backend({ periods: [] });
    const { select } = mount();
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/v1/periods'))).toBe(true));
    expect(values(select())).toEqual(['2026-10']);
    expect(select()).toBeDisabled();
  });

  it('a session on a period outside the offered list keeps it visible alongside the calendar', async () => {
    backend({});
    const { select } = mount(true, { ...SESSION, period: '2024-03' } as Me);
    await waitFor(() => expect(select().options).toHaveLength(6));
    expect(values(select())[0]).toBe('2024-03');
    expect(select().value).toBe('2024-03');
  });

  it('without a current period in the calendar every listed period is offered', async () => {
    backend({ periods: CALENDAR.map((p) => ({ ...p, current: false })) });
    const { select } = mount();
    await waitFor(() => expect(select().options).toHaveLength(7));
    expect(values(select())).toContain('2026-12');
  });
});
