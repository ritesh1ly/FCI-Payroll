'use client';

import { useCallback, useSyncExternalStore } from 'react';

/**
 * Whether a CSS media query matches, kept in step with the viewport. The server (and the first
 * client render) answers {@code serverValue}, so hydration never mismatches; the real value follows
 * on the client. For layout decisions CSS cannot make alone, e.g. mounting a control in the drawer on
 * phones and in the top bar on desktops (never twice).
 */
export function useMediaQuery(query: string, serverValue = false): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = window.matchMedia(query);
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(query).matches,
    () => serverValue,
  );
}

/** The workspace breakpoint: below it the sidebar is a drawer and the top bar is compact. */
export const COMPACT_QUERY = '(max-width: 900px)';
