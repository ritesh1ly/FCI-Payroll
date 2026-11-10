import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StaffNotices } from '@/modules/eseva';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** Staff notices on the home page (owner 2026-10-28; SRS-15-30, SRS-1-68). */

afterEach(() => vi.unstubAllGlobals());

describe('Staff notices', () => {
  it('SRS-15-30: a returned cycle is shown unread and marked read', async () => {
    const calls = backend({
      'GET /v1/notifications/mine?': (url) =>
        paged(
          [
            {
              id: 4,
              type: 'CYCLE_RETURNED',
              message: 'Payroll of DH001 returned',
              read: false,
              createdAt: '2026-10-28T10:00:00',
            },
            {
              id: 3,
              type: 'I21_OVERDUE',
              // A notice may carry no message: only its type is shown.
              message: null,
              read: true,
              createdAt: '2026-10-27T10:00:00',
            },
          ],
          2,
          url,
        ),
      'POST /v1/notifications/mine/4/reads': () => ({ status: 204, body: null }),
    });
    wrap(<StaffNotices />, me(['W-01:VIEW']));
    expect(await screen.findByText(t('notice.CYCLE_RETURNED'))).toBeTruthy();
    expect(screen.getByText(t('notice.I21_OVERDUE'))).toBeTruthy();
    const buttons = screen.getAllByRole('button', { name: t('action.e01.markRead') });
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0] as HTMLElement);
    await vi.waitFor(() => expect(sent(calls, 'POST', '/reads')).toHaveLength(1));
  });

  it('SRS-17-12 (review 14-2): a failed mark-read is shown and the notice stays unread', async () => {
    backend({
      'GET /v1/notifications/mine?': (url) =>
        paged(
          [
            {
              id: 4,
              type: 'CYCLE_RETURNED',
              message: 'Payroll of DH001 returned',
              read: false,
              createdAt: '2026-10-28T10:00:00',
            },
          ],
          1,
          url,
        ),
      'POST /v1/notifications/mine/4/reads': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<StaffNotices />, me(['W-01:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('action.e01.markRead') }));
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.e01.markRead') })).toBeEnabled();
  });

  it('SRS-15-30: no notice says so', async () => {
    backend({ 'GET /v1/notifications/mine?': (url) => paged([], 0, url) });
    wrap(<StaffNotices />, me([]));
    expect(await screen.findByText(t('dash.noNotices'))).toBeTruthy();
  });
});
