import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReleaseSuspensions } from '@/modules/jobs';
import { backend, me, paged, sent, t, wrap } from './support/harness';

/**
 * O-02 Release Suspension (plan 05 phase 05h; SRS-15-35, BR-SEC-006): Finance at HQ suspends at once and
 * asks for the resumption; outside HQ, or while a resumption waits, or once resumed, the actions are
 * disabled with the reason.
 */

afterEach(() => vi.unstubAllGlobals());

const suspension = (over: object = {}) => ({
  id: 5,
  scope: 'UNIT',
  unitCode: 'DH001',
  channel: 'BANK_FILE',
  suspendedBy: 'finance.hq',
  suspendedAt: '2026-10-25T06:00:00Z',
  reason: 'Bank file mismatch',
  resumedBy: null,
  resumedAt: null,
  resumeReason: null,
  pendingApprovalId: null,
  ...over,
});

function routes(rows: unknown[], over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({ 'GET /v1/release-suspensions?': (url) => paged(rows, rows.length, url), ...over });
}

const FINANCE = ['O-02:VIEW', 'O-02:SUSPEND', 'O-02:RESUME'];

describe('O-02 Release Suspension', () => {
  it('SRS-15-35: Finance at HQ suspends a unit and channel with a reason', async () => {
    const calls = routes([], { 'POST /v1/release-suspensions': () => ({ status: 201, body: { id: 6 } }) });
    wrap(<ReleaseSuspensions />, me(FINANCE, { atHeadquarters: true }));
    expect(await screen.findByText(t('o02.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('o02.suspend') }));
    const dialog = screen.getByRole('dialog');
    const submit = within(dialog).getByRole('button', { name: t('o02.suspend') });
    await userEvent.selectOptions(within(dialog).getByLabelText(t('o02.scope')), 'UNIT');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Bank file mismatch');
    expect(submit).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(t('o02.unitCode')), 'DH001');
    await userEvent.selectOptions(within(dialog).getByLabelText(t('o02.channel')), 'BANK_FILE');
    await userEvent.click(submit);
    expect(await screen.findByText(t('o02.suspendedDone'))).toBeTruthy();
    expect(sent(calls, 'POST', '/release-suspensions')).toEqual([
      { scope: 'UNIT', channel: 'BANK_FILE', reason: 'Bank file mismatch', unitCode: 'DH001' },
    ]);
  });

  it('SRS-15-35: an open suspension is shown in force; Finance asks for its resumption; a waiting or resumed one is held', async () => {
    const calls = routes(
      [
        suspension(),
        suspension({ id: 6, scope: 'ALL_UNITS', unitCode: null, channel: 'ALL', pendingApprovalId: 70 }),
        suspension({
          id: 4,
          resumedBy: 'checker.hq',
          resumedAt: '2026-10-24T06:00:00Z',
          resumeReason: 'Reconciled',
        }),
      ],
      { 'POST /v1/release-suspensions/5/resumptions': () => ({ status: 202, body: { id: 71 } }) },
    );
    wrap(<ReleaseSuspensions />, me(FINANCE, { atHeadquarters: true }));
    expect(await screen.findByText('Reconciled')).toBeTruthy();
    expect(screen.getAllByText(t('o02.active'))).toHaveLength(2);
    expect(screen.getByRole('button', { name: t('o02.resume', [6]) })).toHaveAttribute(
      'title',
      t('o02.pending', [70]),
    );
    expect(screen.getByRole('button', { name: t('o02.resume', [4]) })).toHaveAttribute(
      'title',
      t('o02.alreadyResumed'),
    );
    await userEvent.click(screen.getByRole('button', { name: t('o02.resume', [5]) }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Reconciled today');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('o02.resumeRequested'))).toBeTruthy();
    expect(sent(calls, 'POST', '/resumptions')).toEqual([{ reason: 'Reconciled today' }]);
  });

  it('SRS-15-35: outside Headquarters the actions are disabled with the reason; a viewer has none', async () => {
    routes([suspension()]);
    const view = wrap(<ReleaseSuspensions />, me(FINANCE, { atHeadquarters: false }));
    expect(await screen.findByRole('button', { name: t('o02.suspend') })).toHaveAttribute(
      'title',
      t('error.AUTHZ-403-HQ-ONLY'),
    );
    expect(await screen.findByRole('button', { name: t('o02.resume', [5]) })).toBeDisabled();
    view.unmount();
    routes([suspension()]);
    wrap(<ReleaseSuspensions />, me(['O-02:VIEW'], { atHeadquarters: true }));
    expect(await screen.findByText('Bank file mismatch')).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('o02.suspend') })).toBeNull();
  });
});
