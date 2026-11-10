import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Users } from 'lucide-react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Workspace } from '@/modules/shell';
import { SCREENS } from '@/modules/shell/components/screenRegistry';
import { useKeepAlive } from '@/modules/iam';
import { api } from '@/shared/api';
import { I18nProvider, useI18n } from '@/shared/i18n';
import { useToast } from '@/shared/ui';
import { PwaRegistration } from '@/shared/pwa';
import type { Me } from '@/shared/session';
import { backend, me, noContent, problem, t } from './support/harness';

const replace = vi.fn();
const push = vi.fn();
let search = new URLSearchParams();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  useSearchParams: () => search,
}));

/**
 * The workspace shell without every particular (SRS-G-25, SRS-G-29): a session without a role
 * name, office or session detail, a menu answer without sections, a scope switch to a role the
 * catalogue does not name, a screen the registry does not know, a locked action without a stated
 * reason, mock-up actions without arguments, a cancelled OTP on a governed action, and the
 * browser's offer to install the app.
 */

const item = (code: string) => ({
  code,
  labelKey: `screen.${code}`,
  route: `/payroll?s=${code}`,
  requires: `${code}:VIEW`,
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
    role: 'FINANCE',
    roleName: 'Finance',
    persona: 'finance',
    location: 'DH001',
    locationType: 'DO',
    label: 'DO Delhi',
    current: false,
  },
];

function show(profile: Me, menu?: unknown, pwa = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['me'], profile);
  if (menu !== undefined) client.setQueryData(['menu'], menu);
  return render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        {pwa && <PwaRegistration enabled version="test" />}
        <Workspace />
      </QueryClientProvider>
    </I18nProvider>,
  );
}

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend(
    {
      'GET /auth/scopes': () => SCOPES,
      'GET /v1/periods': () => [{ period: '2026-10', status: 'INITIATED', current: true }],
      'POST /auth/logout': () => noContent(),
      ...over,
    },
    [],
  );
}

beforeEach(() => {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  replace.mockReset();
  push.mockReset();
  search = new URLSearchParams();
});
afterEach(() => vi.unstubAllGlobals());

