import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AccessGroups, Assignments, RolesMatrix } from '@/modules/iam';
import type { Assignment, Group, Matrix } from '@/modules/iam/api/adminApi';
import { date } from '@/shared/ui';
import { backend, me, problem, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * A-02 Roles & Permissions, A-03 Access Groups, A-04 Assignments (SRS-22-1, 22-2, 22-3, 22-8, 22-23):
 * the protected administrator role, maker-checker with OTP for groups and assignments, consolidated
 * view only at Headquarters, no self-assignment, revocation with a reason.
 */

const REFERENCE = referenceLists({
  ROLE_TYPE: [
    ['FUNCTIONAL', 'Functional'],
    ['MODULE', 'Module'],
    ['READ_ONLY', 'Read-only'],
  ],
  BASE_ROLE: [
    ['MAKER', 'Payroll Maker'],
    ['CHECKER', 'Payroll Checker'],
  ],
  SCOPE_TYPE: [
    ['DIVISION', 'Division'],
    ['ZONE', 'Zone'],
    ['HEADQUARTERS', 'Headquarters'],
    ['GLOBAL', 'Global'],
  ],
  ACCESS_MODE: [
    ['FULL', 'Full'],
    ['READ_ONLY', 'Read-only'],
    ['REPORTS_ONLY', 'Reports only'],
  ],
});

const MATRIX: Matrix = {
  roles: [
    {
      id: 1,
      code: 'PAYROLL_MAKER',
      name: 'Payroll Maker',
      description: null,
      status: 'SETTLED',
      approver: false,
      roleType: 'FUNCTIONAL',
      baseRole: 'MAKER',
      protectedRole: false,
    },
    {
      id: 2,
      code: 'SYSTEM_ADMINISTRATOR',
      name: 'System Administrator',
      description: null,
      status: 'SETTLED',
      approver: false,
      roleType: 'FUNCTIONAL',
      baseRole: 'ADMIN',
      protectedRole: true,
    },
  ],
  permissions: [
    { id: 100, screenCode: 'M-01', actionCode: 'VIEW', name: 'M-01 view' },
    { id: 101, screenCode: 'M-01', actionCode: 'EDIT', name: 'M-01 edit' },
  ],
  grants: [{ roleId: 1, permissionId: 100 }],
};

const ORG = {
  zones: [{ id: 7, code: 'NA11', name: 'North' }],
  locations: [
    { id: 20, code: 'DH001', name: 'Delhi HQ', type: 'DO', label: 'DO — Delhi HQ (DH001)' },
    { id: 21, code: 'HQ001', name: 'Headquarters', type: 'HQ', label: 'HQ — Headquarters (HQ001)' },
  ],
};

const group = (over: Partial<Group>): Group => ({
  id: 30,
  code: 'AG-PM-DH001',
  name: 'Payroll Maker - Delhi HQ',
  roleCode: 'PAYROLL_MAKER',
  scopeType: 'DIVISION',
  consolidated: false,
  accessMode: 'FULL',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  approvalStatus: 'APPROVED',
  scopes: [{ type: 'LOCATION', id: 20, code: 'DH001', label: 'DO — Delhi HQ (DH001)' }],
  ...over,
});

const GROUPS = [
  group({}),
  group({
    id: 31,
    code: 'AG-HQ-CONS',
    scopeType: 'HEADQUARTERS',
    consolidated: true,
    accessMode: 'READ_ONLY',
    scopes: [],
  }),
  group({ id: 32, code: 'AG-OLD', effectiveTo: '2026-09-30' }),
  group({ id: 33, code: 'AG-PENDING', approvalStatus: 'PENDING_APPROVAL' }),
];

describe('A-02 Roles & Permissions', () => {
  /** A-02 changes are maker-checker with an OTP: the first call answers 428, the second 202. */
  const governed = (_url: string, init?: RequestInit) =>
    (JSON.parse(String(init?.body)) as { otp?: unknown }).otp
      ? { status: 202, body: { approvalId: 77, status: 'PENDING_APPROVAL' } }
      : problem(428, 'AUTH-428-OTP', [12]);
  function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/admin/roles': () => MATRIX,
      'PUT /v1/admin/roles/grants': governed,
      'POST /v1/admin/roles': governed,
      ...over,
    });
  }
  const ALL = ['A-02:VIEW', 'A-02:CREATE', 'A-02:EDIT'];

  async function answerOtp(code = '112233') {
    await userEvent.type(await screen.findByLabelText(t('otp.code')), code);
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
  }

  it('shows the matrix; the protected administrator role cannot be changed', async () => {
    routes();
    wrap(<RolesMatrix />, ALL);
    expect(await screen.findByText(t('a02.title', [2]))).toBeTruthy();
    const granted = screen.getByLabelText(
      t('a02.toggle', ['PAYROLL_MAKER', 'M-01:VIEW']),
    ) as HTMLInputElement;
    expect(granted.checked).toBe(true);
    const protectedBox = screen.getByLabelText(
      t('a02.toggle', ['SYSTEM_ADMINISTRATOR', 'M-01:VIEW']),
    ) as HTMLInputElement;
    expect(protectedBox.disabled).toBe(true);
  });

  it('SRS-NF-3: the matrix pages by permission row, 20 to start, and a filter finds a permission', async () => {
    const many: Matrix = {
      ...MATRIX,
      permissions: Array.from({ length: 45 }, (_, i) => ({
        id: 1000 + i,
        screenCode: 'M-01',
        actionCode: `ACT${String(i).padStart(2, '0')}`,
        name: `action ${i}`,
      })),
      grants: [{ roleId: 1, permissionId: 1044 }],
    };
    routes({ 'GET /v1/admin/roles': () => many });
    wrap(<RolesMatrix />, ALL);
    expect(await screen.findByText(t('a02.title', [2]))).toBeTruthy();
    // Page 1: 20 rows x 2 roles; the last permission is on page 3.
    expect(screen.getAllByRole('checkbox')).toHaveLength(40);
    expect(screen.queryByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:ACT44']))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('page.number', [3]) }));
    const last = screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:ACT44'])) as HTMLInputElement;
    expect(last.checked).toBe(true);
    expect(screen.getAllByRole('checkbox')).toHaveLength(10);
    // The filter goes back to the first page and narrows by code; no match shows no row.
    await userEvent.type(screen.getByLabelText(t('a02.filter')), 'act07');
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
    expect(screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:ACT07']))).toBeTruthy();
    await userEvent.clear(screen.getByLabelText(t('a02.filter')));
    await userEvent.type(screen.getByLabelText(t('a02.filter')), 'nothing like it');
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    // A new size: 50 rows fit all 45 permissions on one page.
    await userEvent.clear(screen.getByLabelText(t('a02.filter')));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: t('page.size') }), '50');
    expect(screen.getAllByRole('checkbox')).toHaveLength(90);
  });

  it('SRS-22-8: a grant is a proposal with a reason and the OTP; the matrix does not change', async () => {
    const calls = routes();
    wrap(<RolesMatrix />, ALL);
    await screen.findByText(t('a02.title', [2]));
    const box = screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:EDIT'])) as HTMLInputElement;
    await userEvent.click(box);
    expect(screen.getByText(t('a02.grantTitle', ['M-01:EDIT', 'PAYROLL_MAKER']))).toBeTruthy();
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Region edits');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    );
    await answerOtp();
    expect(await screen.findByText(t('a02.proposed', ['PAYROLL_MAKER']))).toBeTruthy();
    expect(sent(calls, 'PUT', '/roles/grants').at(-1)).toEqual({
      roleId: 1,
      permissionId: 101,
      granted: true,
      reason: 'Region edits',
      otp: { challengeId: 12, code: '112233' },
    });
    expect(box.checked).toBe(false);
  });

  it('a withdrawal asks for its own reason; cancelling sends nothing', async () => {
    const calls = routes();
    wrap(<RolesMatrix />, ALL);
    await screen.findByText(t('a02.title', [2]));
    await userEvent.click(screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:VIEW'])));
    expect(screen.getByText(t('a02.withdrawTitle', ['M-01:VIEW', 'PAYROLL_MAKER']))).toBeTruthy();
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(0);

    await userEvent.click(screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:VIEW'])));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Not needed');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    );
    await answerOtp();
    await waitFor(() =>
      expect(sent(calls, 'PUT', '/roles/grants').at(-1)).toMatchObject({
        granted: false,
        reason: 'Not needed',
      }),
    );
  });

  it('a cancelled OTP is not an error; a refusal is shown', async () => {
    let refuse = false;
    routes({
      'PUT /v1/admin/roles/grants': (url, init) =>
        refuse ? problem(403, 'ADM-403-HQ-ONLY') : governed(url, init),
    });
    wrap(<RolesMatrix />, ALL);
    await screen.findByText(t('a02.title', [2]));
    await userEvent.click(screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:EDIT'])));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'x');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    );
    await screen.findByLabelText(t('otp.code'));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.queryByRole('alert')).toBeNull();

    refuse = true;
    await userEvent.click(screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:EDIT'])));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'x');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    );
    expect((await screen.findAllByText(t('error.ADM-403-HQ-ONLY'))).length).toBeGreaterThan(0);
  });

  it('SRS-G-7: an administrator outside Headquarters sees New role disabled with the reason', async () => {
    routes();
    wrap(<RolesMatrix />, me(ALL, { atHeadquarters: false, hqOnlyScreens: ['A-02'] }));
    await screen.findByText(t('a02.title', [2]));
    const create = screen.getByRole('button', { name: t('action.newRole') });
    expect(create).toBeDisabled();
    expect(create).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('a viewer can neither create nor change', async () => {
    routes();
    wrap(<RolesMatrix />, ['A-02:VIEW']);
    await screen.findByText(t('a02.title', [2]));
    expect(screen.queryByRole('button', { name: t('action.newRole') })).toBeNull();
    expect(
      (screen.getByLabelText(t('a02.toggle', ['PAYROLL_MAKER', 'M-01:EDIT'])) as HTMLInputElement).disabled,
    ).toBe(true);
  });

  it('proposes a role with a reason and the OTP; a read-only role has no base role', async () => {
    const calls = routes();
    wrap(<RolesMatrix />, ALL);
    await screen.findByText(t('a02.title', [2]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newRole') }));
    await userEvent.type(screen.getByLabelText(t('a02.code')), 'AUDIT_VIEW');
    await userEvent.type(screen.getByLabelText(t('a02.name')), 'Audit viewer');
    await userEvent.selectOptions(screen.getByLabelText(t('a02.roleType')), 'READ_ONLY');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Vigilance');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp('445566');
    expect(await screen.findByText(t('a02.created'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/admin/roles').at(-1)).toEqual({
      code: 'AUDIT_VIEW',
      name: 'Audit viewer',
      description: null,
      approver: false,
      roleType: 'READ_ONLY',
      baseRole: null,
      reason: 'Vigilance',
      otp: { challengeId: 12, code: '445566' },
    });

    await userEvent.click(screen.getByRole('button', { name: t('action.newRole') }));
    await userEvent.type(screen.getByLabelText(t('a02.code')), 'CPF_MGR');
    await userEvent.type(screen.getByLabelText(t('a02.name')), 'Manager CPF');
    await userEvent.type(screen.getByLabelText(t('a02.description')), 'CPF');
    await userEvent.click(screen.getByLabelText(t('a02.approver')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'CPF module');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/admin/roles').at(-1)).toMatchObject({
        roleType: 'MODULE',
        baseRole: 'MAKER',
        approver: true,
        description: 'CPF',
      }),
    );
  });

  it('SRS-22-2: with no base role on offer a module role is proposed without one; a role without HQ rights is refused', async () => {
    let refuse = false;
    const calls = routes({
      'GET /v1/reference/lists': () =>
        referenceLists({
          ROLE_TYPE: [['MODULE', 'Module']],
          BASE_ROLE: [],
        }),
      'POST /v1/admin/roles': (url, init) =>
        refuse ? problem(403, 'AUTHZ-403-HQ-ONLY') : governed(url, init),
    });
    wrap(<RolesMatrix />, ALL);
    await screen.findByText(t('a02.title', [2]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newRole') }));
    expect(within(screen.getByLabelText(t('a02.baseRole'))).queryAllByRole('option')).toHaveLength(0);
    await userEvent.type(screen.getByLabelText(t('a02.code')), 'NO_BASE');
    await userEvent.type(screen.getByLabelText(t('a02.name')), 'No base');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Standalone role');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/admin/roles').at(-1)).toMatchObject({
        roleType: 'MODULE',
        baseRole: null,
        description: null,
      }),
    );
    expect(await screen.findByText(t('a02.created'))).toBeTruthy();
    // A second proposal the server refuses (403): the message shows and the form stays.
    refuse = true;
    await userEvent.click(screen.getByRole('button', { name: t('action.newRole') }));
    await userEvent.type(screen.getByLabelText(t('a02.code')), 'NO_BASE_2');
    await userEvent.type(screen.getByLabelText(t('a02.name')), 'No base 2');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Again');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.AUTHZ-403-HQ-ONLY'))).toBeTruthy();
    expect(screen.getByLabelText(t('a02.code'))).toHaveValue('NO_BASE_2');
  });

  it('keeps the form on a refused proposal, ignores a cancelled OTP, closes on cancel', async () => {
    let answer: (url: string, init?: RequestInit) => unknown = () => problem(409, 'COM-409-CONFLICT');
    routes({ 'POST /v1/admin/roles': (url, init) => answer(url, init) });
    wrap(<RolesMatrix />, ALL);
    await screen.findByText(t('a02.title', [2]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newRole') }));
    await userEvent.type(screen.getByLabelText(t('a02.code')), 'PAYROLL_MAKER');
    await userEvent.type(screen.getByLabelText(t('a02.name')), 'Dup');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.COM-409-CONFLICT'))).toBeTruthy();
    answer = () => problem(428, 'AUTH-428-OTP', [5]);
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByLabelText(t('otp.code'));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
    expect(screen.getByLabelText(t('a02.code'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('a02.code'))).toBeNull();
  });
});

describe('A-03 Access Groups', () => {
  const ALL = ['A-02:VIEW', 'A-03:VIEW', 'A-03:CREATE', 'A-03:EDIT'];
  function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/admin/roles': () => MATRIX,
      'GET /v1/admin/groups': () => GROUPS,
      'GET /v1/admin/org': () => ORG,
      'POST /v1/admin/groups/': () => ({}),
      ...over,
    });
  }

  it('lists groups with their scope, mode and state; only open groups can be end-dated', async () => {
    routes();
    wrap(<AccessGroups />, ALL);
    expect(await screen.findByText(t('a03.title', [4]))).toBeTruthy();
    const cons = screen.getByText('AG-HQ-CONS').closest('tr') as HTMLElement;
    expect(within(cons).getByText(t('a03.readOnlyView'))).toBeTruthy();
    expect(within(cons).getByText('Read-only')).toBeTruthy();
    expect(within(cons).getByText(t('scope.GLOBAL'))).toBeTruthy();
    const old = screen.getByText('AG-OLD').closest('tr') as HTMLElement;
    // UI_BMAD_g1 #81: dates as DD-MM-YYYY.
    expect(within(old).getByText(`${date('2026-04-01')} → ${date('2026-09-30')}`)).toBeTruthy();
    expect(within(old).queryByRole('button', { name: t('a03.end') })).toBeNull();
  });

  it('offers the scope by type, the consolidated view only at HQ or GLOBAL, and submits with an OTP', async () => {
    let otpAsked = 0;
    const calls = routes({
      'POST /v1/admin/groups': (_url, init) => {
        const body = JSON.parse(String(init?.body)) as { otp?: unknown };
        if (!body.otp) {
          otpAsked++;
          return problem(428, 'AUTH-428-OTP', [55]);
        }
        return group({ code: 'AG-NEW', approvalStatus: 'PENDING_APPROVAL' });
      },
    });
    wrap(<AccessGroups />, ALL);
    await screen.findByText(t('a03.title', [4]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAccessGroup') }));
    expect(screen.queryByLabelText(new RegExp(t('a03.consolidated')))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('a03.scopeType')), 'ZONE');
    expect(within(screen.getByLabelText(t('a03.scope'))).getByText('North (NA11)')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('a03.scopeType')), 'GLOBAL');
    expect(screen.queryByLabelText(t('a03.scope'))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('a03.scopeType')), 'HEADQUARTERS');
    expect(within(screen.getByLabelText(t('a03.scope'))).getByText('HQ — Headquarters (HQ001)')).toBeTruthy();
    await userEvent.click(screen.getByLabelText(new RegExp(t('a03.consolidated'))));
    await userEvent.type(screen.getByLabelText(t('a03.code')), 'AG-NEW');
    await userEvent.type(screen.getByLabelText(t('a03.name')), 'New group');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Audit cell');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '123456');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
    expect(await screen.findByText(t('a03.submitted', ['AG-NEW']))).toBeTruthy();
    expect(otpAsked).toBe(1);
    expect(sent(calls, 'POST', '/v1/admin/groups').at(-1)).toMatchObject({
      code: 'AG-NEW',
      roleId: 1,
      scopeType: 'HEADQUARTERS',
      consolidated: true,
      accessMode: 'FULL',
      scopes: [{ locationId: 21 }],
      reason: 'Audit cell',
      otp: { challengeId: 55, code: '123456' },
    });
  });

  it('cancelling the OTP keeps the form and shows nothing as an error', async () => {
    routes({ 'POST /v1/admin/groups': () => problem(428, 'AUTH-428-OTP', [56]) });
    wrap(<AccessGroups />, ALL);
    await screen.findByText(t('a03.title', [4]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAccessGroup') }));
    await userEvent.type(screen.getByLabelText(t('a03.code')), 'AG-X');
    await userEvent.type(screen.getByLabelText(t('a03.name')), 'X');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'r');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByLabelText(t('otp.code'));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
    expect(screen.getByLabelText(t('a03.code'))).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows a refused group and end-dates an open one with a reason', async () => {
    const calls = routes({ 'POST /v1/admin/groups': () => problem(422, 'ADM-422-CONSOLIDATED') });
    wrap(<AccessGroups />, ALL);
    await screen.findByText(t('a03.title', [4]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAccessGroup') }));
    await userEvent.type(screen.getByLabelText(t('a03.code')), 'AG-Y');
    await userEvent.type(screen.getByLabelText(t('a03.name')), 'Y');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'r');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.ADM-422-CONSOLIDATED'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));

    const row = screen.getByText('AG-PM-DH001').closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('a03.end') }));
    expect(screen.getByText(t('a03.endTitle', ['AG-PM-DH001']))).toBeTruthy();
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Office closed');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    await waitFor(() =>
      expect(sent(calls, 'POST', '/groups/30/end')).toContainEqual({ reason: 'Office closed' }),
    );
    expect(await screen.findByText(t('a03.ended', ['AG-PM-DH001']))).toBeTruthy();
  });

  it('a refused end-dating is shown as a message; cancel sends nothing', async () => {
    const calls = routes({ 'POST /v1/admin/groups/': () => problem(409, 'WF-409-PENDING') });
    wrap(<AccessGroups />, ALL);
    await screen.findByText(t('a03.title', [4]));
    const row = screen.getByText('AG-PM-DH001').closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('a03.end') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    await userEvent.click(within(row).getByRole('button', { name: t('a03.end') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'x');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('error.WF-409-PENDING'))).toBeTruthy();
  });

  it('a viewer gets neither create nor end-date', async () => {
    routes();
    wrap(<AccessGroups />, ['A-03:VIEW']);
    await screen.findByText(t('a03.title', [4]));
    expect(screen.queryByRole('button', { name: t('action.newAccessGroup') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('a03.end') })).toBeNull();
  });
});

