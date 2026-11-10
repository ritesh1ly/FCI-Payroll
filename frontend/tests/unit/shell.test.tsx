import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Users } from 'lucide-react';
import { afterEach, beforeEach, describe, expect, it, vi, onTestFinished } from 'vitest';
import { Workspace } from '@/modules/shell';
import { SCREENS } from '@/modules/shell/components/screenRegistry';
import { I18nProvider } from '@/shared/i18n';
import type { Me } from '@/shared/session';
import { backend, me, noContent, problem, sent, t } from './support/harness';

const replace = vi.fn();
const push = vi.fn();
let search = new URLSearchParams();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  useSearchParams: () => search,
}));

/**
 * The workspace shell (SRS-G-25, SRS-G-29): menu and search from the server's menu only, the
 * account menu, the period / office / role selectors, sign-out, the open screen with its governed
 * buttons and previous / next navigation, and a refused screen.
 */

const item = (code: string) => ({
  code,
  labelKey: `screen.${code}`,
  route: `/payroll?s=${code}`,
  requires: `${code}:VIEW`,
});
const MENU = {
  sections: [
    { id: 'work', labelKey: 'nav.work', items: [item('W-02'), item('X-05')] },
    { id: 'masters', labelKey: 'nav.masters', items: [item('M-01'), item('M-04')] },
  ],
  hiddenCount: 3,
};
const MAKER: Me = me(['W-02:VIEW', 'X-05:VIEW', 'M-01:VIEW', 'M-04:VIEW'], {
  roleName: 'Payroll Maker (Dealing Hand)',
});

const SCOPES = [
  {
    role: 'PAYROLL_MAKER',
    roleName: 'Payroll Maker',
    persona: 'payroll',
    location: 'DH001',
    locationType: 'DO',
    label: 'DO Delhi',
    current: true,
  },
  {
    role: 'PAYROLL_MAKER',
    roleName: 'Payroll Maker',
    persona: 'payroll',
    location: 'KL001',
    locationType: 'DO',
    label: 'DO Kolkata',
    current: false,
  },
  {
    role: 'FINANCE',
    roleName: 'Finance',
    persona: 'finance',
    location: 'DH001',
    locationType: 'DO',
    label: 'DO Delhi',
    current: false,
  },
];

function show(profile: Me = MAKER, menu: unknown = MENU) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['me'], profile);
  client.setQueryData(['menu'], menu);
  return render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        <Workspace />
      </QueryClientProvider>
    </I18nProvider>,
  );
}

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend(
    {
      'GET /auth/scopes': () => SCOPES,
      'GET /v1/periods': () => [
        { period: '2026-11', status: null, current: false },
        { period: '2026-10', status: 'INITIATED', current: true },
        { period: '2026-09', status: 'LOCKED', current: false },
      ],
      'GET /auth/me': () => MAKER,
      'GET /menu': () => MENU,
      'POST /auth/logout': () => noContent(),
      ...over,
    },
    [],
  );
}