describe('Workspace without every particular', () => {
  it('a session without a role name, office or session detail opens; a menu without screens says so', async () => {
    // The menu is not pre-loaded: the first paint runs without it, then the server's empty menu.
    routes({ 'GET /menu': () => ({ sections: [], hiddenCount: 0 }) });
    show(
      me(['Z-99:VIEW'], {
        role: null,
        roleName: null,
        location: null,
        session: undefined as unknown as Me['session'],
      }),
    );
    expect((await screen.findAllByText(t('nav.noScreens'))).length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText(t('nav.hidden', [0]))).toBeNull();
    expect(screen.getByRole('button', { name: t('account.menu') })).toHaveAttribute('title', 'Test User · ');
  });

  it('SRS-G-29: a switch to a scope the catalogue names by its name alone is announced by that name', async () => {
    search = new URLSearchParams('s=W-02');
    let roleName: string | null = 'Finance (acting)';
    routes({
      'GET /menu': () => ({
        sections: [{ id: 'work', labelKey: 'nav.work', items: [item('W-02')] }],
        hiddenCount: 0,
      }),
      'POST /auth/switch-scope': () => noContent(),
      'GET /auth/me': () => me(['R-01:VIEW'], { role: null, roleName, location: null }),
    });
    show(me(['W-02:VIEW']));
    const role = await screen.findByRole('combobox', { name: t('top.role') });
    await screen.findByRole('option', { name: t('role.FINANCE') });
    await userEvent.selectOptions(role, 'FINANCE@DH001');
    expect(await screen.findByText(t('scope.switchedEseva', ['Finance (acting)']))).toBeTruthy();
    await waitFor(() => expect(push).toHaveBeenCalledWith('/payroll'));
    roleName = null;
    await userEvent.selectOptions(role, 'FINANCE@DH001');
    expect(await screen.findByText(t('scope.switchedEseva', ['']).trim())).toBeTruthy();
  });

  it('SRS-G-54 (OD-125): an entry shows the code the wireframe prints (X-02 for the built W-01) and its glyph; others their own code', async () => {
    search = new URLSearchParams('s=W-02');
    const menu = {
      sections: [
        {
          id: 'general_cross_cutting',
          labelKey: 'menu.section.general_cross_cutting',
          items: [
            { ...item('W-01'), id: 'x2', labelKey: 'nav.x2' },
            { ...item('X-05'), id: 'x5', labelKey: 'nav.x5' },
          ],
        },
      ],
      hiddenCount: 0,
    };
    routes({ 'GET /menu': () => menu });
    show(me(['W-01:VIEW', 'X-05:VIEW', 'W-02:VIEW']), menu);
    const inbox = await screen.findByRole('button', { name: new RegExp(t('nav.x2')) });
    expect(inbox.querySelector('.tag')?.textContent).toBe('X-02');
    expect(inbox.querySelector('.ic')?.textContent).toBe(t('navicon.x2'));
    const worklist = screen.getByRole('button', { name: new RegExp(t('nav.x5')) });
    expect(worklist.querySelector('.tag')?.textContent).toBe('X-05');
  });

  it('a screen the registry does not know renders nothing of itself and a menu item without an icon still lists', async () => {
    search = new URLSearchParams('s=Z-99');
    const menu = { sections: [{ id: 'work', labelKey: 'nav.work', items: [item('Z-99')] }], hiddenCount: 0 };
    routes({ 'GET /menu': () => menu });
    show(me(['Z-99:VIEW']), menu);
    expect(await screen.findByRole('button', { name: /screen\.Z-99/ })).toBeTruthy();
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
    expect(screen.queryByText(t('denied.title'))).toBeNull();
  });

  it('SRS-G-48: a locked action without a stated reason names the permission; a cancelled OTP is no error; mock-up actions take no arguments', async () => {
    SCREENS['T-99'] = { code: 'T-99', icon: Users, wireframe: true };
    try {
      search = new URLSearchParams('s=T-99');
      const wfAct = (a: string, args: string[]) => JSON.stringify({ act: a, args });
      routes({
        'GET /menu': () => ({
          sections: [{ id: 'x', labelKey: 'nav.work', items: [item('T-99')] }],
          hiddenCount: 0,
        }),
        'GET /v1/me/actions': () => [
          {
            screenCode: 'T-99',
            actionCode: 'CREATE',
            labelKey: 'action.create',
            allowed: true,
            disabledReasonKey: null,
          },
          {
            screenCode: 'T-99',
            actionCode: 'SUBMIT',
            labelKey: 'action.submit',
            allowed: false,
            disabledReasonKey: null,
          },
        ],
        'POST /v1/actions': () => problem(428, 'AUTH-428-OTP', ['7']),
        'GET /payroll/wireframe/T-99': () => [
          [
            'div',
            {},
            [
              ['button', { 'data-act': wfAct('toast', []) }, ['Quiet']],
              ['button', { 'data-act': wfAct('doAct', []) }, ['Bare']],
              ['button', { 'data-act': wfAct('setM1Filter', []) }, ['Unfilter']],
            ],
          ],
        ],
      });
      show(me(['T-99:VIEW']));
      const submit = (await screen.findByRole('button', { name: t('action.submit') })) as HTMLButtonElement;
      expect(submit.disabled).toBe(true);
      expect(submit.title).toBe(t('reason.noPermission'));
      await userEvent.click(screen.getByRole('button', { name: 'Bare' }));
      expect(await screen.findByText(t('wireframe.logged', ['doAct']))).toBeTruthy();
      await userEvent.click(screen.getByRole('button', { name: 'Quiet' }));
      await userEvent.click(screen.getByRole('button', { name: 'Unfilter' }));
      await userEvent.click(screen.getByRole('button', { name: t('action.create') }));
      await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'New hire');
      await userEvent.click(
        within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
      );
      const otp = await screen.findByRole('dialog', { name: t('otp.title') });
      await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      expect(screen.queryByText(t('error.AUTH-428-OTP', ['7']))).toBeNull();
    } finally {
      delete SCREENS['T-99'];
    }
  });

  it('the account menu offers to install the app once the browser made the offer', async () => {
    const register = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'serviceWorker', {
      value: { register, getRegistrations: vi.fn().mockResolvedValue([]) },
      configurable: true,
    });
    routes({ 'GET /menu': () => ({ sections: [], hiddenCount: 0 }) });
    show(me(['W-02:VIEW']), undefined, true);
    const prompt = vi.fn().mockResolvedValue(undefined);
    const offer = Object.assign(new Event('beforeinstallprompt'), {
      prompt,
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    });
    window.dispatchEvent(offer);
    await userEvent.click(await screen.findByRole('button', { name: t('account.menu') }));
    await userEvent.click(await screen.findByRole('menuitem', { name: t('pwa.install') }));
    await waitFor(() => expect(prompt).toHaveBeenCalled());
  });
});