describe('A-04 Assignments', () => {
  const ALL = ['A-01:VIEW', 'A-03:VIEW', 'A-04:VIEW', 'A-04:CREATE', 'A-04:DELETE'];
  const assignment = (over: Partial<Assignment>): Assignment => ({
    id: 40,
    userId: 10,
    loginId: 'maker.delhi@fci-demo.test',
    groupCode: 'AG-PM-DH001',
    roleCode: 'PAYROLL_MAKER',
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    assignedBy: 'sysadmin',
    approvalStatus: 'APPROVED',
    revokedAt: null,
    revokeReason: null,
    current: true,
    ...over,
  });
  const LIST = [
    assignment({}),
    assignment({
      id: 41,
      loginId: null,
      userId: 22,
      current: false,
      approvalStatus: 'PENDING_APPROVAL',
      groupCode: null,
      roleCode: null,
    }),
    assignment({
      id: 42,
      loginId: 'gone@fci-demo.test',
      revokedAt: '2026-10-01T05:00:00Z',
      revokeReason: 'Transfer',
      effectiveTo: '2026-10-31',
    }),
    assignment({ id: 43, userId: 1, loginId: 'user@fci-demo.test' }),
  ];
  function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/admin/assignments': () => ({ items: LIST, total: LIST.length }),
      'GET /v1/admin/users': () => ({
        items: [
          { id: 1, loginId: 'user@fci-demo.test', displayName: 'Me' },
          { id: 10, loginId: 'maker.delhi@fci-demo.test', displayName: 'Amit' },
        ],
        total: 2,
      }),
      'GET /v1/admin/groups': () => GROUPS,
      'POST /v1/admin/assignments/': () => ({}),
      ...over,
    });
  }

  it("shows in force, inert and revoked assignments; never a revoke of one's own", async () => {
    routes();
    wrap(<Assignments />, ALL);
    expect(await screen.findByText(t('a04.title', [4]))).toBeTruthy();
    expect(screen.getAllByText(t('a04.current'), { selector: 'span.pill' }).length).toBe(2);
    expect(screen.getByText(t('a04.inert'))).toBeTruthy();
    expect(screen.getByText('22')).toBeTruthy();
    const revoked = screen.getByText('gone@fci-demo.test').closest('tr') as HTMLElement;
    expect(within(revoked).queryByRole('button', { name: t('action.revoke') })).toBeNull();
    const own = screen.getByText('user@fci-demo.test').closest('tr') as HTMLElement;
    expect(within(own).queryByRole('button', { name: t('action.revoke') })).toBeNull();
  });

  it('proposes an assignment with an OTP: no self, only approved open groups', async () => {
    const calls = routes({
      'POST /v1/admin/assignments': (_url, init) =>
        (JSON.parse(String(init?.body)) as { otp?: unknown }).otp ? {} : problem(428, 'AUTH-428-OTP', [9]),
    });
    wrap(<Assignments />, ALL);
    await screen.findByText(t('a04.title', [4]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAssignment') }));
    const users = within(screen.getByLabelText(t('a04.user'))).getAllByRole('option');
    expect(users.map((o) => o.textContent)).toEqual(['maker.delhi@fci-demo.test — Amit']);
    const groups = within(screen.getByLabelText(t('a04.group'))).getAllByRole('option');
    expect(groups.map((o) => o.getAttribute('value'))).toEqual(['30', '31']);
    await userEvent.type(screen.getByLabelText(t('a04.to')), '2026-12-31');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Posting');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '654321');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
    expect(await screen.findByText(t('a04.submitted'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/admin/assignments').at(-1)).toEqual({
      userId: 10,
      groupId: 30,
      effectiveFrom: null,
      effectiveTo: '2026-12-31',
      reason: 'Posting',
      otp: { challengeId: 9, code: '654321' },
    });
  });

  // UI_BMAD_g1 #82: Effective To before Effective From is named on the screen and nothing is sent;
  // From = To is a one-day assignment.
  it('SRS l.1131: an assignment ending before it starts is refused on the screen', async () => {
    const calls = routes();
    wrap(<Assignments />, ALL);
    await screen.findByText(t('a04.title', [4]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAssignment') }));
    await userEvent.type(screen.getByLabelText(t('a04.from')), '2026-12-31');
    await userEvent.type(screen.getByLabelText(t('a04.to')), '2026-12-30');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Posting');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('a04.toBeforeFrom'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/admin/assignments')).toHaveLength(0);
    await userEvent.clear(screen.getByLabelText(t('a04.to')));
    await userEvent.type(screen.getByLabelText(t('a04.to')), '2026-12-31');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/admin/assignments').length).toBeGreaterThan(0));
    expect(screen.queryByText(t('a04.toBeforeFrom'))).toBeNull();
  });

  it('SRS-NF-3: the user is found on the server by login or name, the first 20 matches shown', async () => {
    const calls = routes({
      'GET /v1/admin/users': (url) =>
        url.includes('q=amit')
          ? { items: [{ id: 10, loginId: 'maker.delhi@fci-demo.test', displayName: 'Amit' }], total: 1 }
          : {
              items: Array.from({ length: 20 }, (_, i) => ({
                id: 100 + i,
                loginId: `u${i}@fci-demo.test`,
                displayName: `U${i}`,
              })),
              total: 340,
            },
    });
    wrap(<Assignments />, ALL);
    await screen.findByText(t('a04.title', [4]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAssignment') }));
    expect(await screen.findByText(t('a04.moreUsers', [20, 340]))).toBeTruthy();
    expect(
      calls.some((c) => c.url.includes('/v1/admin/users?page=0&size=20') && c.url.includes('status=ACTIVE')),
    ).toBe(true);
    await userEvent.type(screen.getByLabelText(t('a04.findUser')), 'amit');
    await waitFor(() =>
      expect(
        within(screen.getByLabelText(t('a04.user')))
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['maker.delhi@fci-demo.test — Amit']),
    );
    expect(screen.queryByText(t('a04.moreUsers', [20, 340]))).toBeNull();
  });

  it('shows a refused proposal, ignores a cancelled OTP, and closes the form', async () => {
    let answer: unknown = problem(403, 'ADM-403-SELF');
    routes({ 'POST /v1/admin/assignments': () => answer });
    wrap(<Assignments />, ALL);
    await screen.findByText(t('a04.title', [4]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAssignment') }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.ADM-403-SELF'))).toBeTruthy();
    answer = problem(428, 'AUTH-428-OTP', [3]);
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByLabelText(t('otp.code'));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('a04.user'))).toBeNull();
  });

  it('revokes with a reason, and shows a refused revocation', async () => {
    let refuse = false;
    const calls = routes({
      'POST /v1/admin/assignments/': () => (refuse ? problem(409, 'ADM-409-REVOKED') : {}),
    });
    wrap(<Assignments />, ALL);
    await screen.findByText(t('a04.title', [4]));
    const row = () => screen.getByText('maker.delhi@fci-demo.test').closest('tr') as HTMLElement;
    await userEvent.click(within(row()).getByRole('button', { name: t('action.revoke') }));
    expect(screen.getByText(t('a04.revokeTitle', ['maker.delhi@fci-demo.test', 'AG-PM-DH001']))).toBeTruthy();
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Transferred');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    await waitFor(() =>
      expect(sent(calls, 'POST', '/assignments/40/revoke')).toContainEqual({ reason: 'Transferred' }),
    );
    expect(await screen.findByText(t('a04.revoked', ['maker.delhi@fci-demo.test']))).toBeTruthy();

    refuse = true;
    const inert = screen.getByText('22').closest('tr') as HTMLElement;
    await userEvent.click(within(inert).getByRole('button', { name: t('action.revoke') }));
    expect(screen.getByRole('heading', { name: /Revoke\s+from 22/ })).toBeTruthy();
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(within(inert).getByRole('button', { name: t('action.revoke') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'x');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('error.ADM-409-REVOKED'))).toBeTruthy();
  });

  it('a viewer sees the list only, and a refused list is shown', async () => {
    routes({ 'GET /v1/admin/assignments': () => problem(403, 'AUTHZ-403-PERMISSION', ['A-04', 'VIEW']) });
    wrap(<Assignments />, ['A-04:VIEW']);
    expect(await screen.findByText(t('error.AUTHZ-403-PERMISSION', ['A-04', 'VIEW']))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.newAssignment') })).toBeNull();
  });
});
