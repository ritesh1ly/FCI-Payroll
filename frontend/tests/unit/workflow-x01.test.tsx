import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RolesMatrix } from '@/modules/iam';
import { WorkflowMaster } from '@/modules/workflow';
import { X01_PANELS, X01_TABLES } from '@/modules/workflow/components/x01Content';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * X-01 Approval Workflow Master & RBAC as Wireframes v6.1 draws it (OD-121; SRS-22-8, SRS-22-12, SRS-22-19, SRS-G-50):
 * every drawn table and panel, the actions with their disabled reasons per role, the Screen-Level Access Matrix read
 * from the grants, the Active User Report with its flags, and the way to A-01 / A-02.
 */

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.pushState({}, '', '/');
});
const scrolled = vi.fn();
Element.prototype.scrollIntoView = scrolled;

const ROUTE = {
  actionCode: 'I20_SUBMIT',
  officeLevel: 'PAYING_UNIT',
  current: {
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
  },
  pending: null,
  history: [],
};

const USERS = [
  {
    userId: 1,
    loginId: 'maker.delhi@fci-demo.test',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-01T04:30:00Z',
    inactive: false,
    access: [{ groupCode: 'AG-DH-MAKER', roleCode: 'PAYROLL_MAKER', unit: 'DO — Delhi HQ (DH001)' }],
  },
  {
    userId: 2,
    loginId: 'old.user@fci-demo.test',
    status: 'ACTIVE',
    lastLoginAt: '2026-01-01T04:30:00Z',
    inactive: true,
    access: [{ groupCode: 'AG-ALL', roleCode: 'SYSTEM_ADMINISTRATOR', unit: 'ALL' }],
  },
  {
    userId: 3,
    loginId: 'new.user@fci-demo.test',
    status: 'ACTIVE',
    lastLoginAt: null,
    inactive: null,
    access: [],
  },
  {
    userId: 4,
    loginId: 'no.unit@fci-demo.test',
    status: 'ACTIVE',
    lastLoginAt: null,
    inactive: null,
    access: [{ groupCode: 'AG-X', roleCode: 'FINANCE', unit: null }],
  },
];

function routes(threshold: number | null = 60) {
  return backend({
    'GET /v1/admin/workflow-actions/unstaffed': () => paged([]),
    'GET /v1/admin/workflow-actions': () => paged([ROUTE]),
    'GET /v1/admin/access-matrix': () =>
      paged([
        {
          screenCode: 'A-08',
          access: { PAYROLL_MAKER: 'V', PAYROLL_CHECKER: 'A', SYSTEM_ADMINISTRATOR: 'E' },
        },
      ]),
    'GET /v1/admin/active-users': () => ({ ...paged(USERS), inactiveAfterDays: threshold }),
  });
}

const ADMIN = [
  'A-08:VIEW',
  'A-08:CREATE',
  'A-08:EDIT',
  'A-01:VIEW',
  'A-01:EXPORT',
  'A-02:VIEW',
  'A-02:CREATE',
  'A-02:EDIT',
];