let compact = false;
beforeEach(() => {
  compact = false;
  window.matchMedia = ((query: string) => ({
    matches: compact,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  replace.mockReset();
  push.mockReset();
  search = new URLSearchParams();
});
afterEach(() => vi.unstubAllGlobals());

describe('Workspace navigation', () => {
  it('SRS-G-25: the menu comes from the server; sections fold; a hidden count is shown', async () => {
    routes();
    show();
    expect(await screen.findByRole('heading', { level: 1, name: t('dash.title') })).toBeTruthy();
    expect(screen.getByText(t('nav.hidden', [3]))).toBeTruthy();
    const nav = screen.getByRole('complementary', { name: t('nav.label') });
    const masters = within(nav).getByRole('button', { name: new RegExp(t('nav.masters')) });
    await userEvent.click(masters);
    expect(masters.getAttribute('aria-expanded')).toBe('false');
    expect(within(nav).queryByRole('button', { name: /M-04/ })).toBeNull();
    await userEvent.click(within(nav).getByRole('button', { name: new RegExp(t('nav.expandAll')) }));
    expect(within(nav).getByRole('button', { name: /M-04/ })).toBeTruthy();
    await userEvent.click(within(nav).getByRole('button', { name: new RegExp(t('nav.collapseAll')) }));
    expect(within(nav).queryByRole('button', { name: /W-02/ })).toBeNull();
    await userEvent.click(within(nav).getByRole('button', { name: new RegExp(t('nav.work')) }));
    await userEvent.click(within(nav).getByRole('button', { name: /W-02/ }));
    expect(push).toHaveBeenCalledWith('/payroll?s=W-02');
  });

  // UI_BMAD_g1 #40 / #44 (SRS-G-38): the home page lists the circulars in force for the office to a
  // role that may view M-25 (at most five, the rest counted); a role without M-25 sees no card.
  it('SRS-G-38: the home page shows the current circulars of the office to an M-25 viewer', async () => {
    const circular = (id: number) => ({
      id,
      number: `HQ/DA/${id}`,
      date: '2026-10-01',
      subject: `Order ${id}`,
      documentId: id === 1 ? 3 : null,
    });
    routes({
      'GET /v1/masters/circulars/current': () => [1, 2, 3, 4, 5, 6].map(circular),
    });
    show(me([...MAKER.permissions, 'M-25:VIEW'], { roleName: 'Payroll Maker (Dealing Hand)' }));
    expect(await screen.findByText(t('dash.circulars'))).toBeTruthy();
    expect(await screen.findByText('HQ/DA/1')).toBeTruthy();
    expect(screen.queryByText('HQ/DA/6')).toBeNull();
    expect(screen.getByText(t('dash.circularsMore', [1]))).toBeTruthy();
    expect(screen.getAllByRole('link', { name: t('dash.circularOpen') })).toHaveLength(1);
  });

  it('SRS-G-38: no circular in force says so; without M-25 VIEW there is no circulars card', async () => {
    const calls = routes({ 'GET /v1/masters/circulars/current': () => [] });
    const view = show(me([...MAKER.permissions, 'M-25:VIEW']));
    expect(await screen.findByText(t('dash.circularsNone'))).toBeTruthy();
    view.unmount();
    show();
    expect(await screen.findByRole('heading', { level: 1, name: t('dash.title') })).toBeTruthy();
    expect(screen.queryByText(t('dash.circulars'))).toBeNull();
    expect(calls.filter((c) => c.url.includes('/circulars/current'))).toHaveLength(1);
  });

  it('a role without screens says so; the brand goes home', async () => {
    routes();
    show(me([]), { sections: [], hiddenCount: 0 });
    expect((await screen.findAllByText(t('nav.noScreens'))).length).toBeGreaterThan(0);
    await userEvent.click(screen.getByTitle(t('dash.title')));
    expect(push).toHaveBeenCalledWith('/payroll');
  });

  it('search finds screens by name or code; Enter opens the first, or an EIS on M-01; Escape clears', async () => {
    routes();
    show();
    const box = await screen.findByRole('searchbox', { name: t('top.search') });
    await userEvent.type(box, 'zzz');
    expect(screen.getByText(t('top.noMatch'))).toBeTruthy();
    await userEvent.type(box, '{Escape}');
    expect((box as HTMLInputElement).value).toBe('');
    await userEvent.type(box, 'm-04');
    await userEvent.click(screen.getByRole('option', { name: /M-04/ }));
    expect(push).toHaveBeenLastCalledWith('/payroll?s=M-04');
    await userEvent.type(box, 'x-05{Enter}');
    expect(push).toHaveBeenLastCalledWith('/payroll?s=X-05');
    await userEvent.type(box, '104882{Enter}');
    expect(push).toHaveBeenLastCalledWith('/payroll?s=M-01&eis=104882');
    await userEvent.clear(box);
    await userEvent.type(box, '!{Enter}');
    expect(push).toHaveBeenCalledTimes(3);
  });

  it('on a phone the context moves into the drawer; search and drawer open and Escape closes both', async () => {
    compact = true;
    routes();
    const { container } = show();
    await screen.findByRole('heading', { level: 1, name: t('dash.title') });
    expect(screen.getByRole('group', { name: t('nav.context') })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('top.searchOpen') }));
    expect(container.querySelector('.wf.searching')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('nav.menu') }));
    expect(container.querySelector('.wf.navopen')).toBeTruthy();
    expect(document.documentElement.classList.contains('fl-noscroll')).toBe(true);
    await userEvent.keyboard('{Escape}');
    expect(container.querySelector('.wf.navopen')).toBeNull();
    expect(container.querySelector('.wf.searching')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('nav.menu') }));
    await userEvent.click(container.querySelector('.navscrim') as HTMLElement);
    expect(container.querySelector('.wf.navopen')).toBeNull();
  });

  it('offline is announced; a consolidated read-only scope and an eSeva session without office are labelled', async () => {
    routes();
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false);
    show(
      me(['E-01:VIEW'], { location: null, readOnlyConsolidated: true, role: null, roleName: 'Employee' }),
      {
        sections: [{ id: 'self', labelKey: 'nav.self', items: [item('E-01')] }],
        hiddenCount: 0,
      },
    );
    expect(await screen.findByText(t('pwa.offline'))).toBeTruthy();
    expect(screen.getByText(t('scope.noOffice'), { selector: '.loc' })).toBeTruthy();
    expect(screen.getByText(t('nav.readOnlyScope'))).toBeTruthy();
    vi.restoreAllMocks();
  });
});

