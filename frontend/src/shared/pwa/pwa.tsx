'use client';

import { useEffect, useSyncExternalStore } from 'react';
import { withBase } from '@/shared/ui/basePath';

/**
 * PWA support (docs/architecture/RESPONSIVE_AND_PWA.md). Off unless the server runs with
 * FCI_PWA_ENABLED=true: then /sw.js is registered (versioned by build, so a deployment gets fresh
 * caches); when off, any worker a browser still has is unregistered and its caches deleted (the kill
 * switch). The worker never caches /api or pages; sign-out clears its caches as well.
 */
const CACHE_PREFIX = 'fcilekha-';

interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferred: InstallPromptEvent | null = null;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

/** Registers or removes the service worker; captures the browser's install prompt for later. */
export function PwaRegistration({ enabled, version }: { enabled: boolean; version: string }) {
  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;
    if (!enabled) {
      void navigator.serviceWorker.getRegistrations().then((all) => all.forEach((r) => void r.unregister()));
      void clearAppCaches();
      return;
    }
    void navigator.serviceWorker
      .register(withBase(`/sw.js?v=${encodeURIComponent(version)}`), { scope: withBase('/') })
      .catch(() => undefined);
    const onPrompt = (e: Event) => {
      e.preventDefault();
      deferred = e as InstallPromptEvent;
      notify();
    };
    const onInstalled = () => {
      deferred = null;
      notify();
    };
    window.addEventListener('beforeinstallprompt', onPrompt);
    window.addEventListener('appinstalled', onInstalled);
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, [enabled, version]);
  return null;
}

/** Deletes the app's caches (sign-out): asks the worker, and deletes them from the page as well. */
export async function clearAppCaches(): Promise<void> {
  try {
    navigator.serviceWorker?.controller?.postMessage({ type: 'CLEAR' });
    if ('caches' in window) {
      const keys = await caches.keys();
      await Promise.all(keys.filter((k) => k.startsWith(CACHE_PREFIX)).map((k) => caches.delete(k)));
    }
  } catch {
    // Storage unavailable (private mode): nothing was cached either.
  }
}

/** Whether the browser offered to install the app (only once signed in do screens show the offer). */
export function useInstallPrompt(): { available: boolean; install: () => Promise<boolean> } {
  const available = useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => deferred !== null,
    () => false,
  );
  return {
    available,
    install: async () => {
      const event = deferred;
      if (!event) return false;
      deferred = null;
      notify();
      await event.prompt();
      return (await event.userChoice).outcome === 'accepted';
    },
  };
}

/** Whether the browser believes it is online (true on the server). */
export function useOnline(): boolean {
  return useSyncExternalStore(
    (l) => {
      window.addEventListener('online', l);
      window.addEventListener('offline', l);
      return () => {
        window.removeEventListener('online', l);
        window.removeEventListener('offline', l);
      };
    },
    () => navigator.onLine,
    () => true,
  );
}
