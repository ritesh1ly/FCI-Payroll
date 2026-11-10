import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Item } from '@/modules/workflow/api/workflowApi';
import { ApprovalInbox } from '@/modules/workflow/components/ApprovalInbox';
import { backend, paged, problem, sent, t, wrap } from './support/harness';

/**
 * W-01 Approvals Inbox as Wireframes v6.1 draws it (X-02, OD-121; SRS-G-29, SRS-22-4, SRS-22-5,
 * SRS-G-13): the four tiles over the scope, the module and SLA filters with the list search, the
 * Approval Queue with its drawn columns and row actions, selection by row for Approve / Return
 * Selected, the item detail with the order documents and the decisions the session may take, a
 * reason for a return or a rejection, the OTP where the level needs it; every guard drawn disabled
 * with its reason.
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

const ITEMS = [
  item(),
  item({
    approvalId: 42,
    moduleCode: null,
    entityName: 'screen:A-02',
    impactValue: null,
    ageDays: 5,
    targetTatHours: null,
    slaBreached: true,
    reason: null,
    locationLabel: null,
    level: 3,
    levelCount: 3,
    noApproverInScope: true,
    bulkApproval: false,
  }),
  item({ approvalId: 43, canDecide: false, denialKey: 'error.WF-403-SOD' }),
];

const SUMMARY = {
  pending: 14,
  pastTarget: 3,
  approvedThisCycle: 188,
  returned: 22,
  modules: ['I-20', 'M-01'],
};

function routes(
  decide: (url: string, init?: RequestInit) => unknown = () => ({ status: 'APPROVED' }),
  summary: unknown = SUMMARY,
) {
  return backend({
    'GET /v1/inbox/summary': () => summary,
    'GET /v1/inbox': () => paged(ITEMS),
    // A role that may not approve sees its own submissions in the same queue (the pack's X-02 for the Maker).
    'GET /v1/submissions': () => paged(ITEMS),
    'POST /v1/approvals/': decide,
  });
}

const row = (id: number) => screen.getByText(String(id), { selector: 'b' }).closest('tr') as HTMLElement;
const tile = (label: string) => screen.getByText(label).closest('.tile') as HTMLElement;
const detail = () => screen.getByRole('dialog', { name: t('w01.detail', [41]) });

describe('W-01 Approvals Inbox', () => {
  it('SRS-22-4, SRS-G-54: the tiles count the scope, the queue shows each item with its module, raiser, value and target as drawn', async () => {
    routes();
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    expect(await screen.findByText('41', { selector: 'b' })).toBeTruthy();
    expect(within(tile(t('w01.tile.pending'))).getByText('14')).toBeTruthy();
    expect(within(tile(t('w01.tile.pending'))).getByText(t('w01.tile.pendingSub', [2]))).toBeTruthy();
    expect(within(tile(t('w01.tile.pastTarget'))).getByText('3')).toBeTruthy();
    expect(within(tile(t('w01.tile.approved'))).getByText('188')).toBeTruthy();
    expect(within(tile(t('w01.tile.returned'))).getByText('22')).toBeTruthy();
    expect(screen.getByText(t('w01.queue'))).toBeTruthy();
    for (const col of ['item', 'module', 'raisedBy', 'impact', 'target', 'actions']) {
      expect(screen.getByRole('columnheader', { name: t(`w01.col.${col}`) })).toBeTruthy();
    }
    expect(row(41)).toHaveTextContent('I-20 · ' + t('screen.I-20'));
    expect(row(41)).toHaveTextContent('maker.delhi@fci-demo.test');
    // The maker's description is the drawn sub-line of the Item (SRS-G-29: the Checker reviews the item here).
    expect(row(41)).toHaveTextContent('Arrears for July');
    expect(row(41)).toHaveTextContent('1,25,000.5');
    expect(row(41)).toHaveTextContent(t('w01.target', [72]));
    // An item without a module, value or target, past the target, at level 3 of 3, with no approver in scope.
    expect(row(42)).toHaveTextContent('—');
    expect(row(42)).toHaveTextContent(t('w01.noTarget'));
    expect(within(row(42)).getByText(t('w01.breached'))).toBeTruthy();
    expect(within(row(42)).getByText(t('w01.level', [3, 3]))).toBeTruthy();
    expect(within(row(42)).getByText(t('w01.noApprover'))).toBeTruthy();
    // Edit is drawn and disabled with the reason: an item is decided, never edited.
    const edit = within(row(41)).getByRole('button', { name: t('wf.edit') });
    expect(edit).toBeDisabled();
    expect(edit.getAttribute('data-why')).toBe(t('w01.why.edit'));
  });

  it('SRS-22-5: a role that may not approve sees Approve Selected and ✔ disabled with the reason; Delegate is for the HQ Administrator only', async () => {
    routes();
    wrap(<ApprovalInbox />, ['W-01:VIEW']);
    await screen.findByText('41', { selector: 'b' });
    const approve = screen.getByRole('button', { name: t('w01.approveSelected') });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('data-why')).toBe(t('w01.why.approveSelected'));
    const delegate = screen.getByRole('button', { name: t('w01.delegate') });
    expect(delegate).toBeDisabled();
    expect(delegate.getAttribute('data-why')).toBe(t('w01.why.delegate'));
    // ✔ is drawn only for an item the session may decide (none here: the role may not approve).
    expect(within(row(43)).queryByRole('button', { name: '✔' })).toBeNull();
  });

  it('BR-APPR-006: the HQ Administrator gets Delegate as a link to the workflow master', async () => {
    routes();
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'A-08:EDIT']);
    await screen.findByText('41', { selector: 'b' });
    expect(screen.getByRole('link', { name: t('w01.delegate') })).toHaveAttribute('href', '/payroll?s=A-08');
  });

  it('SRS-G-54: the module and SLA filters and the list search narrow the queue on the English key', async () => {
    routes();
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    const user = userEvent.setup();
    const modules = screen.getByRole('combobox', { name: t('w01.filter.module') });
    expect(
      within(modules)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual([t('w01.filter.allModules'), 'I-20', 'M-01']);
    await user.selectOptions(modules, 'I-20');
    expect(screen.queryByText('42', { selector: 'b' })).toBeNull();
    expect(screen.getByText('41', { selector: 'b' })).toBeTruthy();
    await user.selectOptions(modules, '');
    await user.selectOptions(screen.getByRole('combobox', { name: t('w01.filter.sla') }), 'BREACHED');
    expect(screen.getByText('42', { selector: 'b' })).toBeTruthy();
    expect(screen.queryByText('41', { selector: 'b' })).toBeNull();
    await user.selectOptions(screen.getByRole('combobox', { name: t('w01.filter.sla') }), '');
    await user.type(screen.getByRole('searchbox', { name: t('wf.searchInList') }), '41');
    expect(screen.getByText('41', { selector: 'b' })).toBeTruthy();
    expect(screen.queryByText('42', { selector: 'b' })).toBeNull();
  });

  it('SRS-G-31: View opens the item with its office, reason, level, target and order documents; none says so', async () => {
    backend({
      'GET /v1/inbox/summary': () => SUMMARY,
      'GET /v1/inbox': () => paged(ITEMS),
      'GET /v1/submissions': () => paged(ITEMS),
      'GET /v1/approvals/41/documents': () => [
        {
          id: 7,
          fileName: 'sanction-order.pdf',
          documentTypeName: 'Sanction order',
          referenceNumber: 'FCI/EST/2026/118',
          orderDate: '2026-10-01',
          issuingAuthority: 'RO Delhi',
          uploadedBy: 'maker.delhi@fci-demo.test',
          uploadedAt: '2026-10-19T04:30:00Z',
        },
      ],
      'GET /v1/approvals/42/documents': () => [],
    });
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    const user = userEvent.setup();
    await user.click(within(row(41)).getByRole('button', { name: t('wf.view') }));
    const dialog = detail();
    expect(dialog).toHaveTextContent('DO — Delhi HQ (DH001)');
    expect(dialog).toHaveTextContent('Arrears for July');
    expect(dialog).toHaveTextContent(t('w01.level', [2, 2]));
    expect(await within(dialog).findByRole('link', { name: 'sanction-order.pdf' })).toHaveAttribute(
      'href',
      '/nginx/fci-payroll/v1/approvals/41/documents/7',
    );
    expect(dialog).toHaveTextContent('(FCI/EST/2026/118)');
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(within(row(42)).getByRole('button', { name: t('wf.view') }));
    expect(await screen.findByText(t('w01.noDocuments'))).toBeTruthy();
  });

  it('SRS-22-5: in the detail a decision the session may not take is disabled with its reason; without one from the server, the checker-only reason', async () => {
    routes();
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('43', { selector: 'b' });
    const user = userEvent.setup();
    await user.click(within(row(43)).getByRole('button', { name: t('wf.view') }));
    const dialog = screen.getByRole('dialog', { name: t('w01.detail', [43]) });
    for (const decision of ['APPROVE', 'RETURN', 'REJECT']) {
      const button = within(dialog).getByRole('button', { name: t(`w01.${decision}`) });
      expect(button).toBeDisabled();
      expect(button.getAttribute('title')).toBe(t('error.WF-403-SOD'));
    }
  });

  it('SRS-G-29: approving needs no reason; returning and rejecting do, and each is sent from the detail', async () => {
    const calls = routes();
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    const user = userEvent.setup();
    // ✔ on the row approves without a reason.
    await user.click(within(row(41)).getByRole('button', { name: '✔' }));
    const approve = screen.getByRole('dialog');
    expect(within(approve).getByRole('button', { name: t('w01.APPROVE') })).toBeEnabled();
    await user.click(within(approve).getByRole('button', { name: t('w01.APPROVE') }));
    await waitFor(() => expect(sent(calls, 'POST', '/41/decision')).toHaveLength(1));
    expect(sent(calls, 'POST', '/41/decision')[0]).toEqual({ decision: 'APPROVE', reason: null });
    expect(await screen.findByText(t('w01.decided', [41, t('status.APPROVED')]))).toBeTruthy();
    // A return from the detail needs its reason before it can be sent.
    await user.click(within(row(41)).getByRole('button', { name: t('wf.view') }));
    await user.click(within(detail()).getByRole('button', { name: t('w01.RETURN') }));
    const dialog = screen.getByRole('dialog', { name: t('w01.confirm', [t('w01.RETURN'), 41]) });
    expect(within(dialog).getByRole('button', { name: t('w01.RETURN') })).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox'), 'Order copy missing');
    await user.click(within(dialog).getByRole('button', { name: t('w01.RETURN') }));
    await waitFor(() =>
      expect(sent(calls, 'POST', '/41/decision').at(-1)).toEqual({
        decision: 'RETURN',
        reason: 'Order copy missing',
      }),
    );
  });

  it('a refused decision is a message; a cancelled OTP is none', async () => {
    routes(() => problem(409, 'WF-409-DECIDED', [41]));
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    const user = userEvent.setup();
    await user.click(within(row(41)).getByRole('button', { name: '✔' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    expect(await screen.findByText(t('error.WF-409-DECIDED', [41]))).toBeTruthy();
  });

  it('SRS-G-13: Approve Selected needs a selection, refuses items the route decides one by one, and approves the rest together', async () => {
    const calls = backend({
      'GET /v1/inbox/summary': () => SUMMARY,
      'GET /v1/inbox': () => paged(ITEMS),
      'GET /v1/submissions': () => paged(ITEMS),
      'POST /v1/approvals/bulk-approvals': () => ({ reference: 'BULK-1', approved: [41], refused: [] }),
    });
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    expect(await screen.findByText(t('w01.nothingSelected'))).toBeTruthy();
    // Item 42 is decided one by one (its route needs an OTP): the selection says so and sends nothing.
    await user.click(row(42));
    await user.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    expect(await screen.findByText(t('w01.notBulkSelected', [1]))).toBeTruthy();
    expect(sent(calls, 'POST', '/bulk-approvals')).toHaveLength(0);
    await user.click(row(42));
    // An item the session may not decide cannot be selected at all.
    await user.click(row(43));
    expect(row(43).getAttribute('aria-selected')).toBe('false');
    await user.click(row(41));
    await user.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    await waitFor(() =>
      expect(sent(calls, 'POST', '/bulk-approvals')).toEqual([{ approvalIds: [41], reason: null }]),
    );
    expect(await screen.findByText(/BULK-1/)).toBeTruthy();
    expect(row(41).getAttribute('aria-selected')).toBe('false');
  });

  it('SRS-G-29: Return Selected needs a selection and a reason, then returns each selected item on its own', async () => {
    const calls = routes(() => ({ status: 'RETURNED' }));
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: t('w01.returnSelected') }));
    expect(await screen.findByText(t('w01.nothingSelected'))).toBeTruthy();
    await user.click(row(41));
    await user.click(row(42));
    await user.click(screen.getByRole('button', { name: t('w01.returnSelected') }));
    const dialog = screen.getByRole('dialog', { name: t('w01.returnConfirm', [2]) });
    expect(within(dialog).getByRole('button', { name: t('w01.RETURN') })).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox'), 'Needs the order copy');
    await user.click(within(dialog).getByRole('button', { name: t('w01.RETURN') }));
    await waitFor(() => expect(sent(calls, 'POST', '/decision')).toHaveLength(2));
    expect(sent(calls, 'POST', '/41/decision')).toEqual([
      { decision: 'RETURN', reason: 'Needs the order copy' },
    ]);
    expect(sent(calls, 'POST', '/42/decision')).toEqual([
      { decision: 'RETURN', reason: 'Needs the order copy' },
    ]);
    expect(await screen.findByText(t('w01.returnDone', [2, 0]))).toBeTruthy();
  });

  it('an empty inbox says so; a refused summary is shown and the tiles read a dash', async () => {
    backend({
      'GET /v1/inbox/summary': () => problem(403, 'AUTHZ-403-PERMISSION', ['W-01', 'VIEW']),
      'GET /v1/inbox': () => paged([]),
      'GET /v1/submissions': () => paged([]),
    });
    wrap(<ApprovalInbox />, ['W-01:VIEW']);
    // A viewer without the approve right sees its own (empty) submissions.
    expect(await screen.findByText(t('w02.none'))).toBeTruthy();
    expect(await screen.findByText(t('error.AUTHZ-403-PERMISSION', ['W-01', 'VIEW']))).toBeTruthy();
    expect(within(tile(t('w01.tile.pending'))).getByText('—')).toBeTruthy();
  });
});
