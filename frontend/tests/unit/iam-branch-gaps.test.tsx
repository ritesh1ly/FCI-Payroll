import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccessGroups, RolesMatrix, UsersAdmin } from '@/modules/iam';
import type { Group, Matrix, UserRow } from '@/modules/iam/api/adminApi';
import { LoginHero } from '@/modules/iam/components/LoginHero';
import { I18nProvider } from '@/shared/i18n';
import { backend, problem, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * Branch gaps of the IAM screens: the sign-in scene fitted to its width (wireframe login page), a
 * one-word feature label; A-01 a page change dropping users no longer shown from the group
 * selection (UI_BMAD_g1 #83, SRS-G-49) and a sign-in without its day count (SRS-G-50); A-02 a role
 * proposed while the base-role list is empty (SRS-22-8); A-03 the zone scope while the hierarchy
 * cannot be read (SRS-22-2).
 */

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.doUnmock('@/shared/i18n');
  vi.resetModules();
});

describe('Sign-in scene', () => {
  it('the laptop is scaled to the scene width once it has one, and watched while mounted', () => {
    const observed: Element[] = [];
    let disconnected = 0;
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private readonly callback: () => void) {}
        observe(el: Element) {
          observed.push(el);
          this.callback();
        }
        disconnect() {
          disconnected++;
        }
      },
    );
    const width = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(0);
    const { container, unmount } = render(
      <I18nProvider>
        <LoginHero />
      </I18nProvider>,
    );
    const scene = container.querySelector('.lx-scene') as HTMLElement;
    expect(observed).toEqual([scene]);
    expect(scene.style.getPropertyValue('--lx-k')).toBe('');
    unmount();
    expect(disconnected).toBe(1);

    width.mockReturnValue(1000);
    const again = render(
      <I18nProvider>
        <LoginHero />
      </I18nProvider>,
    );
    expect((again.container.querySelector('.lx-scene') as HTMLElement).style.getPropertyValue('--lx-k')).toBe(
      ((1000 * 0.54) / 560).toFixed(3),
    );
  });

  it('a feature label of one word stays on one line', async () => {
    vi.resetModules();
    vi.doMock('@/shared/i18n', async (original) => ({
      ...(await original<typeof import('@/shared/i18n')>()),
      useI18n: () => ({
        locale: 'en',
        setLocale: vi.fn(),
        t: (key: string) => (key.startsWith('login.feature.') ? 'Payroll' : key),
      }),
    }));
    const { LoginHero: Hero } = await import('@/modules/iam/components/LoginHero');
    const { container } = render(<Hero />);
    const labels = [...container.querySelectorAll('.lx-ft')];
    expect(labels).toHaveLength(4);
    expect(labels.every((f) => f.textContent === 'Payroll' && f.querySelector('br') === null)).toBe(true);
  });
});

const USER_REFERENCE = referenceLists({
  USER_STATUS: [['ACTIVE', 'Active']],
  USER_CATEGORY: [['OPERATIONS', 'Operations']],
});

const user = (over: Partial<UserRow>): UserRow => ({
  id: 10,
  loginId: 'maker.delhi@fci-demo.test',
  displayName: 'Amit Saxena',
  category: 'OPERATIONS',
  status: 'ACTIVE',
  employeeEis: '107001',
  locked: false,
  mustChangePassword: false,
  lastLoginAt: '2026-10-20T04:30:00Z',
  ...over,
});

