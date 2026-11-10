import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Item } from '@/modules/workflow/api/workflowApi';
import { ApprovalInbox } from '@/modules/workflow/components/ApprovalInbox';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * W-01 Approvals Inbox (SRS-G-29, SRS-G-13, SRS-22-5, R-OTP): a return of several items counts the refused ones and
 * stops when the Checker closes the OTP step; a bulk approval names the reason of each refusal; the reason dialogs
 * cancel without sending; a closed OTP step on one decision says nothing; a decision the server gives no reason for
 * is the Checker's only; an item decided elsewhere drops out of the selection.
 */

afterEach(() => vi.unstubAllGlobals());

const item = (over: Partial<Item> = {}): Item => ({
  approvalId: 41,
  moduleCode: 'I-20',
  entityName: 'off_cycle_payment',
  entityKey: 'OC-2026-0001',
  actionType: 'CREATE',
  status: 'PENDING_APPROVAL',
  submittedBy: 'maker.delhi@fci-demo.test',
  submittedAt: '2026-10-19T04:30:00Z',
  reason: 'Arrears for July',
  assignedRoleCode: 'PAYROLL_CHECKER',
  locationLabel: 'DO — Delhi HQ (DH001)',
  slaDueAt: '2026-10-22T04:30:00Z',
  slaBreached: false,
  impactValue: 125000.5,
  ageDays: 2,
  targetTatHours: 72,
  level: 2,
  levelCount: 2,
  noApproverInScope: false,
  canDecide: true,
  denialKey: null,
  bulkApproval: true,
  ...over,
});

const SUMMARY = { pending: 2, pastTarget: 0, approvedThisCycle: 0, returned: 0, modules: ['I-20'] };

function routes(
  items: () => Item[],
  decide: (url: string) => unknown = () => ({ status: 'APPROVED' }),
  extra: Record<string, (url: string) => unknown> = {},
) {
  return backend({
    'GET /v1/inbox/summary': () => SUMMARY,
    'GET /v1/inbox': (url) => paged(items(), items().length, url),
    'GET /v1/submissions': (url) => paged(items(), items().length, url),
    'POST /v1/approvals/': decide,
    ...extra,
  });
}

const row = (id: number) => screen.getByText(String(id), { selector: 'b' }).closest('tr') as HTMLElement;
const ROLES = ['W-01:VIEW', 'W-01:APPROVE'];

async function cancelOtp() {
  const otp = await screen.findByRole('dialog', { name: t('otp.title') });
  await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
}

describe('W-01 several items at once', () => {
  it('SRS-G-29: a return counts a refused item and stops at a closed OTP step', async () => {
    const calls = routes(
      () => [item(), item({ approvalId: 42 }), item({ approvalId: 44 })],
      (url) =>
        url.includes('/41/') ? problem(409, 'WF-409-DECIDED', [41]) : problem(428, 'AUTH-428-OTP', ['7']),
    );
    wrap(<ApprovalInbox />, ROLES);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(row(41));
    await userEvent.click(row(42));
    await userEvent.click(row(44));
    await userEvent.click(screen.getByRole('button', { name: t('w01.returnSelected') }));
    const dialog = screen.getByRole('dialog', { name: t('w01.returnConfirm', [3]) });
    await userEvent.type(within(dialog).getByRole('textbox'), 'Needs the order copy');
    await userEvent.click(within(dialog).getByRole('button', { name: t('w01.RETURN') }));
    await cancelOtp();
    expect(await screen.findByText(t('w01.returnDone', [0, 1]))).toBeTruthy();
    // Item 44 was never sent: the closed OTP step stopped the run.
    expect(sent(calls, 'POST', '/44/decision')).toHaveLength(0);
  });

  it('SRS-G-13: a bulk approval names the reason of an item the server refused', async () => {
    routes(() => [item(), item({ approvalId: 42 })], undefined, {
      'POST /v1/approvals/bulk-approvals': () => ({
        reference: 'BULK-2',
        approved: [42],
        refused: [{ approvalId: 41, reason: 'WF-409-DECIDED' }],
      }),
    });
    wrap(<ApprovalInbox />, ROLES);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(row(41));
    await userEvent.click(row(42));
    await userEvent.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    const refused = t('w01.bulkRefused', [41, t('error.WF-409-DECIDED')]);
    expect(await screen.findByText((text) => text.includes(refused))).toBeTruthy();
    expect(screen.queryByText((text) => text.includes(t('w01.notBulk')))).toBeNull();
  });

  it('SRS-G-29: the return reason dialog cancels without sending; the selection stays', async () => {
    const calls = routes(() => [item()]);
    wrap(<ApprovalInbox />, ROLES);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(row(41));
    await userEvent.click(screen.getByRole('button', { name: t('w01.returnSelected') }));
    const dialog = screen.getByRole('dialog', { name: t('w01.returnConfirm', [1]) });
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    expect(row(41).getAttribute('aria-selected')).toBe('true');
  });

  it('SRS-22-5: an item decided elsewhere drops out of the selection on the next read', async () => {
    let list = [item(), item({ approvalId: 42 })];
    routes(
      () => list,
      () => {
        list = [item()];
        return { status: 'APPROVED' };
      },
      // The session read again after the decision keeps the Checker's rights.
      { 'GET /auth/me': () => me(ROLES) },
    );
    wrap(<ApprovalInbox />, ROLES);
    await screen.findByText('42', { selector: 'b' });
    await userEvent.click(row(41));
    await userEvent.click(row(42));
    await userEvent.click(within(row(42)).getByRole('button', { name: '✔' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    await waitFor(() => expect(screen.queryByText('42', { selector: 'b' })).toBeNull());
    await userEvent.click(screen.getByRole('button', { name: t('w01.returnSelected') }));
    expect(screen.getByRole('dialog', { name: t('w01.returnConfirm', [1]) })).toBeTruthy();
  });
});

describe('W-01 one item', () => {
  it('R-OTP: a closed OTP step on a decision says nothing and keeps the item', async () => {
    routes(
      () => [item()],
      () => problem(428, 'AUTH-428-OTP', ['7']),
    );
    wrap(<ApprovalInbox />, ROLES);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(within(row(41)).getByRole('button', { name: '✔' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    await cancelOtp();
    expect(screen.queryByText(t('w01.decided', [41, t('status.APPROVED')]))).toBeNull();
    expect(row(41)).toBeTruthy();
  });

  it('SRS-G-29: the decision reason dialog cancels without sending', async () => {
    const calls = routes(() => [item()]);
    wrap(<ApprovalInbox />, ROLES);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(within(row(41)).getByRole('button', { name: '✔' }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS-22-5: a decision the session may not take, with no reason from the server, is the Checker’s only', async () => {
    routes(() => [item({ canDecide: false, denialKey: null })]);
    wrap(<ApprovalInbox />, ROLES);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(within(row(41)).getByRole('button', { name: t('wf.view') }));
    const dialog = screen.getByRole('dialog', { name: t('w01.detail', [41]) });
    for (const decision of ['APPROVE', 'RETURN', 'REJECT']) {
      const button = within(dialog).getByRole('button', { name: t(`w01.${decision}`) });
      expect(button).toBeDisabled();
      expect(button.getAttribute('title')).toBe(t('reason.checkerOnly'));
      expect(button.getAttribute('data-why')).toBe(t('reason.checkerOnly'));
    }
  });
});
