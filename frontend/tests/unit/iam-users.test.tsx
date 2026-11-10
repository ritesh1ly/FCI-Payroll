import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { UsersAdmin } from '@/modules/iam';
import type { UserRow } from '@/modules/iam/api/adminApi';
import { backend, me, problem, referenceLists, sent, t, wrap, paged } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * A-01 Users: list and filter, create, and the account actions (suspend, reinstate, unlock, reset
 * password, deactivate) with their reasons; never on one's own account, never on a deactivated one,
 * and only with the permission. Refusals are shown with their reason.
 */

const REFERENCE = referenceLists({
  USER_STATUS: [
    ['ACTIVE', 'Active'],
    ['SUSPENDED', 'Suspended'],
    ['DEACTIVATED', 'Deactivated'],
  ],
  USER_CATEGORY: [
    ['OPERATIONS', 'Operations'],
    ['ESEVA', 'eSeva'],
  ],
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

const USERS = [
  user({}),
  user({ id: 11, loginId: 'locked@fci-demo.test', locked: true, mustChangePassword: true }),
  user({ id: 12, loginId: 'suspended@fci-demo.test', status: 'SUSPENDED', employeeEis: null }),
  user({ id: 13, loginId: 'gone@fci-demo.test', status: 'DEACTIVATED' }),
  user({ id: 1, loginId: 'user@fci-demo.test' }),
];

const ALL = ['A-01:VIEW', 'A-01:CREATE', 'A-01:EDIT', 'A-01:DELETE'];

function routes(over: Record<string, (url?: string) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/admin/users': () => ({ items: USERS, total: USERS.length }),
    'POST /v1/admin/users': () => ({}),
    ...over,
  });
}

const row = (login: string) => screen.getByText(login).closest('tr') as HTMLElement;

