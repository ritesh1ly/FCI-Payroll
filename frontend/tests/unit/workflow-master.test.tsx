import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkflowMaster } from '@/modules/workflow';
import type { RouteAction, RouteVersion } from '@/modules/workflow/api/workflowMasterApi';
import { backend, me, problem, sent, t, wrap, paged } from './support/harness';

/**
 * A-08 Approval Workflow Master (Module 02 F-1; SRS-22-12, 22-16, 22-17, 22-19, SRS-G-28): the
 * routes with their versions, a new action or version proposed for approval, every broken rule
 * shown at its field, and the items no one in scope can decide.
 */

afterEach(() => vi.unstubAllGlobals());
// jsdom lays nothing out: the form of a long page scrolls into view through this.
Element.prototype.scrollIntoView = vi.fn();

const version = (over: Partial<RouteVersion> = {}): RouteVersion => ({
  id: 7,
  versionNo: 1,
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
    {
      levelNo: 2,
      name: 'CHECKER',
      roleCode: 'PAYROLL_CHECKER',
      officeLevel: 'PAYING_UNIT',
      otpAtLevel: false,
    },
  ],
  ...over,
});

const ACTIONS: RouteAction[] = [
  {
    actionCode: 'I20_SUBMIT',
    officeLevel: 'PAYING_UNIT',
    current: version(),
    pending: null,
    history: [
      version({
        id: 6,
        versionNo: 0,
        status: 'SUPERSEDED',
        effectiveTo: '2026-03-31',
        targetTatHours: null,
        otpAction: 'CPF_SANCTION_FINAL_PAYMENT',
      }),
    ],
  },
  {
    actionCode: 'A07_EDIT',
    officeLevel: 'HQ',
    current: version({
      id: 8,
      actionName: 'System parameters',
      screenCode: null,
      targetTatHours: null,
      otpAction: 'ROLE_ACCESS_GROUP_CHANGE',
      attachmentRequired: false,
    }),
    pending: version({
      id: 9,
      versionNo: 2,
      status: 'PENDING_APPROVAL',
      effectiveFrom: '2026-11-01',
      approvalId: 55,
    }),
    history: [],
  },
  {
    actionCode: 'NEW_ONE',
    officeLevel: 'DO',
    current: null,
    pending: version({ id: 10, status: 'PENDING_APPROVAL', actionName: 'New' }),
    history: [],
  },
];

const UNSTAFFED = [
  {
    approvalId: 99,
    moduleCode: 'I-17',
    entityName: 'employee_lifecycle_event',
    entityKey: '12',
    submittedBy: 'hr.delhi@fci-demo.test',
    submittedAt: '2026-10-20T04:30:00Z',
    waitingForRole: 'LABOUR_DEALING_HAND',
    office: 'DO — Delhi HQ (DH001)',
    level: 2,
    levelCount: 2,
  },
];

const ADMIN = ['A-08:VIEW', 'A-08:CREATE', 'A-08:EDIT'];

function routes(
  post: (url: string, init?: RequestInit) => unknown = () => ({
    status: 202,
    body: version({ id: 11, versionNo: 2, status: 'PENDING_APPROVAL', approvalId: 77 }),
  }),
) {
  return backend({
    'GET /v1/admin/workflow-actions/unstaffed': () => paged(UNSTAFFED),
    'GET /v1/admin/workflow-actions': () => paged(ACTIONS),
    'POST /v1/admin/workflow-actions': post,
    'GET /v1/admin/access-matrix': () => paged([]),
    'GET /v1/admin/active-users': () => ({ ...paged([]), inactiveAfterDays: null }),
  });
}

const row = (code: string) => screen.getByText(code, { selector: 'b' }).closest('tr') as HTMLElement;
const listed = () => screen.findByText('I20_SUBMIT', { selector: 'b' });
const viewOf = (code: string) => `${t('common.view')} ${code}`;
const editOf = (code: string) => `${t('common.edit')} ${code}`;

