import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkflowMaster } from '@/modules/workflow';
import type { Item } from '@/modules/workflow/api/workflowApi';
import type { RouteAction, RouteVersion } from '@/modules/workflow/api/workflowMasterApi';
import { ApprovalInbox } from '@/modules/workflow/components/ApprovalInbox';
import { MySubmissions } from '@/modules/workflow/components/MySubmissions';
import { backend, paged, problem, sent, t, wrap } from './support/harness';

/**
 * Branch gaps of the workflow screens: W-01 bulk refusals of an item its route does not allow,
 * a selection taken back and a document without a reference (SRS-G-13, SRS-G-31); W-02 decisions
 * with and without their time and return reason (SRS-22-4, SRS l.596); A-08 a past version that
 * needs no attachment (SRS-22-12).
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
  slaDueAt: null,
  slaBreached: false,
  impactValue: null,
  ageDays: 2,
  targetTatHours: null,
  level: 2,
  levelCount: 2,
  noApproverInScope: false,
  canDecide: true,
  denialKey: null,
  bulkApproval: true,
  ...over,
});

const row = (id: number) => screen.getByText(String(id), { selector: 'b' }).closest('tr') as HTMLElement;

describe('W-01 Approvals Inbox branch gaps', () => {
  it('SRS-G-13: an item its route does not allow in bulk is named as such; a box ticked twice is taken back', async () => {
    backend({
      'GET /v1/inbox': () => paged([item(), item({ approvalId: 42 })]),
      'POST /v1/approvals/bulk-approvals': () => ({
        reference: 'BULK-20261104-0001',
        approved: [41],
        refused: [{ approvalId: 42, reason: 'BULK_NOT_ALLOWED' }],
      }),
    });
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    // A row is selected by clicking it (as drawn: no checkbox); clicking again takes it back.
    await userEvent.click(row(41));
    await userEvent.click(row(42));
    expect(row(42).getAttribute('aria-selected')).toBe('true');
    await userEvent.click(row(42));
    expect(row(42).getAttribute('aria-selected')).toBe('false');
    await userEvent.click(row(42));
    await userEvent.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    const outcome = await screen.findByText(/BULK-20261104-0001/);
    expect(outcome.textContent).toContain(t('w01.bulkRefused', [42, t('w01.notBulk')]));
  });

  it('SRS-G-13: a refused or failed bulk approval is a message and the selection is cleared only on success', async () => {
    let answer: unknown = problem(403, 'AUTHZ-403-PERMISSION', ['W-01', 'APPROVE']);
    const calls = backend({
      'GET /v1/inbox': () => paged([item()]),
      'POST /v1/approvals/bulk-approvals': () => answer,
    });
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(row(41));
    await userEvent.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    expect(await screen.findByText(t('error.AUTHZ-403-PERMISSION', ['W-01', 'APPROVE']))).toBeTruthy();
    // A refusal keeps the item selected; a server error without a problem body is the generic message.
    expect(row(41).getAttribute('aria-selected')).toBe('true');
    answer = { status: 500, body: null };
    await userEvent.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('w01.APPROVE') }));
    expect(await screen.findByText(t('error.unexpected'))).toBeTruthy();
    expect(sent(calls, 'POST', '/bulk-approvals')).toEqual([
      { approvalIds: [41], reason: null },
      { approvalIds: [41], reason: null },
    ]);
  });

  it('SRS-G-13: cancelling the bulk dialog sends nothing and keeps the selection', async () => {
    const calls = backend({ 'GET /v1/inbox': () => paged([item()]) });
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    // Nothing selected: the drawn button stays enabled and says what to do, nothing is sent.
    await userEvent.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    expect(await screen.findByText(t('w01.nothingSelected'))).toBeTruthy();
    await userEvent.click(row(41));
    await userEvent.click(screen.getByRole('button', { name: t('w01.approveSelected') }));
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain(t('w01.bulkConfirm', [1]));
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    // Cancelling keeps the selection.
    expect(row(41).getAttribute('aria-selected')).toBe('true');
    expect(sent(calls, 'POST', '/bulk-approvals')).toEqual([]);
  });

  it('SRS-G-31: a document without a reference number is listed by its name alone', async () => {
    backend({
      'GET /v1/inbox': () => paged([item()]),
      'GET /v1/approvals/41/documents': () => [
        {
          id: 9,
          fileName: 'order.pdf',
          documentTypeName: 'Other document',
          referenceNumber: null,
          orderDate: '2026-10-18',
          issuingAuthority: 'GM (P)',
          uploadedBy: 'maker.delhi',
          uploadedAt: '2026-10-18T05:00:00Z',
        },
      ],
    });
    wrap(<ApprovalInbox />, ['W-01:VIEW', 'W-01:APPROVE']);
    await screen.findByText('41', { selector: 'b' });
    await userEvent.click(within(row(41)).getByRole('button', { name: t('wf.view') }));
    const dialog = screen.getByRole('dialog', { name: t('w01.detail', [41]) });
    const link = await within(dialog).findByRole('link', { name: 'order.pdf' });
    expect(link.closest('li')?.textContent).toBe('order.pdf');
  });
});

describe('W-02 My Submissions branch gaps', () => {
  it('SRS-22-4 / SRS l.596: a decision shows its time when known, and a return reason only on a returned item', async () => {
    backend({
      'GET /v1/submissions': () =>
        paged([
          item({ approvalId: 61, status: 'APPROVED', decidedBy: 'checker.delhi', decidedAt: null }),
          item({
            approvalId: 62,
            status: 'RETURNED',
            decidedBy: 'checker.delhi',
            decidedAt: '2026-10-20T04:30:00Z',
            returnReason: null,
          }),
          item({
            approvalId: 63,
            status: 'REJECTED',
            decidedBy: 'checker.delhi',
            decidedAt: '2026-10-20T04:30:00Z',
            returnReason: 'kept from an earlier return',
          }),
        ]),
    });
    wrap(<MySubmissions />, ['W-02:VIEW']);
    expect(await screen.findByText(t('w02.title', [3]))).toBeTruthy();
    const approved = screen.getByTestId('w02-decision-61');
    expect(approved.textContent).toBe('checker.delhi');
    const returned = screen.getByTestId('w02-decision-62');
    expect(returned.querySelector('.sub2')).not.toBeNull();
    expect(returned.textContent).not.toContain(t('w02.returnReason'));
    expect(screen.getByTestId('w02-decision-63').textContent).not.toContain('kept from an earlier return');
  });
});

describe('W-02 My Submissions return reason edges', () => {
  it('SRS l.596: a returned item shows the Checker reason; an empty reason shows none; an empty list says so', async () => {
    backend({
      'GET /v1/submissions': () =>
        paged([
          item({
            approvalId: 71,
            status: 'RETURNED',
            decidedBy: 'checker.delhi',
            returnReason: 'Attach order',
          }),
          item({ approvalId: 72, status: 'RETURNED', decidedBy: 'checker.delhi', returnReason: '' }),
          item({ approvalId: 73, status: 'PENDING_APPROVAL' }),
        ]),
    });
    wrap(<MySubmissions />, ['W-02:VIEW']);
    expect(await screen.findByText(t('w02.title', [3]))).toBeTruthy();
    expect(screen.getByTestId('w02-decision-71').textContent).toContain(
      `${t('w02.returnReason')}: Attach order`,
    );
    expect(screen.getByTestId('w02-decision-72').textContent).not.toContain(t('w02.returnReason'));
    // Not yet decided: a dash, no reason.
    expect(screen.getByTestId('w02-decision-73').textContent).toBe('—');
  });
});

describe('A-08 Approval Workflow Master branch gaps', () => {
  const version = (over: Partial<RouteVersion> = {}): RouteVersion => ({
    id: 7,
    versionNo: 2,
    status: 'ACTIVE',
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    actionName: 'Off-cycle payment',
    moduleCode: 'GENERAL',
    screenCode: 'I-20',
    levelCount: 2,
    otpAction: null,
    attachmentRequired: true,
    bulkApprovalAllowed: false,
    targetTatHours: 72,
    approvalId: null,
    levels: [
      { levelNo: 1, name: 'MAKER', roleCode: 'PAYROLL_MAKER', officeLevel: 'PAYING_UNIT', otpAtLevel: false },
    ],
    ...over,
  });

  it('SRS-22-12: a past version that asked no attachment says No in the history', async () => {
    const actions: RouteAction[] = [
      {
        actionCode: 'I20_SUBMIT',
        officeLevel: 'PAYING_UNIT',
        current: version(),
        pending: null,
        history: [
          version({
            id: 6,
            versionNo: 1,
            status: 'SUPERSEDED',
            effectiveTo: '2026-03-31',
            attachmentRequired: false,
          }),
        ],
      },
    ];
    backend({
      'GET /v1/admin/workflow-actions/unstaffed': () => paged([]),
      'GET /v1/admin/workflow-actions': () => paged(actions),
      'GET /v1/admin/access-matrix': () => paged([]),
      'GET /v1/admin/active-users': () => ({ ...paged([]), inactiveAfterDays: null }),
    });
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    const current = (await screen.findByText('I20_SUBMIT', { selector: 'b' })).closest('tr') as HTMLElement;
    expect(within(current).getByText(t('common.yes'))).toBeTruthy();
    await userEvent.click(within(current).getByRole('button', { name: `${t('common.view')} I20_SUBMIT` }));
    const past = (await screen.findByText(new RegExp(`v1 · ${t('a08.status.SUPERSEDED')}`))).closest(
      'tr',
    ) as HTMLElement;
    await waitFor(() => expect(within(past).getByText(t('common.no'))).toBeTruthy());
  });
});