describe('A-01 Users', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  });

  it('lists users with their state, and offers the actions each state allows', async () => {
    routes();
    wrap(<UsersAdmin />, ALL);
    expect(await screen.findByText('maker.delhi@fci-demo.test')).toBeTruthy();
    expect(screen.getByText(t('a01.title', [5]))).toBeTruthy();
    const active = within(row('maker.delhi@fci-demo.test'));
    expect(active.getByRole('button', { name: t('a01.suspend') })).toBeTruthy();
    expect(active.queryByRole('button', { name: t('a01.unlock') })).toBeNull();
    const locked = within(row('locked@fci-demo.test'));
    expect(locked.getByText(t('a01.lockedOut'))).toBeTruthy();
    expect(locked.getByText(t('a01.mustChange'))).toBeTruthy();
    expect(locked.getByRole('button', { name: t('a01.unlock') })).toBeTruthy();
    expect(
      within(row('suspended@fci-demo.test')).getByRole('button', { name: t('a01.reinstate') }),
    ).toBeTruthy();
    expect(within(row('suspended@fci-demo.test')).getByText('—')).toBeTruthy();
    // Never on a deactivated account, never on the administrator's own: only the login record.
    for (const login of ['gone@fci-demo.test', 'user@fci-demo.test']) {
      expect(
        within(row(login))
          .getAllByRole('button')
          .map((b) => b.textContent),
      ).toEqual([t('a01.logins')]);
      expect(within(row(login)).queryByRole('checkbox')).toBeNull();
    }
  });

  it('sends the search text and the status filter', async () => {
    const calls = routes();
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.type(screen.getByLabelText(t('a01.search')), 'amit');
    await userEvent.selectOptions(screen.getByLabelText(t('m01.status')), 'SUSPENDED');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('q=amit') && c.url.includes('status=SUSPENDED'))).toBe(true),
    );
  });

  it('says so when no user matches', async () => {
    routes({ 'GET /v1/admin/users': () => ({ items: [], total: 0 }) });
    wrap(<UsersAdmin />, ALL);
    expect(await screen.findByText(t('a01.none'))).toBeTruthy();
  });

  it('a viewer sees no create button and no account actions', async () => {
    routes();
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await screen.findByText('maker.delhi@fci-demo.test');
    expect(screen.queryByRole('button', { name: t('action.newUser') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('a01.suspend') })).toBeNull();
  });

  it('without DELETE the deactivate action is not offered', async () => {
    routes();
    wrap(<UsersAdmin />, ['A-01:VIEW', 'A-01:EDIT']);
    await screen.findByText('maker.delhi@fci-demo.test');
    expect(screen.queryByRole('button', { name: t('a01.deactivate') })).toBeNull();
    expect(screen.getAllByRole('button', { name: t('a01.resetPassword') }).length).toBeGreaterThan(0);
  });

  it('suspends with a reason, reinstates, unlocks with a reason, deactivates as a separation', async () => {
    const calls = routes({ 'POST /v1/admin/users/': () => ({}) });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');

    await userEvent.click(
      within(row('maker.delhi@fci-demo.test')).getByRole('button', { name: t('a01.suspend') }),
    );
    const dialog = screen.getByRole('dialog');
    expect(
      (within(dialog).getByRole('button', { name: t('common.submit') }) as HTMLButtonElement).disabled,
    ).toBe(true);
    await userEvent.type(within(dialog).getByRole('textbox'), 'Long leave');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    await waitFor(() =>
      expect(sent(calls, 'POST', '/users/10/status')).toContainEqual({
        status: 'SUSPENDED',
        note: 'Long leave',
      }),
    );
    expect(await screen.findByText(t('a01.suspended'))).toBeTruthy();

    await userEvent.click(
      within(row('suspended@fci-demo.test')).getByRole('button', { name: t('a01.reinstate') }),
    );
    // OD-130: reinstating is a recorded decision: the reason dialog first.
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Reviewed');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('a01.reinstate') }),
    );
    await waitFor(() =>
      expect(sent(calls, 'POST', '/users/12/status')).toContainEqual({ status: 'ACTIVE', note: 'Reviewed' }),
    );

    await userEvent.click(within(row('locked@fci-demo.test')).getByRole('button', { name: t('a01.unlock') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Verified by phone');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    await waitFor(() =>
      expect(sent(calls, 'POST', '/users/11/unlock')).toContainEqual({ reason: 'Verified by phone' }),
    );

    await userEvent.click(
      within(row('maker.delhi@fci-demo.test')).getByRole('button', { name: t('a01.deactivate') }),
    );
    expect(screen.getByText(t('a01.deactivateTitle', ['maker.delhi@fci-demo.test']))).toBeTruthy();
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Retired');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    await waitFor(() =>
      expect(sent(calls, 'POST', '/users/10/status')).toContainEqual({
        status: 'DEACTIVATED',
        reason: 'SEPARATION',
        note: 'Retired',
      }),
    );
  });

  it('cancelling a dialog sends nothing', async () => {
    const calls = routes();
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(
      within(row('maker.delhi@fci-demo.test')).getByRole('button', { name: t('a01.suspend') }),
    );
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(
      within(row('maker.delhi@fci-demo.test')).getByRole('button', { name: t('a01.resetPassword') }),
    );
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('sets a temporary password and shows a refusal with its reason', async () => {
    const calls = routes({
      'POST /v1/admin/users/10/password': () => ({}),
      'POST /v1/admin/users/11/password': () => problem(422, 'AUTH-422-PASSWORD-POLICY'),
    });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(
      within(row('maker.delhi@fci-demo.test')).getByRole('button', { name: t('a01.resetPassword') }),
    );
    expect(screen.getByText(t('a01.resetTitle', ['maker.delhi@fci-demo.test']))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('a01.temporaryPassword')), 'Temp@Pass2026x');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('a01.resetPassword') }),
    );
    await waitFor(() =>
      expect(sent(calls, 'POST', '/users/10/password')).toContainEqual({
        temporaryPassword: 'Temp@Pass2026x',
      }),
    );
    expect(await screen.findByText(t('a01.passwordReset'))).toBeTruthy();

    await userEvent.click(
      within(row('locked@fci-demo.test')).getByRole('button', { name: t('a01.resetPassword') }),
    );
    await userEvent.type(screen.getByLabelText(t('a01.temporaryPassword')), 'short');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('a01.resetPassword') }),
    );
    expect((await screen.findAllByText(t('error.AUTH-422-PASSWORD-POLICY'))).length).toBeGreaterThan(0);
  });

  it('creates a user with optional fields left empty, and keeps the form on a refusal', async () => {
    let refuse = true;
    const calls = routes({
      'POST /v1/admin/users': () => (refuse ? problem(409, 'ADM-409-LOGIN-TAKEN') : {}),
    });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(screen.getByRole('button', { name: t('action.newUser') }));
    await userEvent.type(screen.getByLabelText(t('a01.loginId')), 'new.user@fci-demo.test');
    await userEvent.type(screen.getByLabelText(t('a01.displayName')), 'New User');
    await userEvent.type(screen.getByLabelText(t('a01.temporaryPassword')), 'Temp@Pass2026x');
    await userEvent.click(screen.getByRole('button', { name: t('common.create') }));
    expect(await screen.findByText(t('error.ADM-409-LOGIN-TAKEN'))).toBeTruthy();
    expect(screen.getByLabelText(t('a01.loginId'))).toBeTruthy();

    refuse = false;
    await userEvent.click(screen.getByRole('button', { name: t('common.create') }));
    await waitFor(() => expect(screen.queryByLabelText(t('a01.loginId'))).toBeNull());
    expect(sent(calls, 'POST', '/v1/admin/users').at(-1)).toEqual({
      loginId: 'new.user@fci-demo.test',
      displayName: 'New User',
      category: 'OPERATIONS',
      employeeEis: null,
      mobile: null,
      temporaryPassword: 'Temp@Pass2026x',
    });
    expect(await screen.findByText(t('a01.created'))).toBeTruthy();
  });

  it('closes the create form without sending', async () => {
    const calls = routes();
    wrap(<UsersAdmin />, me(ALL));
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(screen.getByRole('button', { name: t('action.newUser') }));
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('a01.loginId'))).toBeNull();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('shows a refused list load', async () => {
    routes({ 'GET /v1/admin/users': () => problem(403, 'AUTHZ-403-PERMISSION', ['A-01', 'VIEW']) });
    wrap(<UsersAdmin />, ALL);
    expect(await screen.findByText(t('error.AUTHZ-403-PERMISSION', ['A-01', 'VIEW']))).toBeTruthy();
  });

  it('SRS-G-49: a dismissed session end or unlock sends nothing', async () => {
    const calls = routes();
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(
      within(row('maker.delhi@fci-demo.test')).getByRole('button', { name: t('a01.terminate') }),
    );
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(within(row('locked@fci-demo.test')).getByRole('button', { name: t('a01.unlock') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/terminate-session')).toHaveLength(0);
    expect(sent(calls, 'POST', '/unlock')).toHaveLength(0);
  });

  it("SRS-G-49: ends a user's open sessions with a reason; the count comes back", async () => {
    const calls = routes({ 'POST /v1/admin/users/10/terminate-session': () => ({ sessions: 2 }) });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(
      within(row('maker.delhi@fci-demo.test')).getByRole('button', { name: t('a01.terminate') }),
    );
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('a01.terminateTitle', ['maker.delhi@fci-demo.test']))).toBeTruthy();
    expect(
      (within(dialog).getByRole('button', { name: t('a01.terminate') }) as HTMLButtonElement).disabled,
    ).toBe(true);
    await userEvent.type(within(dialog).getByRole('textbox'), 'Incident 42');
    await userEvent.click(within(dialog).getByRole('button', { name: t('a01.terminate') }));
    expect(await screen.findByText(t('a01.terminated', [2]))).toBeTruthy();
    expect(sent(calls, 'POST', '/terminate-session').at(-1)).toEqual({ reason: 'Incident 42' });
  });

  it('SRS-G-49: suspends a group of selected accounts with one reason; select-all takes the active ones', async () => {
    const calls = routes({ 'POST /v1/admin/users/suspend': () => ({ suspended: [10], skipped: [11] }) });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    expect(screen.queryByRole('button', { name: t('a01.suspendSelected', [1]) })).toBeNull();
    // Only active accounts other than one's own can be selected.
    expect(within(row('suspended@fci-demo.test')).queryByRole('checkbox')).toBeNull();
    const all = screen.getByRole('checkbox', { name: t('a01.selectAll') }) as HTMLInputElement;
    await userEvent.click(all);
    expect(all.checked).toBe(true);
    await userEvent.click(screen.getByRole('checkbox', { name: t('a01.select', ['locked@fci-demo.test']) }));
    expect(all.checked).toBe(false);
    await userEvent.click(screen.getByRole('checkbox', { name: t('a01.select', ['locked@fci-demo.test']) }));
    await userEvent.click(all);
    expect(screen.queryByRole('button', { name: /^Suspend selected/ })).toBeNull();
    await userEvent.click(
      screen.getByRole('checkbox', { name: t('a01.select', ['maker.delhi@fci-demo.test']) }),
    );
    await userEvent.click(screen.getByRole('checkbox', { name: t('a01.select', ['locked@fci-demo.test']) }));
    await userEvent.click(screen.getByRole('button', { name: t('a01.suspendSelected', [2]) }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('a01.suspendGroupTitle', [2]))).toBeTruthy();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Credential leak');
    await userEvent.click(within(dialog).getByRole('button', { name: t('a01.suspend') }));
    expect(await screen.findByText(t('a01.suspendedGroup', [1, 1]))).toBeTruthy();
    expect(sent(calls, 'POST', '/users/suspend').at(-1)).toEqual({
      userIds: [10, 11],
      reason: 'Credential leak',
    });
    expect(screen.queryByRole('button', { name: /^Suspend selected/ })).toBeNull();
  });

  // UI_BMAD_g1 #83: a new search or status filter clears the selection, so the group suspension
  // never takes users no longer on the screen.
  it('SRS-G-49: a search or a status change clears the group selection', async () => {
    routes();
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(
      screen.getByRole('checkbox', { name: t('a01.select', ['maker.delhi@fci-demo.test']) }),
    );
    expect(screen.getByRole('button', { name: t('a01.suspendSelected', [1]) })).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('a01.search')), 'mak');
    expect(screen.queryByRole('button', { name: /^Suspend selected/ })).toBeNull();
    await userEvent.click(
      await screen.findByRole('checkbox', { name: t('a01.select', ['maker.delhi@fci-demo.test']) }),
    );
    expect(screen.getByRole('button', { name: t('a01.suspendSelected', [1]) })).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m01.status')), 'ACTIVE');
    expect(screen.queryByRole('button', { name: /^Suspend selected/ })).toBeNull();
  });

  it('a refused group suspension is shown and the selection kept; cancelling sends nothing', async () => {
    const calls = routes({
      'POST /v1/admin/users/suspend': () => problem(403, 'AUTHZ-403-SCOPE'),
    });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    await userEvent.click(
      screen.getByRole('checkbox', { name: t('a01.select', ['maker.delhi@fci-demo.test']) }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('a01.suspendSelected', [1]) }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(calls.filter((c) => c.url.endsWith('/suspend'))).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: t('a01.suspendSelected', [1]) }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'x');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('a01.suspend') }));
    expect((await screen.findAllByText(t('error.AUTHZ-403-SCOPE'))).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: t('a01.suspendSelected', [1]) })).toBeTruthy();
  });

  it('SRS-G-50: the login record lists sign-ins with address, device and how each ended', async () => {
    routes({
      'GET /v1/admin/users/10/logins': () =>
        paged([
          {
            sessionId: 3,
            startedAt: '2026-10-21T04:30:00Z',
            endedAt: null,
            endReason: null,
            sourceIp: '10.0.0.7',
            device: 'Chrome 140 · Windows',
            open: true,
          },
          {
            sessionId: 2,
            startedAt: '2026-10-20T04:30:00Z',
            endedAt: '2026-10-20T05:00:00Z',
            endReason: 'ADMIN_REVOKE',
            sourceIp: null,
            device: 'Safari 18 · iPhone',
            open: false,
          },
          {
            sessionId: 1,
            startedAt: '2026-10-19T04:30:00Z',
            endedAt: '2026-10-19T05:00:00Z',
            endReason: null,
            sourceIp: '10.0.0.8',
            device: '—',
            open: false,
          },
        ]),
      'GET /v1/admin/users/11/logins': () => paged([]),
      'GET /v1/admin/users/12/logins': () => problem(403, 'AUTHZ-403-SCOPE'),
    });
    wrap(<UsersAdmin />, ['A-01:VIEW']);
    await screen.findByText('maker.delhi@fci-demo.test');
    const open = (login: string) =>
      userEvent.click(within(row(login)).getByRole('button', { name: t('a01.logins') }));
    await open('maker.delhi@fci-demo.test');
    expect(await screen.findByText(t('a01.loginsOf', ['maker.delhi@fci-demo.test']))).toBeTruthy();
    expect(screen.getByText('Chrome 140 · Windows')).toBeTruthy();
    expect(screen.getByText(t('a01.login.open'))).toBeTruthy();
    expect(screen.getByText(t('session.end.ADMIN_REVOKE'))).toBeTruthy();
    await open('maker.delhi@fci-demo.test');
    expect(screen.queryByText(t('a01.loginsOf', ['maker.delhi@fci-demo.test']))).toBeNull();
    await open('locked@fci-demo.test');
    expect(await screen.findByText(t('a01.login.none'))).toBeTruthy();
    await open('suspended@fci-demo.test');
    expect(await screen.findByText(t('error.AUTHZ-403-SCOPE'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText(t('a01.loginsOf', ['suspended@fci-demo.test']))).toBeNull();
    // A viewer sees no account action and no selection.
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('button', { name: t('a01.terminate') })).toBeNull();
  });

  it('SRS-NF-3: the list is fetched one page at a time and pages on', async () => {
    const calls = routes({
      'GET /v1/admin/users': (url?: string) =>
        String(url).includes('page=1')
          ? { items: [user({ id: 99, loginId: 'page.two@fci-demo.test' })], page: 1, size: 20, total: 51 }
          : { items: USERS, page: 0, size: 20, total: 51 },
    });
    wrap(<UsersAdmin />, ALL);
    await screen.findByText('maker.delhi@fci-demo.test');
    expect(screen.getByText(t('a01.title', [51]))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('page=0') && c.url.includes('size=20'))).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('page.next') }));
    expect(await screen.findByText('page.two@fci-demo.test')).toBeTruthy();
  });
});