describe('A-08 Approval Workflow Master', () => {
  it('SRS-22-12: lists each action with its version in force, its levels, OTP, target and a pending version', async () => {
    routes();
    wrap(<WorkflowMaster />, ADMIN);
    await listed();
    const i20 = within(row('I20_SUBMIT'));
    expect(i20.getByText(`${t('role.PAYROLL_MAKER')} → ${t('role.PAYROLL_CHECKER')}`)).toBeTruthy();
    expect(i20.getByText(t('w01.target', [72]))).toBeTruthy();
    // UI_BMAD_g1 #73 (SRS l.1130): the Attachment flag is a column of the list.
    expect(i20.getByText(t('common.yes'))).toBeTruthy();
    const a07 = within(row('A07_EDIT'));
    expect(a07.getByText(`ROLE_ACCESS_GROUP_CHANGE - ${t('a08.otp.ROLE_ACCESS_GROUP_CHANGE')}`)).toBeTruthy();
    expect(a07.getByText(t('w01.noTarget'))).toBeTruthy();
    expect(a07.getByText(/^v2 /)).toBeTruthy();
    // A version waits: no second proposal beside it.
    const edit = a07.getByRole('button', { name: editOf('A07_EDIT') });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('x01.why.pending'));
    expect(within(row('NEW_ONE')).getByText(t('a08.notInForce'))).toBeTruthy();
    // The version history opens and closes, with the levels of every version.
    await userEvent.click(i20.getByRole('button', { name: viewOf('I20_SUBMIT') }));
    expect(screen.getByText(new RegExp(`v0 · ${t('a08.status.SUPERSEDED')}`))).toBeTruthy();
    expect(
      screen.getAllByText(/Maker PAYROLL_MAKER \(PAYING_UNIT\) → Checker PAYROLL_CHECKER/).length,
    ).toBeGreaterThan(0);
    expect(
      screen.getByText(`CPF_SANCTION_FINAL_PAYMENT - ${t('a08.otp.CPF_SANCTION_FINAL_PAYMENT')}`),
    ).toBeTruthy();
    await userEvent.click(i20.getByRole('button', { name: viewOf('I20_SUBMIT') }));
    expect(screen.queryByText(new RegExp(`v0 · ${t('a08.status.SUPERSEDED')}`))).toBeNull();
    expect(a07.getByText(t('common.no'))).toBeTruthy();
    await userEvent.click(a07.getByRole('button', { name: viewOf('A07_EDIT') }));
    expect(screen.getByText(t('a08.approvalNo', [55]))).toBeTruthy();
  });

  it('SRS-22-19: an administrator outside Headquarters sees the actions disabled with the reason', async () => {
    routes();
    wrap(<WorkflowMaster />, me(ADMIN, { atHeadquarters: false, hqOnlyScreens: ['A-08'] }));
    await listed();
    const newAction = screen.getByRole('button', { name: t('x01.addAction') });
    expect(newAction).toBeDisabled();
    expect(newAction).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
    const versions = screen.getAllByRole('button', { name: /^Edit [A-Z0-9_]+$/ });
    expect(versions.length).toBeGreaterThan(0);
    for (const button of versions) expect(button).toBeDisabled();
  });

  it('SRS-22-19: a new version starts from the one in force and is sent for approval with a reason', async () => {
    const calls = routes();
    wrap(<WorkflowMaster />, ADMIN);
    await listed();
    await userEvent.click(within(row('I20_SUBMIT')).getByRole('button', { name: editOf('I20_SUBMIT') }));
    expect(screen.getByText(t('a08.newVersionOf', ['I20_SUBMIT', 'PAYING_UNIT']))).toBeTruthy();
    const code = screen.getByLabelText(t('a08.code')) as HTMLInputElement;
    expect(code.value).toBe('I20_SUBMIT');
    expect(code.disabled).toBe(true);
    expect((screen.getByLabelText(t('a08.targetHours')) as HTMLInputElement).value).toBe('72');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await userEvent.clear(screen.getByLabelText(t('a08.targetHours')));
    await userEvent.type(screen.getByLabelText(t('a08.targetHours')), '48');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Faster turnaround');
    await userEvent.click(submit);
    expect(await screen.findByText(t('a08.proposed', [77]))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/admin/workflow-actions').at(-1)).toMatchObject({
      actionCode: 'I20_SUBMIT',
      officeLevel: 'PAYING_UNIT',
      levelCount: 2,
      targetTatHours: 48,
      reason: 'Faster turnaround',
      screenCode: 'I-20',
      levels: [
        { levelNo: 1, roleCode: 'PAYROLL_MAKER' },
        { levelNo: 2, roleCode: 'PAYROLL_CHECKER' },
      ],
    });
    expect(screen.queryByLabelText(t('a08.code'))).toBeNull();
  });

  it('SRS-22-17: a new action with three levels; every broken rule is shown at its field', async () => {
    const calls = routes(() => ({
      status: 422,
      body: {
        errorCode: 'WF-422-ROUTE',
        messageKey: 'error.WF-422-ROUTE',
        violations: [
          { field: 'actionCode', code: 'ACTION_CODE', message: 'a08.rule.ACTION_CODE' },
          { field: 'levels[2]', code: 'LEVEL_ROLE', message: 'a08.rule.LEVEL_ROLE' },
          { field: 'levels', code: 'OTP_WITHOUT_ACTION', message: 'x' },
        ],
      },
    }));
    wrap(<WorkflowMaster />, ADMIN);
    await listed();
    await userEvent.click(screen.getByRole('button', { name: t('x01.addAction') }));
    expect(screen.getByRole('heading', { name: t('a08.newAction') })).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('a08.code')), 'cpf_sanction');
    await userEvent.type(screen.getByLabelText(t('a08.name')), 'CPF sanction');
    await userEvent.type(screen.getByLabelText(t('a08.module')), 'cpf');
    await userEvent.type(screen.getByLabelText(t('a08.screen')), 'c-09');
    await userEvent.selectOptions(screen.getByLabelText(t('a08.officeLevel')), 'ZO');
    await userEvent.selectOptions(screen.getByLabelText(t('a08.levelCount')), '3');
    expect(screen.getAllByRole('textbox', { name: /Role of level/ })).toHaveLength(3);
    // No OTP code chosen: a level's OTP cannot be ticked.
    expect((screen.getByLabelText(t('a08.otpOf', [2])) as HTMLInputElement).disabled).toBe(true);
    await userEvent.selectOptions(screen.getByLabelText(t('a08.otp')), 'CPF_SANCTION_FINAL_PAYMENT');
    expect((screen.getByLabelText(t('a08.otpOf', [1])) as HTMLInputElement).disabled).toBe(true);
    await userEvent.click(screen.getByLabelText(t('a08.otpOf', [3])));
    await userEvent.type(screen.getByLabelText(t('a08.roleOf', [1])), 'payroll_maker');
    await userEvent.type(screen.getByLabelText(t('a08.roleOf', [2])), 'payroll_checker');
    await userEvent.type(screen.getByLabelText(t('a08.roleOf', [3])), 'agm_cpf');
    await userEvent.selectOptions(screen.getByLabelText(t('a08.officeOf', [3])), 'HQ');
    await userEvent.click(screen.getByLabelText(t('a08.attachment')));
    await userEvent.click(screen.getByLabelText(t('a08.bulk')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'CPF module');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('a08.rule.ACTION_CODE'))).toBeTruthy();
    expect(screen.getByText(t('a08.rule.LEVEL_ROLE'))).toBeTruthy();
    expect(screen.getByText(t('a08.rule.OTP_WITHOUT_ACTION'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/admin/workflow-actions').at(-1)).toMatchObject({
      actionCode: 'CPF_SANCTION',
      moduleCode: 'CPF',
      screenCode: 'C-09',
      officeLevel: 'ZO',
      levelCount: 3,
      otpAction: 'CPF_SANCTION_FINAL_PAYMENT',
      attachmentRequired: true,
      bulkApprovalAllowed: true,
      levels: [
        { levelNo: 1, roleCode: 'PAYROLL_MAKER', otpAtLevel: false },
        { levelNo: 2, roleCode: 'PAYROLL_CHECKER', otpAtLevel: false },
        { levelNo: 3, roleCode: 'AGM_CPF', officeLevel: 'HQ', otpAtLevel: true },
      ],
    });
    // Back to two levels; cancel closes the form.
    await userEvent.selectOptions(screen.getByLabelText(t('a08.levelCount')), '2');
    expect(screen.getAllByRole('textbox', { name: /Role of level/ })).toHaveLength(2);
    await userEvent.clear(screen.getByLabelText(t('a08.screen')));
    await userEvent.clear(screen.getByLabelText(t('a08.targetHours')));
    await userEvent.type(screen.getByLabelText(t('a08.from')), '2026-12-01');
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('a08.code'))).toBeNull();
  });

  it('SRS-G-28: items nobody in scope can decide are listed for the HQ Administrator', async () => {
    routes();
    wrap(<WorkflowMaster />, ADMIN);
    await userEvent.click(await screen.findByRole('link', { name: t('a08.tab.unstaffed', [1]) }));
    expect(screen.getByText(t('a08.unstaffedHint'))).toBeTruthy();
    expect(await screen.findByText(/LABOUR_DEALING_HAND/)).toBeTruthy();
    expect(screen.getByText('hr.delhi@fci-demo.test')).toBeTruthy();
    await userEvent.click(screen.getByRole('link', { name: t('a08.tab.unstaffed', [1]) }));
    expect(screen.queryByText(t('a08.unstaffedHint'))).toBeNull();
  });

  it('a Payroll Checker views without proposing; an empty list and none unstaffed say so', async () => {
    backend({
      'GET /v1/admin/workflow-actions/unstaffed': () => paged([]),
      'GET /v1/admin/workflow-actions': () => paged([]),
      'GET /v1/admin/access-matrix': () => paged([]),
      'GET /v1/admin/active-users': () => ({ ...paged([]), inactiveAfterDays: null }),
    });
    wrap(<WorkflowMaster />, ['A-08:VIEW', 'A-08:APPROVE']);
    expect(await screen.findByText(t('a08.none'))).toBeTruthy();
    const add = screen.getByRole('button', { name: t('x01.addAction') });
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('data-why', t('x01.why.hqAdmin'));
    await userEvent.click(screen.getByRole('link', { name: t('a08.tab.unstaffed', [0]) }));
    expect(await screen.findByText(t('a08.unstaffedNone'))).toBeTruthy();
  });

  it('refused loads are shown', async () => {
    backend({
      'GET /v1/admin/workflow-actions/unstaffed': () =>
        problem(403, 'AUTHZ-403-PERMISSION', ['A-08', 'VIEW']),
      'GET /v1/admin/workflow-actions': () => problem(403, 'AUTHZ-403-PERMISSION', ['A-08', 'VIEW']),
      'GET /v1/admin/access-matrix': () => problem(403, 'AUTHZ-403-PERMISSION', ['A-08', 'VIEW']),
      'GET /v1/admin/active-users': () => problem(403, 'AUTHZ-403-PERMISSION', ['A-08', 'VIEW']),
    });
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    await waitFor(() =>
      expect(screen.getAllByText(t('error.AUTHZ-403-PERMISSION', ['A-08', 'VIEW'])).length).toBeGreaterThan(
        0,
      ),
    );
    await userEvent.click(screen.getByRole('link', { name: t('a08.tab.unstaffed', [0]) }));
    expect(screen.getAllByText(t('error.AUTHZ-403-PERMISSION', ['A-08', 'VIEW'])).length).toBeGreaterThan(0);
  });
});