describe('Workspace session edges', () => {
  it('SRS-G-25: a 401 from any call returns to sign-in; once the shell is gone a 401 no longer navigates', async () => {
    routes({
      'GET /menu': () => ({ sections: [], hiddenCount: 0 }),
      'GET /v1/probe': () => problem(401, 'AUTH-401'),
    });
    const view = show(me(['W-02:VIEW']));
    await screen.findByRole('button', { name: t('account.menu') });
    replace.mockClear();
    await api('/v1/probe').catch(() => undefined);
    expect(replace).toHaveBeenCalledWith('/');
    // The handler is removed with the shell: a late 401 does not navigate.
    view.unmount();
    replace.mockClear();
    await api('/v1/probe').catch(() => undefined);
    expect(replace).not.toHaveBeenCalled();
  });

  it('SRS-G-25: signing out still ends the session on the device when the server cannot be reached', async () => {
    routes({
      'GET /menu': () => ({ sections: [], hiddenCount: 0 }),
      'POST /auth/logout': () => problem(500, 'COM-500-UNEXPECTED'),
    });
    show(me(['W-02:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('account.menu') }));
    await userEvent.click(await screen.findByRole('menuitem', { name: t('account.signOut') }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
  });
});

describe('keep-alive, language and toast defaults', () => {
  it('SRS-G-25: the keep-alive call refreshes the profile only', async () => {
    const calls = backend({ 'GET /auth/me': () => me(['W-02:VIEW']) }, []);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(client, 'refetchQueries');
    const { result } = renderHook(() => useKeepAlive(), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await act(async () => {
      await result.current();
    });
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0]?.[0]).toEqual({ queryKey: ['me'] });
    // No profile is cached here, so nothing was fetched: no other query is touched.
    expect(calls.every((c) => c.url.includes('/auth/me') || c.url.includes('/i18n'))).toBe(true);
  });

  it('SRS-G-29: a language choice stands in the page when the server cannot save it', async () => {
    const calls = backend({ 'PUT /auth/locale': () => problem(500, 'COM-500-UNEXPECTED') }, {});
    function Probe() {
      const { locale, setLocale } = useI18n();
      return (
        <button type="button" onClick={() => void setLocale('hi')}>
          {locale}
        </button>
      );
    }
    render(
      <I18nProvider>
        <Probe />
      </I18nProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'en' }));
    expect(await screen.findByRole('button', { name: 'hi' })).toBeInTheDocument();
    await waitFor(() =>
      expect(calls.some((c) => c.method === 'PUT' && c.url.includes('/auth/locale'))).toBe(true),
    );
    expect(document.documentElement.lang).toBe('hi');
  });

  it('a toast raised outside the provider is a no-op and shows nothing', () => {
    const { result } = renderHook(() => useToast());
    expect(() => result.current('Saved')).not.toThrow();
    expect(screen.queryByRole('status')).toBeNull();
  });
});
