import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Workspace } from '@/modules/shell';
import { I18nProvider } from '@/shared/i18n';
import type { Me } from '@/shared/session';
import { backend, me, problem, t } from './support/harness';

const replace = vi.fn();
const push = vi.fn();
let search = new URLSearchParams();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  useSearchParams: () => search,
}));

const MENU = {
  sections: [
    {
      id: 'work',
      labelKey: 'nav.work',
      items: [
        { code: 'W-01', labelKey: 'screen.W-01', route: '/payroll?s=W-01', requires: 'W-01:VIEW' },
        { code: 'W-02', labelKey: 'screen.W-02', route: '/payroll?s=W-02', requires: 'W-02:VIEW' },
      ],
    },
  ],
  hiddenCount: 0,
};

/** A checker whose step-up is due: W-01 (approve) is its sensitive screen. */
const CHECKER: Me = me(['W-01:VIEW', 'W-01:APPROVE', 'W-02:VIEW'], {
  role: 'PAYROLL_CHECKER',
  stepUpRequired: true,
  sensitiveScreens: ['W-01'],
});

function show(profile: Me) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['me'], profile);
  client.setQueryData(['menu'], MENU);
  return render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        <Workspace />
      </QueryClientProvider>
    </I18nProvider>,
  );
}

beforeEach(() => {
  // jsdom has no matchMedia: a desktop layout.
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  replace.mockReset();
  push.mockReset();
  search = new URLSearchParams();
});
afterEach(() => vi.unstubAllGlobals());

describe('Workspace step-up (SRS-G-48)', () => {
  it('SRS-G-48: a step-up the server cannot start is shown, never swallowed', async () => {
    search = new URLSearchParams('s=W-01');
    backend({ 'POST /auth/step-up': () => problem(429, 'COM-429-RATE-LIMIT') }, []);
    show(CHECKER);
    // The gate holds the sensitive screen and says why no OTP came.
    expect(await screen.findByRole('heading', { name: t('stepup.title') })).toBeTruthy();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe(t('error.COM-429-RATE-LIMIT'));
    expect(screen.queryByRole('heading', { level: 1, name: t('screen.W-01') })).toBeNull();
  });

  it('SRS-G-48: the gate asks again on Verify; a cancelled OTP is no error', async () => {
    search = new URLSearchParams('s=W-01');
    const calls = backend({ 'POST /auth/step-up': () => problem(428, 'AUTH-428-OTP', [9]) }, []);
    show(CHECKER);
    const cancel = async () => {
      const dialog = await screen.findByRole('dialog');
      await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    };
    await cancel();
    expect(screen.queryByRole('alert')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('dash.otpVerify') }));
    await cancel();
    expect(calls.filter((c) => c.url.endsWith('/auth/step-up')).length).toBeGreaterThanOrEqual(2);
  });

  it('a maker without a sensitive screen opens on the dashboard and is asked nothing', async () => {
    const calls = backend({}, []);
    show(me(['W-02:VIEW']));
    expect(await screen.findByRole('heading', { level: 1, name: t('dash.title') })).toBeTruthy();
    expect(calls.some((c) => c.url.endsWith('/auth/step-up'))).toBe(false);
  });
});

describe('OTP verification switched off (SRS-G-21, project owner 2026-10-23)', () => {
  it('SRS-G-21: a development or local session says the OTP is off; UAT and production say nothing', async () => {
    backend({}, []);
    const { unmount } = show(me(['W-02:VIEW'], { otpRequired: false }));
    const note = await screen.findByRole('note');
    expect(note.textContent).toContain(t('nav.otpOff'));
    expect(note.getAttribute('title')).toBe(t('nav.otpOffHint'));
    unmount();
    show(me(['W-02:VIEW']));
    expect(await screen.findByRole('heading', { level: 1, name: t('dash.title') })).toBeTruthy();
    expect(screen.queryByRole('note')).toBeNull();
  });
});

describe('Workspace step-up confirmed and idle sign-out', () => {
  it('SRS-G-48: a confirmed step-up refreshes the profile and opens the sensitive screen', async () => {
    search = new URLSearchParams('s=W-01');
    let asked = 0;
    const calls = backend(
      {
        'POST /auth/step-up': () => (asked++ === 0 ? problem(428, 'AUTH-428-OTP', [9]) : { status: 204 }),
        'GET /auth/me': () => ({ ...CHECKER, stepUpRequired: false }),
      },
      [],
    );
    show(CHECKER);
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '482913');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
    await waitFor(() =>
      expect(calls.some((c) => c.method === 'GET' && c.url.endsWith('/auth/me'))).toBe(true),
    );
    expect(calls.filter((c) => c.url.endsWith('/auth/step-up')).at(-1)?.body).toMatchObject({
      otp: { challengeId: 9, code: '482913' },
    });
  });

  it('SRS-SEC-14: an idle session that nobody keeps alive is ended and returns to sign-in', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const calls = backend({ 'POST /auth/logout': () => ({ status: 204 }) }, []);
      show(
        me(['W-02:VIEW'], {
          session: {
            idleExpiresAt: '2026-10-21T10:15:00Z',
            absoluteExpiresAt: '2026-10-21T18:00:00Z',
            idleTimeoutSeconds: 60,
          },
        }),
      );
      await screen.findByRole('heading', { level: 1, name: t('dash.title') });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(61_000);
      });
      await waitFor(() => expect(replace).toHaveBeenCalledWith('/?signedOut=idle'));
      expect(calls.some((c) => c.url.endsWith('/auth/logout'))).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });
});
