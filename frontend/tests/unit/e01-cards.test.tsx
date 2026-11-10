import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MyCpfStatement } from '@/modules/cpf';
import { MyNotifications } from '@/modules/eseva';
import { MyLeaveBalance } from '@/modules/leave';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * E-01 cards of plan 14 phase 14e: the member's notices (SRS-17-12), the leave balance from HRMS
 * (SRS-LV-32) and the CPF statement from the Payroll ledger (SRS-17-17).
 */

afterEach(() => vi.unstubAllGlobals());

describe('E-01 notices', () => {
  it('SRS-17-12: each status is a notice the member marks read', async () => {
    const calls = backend({
      'GET /v1/eseva/notifications/mine': (url) =>
        paged(
          [
            {
              id: 5,
              type: 'ESEVA_ROUTED',
              relatedEntity: 'ess_submission',
              relatedKey: '3',
              message: 'VPF_SUBSCRIPTION: VPF 3000',
              read: false,
              createdAt: '2026-10-28T10:00:00',
            },
            {
              id: 4,
              type: 'ESEVA_SUBMITTED',
              relatedEntity: 'ess_submission',
              relatedKey: '3',
              message: null,
              read: true,
              createdAt: '2026-10-28T09:00:00',
            },
          ],
          2,
          url,
        ),
      'POST /v1/eseva/notifications/mine/5/reads': () => ({ status: 204 }),
    });
    wrap(<MyNotifications />, me(['E-01:VIEW']));
    const list = await screen.findByRole('list', { name: t('e01.notices') });
    expect(within(list).getByText(t('notice.ESEVA_ROUTED'))).toBeTruthy();
    expect(within(list).getAllByRole('button', { name: t('action.e01.markRead') })).toHaveLength(1);
    await userEvent.click(within(list).getByRole('button', { name: t('action.e01.markRead') }));
    await vi.waitFor(() => expect(sent(calls, 'POST', '/reads')).toHaveLength(1));
  });

  it('SRS-17-12 (review 14-2): a failed mark-read is shown and the notice stays unread', async () => {
    backend({
      'GET /v1/eseva/notifications/mine': (url) =>
        paged(
          [
            {
              id: 5,
              type: 'ESEVA_ROUTED',
              relatedEntity: 'ess_submission',
              relatedKey: '3',
              message: null,
              read: false,
              createdAt: '2026-10-28T10:00:00',
            },
          ],
          1,
          url,
        ),
      'POST /v1/eseva/notifications/mine/5/reads': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<MyNotifications />, me(['E-01:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('action.e01.markRead') }));
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.e01.markRead') })).toBeEnabled();
  });

  it('SRS-17-12: nothing for staff; an empty list says so', async () => {
    backend({ 'GET /v1/eseva/notifications/mine': (url) => paged([], 0, url) });
    const staff = wrap(<MyNotifications />, me(['E-02:VIEW']));
    expect(staff.container.textContent).toBe('');
    wrap(<MyNotifications />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('e01.noNotices'))).toBeTruthy();
  });
});

describe('E-01 notice labels', () => {
  it('SRS-12-10 (US-09-12 AC1): every notice type the server writes reads as a label, never its code', () => {
    for (const type of [
      'ESEVA_SUBMITTED',
      'ESEVA_ROUTED',
      'ESEVA_ACTIONED',
      'ESEVA_REJECTED',
      'LOAN_INSTALMENT',
    ]) {
      expect(t(`notice.${type}`)).not.toBe(`notice.${type}`);
    }
    for (const type of [
      'SALARY',
      'INCENTIVE',
      'SUPPLEMENTARY',
      'ARREAR',
      'ADJUSTMENT',
      'DEPUTATION_RECEIPT',
    ]) {
      expect(t(`e01.paymentType.${type}`)).not.toBe(`e01.paymentType.${type}`);
    }
  });
});

describe('E-01 leave balance', () => {
  it('SRS-LV-32: the latest balance per leave type from HRMS, read only', async () => {
    backend({
      'GET /v1/leave-balances/mine': () => [
        { leaveType: 'EL', leaveTypeName: 'Earned Leave', days: 120.5, asOf: '2026-10-01' },
      ],
    });
    wrap(<MyLeaveBalance />, me(['E-01:VIEW']));
    const table = await screen.findByRole('table', { name: t('e01.leaveBalance') });
    expect(within(table).getByText('EL - Earned Leave')).toBeTruthy();
    expect(within(table).getByText('120.5')).toBeTruthy();
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('SRS-LV-32: no balance yet; nothing for staff', async () => {
    backend({ 'GET /v1/leave-balances/mine': () => [] });
    wrap(<MyLeaveBalance />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('e01.noLeaveBalance'))).toBeTruthy();
    const staff = wrap(<MyLeaveBalance />, me(['E-02:VIEW']));
    expect(staff.container.textContent).toBe('');
  });
});

describe('E-01 CPF statement', () => {
  it('SRS-17-17: the balances of each part and the postings of the year; another year on request', async () => {
    const calls = backend({
      'GET /v1/cpf/statements/mine': (url) => ({
        finYear: url.includes('finYear=2025-26') ? '2025-26' : '2026-27',
        ledgers: [
          {
            ledgerId: 1,
            memberStatus: 'ACTIVE',
            openingEmployee: 100000,
            openingEmployer: 90000,
            openingVpf: 5000,
            closingEmployee: 110000,
            closingEmployer: 99000,
            closingVpf: 7500,
            interestCredited: 8000,
          },
        ],
        postings: [{ yymm: '202604', paymentType: 'SALARY', employee: 5400, employer: 5400, vpf: 2500 }],
      }),
    });
    wrap(<MyCpfStatement />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('e01.cpfStatement', ['2026-27']))).toBeTruthy();
    const postings = screen.getByRole('table', { name: t('e01.cpfPostings') });
    expect(within(postings).getByText('04/2026')).toBeTruthy();
    // UI review #52 (UI-DESC): the posting type by its description, never the code.
    expect(within(postings).getByText(t('e01.paymentType.SALARY'))).toBeTruthy();
    expect(within(postings).queryByText('SALARY')).toBeNull();
    await userEvent.type(screen.getByLabelText(t('e01.finYear')), '2025-26');
    expect(await screen.findByText(t('e01.cpfStatement', ['2025-26']))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('finYear=2025-26'))).toBe(true);
  });

  it('SRS-17-17: no ledger this year; nothing for staff', async () => {
    backend({ 'GET /v1/cpf/statements/mine': () => ({ finYear: '2026-27', ledgers: [], postings: [] }) });
    wrap(<MyCpfStatement />, me(['E-01:VIEW']));
    expect(await screen.findByText(t('e01.noCpfLedger'))).toBeTruthy();
    const staff = wrap(<MyCpfStatement />, me(['E-02:VIEW']));
    expect(staff.container.textContent).toBe('');
  });

  it('SRS-17-17: a failed load shows the problem and keeps the year selector, so another year can be asked', async () => {
    const calls = backend({
      'GET /v1/cpf/statements/mine': (url) =>
        url.includes('finYear=2025-26')
          ? { finYear: '2025-26', ledgers: [], postings: [] }
          : problem(500, 'COM-500-INTERNAL'),
    });
    wrap(<MyCpfStatement />, me(['E-01:VIEW']));
    expect(await screen.findByRole('alert')).toBeTruthy();
    const year = screen.getByLabelText(t('e01.finYear'));
    // Edge: a partly typed year asks nothing new.
    await userEvent.type(year, '2025-2');
    expect(calls.filter((c) => c.url.includes('finYear='))).toHaveLength(0);
    await userEvent.type(year, '6');
    expect(await screen.findByText(t('e01.cpfStatement', ['2025-26']))).toBeTruthy();
    expect(await screen.findByText(t('e01.noCpfLedger'))).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
