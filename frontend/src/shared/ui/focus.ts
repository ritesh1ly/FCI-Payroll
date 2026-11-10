'use client';

import { useEffect, useRef } from 'react';

/**
 * Moves the focus into a dialog when it opens: to its [data-autofocus] element, else its first
 * focusable control. (The autoFocus prop is avoided: it fires on every remount, not on opening.)
 */
export function useDialogFocus<T extends HTMLElement>(open = true) {
  const ref = useRef<T>(null);
  useEffect(() => {
    if (!open || !ref.current) return;
    const target =
      ref.current.querySelector<HTMLElement>('[data-autofocus]') ??
      ref.current.querySelector<HTMLElement>('input, textarea, select, button');
    target?.focus();
  }, [open]);
  return ref;
}
