'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, useState } from 'react';
import { ApiError, onPermissions } from '@/shared/api';
import { I18nProvider } from '@/shared/i18n';
import { ClientErrorLogging } from '@/shared/log';
import { PwaRegistration } from '@/shared/pwa';
import { type Me, meKey } from '@/shared/session';

/** The client providers; {@code pwa}: the server's FCI_PWA_ENABLED, {@code version}: the build (worker caches). */
export function Providers({
  children,
  pwa,
  version,
}: {
  children: ReactNode;
  pwa: boolean;
  version: string;
}) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 15_000,
            refetchOnWindowFocus: false,
            // Only transient failures are retried; 4xx answers are final.
            retry: (count, error) =>
              count < 2 && error instanceof ApiError && (error.status === 0 || error.status === 503),
          },
          mutations: { retry: false },
        },
      }),
  );
  // OD-130: the client reads the live session's permissions at each call (see refusedGate).
  onPermissions(() => client.getQueryData<Me>(meKey)?.permissions);
  return (
    <I18nProvider>
      <ClientErrorLogging />
      <PwaRegistration enabled={pwa} version={version} />
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </I18nProvider>
  );
}