describe('X-01 as drawn', () => {
  it('SRS-22-12: every drawn table and panel stands on the screen with its columns and rows', async () => {
    routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    await screen.findByText('I20_SUBMIT', { selector: 'b' });
    for (const table of Object.values(X01_TABLES)) {
      const heading = screen.getByRole('heading', { name: t(table.title) });
      const card = heading.closest('.card') as HTMLElement;
      const headers = within(card).getAllByRole('columnheader');
      const label = (h: HTMLElement) => {
        const copy = h.cloneNode(true) as HTMLElement;
        copy.querySelectorAll('.src').forEach((badge) => badge.remove());
        return copy.textContent?.trim();
      };
      expect(headers.map(label)).toEqual(table.cols.map((c) => t(c.t)));
    }
    for (const panel of Object.values(X01_PANELS)) {
      const card = screen.getByRole('heading', { name: t(panel.title) }).closest('.card') as HTMLElement;
      for (const item of panel.items) expect(within(card).getByText(t(item.text))).toBeTruthy();
    }
    expect(screen.getByText(t(X01_PANELS.enforcement.note))).toBeTruthy();
    expect(screen.getByText(t(X01_TABLES.orgScope.note.lead))).toBeTruthy();
    const scope = screen
      .getByRole('heading', { name: t(X01_TABLES.orgScope.title) })
      .closest('.card') as HTMLElement;
    expect(within(scope).getByText('166')).toHaveClass('num');
    // A cell with its small line, and one with bold text after it, as drawn.
    expect(screen.getAllByText(/^\(Finance releases/)[0]).toHaveClass('sub2');
    expect(screen.getByText('Labour pay generated at DO only').tagName).toBe('B');
  });

  it('SRS-22-19: a role that is not the HQ Administrator sees every action drawn and disabled with its reason', async () => {
    routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    await screen.findByText('I20_SUBMIT', { selector: 'b' });
    const reason: [string, string][] = [
      ['x01.users', 'x01.why.users'],
      ['x01.newRole', 'x01.why.admin'],
      ['x01.editMatrix', 'x01.why.editMatrix'],
      ['x01.edit', 'x01.why.hqAdmin'],
      ['x01.addAction', 'x01.why.hqAdmin'],
    ];
    for (const [label, why] of reason) {
      const button = screen.getByRole('button', { name: t(label) });
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('data-why', t(why));
    }
    const edit = screen.getByRole('button', { name: `${t('common.edit')} I20_SUBMIT` });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('x01.why.hqAdmin'));
    expect(screen.queryByTitle(t('wf.approveTitle'))).toBeNull();
  });

  it('SRS-22-19: outside Headquarters the role changes say so', async () => {
    routes();
    wrap(<WorkflowMaster />, me(ADMIN, { atHeadquarters: false, hqOnlyScreens: ['A-08', 'A-02'] }));
    await screen.findByText('I20_SUBMIT', { selector: 'b' });
    for (const label of ['x01.newRole', 'x01.editMatrix', 'x01.edit', 'x01.addAction']) {
      expect(screen.getByRole('button', { name: t(label) })).toHaveAttribute(
        'data-why',
        t('error.AUTHZ-403-HQ-ONLY'),
      );
    }
    // A-01 is not kept at Headquarters only: a scoped administrator manages its users (SRS-22-19).
    expect(screen.getByRole('link', { name: t('x01.users') })).toBeTruthy();
  });

  it('SRS-22-8: the HQ Administrator reaches A-01 and A-02, where those changes are made with an OTP', async () => {
    routes();
    wrap(<WorkflowMaster />, ADMIN);
    await screen.findByText('I20_SUBMIT', { selector: 'b' });
    expect(screen.getByRole('link', { name: t('x01.users') }).getAttribute('href')).toContain('s=A-01');
    expect(screen.getByRole('link', { name: t('x01.newRole') }).getAttribute('href')).toContain(
      's=A-02&new=role',
    );
    expect(screen.getByRole('link', { name: t('x01.editMatrix') }).getAttribute('href')).toContain('s=A-02');
    scrolled.mockClear();
    await userEvent.click(screen.getByRole('button', { name: t('x01.edit') }));
    expect(scrolled).toHaveBeenCalledTimes(1);
  });

  it('SRS-22-12: History opens every version on the page and closes again', async () => {
    routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    await screen.findByText('I20_SUBMIT', { selector: 'b' });
    const first = new RegExp(`v1 · ${t('a08.status.ACTIVE')}`);
    expect(screen.queryByText(first)).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('history.button') }));
    expect(screen.getByText(first)).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('history.button') }));
    expect(screen.queryByText(first)).toBeNull();
  });

  it('SRS-22-19: the Checker has the drawn tick, which leads to W-01 where the change is decided', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW', 'A-08:APPROVE']);
    await screen.findByText('I20_SUBMIT', { selector: 'b' });
    await userEvent.click(screen.getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign.mock.calls[0]?.[0]).toContain('s=W-01');
  });
});

describe('X-01 Screen-Level Access Matrix', () => {
  it('SRS-22-8: each role shows its letter from the grants in force, a dash without access', async () => {
    routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    const name = await screen.findByText('A-08', { selector: 'b' });
    const cells = within(name.closest('tr') as HTMLElement).getAllByRole('cell');
    expect(cells[0]?.textContent).toContain(t('screen.A-08'));
    expect(cells.slice(1).map((c) => c.textContent)).toEqual(['V', 'A', '—', '—', '—', 'E', '—']);
    expect(within(cells[2] as HTMLElement).getByText('A')).toHaveClass('p-ok');
    expect(within(cells[6] as HTMLElement).getByText('E')).toHaveClass('p-info');
    expect(within(cells[1] as HTMLElement).getByText('V')).toHaveClass('p-pur');
    expect(within(cells[3] as HTMLElement).getByText('—')).toHaveClass('p-gray');
  });

  it('an empty catalogue says so', async () => {
    backend({
      'GET /v1/admin/workflow-actions/unstaffed': () => paged([]),
      'GET /v1/admin/workflow-actions': () => paged([]),
      'GET /v1/admin/access-matrix': () => paged([]),
      'GET /v1/admin/active-users': () => ({ ...paged([]), inactiveAfterDays: null }),
    });
    wrap(<WorkflowMaster />, ['A-08:VIEW', 'A-01:VIEW']);
    expect(await screen.findByText(t('x01.noScreens'))).toBeTruthy();
    expect(await screen.findByText(t('a01.none'))).toBeTruthy();
  });
});

