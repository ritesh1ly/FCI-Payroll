import { waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { wireframeApi } from '@/modules/shell/api/wireframeApi';
import { Dashboard } from '@/modules/shell/components/Dashboard';
import { backend, me, problem, wrap } from './support/harness';

/**
 * Branch gaps of the workspace shell: the D-01 cycle status on the home of a role that may view it
 * (SRS D-01), and a wireframe body the server refuses (deny by default).
 */

afterEach(() => vi.unstubAllGlobals());

describe('Workspace home branch gaps', () => {
  it('SRS D-01: a role that may view the cycle status sees it on the home', async () => {
    const calls = backend({ 'GET /v1/cycle-status': () => problem(503, 'COM-503-UNAVAILABLE') });
    wrap(<Dashboard me={me(['D-01:VIEW'])} items={[]} onOpen={vi.fn()} onVerify={vi.fn()} />, ['D-01:VIEW']);
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/cycle-status'))).toBe(true));
  });

  it('a wireframe body the server refuses is an error naming the screen and the status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('denied', { status: 403 })),
    );
    await expect(wireframeApi.body('M-01')).rejects.toThrow('wireframe M-01: 403');
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify([['div', {}]]), { status: 200 })),
    );
    await expect(wireframeApi.body('M-01')).resolves.toEqual([['div', {}]]);
  });
});