describe('A-01 Users branch gaps', () => {
  const ALL = ['A-01:VIEW', 'A-01:CREATE', 'A-01:EDIT', 'A-01:DELETE'];

  it('SRS-G-49: a page change drops the users no longer shown from the group selection', async () => {
    backend({
      'GET /v1/reference/lists': () => USER_REFERENCE,
      'GET /v1/admin/users': (url: string) =>
        url.includes('page=1')
          ? { items: [user({ id: 99, loginId: 'page.two@fci-demo.test' })], page: 1, size: 20, total: 21 }
          : { items: [user({})], page: 0, size: 20, total: 21 },
    });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(
      screen.getByRole('checkbox', { name: t('a01.select', ['maker.delhi@fci-demo.test']) }),
    );
    expect(screen.getByRole('button', { name: t('a01.suspendSelected', [1]) })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('page.next') }));
    expect(await screen.findByText('page.two@fci-demo.test')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Suspend selected/ })).toBeNull());
  });

  it('SRS-G-50: a sign-in shown without its day count counts as today', async () => {
    backend({
      'GET /v1/reference/lists': () => USER_REFERENCE,
      'GET /v1/admin/users': () => ({ items: [], page: 0, size: 50, total: 0 }),
      'GET /v1/admin/readiness': () => ({ doMinimumStaff: null, units: [] }),
      'GET /v1/admin/active-users': () => ({
        inactiveAfterDays: null,
        items: [
          {
            userId: 10,
            loginId: 'maker.delhi@fci-demo.test',
            displayName: 'Amit Saxena',
            status: 'ACTIVE',
            lastLoginAt: '2026-10-01T04:30:00Z',
            daysSinceLogin: null,
            inactive: null,
            access: [],
          },
        ],
        page: 0,
        size: 50,
        total: 1,
      }),
    });
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await userEvent.click(await screen.findByRole('tab', { name: t('a01.tab.report') }));
    expect(await screen.findByText(t('a01.report.days', [0]))).toBeTruthy();
  });
});

const ROLE_REFERENCE = referenceLists({
  ROLE_TYPE: [
    ['FUNCTIONAL', 'Functional'],
    ['MODULE', 'Module'],
    ['READ_ONLY', 'Read-only'],
  ],
  BASE_ROLE: [],
  SCOPE_TYPE: [
    ['DIVISION', 'Division'],
    ['ZONE', 'Zone'],
  ],
  ACCESS_MODE: [['FULL', 'Full']],
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
  ],
  permissions: [{ id: 100, screenCode: 'M-01', actionCode: 'VIEW', name: 'M-01 view' }],
  grants: [{ roleId: 1, permissionId: 100 }],
};

describe('A-02 / A-03 branch gaps', () => {
  it('SRS-22-8: a functional or module role proposed while no base role can be offered sends none (the server decides)', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => ROLE_REFERENCE,
      'GET /v1/admin/roles': () => MATRIX,
      'POST /v1/admin/roles': () => problem(400, 'COM-400-VALIDATION'),
    });
    wrap(<RolesMatrix />, ['A-02:VIEW', 'A-02:CREATE', 'A-02:EDIT']);
    await screen.findByText(t('a02.title', [1]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newRole') }));
    await userEvent.type(screen.getByLabelText(t('a02.code')), 'CPF_MGR');
    await userEvent.type(screen.getByLabelText(t('a02.name')), 'Manager CPF');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'CPF module');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/admin/roles')).toHaveLength(1));
    const body = sent(calls, 'POST', '/v1/admin/roles')[0] as { roleType: string; baseRole: string | null };
    expect(body.roleType).not.toBe('READ_ONLY');
    expect(body.baseRole).toBeNull();
  });

  it('SRS-22-2: while the hierarchy cannot be read, a zone group offers no zone', async () => {
    const group: Group = {
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
      scopes: [],
    };
    backend({
      'GET /v1/reference/lists': () => ROLE_REFERENCE,
      'GET /v1/admin/roles': () => MATRIX,
      'GET /v1/admin/groups': () => [group],
      'GET /v1/admin/org': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<AccessGroups />, ['A-02:VIEW', 'A-03:VIEW', 'A-03:CREATE', 'A-03:EDIT']);
    await screen.findByText(t('a03.title', [1]));
    await userEvent.click(screen.getByRole('button', { name: t('action.newAccessGroup') }));
    await userEvent.selectOptions(screen.getByLabelText(t('a03.scopeType')), 'ZONE');
    const scope = screen.queryByLabelText(t('a03.scope'));
    expect(
      scope === null ||
        within(scope)
          .queryAllByRole('option')
          .every((o) => o.textContent !== 'North (NA11)'),
    ).toBe(true);
  });
});
