'use client';

import { useQuery } from '@tanstack/react-query';
import { wireframeApi } from '../api/wireframeApi';

/**
 * The wireframe body of {@code screen} for the session's role; the key includes the acting role and
 * office so a scope switch fetches the body of the new role. Disabled for screens without one.
 */
export function useWireframeBody(screen: string, enabled: boolean, scopeKey: string) {
  return useQuery({
    queryKey: ['wireframe', screen, scopeKey],
    queryFn: () => wireframeApi.body(screen),
    enabled,
    staleTime: Infinity,
    retry: false,
  });
}
