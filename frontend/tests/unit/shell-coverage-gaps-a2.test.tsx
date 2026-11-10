import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Workspace } from '@/modules/shell';
import { SCREENS, type ScreenDefinition } from '@/modules/shell/components/screenRegistry';
import { I18nProvider } from '@/shared/i18n';
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
 * The workspace shell (SRS-G-25, OD-130): a session ended for an access offence goes to sign-in saying why, whether a
 * call or the session read reports it; the open entry of a screen drawn twice carries its view; a headerless home
 * section is always open.
 */

const item = (code: string, route = `/payroll?s=${code}`) => ({
  id: route,
  code,
  labelKey: `screen.${code}`,
  route,
  requires: `${code}:VIEW`,
});

const MENU = {
  sections: [
    { id: 'home', labelKey: 'nav.home', items: [item('W-02')] },
    {
      id: 'labour',
      labelKey: 'nav.work',
      items: [item('I-02', '/payroll?s=I-02&engine=DPS'), item('I-02', '/payroll?s=I-02&engine=NWNP')],
    },
  ],
  hiddenCount: 0,
};

const PROFILE: Me = me(['W-02:VIEW', 'I-02:VIEW']);

function show(profile: Me | null = PROFILE) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  if (profile) client.setQueryData(['me'], profile);
  client.setQueryData(['menu'], MENU);
  return render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        <Workspace />
      </QueryClientProvider>
    </I18nProvider>,
  );
}

function routes(over: Record<string, (url: string) => unknown> = {}) {
  return backend(
    {
      'GET /auth/scopes': () => [],
      'GET /v1/periods': () => [{ period: '2026-10', status: 'INITIATED', current: true }],
      'GET /auth/me': () => PROFILE,
      'GET /menu': () => MENU,
      'GET /v1/me/actions': () => [],
      'POST /auth/logout': () => noContent(),
      ...over,
    },
    [],
  );
}

let saved: ScreenDefinition | undefined;
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
  saved = SCREENS['I-02'];
  SCREENS['I-02'] = { ...(saved as ScreenDefinition), component: () => <p>work slips</p> };
});
afterEach(() => {
  SCREENS['I-02'] = saved as ScreenDefinition;
  vi.unstubAllGlobals();
});

describe('Workspace sign-out for an offence (OD-130)', () => {
  it('OD-130: a call the server ends for an access offence sends the user to sign-in saying why', async () => {
    routes({ 'GET /v1/periods': () => problem(401, 'AUTH-401-OFFENCE') });
    show();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/?signedOut=offence'));
  });

  it('OD-130: the session read refused for an offence goes to sign-in saying why', async () => {
    routes({ 'GET /auth/me': () => problem(401, 'AUTH-401-OFFENCE') });
    show(null);
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/?signedOut=offence'));
    expect(replace).not.toHaveBeenCalledWith('/');
  });
});

describe('Workspace menu entries', () => {
  it('SRS-G-25: the open entry of a screen drawn twice is the one with its view; the home section has no header', async () => {
    search = new URLSearchParams('s=I-02&engine=NWNP');
    routes();
    const { container } = show();
    expect(await screen.findByText('work slips')).toBeTruthy();
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: `${t('screen.I-02')} ▸ ${t('engine.suffix.NWNP')}`,
      }),
    ).toBeTruthy();
    const home = container.querySelector('.grp-home') as HTMLElement;
    expect(home).not.toBeNull();
    expect(home.querySelector('button[aria-expanded]')).toBeNull();
  });
});
