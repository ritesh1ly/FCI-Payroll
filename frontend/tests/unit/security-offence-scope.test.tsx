import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useSwitchScope } from '@/modules/iam/orchestration/authQueries';
import { api, onPermissions } from '@/shared/api';
import { type Me, meKey, reportDeniedScreen } from '@/shared/session';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status });
}

afterEach(() => {
  onPermissions(null);
  vi.unstubAllGlobals();
});

describe('OD-130: a scope switch never asks for what the new role may not read', () => {
  it('OD_130_the open screen does not refetch a read the new role lacks', async () => {
    const urls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        urls.push(url);
        if (url.endsWith('/auth/me')) return json({ permissions: ['E-01:VIEW'] });
        return json({ items: [] });
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData<Partial<Me>>(meKey, { permissions: ['A-01:VIEW'] });
    onPermissions(() => client.getQueryData<Me>(meKey)?.permissions);
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(
      () => ({
        open: useQuery({ queryKey: ['screen'], queryFn: () => api('/v1/admin/users') }),
        change: useSwitchScope(),
      }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.open.isSuccess).toBe(true));
    const before = urls.filter((url) => url.includes('/v1/admin/users')).length;

    await act(async () => {
      await result.current.change.mutateAsync({ role: 'EMPLOYEE_ESEVA', location: null });
    });

    expect(urls.filter((url) => url.includes('/v1/admin/users'))).toHaveLength(before);
    expect(client.getQueryData<Me>(meKey)?.permissions).toEqual(['E-01:VIEW']);
  });
});

describe('OD-130: an address-bar visit to a refused screen', () => {
  it('OD_130_is told to the log and a failure of that call is ignored', async () => {
    const fetched = vi.fn(async (url: string) =>
      url.endsWith('/auth/csrf') ? json({}) : Promise.reject(new TypeError('offline')),
    );
    vi.stubGlobal('fetch', fetched);

    reportDeniedScreen('A-01');

    await waitFor(() =>
      expect(fetched.mock.calls.flat().map(String).join(' ')).toContain('/auth/denied-screen'),
    );
  });
});