describe('X-01 Active User Report', () => {
  const rowOf = async (login: string) =>
    within((await screen.findByText(login, { selector: 'b' })).closest('tr') as HTMLElement);

  it('SRS-G-50: role, unit / Access Group, last login and the flag; no access is a dash', async () => {
    routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW', 'A-01:VIEW']);
    const maker = await rowOf('maker.delhi@fci-demo.test');
    expect(maker.getByText(t('role.PAYROLL_MAKER'))).toBeTruthy();
    expect(maker.getByText('DO — Delhi HQ (DH001) / AG-DH-MAKER')).toBeTruthy();
    expect(maker.getByText('Active')).toBeTruthy();
    const old = await rowOf('old.user@fci-demo.test');
    expect(old.getByText(`${t('a01.report.allOffices')} / AG-ALL`)).toBeTruthy();
    expect(old.getByText(t('x01.flag.dormant', [60]))).toHaveClass('p-bad');
    const fresh = await rowOf('new.user@fci-demo.test');
    expect(fresh.getByText(t('x01.flag.never'))).toHaveClass('p-warn');
    expect(fresh.getAllByText('—')).toHaveLength(3);
    const bare = await rowOf('no.unit@fci-demo.test');
    expect(bare.getByText('— / AG-X')).toBeTruthy();
  });

  it('SRS-G-50: a viewer of X-01 without A-01 VIEW sees the drawn table with the reason, and no user is requested', async () => {
    const calls = routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW']);
    expect(await screen.findByText(t('x01.why.activeUsers'))).toBeTruthy();
    expect(screen.queryByText('maker.delhi@fci-demo.test')).toBeNull();
    expect(JSON.stringify(calls)).not.toContain('/active-users');
  });

  it('SRS-G-50: the export is A-01 EXPORT; a role without it is told why and nothing is requested', async () => {
    const calls = routes();
    wrap(<WorkflowMaster />, ['A-08:VIEW', 'A-01:VIEW']);
    await rowOf('maker.delhi@fci-demo.test');
    await userEvent.click(screen.getByRole('button', { name: t('x01.export') }));
    expect(await screen.findByText(t('reason.noExport'))).toBeTruthy();
    expect(JSON.stringify(calls)).not.toContain('/export');
  });

  it('SRS-G-50: a role with A-01 EXPORT gets the export dialog', async () => {
    routes();
    wrap(<WorkflowMaster />, ADMIN);
    await rowOf('maker.delhi@fci-demo.test');
    await userEvent.click(screen.getByRole('button', { name: t('x01.export') }));
    await waitFor(() => expect(screen.getByRole('dialog')).toBeTruthy());
  });
});

describe('X-01 New Role on A-02', () => {
  const roles = () =>
    backend({
      'GET /v1/admin/roles': () => ({ roles: [], permissions: [], grants: [] }),
      'GET /v1/reference/lists': () => [],
    });

  it('SRS-22-19: new=role opens the form for the role that may propose one', async () => {
    window.history.pushState({}, '', '/?s=A-02&new=role');
    roles();
    wrap(<RolesMatrix />, ['A-02:VIEW', 'A-02:CREATE']);
    expect(await screen.findByLabelText(t('a02.code'))).toBeTruthy();
  });

  it('SRS-22-19: without the right, or outside Headquarters, or without new=role, the form stays closed', async () => {
    window.history.pushState({}, '', '/?s=A-02&new=role');
    roles();
    const first = wrap(<RolesMatrix />, ['A-02:VIEW']);
    await screen.findByLabelText(t('a02.filter'));
    expect(screen.queryByLabelText(t('a02.code'))).toBeNull();
    first.unmount();
    const second = wrap(
      <RolesMatrix />,
      me(['A-02:VIEW', 'A-02:CREATE'], { atHeadquarters: false, hqOnlyScreens: ['A-02'] }),
    );
    await screen.findByLabelText(t('a02.filter'));
    expect(screen.queryByLabelText(t('a02.code'))).toBeNull();
    second.unmount();
    window.history.pushState({}, '', '/?s=A-02');
    wrap(<RolesMatrix />, ['A-02:VIEW', 'A-02:CREATE']);
    await screen.findByLabelText(t('a02.filter'));
    expect(screen.queryByLabelText(t('a02.code'))).toBeNull();
  });
});
