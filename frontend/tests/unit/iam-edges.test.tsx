import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccessGroups, Assignments, LoginPage, ScopeSwitcher } from '@/modules/iam';
import type { Assignment, Group } from '@/modules/iam/api/adminApi';
import { I18nProvider } from '@/shared/i18n';
import type { ScopeOption } from '@/shared/session';
import { backend, me, problem, referenceLists, t, wrap } from './support/harness';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: push }) }));

/**
 * The IAM screens without every particular (SRS-22-3, SRS-22-8, SRS-22-13, SRS-G-29): a group whose
 * role or scope labels are missing, a role catalogue that failed to load, an assignment without a
 * login or a revocation reason, more users than the search shows, a session whose only office or
 * eSeva grant leaves nothing to switch to, and the sign-in help closed by a click outside or Escape.
 */

afterEach(() => vi.unstubAllGlobals());

const HQ_ONLY = t('error.AUTHZ-403-HQ-ONLY');

const REFERENCE = referenceLists({
  SCOPE_TYPE: [
    ['DIVISION', 'Division'],
    ['ZONE', 'Zone'],
    ['HEADQUARTERS', 'Headquarters'],
    ['GLOBAL', 'Global'],
  ],
  ACCESS_MODE: [
    ['FULL', 'Full'],
    ['READ_ONLY', 'Read-only'],
  ],
});

const ORG = {
  zones: [{ id: 7, code: 'NA11', name: 'North' }],
  locations: [{ id: 20, code: 'DH001', name: 'Delhi HQ', type: 'DO', label: 'DO — Delhi HQ (DH001)' }],
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

describe('A-03 without every particular', () => {
  const ALL = ['A-02:VIEW', 'A-03:VIEW', 'A-03:CREATE', 'A-03:EDIT'];
  const bare = group({
    id: 34,
    code: 'AG-BARE',
    roleCode: null,
    scopeType: 'ZONE',
    scopes: [
      { type: 'ZONE', id: 7, code: 'NA11', label: null },
      { type: 'LOCATION', id: 20, code: null, label: null },
    ],
  });

  it('a group whose role is gone and whose scopes have no label shows what it still has; outside Headquarters every change is locked', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/admin/roles': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/admin/groups': () => [bare, group({})],
      'GET /v1/admin/org': () => ORG,
    });
    wrap(<AccessGroups />, me(ALL, { atHeadquarters: false, hqOnlyScreens: ['A-03'] }));
    const row = (await screen.findByText('AG-BARE')).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeTruthy();
    expect(within(row).getByText('NA11, 20')).toBeTruthy();
    const locked = screen.getAllByTitle(HQ_ONLY);
    expect(locked.length).toBeGreaterThanOrEqual(3);
    locked.forEach((button) => expect(button).toBeDisabled());
  });

  it('a role catalogue that failed leaves no role to choose; a zone scope lists the zones', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/admin/roles': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/admin/groups': () => [group({})],
      'GET /v1/admin/org': () => ORG,
    });
    wrap(<AccessGroups />, ALL);
    await userEvent.click(await screen.findByRole('button', { name: t('action.newAccessGroup') }));
    expect(within(screen.getByLabelText(t('a03.role'))).queryAllByRole('option')).toHaveLength(0);
    await userEvent.selectOptions(screen.getByLabelText(t('a03.scopeType')), 'ZONE');
    expect(within(screen.getByLabelText(t('a03.scope'))).getByText('North (NA11)')).toBeTruthy();
  });
});

describe('A-04 without every particular', () => {
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

  it('a revocation of an assignment without a login names the user id; a revoked one without a reason carries no hint; more users than shown is said', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/admin/assignments': () => ({
        items: [
          assignment({ id: 41, loginId: null, userId: 22 }),
          assignment({
            id: 42,
            loginId: 'gone@fci-demo.test',
            revokedAt: '2026-10-01T05:00:00Z',
            revokeReason: null,
          }),
        ],
        total: 2,
      }),
      'GET /v1/admin/users': () => ({
        items: [{ id: 10, loginId: 'maker.delhi@fci-demo.test', displayName: 'Amit' }],
        total: 25,
      }),
      'GET /v1/admin/groups': () => [group({})],
      'POST /v1/admin/assignments/': () => ({}),
    });
    wrap(<Assignments />, ALL);
    await screen.findByText(t('a04.title', [2]));
    const revoked = screen.getByText('gone@fci-demo.test').closest('tr') as HTMLElement;
    expect(revoked.querySelector('.pill.p-gray')).not.toHaveAttribute('title');
    const nameless = screen.getByText('22').closest('tr') as HTMLElement;
    await userEvent.click(within(nameless).getByRole('button', { name: t('action.revoke') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Left the office');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('a04.revoked', [22]))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.newAssignment') }));
    await userEvent.type(screen.getByLabelText(t('a04.findUser')), 'ma');
    expect(await screen.findByText(t('a04.moreUsers', [1, 25]))).toBeTruthy();
  });
});

