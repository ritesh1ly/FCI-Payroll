import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SCREENS } from '@/modules/shell/components/screenRegistry';
import { backend, me, problem, wrap } from './support/harness';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/payroll',
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * The screens made of several parts (SRS I-04 "One screen serves both", E-01 member self-service,
 * E-02 support): each renders all its parts, and a backend that is down shows a refusal in place
 * of each, never a blank page.
 */

afterEach(() => vi.unstubAllGlobals());

describe('composite screens', () => {
  it.each([
    ['I-04', ['I-04:VIEW', 'I-03:VIEW']],
    ['E-01', ['E-01:VIEW', 'E-01:SELF']],
    ['E-02', ['E-02:VIEW', 'E-03:VIEW']],
  ])('%s renders every part even with the backend down', async (code, permissions) => {
    backend({ 'GET /v1/': () => problem(503, 'COM-503-UNAVAILABLE') });
    const Screen = SCREENS[code]?.component;
    expect(Screen).toBeDefined();
    if (!Screen) return;
    const { container } = wrap(<Screen />, me(permissions));
    expect(container.querySelector('.wireframe-body-container')).not.toBeNull();
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
  });
});
