import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ScreenView } from '@/modules/shell/components/ScreenView';
import { SCREENS, type ScreenDefinition } from '@/modules/shell/components/screenRegistry';
import type { MenuItem } from '@/shared/session';
import { backend, me, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/payroll',
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * The screen frame (SRS-G-1, OD-121): a screen drawn under two menu groups names its view (I-04 ▸ Loans) or its labour
 * engine (▸ DPS Labour) in the title and on previous / next; with the sidebar's entries previous / next open the
 * neighbouring entries, without them they follow the screen order.
 */

const saved: Record<string, ScreenDefinition | undefined> = {};
const CODES = ['I-02', 'I-03', 'I-04'];

beforeEach(() => {
  for (const code of CODES) {
    saved[code] = SCREENS[code];
    SCREENS[code] = { ...(SCREENS[code] as ScreenDefinition), component: () => <p>{`body of ${code}`}</p> };
  }
});

afterEach(() => {
  for (const code of CODES) SCREENS[code] = saved[code] as ScreenDefinition;
  vi.unstubAllGlobals();
});

const entry = (code: string, route: string): MenuItem => ({
  id: `${code}-${route}`,
  code,
  labelKey: `screen.${code}`,
  route,
  requires: `${code}:VIEW`,
});

describe('ScreenView titles and neighbours', () => {
  it('SRS-G-1: an engine view is named in the title; the entries before and after open with their own view', async () => {
    backend({ 'GET /v1/me/actions': () => [] });
    const onOpen = vi.fn();
    const onNavigate = vi.fn();
    const entries = [
      entry('I-04', '/payroll?s=I-04&view=LOANS'),
      entry('I-02', '/payroll?s=I-02&engine=DPS'),
      entry('I-03', '/payroll?s=I-03'),
    ];
    wrap(
      <ScreenView
        code="I-02"
        me={me(['I-02:VIEW'])}
        order={['I-02', 'I-03', 'I-04']}
        onNavigate={onNavigate}
        entries={entries}
        route="/payroll?s=I-02&engine=DPS"
        onOpen={onOpen}
      />,
      ['I-02:VIEW'],
    );
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: `${t('screen.I-02')} ▸ ${t('engine.suffix.DPS')}`,
      }),
    ).toBeTruthy();
    expect(screen.getByText('body of I-02')).toBeTruthy();
    await userEvent.click(
      screen.getByRole('button', { name: `← ${t('screen.I-04')} ▸ ${t('view.suffix.LOANS')}` }),
    );
    expect(onOpen).toHaveBeenLastCalledWith(entries[0]);
    await userEvent.click(screen.getByRole('button', { name: `${t('screen.I-03')} →` }));
    expect(onOpen).toHaveBeenLastCalledWith(entries[2]);
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('SRS-G-1: a view route names the view in the title; without entries previous / next follow the screen order', async () => {
    backend({ 'GET /v1/me/actions': () => [] });
    const onNavigate = vi.fn();
    wrap(
      <ScreenView
        code="I-03"
        me={me(['I-03:VIEW'])}
        order={['I-02', 'I-03', 'I-04']}
        onNavigate={onNavigate}
        route="/payroll?s=I-03&view=LOANS"
      />,
      ['I-03:VIEW'],
    );
    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toBe(`${t('screen.I-03')} ▸ ${t('view.suffix.LOANS')}`);
    await userEvent.click(screen.getByRole('button', { name: `← ${t('screen.I-02')}` }));
    expect(onNavigate).toHaveBeenLastCalledWith('I-02');
    await userEvent.click(screen.getByRole('button', { name: `${t('screen.I-04')} →` }));
    expect(onNavigate).toHaveBeenLastCalledWith('I-04');
  });

  it('SRS-G-1: with no route open the title is the screen alone; the last screen has no next', async () => {
    backend({ 'GET /v1/me/actions': () => [] });
    wrap(<ScreenView code="I-04" me={me(['I-04:VIEW'])} order={['I-03', 'I-04']} onNavigate={vi.fn()} />, [
      'I-04:VIEW',
    ]);
    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toBe(t('screen.I-04'));
    expect(screen.getByRole('button', { name: `← ${t('screen.I-03')}` })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /→$/ })).toBeNull();
  });
});