describe('the scope switcher without every particular', () => {
  function show(scopes: ScopeOption[]) {
    const onSwitched = vi.fn();
    const onError = vi.fn();
    const calls = backend({
      'GET /auth/scopes': () => scopes,
      'POST /auth/switch-scope': () => ({ status: 204, body: null }),
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(['me'], me(['W-02:VIEW']));
    render(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <ScopeSwitcher enabled onSwitched={onSwitched} onError={onError} />
        </QueryClientProvider>
      </I18nProvider>,
    );
    return { calls, onSwitched, onError };
  }
  const maker = (current: boolean): ScopeOption => ({
    role: 'PAYROLL_MAKER',
    roleName: 'Payroll Maker',
    persona: 'payroll',
    location: 'DH001',
    locationType: 'DO',
    label: 'DO Delhi',
    current,
  });
  const member = (current: boolean): ScopeOption => ({
    role: 'EMPLOYEE_ESEVA',
    roleName: 'Employee',
    persona: 'employee',
    location: null,
    locationType: null,
    label: null,
    current,
  });

  it('SRS-G-29: a single office cannot be left and choosing it again switches nothing; the eSeva grant is named as such', async () => {
    const { calls } = show([maker(true), member(false)]);
    const office = await screen.findByRole('combobox', { name: t('top.office') });
    await waitFor(() => expect(office).toBeDisabled());
    const role = screen.getByRole('combobox', { name: t('top.role') });
    expect(within(role).getByRole('option', { name: t('role.EMPLOYEE_ESEVA') })).toBeTruthy();
    fireEvent.change(office, { target: { value: 'DH001' } });
    await userEvent.selectOptions(role, 'PAYROLL_MAKER@DH001');
    expect(calls.some((c) => c.url.includes('/auth/switch-scope'))).toBe(false);
  });

  it('an eSeva session shows no office of its own until it switches to one', async () => {
    const { calls } = show([member(true), maker(false)]);
    const office = await screen.findByRole('combobox', { name: t('top.office') });
    await screen.findByRole('option', { name: 'DO Delhi' });
    expect((office as HTMLSelectElement).value).toBe('');
    await userEvent.selectOptions(office, 'DH001');
    await waitFor(() => expect(calls.some((c) => c.url.includes('/auth/switch-scope'))).toBe(true));
  });
});

describe('the sign-in page without every particular', () => {
  it('the help closes on a click outside it or on Escape; a sign-in catalogue that failed is reported', async () => {
    let optionsFail = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.endsWith('/auth/login-options')) {
          return optionsFail
            ? new Response(JSON.stringify({ errorCode: 'COM-503-UNAVAILABLE' }), { status: 503 })
            : new Response(JSON.stringify({ demo: false }), { status: 200 });
        }
        return new Response(null, { status: 204 });
      }),
    );
    document.cookie = 'XSRF-TOKEN=t; Path=/';
    const { unmount } = render(
      <I18nProvider>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <LoginPage />
        </QueryClientProvider>
      </I18nProvider>,
    );
    await screen.findByPlaceholderText('Enter your password');
    await userEvent.click(screen.getByRole('button', { name: 'Need help? Contact your HR / IT helpdesk' }));
    const backdrop = screen.getByRole('dialog').parentElement as HTMLElement;
    await userEvent.click(screen.getByRole('dialog'));
    expect(screen.getByRole('dialog')).toBeTruthy();
    await userEvent.click(backdrop);
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Need help? Contact your HR / IT helpdesk' }));
    fireEvent.keyDown(screen.getByRole('dialog').parentElement as HTMLElement, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    unmount();

    optionsFail = true;
    render(
      <I18nProvider>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <LoginPage />
        </QueryClientProvider>
      </I18nProvider>,
    );
    expect(await screen.findByText(t('error.network'))).toBeTruthy();
  });
});
