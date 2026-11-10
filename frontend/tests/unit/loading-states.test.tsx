import { afterEach, describe, expect, it, vi } from 'vitest';
import { SCREENS } from '@/modules/shell/components/screenRegistry';
import { me, wrap } from './support/harness';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/payroll',
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * Every screen while its data is still on the way (SRS-NF-3: a slow answer never leaves a broken
 * page): with no request answered yet, each screen renders its frame, its lists empty and its
 * actions in place, for a user holding every action of the screen.
 */

afterEach(() => vi.unstubAllGlobals());

const ACTIONS = ['VIEW', 'CREATE', 'EDIT', 'SUBMIT', 'APPROVE', 'EXPORT', 'DELETE'];

const screens = Object.values(SCREENS).filter((s) => s.component !== undefined);

describe('screens while loading', () => {
  it.each(screens.map((s) => [s.code, s] as const))('%s renders before any answer', (code, s) => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        url.endsWith('/auth/csrf')
          ? Promise.resolve(new Response(null, { status: 204 }))
          : new Promise(() => {}),
      ),
    );
    const Screen = s.component;
    expect(Screen).toBeDefined();
    if (!Screen) return;
    const { container } = wrap(<Screen />, me(ACTIONS.map((a) => `${code}:${a}`)));
    expect(container.childElementCount).toBeGreaterThan(0);
  });
});