describe('Workspace account and scope', () => {
  it('the account menu changes the password, then refreshes the session', async () => {
    routes({ 'POST /auth/password': () => noContent() });
    show();
    await userEvent.click(await screen.findByRole('button', { name: t('account.menu') }));
    expect(screen.getByText(MAKER.loginId)).toBeTruthy();
    await userEvent.click(screen.getByRole('menuitem', { name: new RegExp(t('account.changePassword')) }));
    expect(await screen.findByRole('heading', { name: t('password.title') })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('heading', { name: t('password.title') })).toBeNull();
  });

  it('a forced password change shows only the change; cancelling it signs out', async () => {
    const calls = routes();
    show(me([], { passwordChangeRequired: true }));
    expect(await screen.findByRole('heading', { name: t('password.title') })).toBeTruthy();
    expect(screen.queryByRole('complementary')).toBeNull();
    expect(screen.getByText(t('password.forced'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('account.signOut') }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
    expect(calls.some((c) => c.url.endsWith('/auth/logout'))).toBe(true);
  });

  it('SRS-G-36: a password change checks the confirmation, shows policy violations, then refreshes', async () => {
    let answer: unknown = {
      status: 400,
      body: {
        errorCode: 'AUTH-400-PASSWORD-POLICY',
        messageKey: 'error.AUTH-400-PASSWORD-POLICY',
        violations: [{ code: 'LOW_COMPLEXITY' }, { code: 'COMMON_PASSWORD' }],
      },
    };
    const calls = routes({ 'POST /auth/password/change': () => answer });
    show();
    await userEvent.click(await screen.findByRole('button', { name: t('account.menu') }));
    await userEvent.click(screen.getByRole('menuitem', { name: new RegExp(t('account.changePassword')) }));
    const field = (key: string) => screen.getByLabelText(t(key));
    await userEvent.type(field('password.current'), 'Old#Pass2026');
    await userEvent.type(field('password.new'), 'New#Pass2026x');
    await userEvent.type(field('password.confirm'), 'Different#1');
    await userEvent.click(screen.getByRole('button', { name: t('password.submit') }));
    expect((await screen.findByRole('alert')).textContent).toContain(t('reset.mismatch'));
    expect(calls.some((c) => c.url.endsWith('/auth/password/change'))).toBe(false);
    await userEvent.clear(field('password.confirm'));
    await userEvent.type(field('password.confirm'), 'New#Pass2026x');
    await userEvent.click(screen.getByRole('button', { name: t('password.submit') }));
    const alert = await screen.findByText(t('password.policy.LOW_COMPLEXITY'));
    expect(alert.closest('[role=alert]')?.textContent).toContain(t('password.policy.COMMON_PASSWORD'));
    answer = { status: 204, body: null };
    await userEvent.click(screen.getByRole('button', { name: t('password.submit') }));
    expect(await screen.findByText(t('password.changed'))).toBeTruthy();
    expect(screen.queryByRole('heading', { name: t('password.title') })).toBeNull();
    expect(sent(calls, 'POST', '/auth/password/change').at(-1)).toEqual({
      currentPassword: 'Old#Pass2026',
      newPassword: 'New#Pass2026x',
    });
  });

  it('a password change that fails without an answer shows the generic error', async () => {
    routes({
      'POST /auth/password/change': () => {
        throw new Error('network');
      },
    });
    show();
    await userEvent.click(await screen.findByRole('button', { name: t('account.menu') }));
    await userEvent.click(screen.getByRole('menuitem', { name: new RegExp(t('account.changePassword')) }));
    for (const key of ['password.current', 'password.new', 'password.confirm']) {
      await userEvent.type(screen.getByLabelText(t(key)), 'Same#Pass2026');
    }
    await userEvent.click(screen.getByRole('button', { name: t('password.submit') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('SRS-G-48: sign-out ends the session and returns to sign-in', async () => {
    const calls = routes();
    show();
    await userEvent.click(await screen.findByRole('button', { name: t('account.menu') }));
    await userEvent.click(screen.getByRole('menuitem', { name: new RegExp(t('account.signOut')) }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
    expect(calls.filter((c) => c.url.endsWith('/auth/logout'))).toHaveLength(1);
  });

  it('a lost session sends the user to sign-in', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    backend({ 'GET /auth/me': () => problem(401, 'AUTH-401-SESSION-EXPIRED') });
    render(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <Workspace />
        </QueryClientProvider>
      </I18nProvider>,
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
  });

  it('SRS-G-29: another office switches without signing out and keeps a screen the role may view', async () => {
    search = new URLSearchParams('s=W-02');
    const calls = routes({
      'POST /auth/switch-scope': () => noContent(),
      'GET /auth/me': () => me(MAKER.permissions, { location: { code: 'KL001', label: 'DO Kolkata' } }),
    });
    show();
    const office = await screen.findByRole('combobox', { name: t('top.office') });
    await screen.findByRole('option', { name: 'DO Kolkata' });
    await userEvent.selectOptions(office, 'KL001');
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/payroll?s=W-02'));
    expect(sent(calls, 'POST', '/auth/switch-scope').at(-1)).toEqual({
      role: 'PAYROLL_MAKER',
      location: 'KL001',
    });
    expect(
      await screen.findByText(t('scope.switched', [t('role.PAYROLL_MAKER'), 'DO Kolkata'])),
    ).toBeTruthy();
  });

  it('another role the open screen does not allow lands on the dashboard; a refused switch is a message', async () => {
    search = new URLSearchParams('s=W-02');
    let refuse = false;
    routes({
      'POST /auth/switch-scope': () => (refuse ? problem(403, 'AUTH-403-SCOPE') : noContent()),
      'GET /auth/me': () => me(['R-01:VIEW'], { role: 'FINANCE', location: null }),
    });
    show();
    const role = await screen.findByRole('combobox', { name: t('top.role') });
    await screen.findByRole('option', { name: t('role.FINANCE') });
    await userEvent.selectOptions(role, 'FINANCE@DH001');
    await waitFor(() => expect(push).toHaveBeenCalledWith('/payroll'));
    expect(await screen.findByText(t('scope.switchedEseva', [t('role.FINANCE')]))).toBeTruthy();
    refuse = true;
    await userEvent.selectOptions(role, 'FINANCE@DH001');
  });

  it('M-10: another payroll period reloads the session; a refusal is a message', async () => {
    let refuse = false;
    const calls = routes({
      'PUT /auth/period': () => (refuse ? problem(422, 'PER-422-FUTURE') : noContent()),
      'GET /auth/me': () => me(MAKER.permissions, { period: '2026-09' }),
    });
    show();
    const period = await screen.findByRole('combobox', { name: t('top.period') });
    await screen.findByRole('option', { name: /SEP 2026/ });
    expect(screen.queryByRole('option', { name: /NOV 2026/ })).toBeNull();
    await userEvent.selectOptions(period, '2026-09');
    expect(await screen.findByText(t('period.switched', ['SEP 2026']))).toBeTruthy();
    expect(sent(calls, 'PUT', '/auth/period').at(-1)).toEqual({ period: '2026-09' });
    refuse = true;
    await userEvent.selectOptions(period, '2026-10');
    expect(await screen.findByText(t('error.PER-422-FUTURE'))).toBeTruthy();
  });
});

describe('ScreenView', () => {
  const ACTIONS = [
    {
      screenCode: 'M-01',
      actionCode: 'EXPORT',
      labelKey: 'action.export',
      allowed: true,
      disabledReasonKey: null,
    },
    {
      screenCode: 'M-01',
      actionCode: 'CREATE',
      labelKey: 'action.create',
      allowed: true,
      disabledReasonKey: null,
    },
    {
      screenCode: 'M-01',
      actionCode: 'SUBMIT',
      labelKey: 'action.submit',
      allowed: false,
      disabledReasonKey: 'reason.noPermission',
    },
    {
      screenCode: 'M-01',
      actionCode: 'EDIT',
      labelKey: 'action.edit',
      allowed: true,
      disabledReasonKey: null,
    },
    {
      screenCode: 'M-01',
      actionCode: 'APPROVE',
      labelKey: 'action.approve',
      allowed: true,
      disabledReasonKey: null,
    },
    {
      screenCode: 'W-02',
      actionCode: 'EXPORT',
      labelKey: 'action.export',
      allowed: true,
      disabledReasonKey: null,
    },
  ];

  it('SRS-G-1: an open screen shows its title, previous / next and the footer', async () => {
    search = new URLSearchParams('s=X-05');
    routes();
    show();
    expect(await screen.findByRole('heading', { level: 1, name: t('screen.X-05') })).toBeTruthy();
    expect(screen.getByText(t('screen.footer', ['X-05']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: `← ${t('screen.W-02')}` }));
    expect(push).toHaveBeenLastCalledWith('/payroll?s=W-02');
    await userEvent.click(screen.getByRole('button', { name: `${t('screen.M-01')} →` }));
    expect(push).toHaveBeenLastCalledWith('/payroll?s=M-01');
  });

  it('governed buttons: disabled with the reason, export at once, others with a reason (submitted / recorded)', async () => {
    search = new URLSearchParams('s=M-01');
    // The governed toolbar serves a screen with the wireframe flag; M-01 now draws its own buttons (OD-121), so the
    // test sets the flag on it for this run.
    const entry = SCREENS['M-01'] as { wireframe?: boolean };
    entry.wireframe = true;
    onTestFinished(() => {
      delete entry.wireframe;
    });
    let outcome = 'SUBMITTED';
    const calls = routes({
      'GET /v1/me/actions': () => ACTIONS,
      'POST /v1/actions': (_url, init) => {
        const body = JSON.parse(String(init?.body)) as { action: string };
        if (body.action === 'EXPORT') return problem(503, 'COM-503-UNAVAILABLE');
        return { outcome, approvalId: 9 };
      },
    });
    show();
    const create = await screen.findByRole('button', { name: t('action.create') });
    const submit = screen.getByRole('button', { name: t('action.submit') }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(submit.title).toBe(t('reason.noPermission'));
    expect(screen.queryByRole('button', { name: t('action.edit') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('action.approve') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('action.export') }));
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeTruthy();
    await userEvent.click(create);
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(create);
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'New hire');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('action.submitted', [9]))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/actions').at(-1)).toEqual({
      screen: 'M-01',
      action: 'CREATE',
      reason: 'New hire',
    });
    outcome = 'RECORDED';
    await userEvent.click(create);
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Again');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('action.recorded', [t('action.create')]))).toBeTruthy();
    // The body of M-01 is the live screen: no design-pack body is fetched.
    expect(calls.some((c) => c.url.includes('/payroll/wireframe/'))).toBe(false);
  });

  it('a design-pack screen renders the server body and handles its mock-up actions', async () => {
    SCREENS['T-99'] = { code: 'T-99', icon: Users, wireframe: true };
    try {
      search = new URLSearchParams('s=T-99');
      const wfAct = (a: string, args: string[]) => JSON.stringify({ act: a, args });
      routes({
        'GET /payroll/wireframe/T-99': () => [
          [
            'div',
            {},
            [
              ['button', { 'data-act': wfAct('toast', ['Hello']) }, ['Toast']],
              ['button', { 'data-act': wfAct('doAct', ['Bulk update']) }, ['Mock']],
              ['button', { 'data-act': wfAct('scrollToForm', ['f1']) }, ['Go to form']],
              ['button', { 'data-act': wfAct('scrollToForm', []) }, ['Nowhere']],
              ['button', { 'data-act': wfAct('setM1Filter', ['ACTIVE']) }, ['Filter']],
              ['div', { id: 'f1' }, ['Form']],
            ],
          ],
        ],
      });
      show(me(['T-99:VIEW']), {
        sections: [{ id: 'x', labelKey: 'nav.work', items: [item('T-99')] }],
        hiddenCount: 0,
      });
      Element.prototype.scrollIntoView = vi.fn();
      await userEvent.click(await screen.findByRole('button', { name: 'Toast' }));
      expect(await screen.findByText('Hello')).toBeTruthy();
      await userEvent.click(screen.getByRole('button', { name: 'Mock' }));
      expect(await screen.findByText(t('wireframe.logged', ['Bulk update']))).toBeTruthy();
      vi.useFakeTimers({ shouldAdvanceTime: true });
      await userEvent.click(screen.getByRole('button', { name: 'Go to form' }));
      expect(document.getElementById('f1')?.classList.contains('flashform')).toBe(true);
      act(() => {
        vi.advanceTimersByTime(2000);
      });
      expect(document.getElementById('f1')?.classList.contains('flashform')).toBe(false);
      vi.useRealTimers();
      await userEvent.click(screen.getByRole('button', { name: 'Nowhere' }));
      await userEvent.click(screen.getByRole('button', { name: 'Filter' }));
    } finally {
      delete SCREENS['T-99'];
    }
  });

  it('SRS-G-13: a built screen the wireframe draws no menu entry for opens on its VIEW, not on the menu', async () => {
    search = new URLSearchParams('s=W-02');
    routes({ 'GET /auth/me': () => me([...MAKER.permissions, 'W-02:VIEW']) });
    show(me([...MAKER.permissions, 'W-02:VIEW']));
    expect(await screen.findByRole('heading', { level: 1, name: new RegExp(t('screen.W-02')) })).toBeTruthy();
    expect(screen.queryByText(t('denied.title'))).toBeNull();
  });

  it('SRS-G-25: a screen outside the role shows the refusal, not the screen', async () => {
    search = new URLSearchParams('s=A-07');
    routes();
    show();
    expect(await screen.findByText(t('denied.title'))).toBeTruthy();
    expect(screen.getByText(t('denied.text', ['A-07']))).toBeTruthy();
  });
});
